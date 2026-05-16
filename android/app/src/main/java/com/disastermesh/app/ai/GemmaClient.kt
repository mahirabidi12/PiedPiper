package com.disastermesh.app.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * GemmaClient — single bridge to the on-device Gemma 4 E2B model (LiteRT-LM / Google AI Edge).
 *
 * This is the ONLY class that touches LiteRT-LM. Every other AI feature
 * (AI chat, signal classifier) must call through here.
 *
 * Model: litert-community/gemma-4-E2B-it in `.litertlm` format.
 * LiteRT-LM applies Gemma's chat template internally; callers pass plain text only.
 *
 * Threading: load and inference run on a single background thread (serialised).
 * All callbacks are delivered on the main thread.
 */
object GemmaClient {

    private const val TAG = "GemmaClient"

    private const val MODEL_SUBDIR  = "models"
    private const val MODEL_FILENAME = "gemma.litertlm"

    /**
     * Hard cap on the KV-cache (context window) in tokens.
     *
     * Without a cap, a long survival chat slowly grows the KV cache and
     * silently consumes hundreds of MB of RAM on a 2B-parameter model —
     * eventually OOM-killing the process. 2048 tokens covers ~6 typical
     * survival-Q/A exchanges, which is more than enough for the use case.
     *
     * Wire this into EngineConfig with `maxNumTokens = MAX_CONTEXT_TOKENS`
     * once your bundled LiteRT-LM exposes that parameter (it's the
     * parameter name in 0.7+; older builds may call it `contextLength` or
     * `maxTokens`). The constant is consumed inside [buildEngineConfig].
     */
    private const val MAX_CONTEXT_TOKENS = 2048

    enum class Status { ABSENT, LOADING, READY, ERROR }

    @Volatile var status: Status = Status.ABSENT
        private set

    @Volatile var lastError: String? = null
        private set

    private val worker      = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: Engine? = null

    // ── Model file ────────────────────────────────────────────────────────────

    /**
     * Path: /sdcard/Android/data/com.disastermesh.app/files/models/gemma.litertlm
     * Using the app's external files dir → `adb push` works with no storage permission.
     */
    fun modelFile(context: Context): File {
        // getExternalFilesDir() returns null when external storage is unmounted or
        // unavailable (low-storage policy, work profiles, some emulators).
        // Fall back to internal storage so the path is always non-null.
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir  = File(base, MODEL_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, MODEL_FILENAME)
    }

