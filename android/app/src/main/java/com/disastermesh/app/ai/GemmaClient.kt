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
            try {
                val config = EngineConfig(
                    modelPath = path,
                    backend   = Backend.CPU(),
                    cacheDir  = appContext.cacheDir.absolutePath,
                )
                val newEngine = Engine(config)
                newEngine.initialize()
                engine    = newEngine
                status    = Status.READY
                lastError = null
                Log.d(TAG, "Model loaded from $path")
                postStatus(onStatus, Status.READY)
            } catch (t: Throwable) {
                engine    = null
                status    = Status.ERROR
                lastError = t.message ?: t.javaClass.simpleName
                Log.e(TAG, "Model load failed: ${t.message}", t)
                postStatus(onStatus, Status.ERROR)
            }
        }
    }

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
