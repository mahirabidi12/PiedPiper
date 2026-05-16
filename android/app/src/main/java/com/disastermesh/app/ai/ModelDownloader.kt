package com.disastermesh.app.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * ModelDownloader — in-app HTTPS download of the Gemma 4 E2B model with resume support.
 *
 * Download once on Wi-Fi (pre-disaster). Downloads to a `.part` file, renames when complete
 * so a half-finished file never looks loadable to [GemmaClient].
 *
 * HTTP Range is used for resume: an interrupted 2.6 GB download continues from last byte.
 * Callbacks are on the main thread; the download keeps going if the UI screen closes.
 */
object ModelDownloader {

    private const val TAG = "ModelDownloader"

    const val MODEL_URL =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"

    const val MODEL_DISPLAY_NAME = "Gemma 4 E2B (LiteRT-LM)"
    const val APPROX_SIZE_LABEL  = "~2.6 GB"

    // Expected SHA-256 digest of the canonical model binary.
    // Obtain from the model card: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
    // Run:  sha256sum gemma-4-E2B-it.litertlm   and paste the result here before shipping.
    private const val MODEL_SHA256 = ""  // empty = skip integrity check

    enum class State { IDLE, DOWNLOADING, VERIFYING, DONE, FAILED }

    data class Progress(
        val state: State,
        val bytesDownloaded: Long = 0L,
        val bytesTotal: Long = -1L,
        val error: String? = null
    ) {
        val percent: Int
            get() = if (bytesTotal > 0L) ((bytesDownloaded * 100L) / bytesTotal).toInt() else 0
    }

    @Volatile var current: Progress = Progress(State.IDLE)
        private set

    private val worker      = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var listener: ((Progress) -> Unit)? = null
    @Volatile private var cancelRequested = false

    fun isBusy(): Boolean =
        current.state == State.DOWNLOADING || current.state == State.VERIFYING

    fun attach(onProgress: (Progress) -> Unit) { listener = onProgress; onProgress(current) }
    fun detach() { listener = null }
    fun cancel() { cancelRequested = true }