    fun isModelPresent(context: Context): Boolean {
        val f = modelFile(context)
        return f.exists() && f.length() > 0L
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Loads the model if it isn't already loaded. Idempotent — safe to call on every screen open.
     * [onStatus] is invoked on the main thread for every state change.
     */
    fun warmUp(context: Context, onStatus: (Status) -> Unit) {
        if (status == Status.READY && engine != null) {
            postStatus(onStatus, Status.READY)
            return
        }
        if (!isModelPresent(context)) {
            status = Status.ABSENT
            postStatus(onStatus, Status.ABSENT)
            return
        }

        val appContext = context.applicationContext
        val path       = modelFile(appContext).absolutePath

        status = Status.LOADING
        postStatus(onStatus, Status.LOADING)

        worker.execute {
            if (status == Status.READY && engine != null) {
                postStatus(onStatus, Status.READY)
                return@execute
            }
            // Try GPU first — falls back to CPU if the device's OpenCL/Vulkan
            // stack can't service the model (low-end / older GPUs, emulator).
            val newEngine = tryInitEngine(path, useGpu = true, appContext)
                ?: tryInitEngine(path, useGpu = false, appContext)

            if (newEngine == null) {
                engine    = null
                status    = Status.ERROR
                Log.e(TAG, "Model load failed on both GPU and CPU backends")
                postStatus(onStatus, Status.ERROR)
                return@execute
            }

            engine    = newEngine
            status    = Status.READY
            lastError = null
            postStatus(onStatus, Status.READY)
        }
    }

    /**
     * Attempts to spin up an Engine on the requested backend. Returns null on
     * failure (logged, with [lastError] populated) so the caller can fall
     * back to the next backend cleanly.
     *
     * Hardware notes:
     *  - GPU delegate (OpenCL on most Snapdragons, Vulkan on newer Pixel/Mali)
     *    delivers 4–8× the tokens/sec of CPU on Gemma 2B. Whether OpenCL or
     *    Vulkan is used is selected internally by LiteRT-LM based on
     *    [Backend.GPU]'s capability probe — don't hard-code one.
     *  - The model file at [modelPath] is referenced by path (not loaded into
     *    a ByteArray) so the kernel mmaps the weights from filesDir on
     *    demand. This is the OOM-safe layout: a 2.6 GB model uses only the
     *    pages it touches at inference time.
     */
    private fun tryInitEngine(path: String, useGpu: Boolean, appContext: Context): Engine? = try {
        val backend = if (useGpu) Backend.GPU() else Backend.CPU()
        val config  = buildEngineConfig(path, backend, appContext)
        val e = Engine(config)
        e.initialize()
        Log.d(TAG, "Engine initialized on ${if (useGpu) "GPU" else "CPU"} from $path")
        e
    } catch (t: Throwable) {
        lastError = "${if (useGpu) "GPU" else "CPU"} init failed: ${t.message}"
        Log.w(TAG, lastError, t)
        null
    }

    /**
     * Centralised EngineConfig builder. Edit here to apply tuning knobs
     * uniformly to both GPU and CPU init attempts.
     */
    private fun buildEngineConfig(
        path: String,
        backend: Backend,
        appContext: Context
    ): EngineConfig = EngineConfig(
        modelPath = path,                            // mmap'd by the engine
        backend   = backend,
        cacheDir  = appContext.cacheDir.absolutePath,
        // ── KV-cache cap ─────────────────────────────────────────────────────
        // Uncomment the line below once your LiteRT-LM build accepts the
        // parameter (name varies: maxNumTokens in 0.7+, maxTokens in 0.5,
        // contextLength in early previews). Until then [MAX_CONTEXT_TOKENS]
        // remains documented intent — apply it via Conversation sampling
        // config or a vendor-specific call if your version exposes one.
        // maxNumTokens = MAX_CONTEXT_TOKENS,
    )

    /**
     * Runs one generation on a fresh single-turn conversation.
     * The model must be READY (call [warmUp] first).
     * [onResult] / [onError] are delivered on the main thread.
     */
    fun generate(
        prompt: String,
        systemInstruction: String? = null,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val activeEngine = engine
        if (status != Status.READY || activeEngine == null) {
            mainHandler.post { onError("Model not ready (status: $status)") }
            return
        }
        worker.execute {
            try {
                val conversation = if (systemInstruction.isNullOrBlank()) {
                    activeEngine.createConversation()
                } else {
                    activeEngine.createConversation(
                        ConversationConfig(systemInstruction = Contents.of(systemInstruction))
                    )
                }
                val answer: String = conversation.use { it.sendMessage(prompt).toString() }
                mainHandler.post { onResult(answer.trim()) }
            } catch (t: Throwable) {
                Log.e(TAG, "Generation failed: ${t.message}", t)
                mainHandler.post { onError(t.message ?: "Inference failed") }
            }
        }
    }

    /**
     * Coroutine-friendly wrapper around [generate]. Lets callers write linear
     * code instead of nesting callbacks — required by the chat flow where we
     * insert the user turn, await Gemma, then insert the AI turn in one
     * lifecycleScope coroutine.
     *
     * Throws [GemmaException] on failure so the caller can render the error
     * inline as an AI bubble. Cancellation propagates if the lifecycleScope
     * is cancelled mid-inference.
     */
    suspend fun generateSuspend(
        prompt: String,
        systemInstruction: String? = null,
    ): String = suspendCancellableCoroutine { cont ->
        generate(
            prompt            = prompt,
            systemInstruction = systemInstruction,
            onResult          = { answer -> if (cont.isActive) cont.resume(answer) },
            onError           = { err    -> if (cont.isActive) cont.resumeWithException(GemmaException(err)) }
        )
    }

    class GemmaException(message: String) : Exception(message)

    /**
     * Streaming generation. Emits the cumulative answer text every time a new
     * chunk is decoded by the engine — the caller binds the latest value to
     * the TextView and the bubble grows in place ("typewriter" effect).
     *
     * Emitting CUMULATIVE text (not deltas) is deliberate: if the consumer
     * ever drops a chunk under load, the next emission still carries the full
     * up-to-date string, so the UI can never desync from the model.
     *
     * The flow completes when the model finishes; on failure it raises
     * [GemmaException]. Cancellation is propagated via [awaitClose] — the
     * worker thread observes [isActive] and aborts the chunk loop.
     *
     * ──────────────────────────────────────────────────────────────────────
     * SWAP POINT — if your bundled LiteRT-LM exposes a Flow-returning
     * streaming method (`sendMessageStream(Contents)` / `generateContentStream`),
     * replace the marked block below with a single `.collect { emit(...) }`
     * call. The wrapper, error handling and cancellation already match that
     * shape. Until then we run the blocking `sendMessage` on the worker
     * thread and replay the answer in word-sized chunks so the UI gets the
     * full typewriter UX — note this does NOT shorten the wait until the
     * first token; only the LiteRT streaming API can do that.
     * ──────────────────────────────────────────────────────────────────────
     */
    fun generateStream(
        prompt: String,
        systemInstruction: String? = null,
    ): Flow<String> = callbackFlow {
        val activeEngine = engine
        if (status != Status.READY || activeEngine == null) {
            close(GemmaException("Model not ready (status: $status)"))
            return@callbackFlow
        }

        val cancelled = AtomicBoolean(false)

        val task = worker.submit {
            try {
                val conversation = if (systemInstruction.isNullOrBlank()) {
                    activeEngine.createConversation()
                } else {
                    activeEngine.createConversation(
                        ConversationConfig(systemInstruction = Contents.of(systemInstruction))
                    )
                }
                conversation.use { conv ->
                    // ── BEGIN swap-point block ─────────────────────────────────
                    val full = conv.sendMessage(Contents.of(prompt)).toString().trim()
                    val acc  = StringBuilder()
                    // Keep whitespace with each token so chunks read naturally.
                    val tokens = full.split(Regex("(?<=\\s)"))
                    for (t in tokens) {
                        if (cancelled.get()) return@use
                        acc.append(t)
                        // Cumulative emission — dropping a chunk under back-pressure
                        // can't desync the UI; the next chunk carries the full
                        // text-so-far. trySend never blocks the worker.
                        trySend(acc.toString())
                        Thread.sleep(30)
                    }
                    // ── END swap-point block ───────────────────────────────────
                }
                if (!cancelled.get()) close()
            } catch (t: Throwable) {
                Log.e(TAG, "Stream generation failed: ${t.message}", t)
                close(GemmaException(t.message ?: "Inference failed"))
            }
        }

        awaitClose {
            cancelled.set(true)
            task.cancel(true)
        }
    }

    fun shutdown() {
        worker.execute {
            try { engine?.close() } catch (t: Throwable) {
                Log.e(TAG, "Error closing model: ${t.message}", t)
            } finally {
                engine = null
                status = Status.ABSENT
            }
        }
    }

    private fun postStatus(cb: (Status) -> Unit, s: Status) = mainHandler.post { cb(s) }
}
