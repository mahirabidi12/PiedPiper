package com.disastermesh.ai

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

/**
 * GemmaClient — the single bridge to the on-device Gemma model (LiteRT-LM / Google AI Edge).
 *
 * V1 contract (see docs/knowledge/SYSTEM_KNOWLEDGE.md §2.1):
 *  - The model is **sideloaded or downloaded in-app** per device — there is no P2P transfer in V1.
 *  - This is the ONLY class in the app that touches LiteRT-LM. Every other AI feature
 *    (assistant, future classifier/translator/summarizer) must call through here.
 *  - It is fully self-contained: it does not touch the mesh, the DB, or any UI.
 *    If the model file is absent the rest of the app is unaffected.
 *
 * Runtime: the model is the `litert-community/gemma-4-E2B-it` build in LiteRT-LM's `.litertlm`
 * format, executed through the `com.google.ai.edge.litertlm:litertlm-android` engine. LiteRT-LM applies Gemma's
 * chat template (the `<start_of_turn>` turn tokens) itself, so callers pass plain text — see
 * [PromptTemplates].
 *
 * Threading: engine load and inference both run on a single background thread, so they are
 * serialised and never block the UI. Callbacks are delivered on the main thread.
 */
object GemmaClient {

    private const val TAG = "GemmaClient"

    /** Folder + filename the model is expected at, inside the app's external files dir. */
    private const val MODEL_SUBDIR = "models"
    private const val MODEL_FILENAME = "gemma.litertlm"

    enum class Status { ABSENT, LOADING, READY, ERROR }

    @Volatile
    var status: Status = Status.ABSENT
        private set

    /** Last failure detail, for surfacing in the UI. Null unless [status] == ERROR. */
    @Volatile
    var lastError: String? = null
        private set

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var engine: Engine? = null

    // ── Model file location ───────────────────────────────────────────────────

    /**
     * Absolute path the model must live at. Using the app's external files dir means
     * `adb push` works with **no runtime storage permission**:
     *   /sdcard/Android/data/com.disastermesh/files/models/gemma.litertlm
     */
    fun modelFile(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), MODEL_SUBDIR)
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
        // Already loaded — report and return.
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
        val path = modelFile(appContext).absolutePath

        status = Status.LOADING
        postStatus(onStatus, Status.LOADING)

        worker.execute {
            // Re-check inside the worker: another queued warmUp may have already loaded it.
            if (status == Status.READY && engine != null) {
                postStatus(onStatus, Status.READY)
                return@execute
            }
            try {
                val config = EngineConfig(
                    modelPath = path,
                    // CPU keeps the widest device compatibility for a field-relief app; LiteRT-LM
                    // also supports Backend.GPU() / Backend.NPU(...) for faster inference later.
                    backend = Backend.CPU(),
                    // A writable scratch dir lets LiteRT-LM cache compiled artefacts → faster reloads.
                    cacheDir = appContext.cacheDir.absolutePath,
                )
                val newEngine = Engine(config)
                newEngine.initialize()   // can take several seconds — already on a background thread
                engine = newEngine
                status = Status.READY
                lastError = null
                Log.d(TAG, "Model loaded from $path")
                postStatus(onStatus, Status.READY)
            } catch (t: Throwable) {
                engine = null
                status = Status.ERROR
                lastError = t.message ?: t.javaClass.simpleName
                Log.e(TAG, "Model load failed: ${t.message}", t)
                postStatus(onStatus, Status.ERROR)
            }
        }
    }

    /**
     * Runs one generation. The model must be READY (call [warmUp] first).
     *
     * Each call uses a **fresh, single-turn conversation** — generations are independent, so the
     * Help Assistant (and future per-call AI features) never leak context between questions.
     * Pass [systemInstruction] to frame the model's behaviour for this call; see [PromptTemplates].
     *
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
                // .use { } releases the conversation's KV-cache as soon as the answer is in hand.
                val answer: String = conversation.use { convo ->
                    convo.sendMessage(prompt).toString()
                }
                mainHandler.post { onResult(answer.trim()) }
            } catch (t: Throwable) {
                Log.e(TAG, "Generation failed: ${t.message}", t)
                mainHandler.post { onError(t.message ?: "Inference failed") }
            }
        }
    }

    /** Releases the model from memory. Safe to call when the model was never loaded. */
    fun shutdown() {
        worker.execute {
            try {
                engine?.close()
            } catch (t: Throwable) {
                Log.e(TAG, "Error closing model: ${t.message}", t)
            } finally {
                engine = null
                status = Status.ABSENT
            }
        }
    }

    private fun postStatus(onStatus: (Status) -> Unit, s: Status) {
        mainHandler.post { onStatus(s) }
    }
}