    /**
     * Starts — or resumes — the download. Safe to call when already running (just re-attaches).
     */
    fun start(context: Context, onProgress: (Progress) -> Unit) {
        listener = onProgress
        if (isBusy()) { onProgress(current); return }

        val appContext = context.applicationContext
        val finalFile  = GemmaClient.modelFile(appContext)
        val partFile   = File(finalFile.parentFile, finalFile.name + ".part")
        cancelRequested = false

        update(Progress(State.DOWNLOADING, partFile.length(), -1L))

        worker.execute {
            var connection: HttpURLConnection? = null
            try {
                val existing   = if (partFile.exists()) partFile.length() else 0L
                connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout    = 30_000
                    // CACHE LEAK FIX — explicitly disable response caching.
                    // Without this, a system-installed HttpResponseCache (or any
                    // transitive lib that installs one) will copy the entire 2.6 GB
                    // stream into context.cacheDir as a side effect, producing the
                    // ~750 MB ghost cache users were seeing. Bytes flow straight
                    // from the socket into the .part file in filesDir — no detour.
                    useCaches        = false
                    defaultUseCaches = false
                    if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
                }
                connection.connect()

                val code     = connection.responseCode
                val resuming = code == HttpURLConnection.HTTP_PARTIAL
                if (code != HttpURLConnection.HTTP_OK && !resuming)
                    throw Exception("Server returned HTTP $code")

                val startAt   = if (resuming) existing else 0L
                val remaining = connection.contentLengthLong
                val total     = if (remaining > 0L) startAt + remaining else -1L

                if (!resuming && partFile.exists()) partFile.delete()

                val out = RandomAccessFile(partFile, "rw")
                out.seek(startAt)
                var downloaded = startAt

                connection.inputStream.use { input ->
                    val buf      = ByteArray(64 * 1024)
                    var lastPct  = -1
                    var lastPost = 0L
                    while (true) {
                        if (cancelRequested) {
                            out.close()
                            update(Progress(State.IDLE, downloaded, total))
                            return@execute
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        val pct = if (total > 0L) ((downloaded * 100L) / total).toInt() else -1
                        val now = System.currentTimeMillis()
                        if (pct != lastPct || now - lastPost > 400L) {
                            lastPct  = pct
                            lastPost = now
                            update(Progress(State.DOWNLOADING, downloaded, total))
                        }
                    }
                }
                out.close()

                update(Progress(State.VERIFYING, downloaded, total))
                if (total > 0L && partFile.length() != total)
                    throw Exception("Size mismatch: got ${partFile.length()}, expected $total")

                if (MODEL_SHA256.isNotEmpty()) {
                    val actualHash = sha256(partFile)
                    if (actualHash != MODEL_SHA256) {
                        partFile.delete()
                        throw Exception("Integrity check failed — model binary may be corrupt or tampered.\nExpected: $MODEL_SHA256\nActual:   $actualHash")
                    }
                }

                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile))
                    throw Exception("Could not move downloaded file into place")

                update(Progress(State.DONE, downloaded, total))
                Log.d(TAG, "Model downloaded to ${finalFile.absolutePath}")
            } catch (t: Throwable) {
                Log.e(TAG, "Download failed: ${t.message}", t)
                update(Progress(State.FAILED, current.bytesDownloaded, current.bytesTotal, t.message ?: "Download failed"))
            } finally {
                connection?.disconnect()
            }
        }
    }

    fun clear(context: Context) {
        val appContext = context.applicationContext
        worker.execute {
            val finalFile = GemmaClient.modelFile(appContext)
            File(finalFile.parentFile, finalFile.name + ".part").delete()
            finalFile.delete()
            update(Progress(State.IDLE))
        }
    }

    /**
     * Sweep orphaned download artefacts on app start.
     *
     * Scans `cacheDir` and the model directory for `.part`, `.tmp`, `.download`
     * files left behind by a previous interrupted download or by a transient
     * HttpResponseCache copy. Pure reclaim — never touches a download that is
     * currently in flight (guarded by [isBusy]) and never touches the finalised
     * model binary.
     *
     * Run from [MeshService.onCreate] (or any single-shot init point) so the
     * user reclaims storage every time the app launches.
     */
    fun clearTempDownloads(context: Context) {
        if (isBusy()) return                                // never sweep a live download
        val appContext = context.applicationContext
        worker.execute {
            // Belt-and-braces: if anything ever called HttpResponseCache.install(),
            // flush + delete it. We never install one ourselves; this catches
            // transitively-installed caches that might be hoarding the model stream.
            try {
                val installed = android.net.http.HttpResponseCache.getInstalled()
                if (installed != null) {
                    installed.flush()
                    installed.delete()
                }
            } catch (_: Throwable) { /* no cache installed — fine */ }

            val orphanSuffixes = listOf(".part", ".tmp", ".download")
            val finalModel     = GemmaClient.modelFile(appContext)
            val activePart     = File(finalModel.parentFile, finalModel.name + ".part")

            var freed = 0L
            var swept = 0

            // 1. cacheDir — model bits must NEVER live here. Sweep aggressively.
            appContext.cacheDir.walkTopDown()
                .filter { it.isFile }
                .filter { f -> orphanSuffixes.any { f.name.endsWith(it) } || f.name.endsWith(".litertlm") }
                .forEach { f ->
                    val size = f.length()
                    if (f.delete()) { freed += size; swept++ }
                }

            // 2. Model directory in filesDir / externalFilesDir — keep the
            //    finalised model only; sweep stale .part / .tmp / .download.
            //    Skip the active .part if a download is somehow racing us.
            finalModel.parentFile?.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                if (f.absolutePath == finalModel.absolutePath) return@forEach   // keep model
                if (f.absolutePath == activePart.absolutePath && isBusy()) return@forEach
                if (orphanSuffixes.any { f.name.endsWith(it) }) {
                    val size = f.length()
                    if (f.delete()) { freed += size; swept++ }
                }
            }

            if (swept > 0) {
                Log.i(TAG, "clearTempDownloads — swept $swept orphan(s), freed ${freed / 1024 / 1024} MB")
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (input.read(buf).also { n = it } >= 0) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun update(p: Progress) {
        current = p
        mainHandler.post { listener?.invoke(p) }
    }
}
