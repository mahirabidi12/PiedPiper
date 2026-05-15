package com.disastermesh.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.disastermesh.AppConstants
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * ModelDownloader — fetches the Gemma model into the device, in-app, with a progress bar
 * (the "CODM / PUBG extra-resources download" flow).
 *
 * V1 model delivery (see docs/knowledge/SYSTEM_KNOWLEDGE.md §2.1):
 *  - Primary: this in-app HTTPS download, done once *before* a disaster while internet exists.
 *  - Fallback: manual sideload to [GemmaClient.modelFile].
 *  - P2P transfer over the mesh is still deferred to V2.
 *
 * Implementation notes:
 *  - Downloads to "<model>.litertlm.part", then renames to the final path — a half-finished file
 *    never looks loadable to [GemmaClient].
 *  - Supports HTTP Range resume: an interrupted 3 GB download continues from where it stopped.
 *  - Runs on a single background thread; the download keeps going even if the UI screen closes.
 *    Callbacks are delivered on the main thread.
 */
object ModelDownloader {

    private const val TAG = "ModelDownloader"

    // ─────────────────────────────────────────────────────────────────────────
    //  The direct HTTPS URL of the Gemma .litertlm model file.
    //
    //  Unlike the license-gated Google / Kaggle Gemma builds, the `litert-community`
    //  Hugging Face repos are publicly hosted and can be hot-linked directly via the
    //  `/resolve/main/` path. This points at the CPU/GPU build of Gemma 4 E2B in
    //  LiteRT-LM's `.litertlm` format (the Qualcomm-suffixed files in that repo are
    //  NPU-specific and not portable across devices).
    //
    //  To self-host instead (e.g. for a private mirror), swap in any direct HTTPS URL.
    // ─────────────────────────────────────────────────────────────────────────
    const val MODEL_URL =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"

    /** Shown in the download prompt. */
    const val MODEL_DISPLAY_NAME = "Gemma 4 E2B (LiteRT-LM)"

    /** Rough size for the prompt, shown before the server reports the real Content-Length. */
    const val APPROX_SIZE_LABEL = "~2.6 GB"

    enum class State { IDLE, DOWNLOADING, VERIFYING, DONE, FAILED }

    data class Progress(
        val state: State,
        val bytesDownloaded: Long = 0L,
        val bytesTotal: Long = -1L,        // -1 = server didn't report a length
        val error: String? = null
    ) {
        /** 0..100, or 0 when the total is unknown. */
        val percent: Int
            get() = if (bytesTotal > 0L) ((bytesDownloaded * 100L) / bytesTotal).toInt() else 0
    }

    @Volatile
    var current: Progress = Progress(State.IDLE)
        private set

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var listener: ((Progress) -> Unit)? = null
    @Volatile private var cancelRequested = false

    /** True while a download or verification is actively running. */
    fun isBusy(): Boolean =
        current.state == State.DOWNLOADING || current.state == State.VERIFYING

    /** False while [MODEL_URL] is still the placeholder — the UI uses this to warn the user. */
    fun isConfigured(): Boolean = !MODEL_URL.contains("REPLACE-WITH-YOUR-HOST")

    /** Re-attach a UI listener without (re)starting — e.g. the screen was reopened mid-download. */
    fun attach(onProgress: (Progress) -> Unit) {
        listener = onProgress
        onProgress(current)
    }

    fun detach() { listener = null }

    fun cancel() { cancelRequested = true }

    /**
     * Starts — or resumes — the download. Safe to call when already running (just re-attaches the
     * listener). On success the final model file is in place and [GemmaClient] can load it.
     */
    fun start(context: Context, onProgress: (Progress) -> Unit) {
        listener = onProgress
        if (isBusy()) { onProgress(current); return }

        val appContext = context.applicationContext
        val finalFile = GemmaClient.modelFile(appContext)
        val partFile = File(finalFile.parentFile, finalFile.name + ".part")
        cancelRequested = false

        update(Progress(State.DOWNLOADING, partFile.length(), -1L))

        worker.execute {
            var connection: HttpURLConnection? = null
            try {
                val existing = if (partFile.exists()) partFile.length() else 0L
                connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = AppConstants.CONNECT_TIMEOUT_MS
                    readTimeout = AppConstants.READ_TIMEOUT_MS
                    if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
                }
                connection.connect()

                val code = connection.responseCode
                val resuming = code == HttpURLConnection.HTTP_PARTIAL
                if (code != HttpURLConnection.HTTP_OK && !resuming) {
                    throw Exception("Server returned HTTP $code")
                }

                val startAt = if (resuming) existing else 0L
                val remaining = connection.contentLengthLong          // bytes still to come
                val total = if (remaining > 0L) startAt + remaining else -1L

                // Fresh download but a stale .part exists → discard it.
                if (!resuming && partFile.exists()) partFile.delete()

                val out = RandomAccessFile(partFile, "rw")
                out.seek(startAt)
                var downloaded = startAt

                connection.inputStream.use { input ->
                    val buf = ByteArray(AppConstants.DOWNLOAD_BUFFER_SIZE)
                    var lastPct = -1
                    var lastPostMs = 0L
                    while (true) {
                        if (cancelRequested) {
                            out.close()
                            // Keep the .part file so a later Retry resumes instead of restarting.
                            update(Progress(State.IDLE, downloaded, total))
                            Log.d(TAG, "Cancelled at $downloaded bytes (.part kept for resume)")
                            return@execute
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n

                        // Throttle UI updates: on each 1% change, or at least every 400 ms.
                        val pct = if (total > 0L) ((downloaded * 100L) / total).toInt() else -1
                        val now = System.currentTimeMillis()
                        if (pct != lastPct || now - lastPostMs > AppConstants.DOWNLOAD_UI_THROTTLE_MS) {
                            lastPct = pct
                            lastPostMs = now
                            update(Progress(State.DOWNLOADING, downloaded, total))
                        }
                    }
                }
                out.close()

                // Verification: size sanity check (a SHA-256 check can be added here later).
                update(Progress(State.VERIFYING, downloaded, total))
                if (total > 0L && partFile.length() != total) {
                    throw Exception("Size mismatch: got ${partFile.length()}, expected $total")
                }

                // Move the completed file into place.
                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    throw Exception("Could not move the downloaded file into place")
                }

                update(Progress(State.DONE, downloaded, total))
                Log.d(TAG, "Model downloaded to ${finalFile.absolutePath}")
            } catch (t: Throwable) {
                Log.e(TAG, "Download failed: ${t.message}", t)
                update(
                    Progress(
                        State.FAILED,
                        current.bytesDownloaded,
                        current.bytesTotal,
                        t.message ?: "Download failed"
                    )
                )
            } finally {
                connection?.disconnect()
            }
        }
    }

    /** Deletes the partial and final model files, for a clean re-download. */
    fun clear(context: Context) {
        val appContext = context.applicationContext
        worker.execute {
            val finalFile = GemmaClient.modelFile(appContext)
            File(finalFile.parentFile, finalFile.name + ".part").delete()
            finalFile.delete()
            update(Progress(State.IDLE))
        }
    }

    private fun update(p: Progress) {
        current = p
        mainHandler.post { listener?.invoke(p) }
    }
}
