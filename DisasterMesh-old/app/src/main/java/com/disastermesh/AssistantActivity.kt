package com.disastermesh

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.disastermesh.ai.GemmaClient
import com.disastermesh.ai.ModelDownloader
import com.disastermesh.ai.PromptTemplates

/**
 * AssistantActivity — the V1 on-device Gemma test surface.
 *
 * A self-contained Help Assistant: the user asks an emergency/survival question and Gemma
 * (running locally via [GemmaClient]) answers, fully offline. When the model is missing it shows
 * a CODM/PUBG-style in-app download panel ([ModelDownloader]).
 *
 * This screen deliberately touches NOTHING in the mesh / DB / chat path — if the model is absent
 * the rest of the app is unaffected.
 */
class AssistantActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvAnswer: TextView
    private lateinit var scrollAnswer: ScrollView
    private lateinit var etQuestion: EditText
    private lateinit var btnAsk: Button

    // Model download panel
    private lateinit var downloadPanel: LinearLayout
    private lateinit var tvDownloadInfo: TextView
    private lateinit var progressDownload: ProgressBar
    private lateinit var tvDownloadProgress: TextView
    private lateinit var btnDownload: Button

    /** True while a generation is in flight — blocks concurrent asks. */
    private var generating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_assistant)

        tvStatus     = findViewById(R.id.tvStatus)
        tvAnswer     = findViewById(R.id.tvAnswer)
        scrollAnswer = findViewById(R.id.scrollAnswer)
        etQuestion   = findViewById(R.id.etQuestion)
        btnAsk       = findViewById(R.id.btnAsk)

        downloadPanel      = findViewById(R.id.downloadPanel)
        tvDownloadInfo     = findViewById(R.id.tvDownloadInfo)
        progressDownload   = findViewById(R.id.progressDownload)
        tvDownloadProgress = findViewById(R.id.tvDownloadProgress)
        btnDownload        = findViewById(R.id.btnDownload)

        btnAsk.setOnClickListener { ask() }
        etQuestion.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { ask(); true } else false
        }
        btnDownload.setOnClickListener { onDownloadButton() }

        // Load the model (idempotent — also fine if already warm from a previous open).
        GemmaClient.warmUp(this) { status -> applyStatus(status) }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop receiving download callbacks; the download itself keeps running in the background.
        ModelDownloader.detach()
    }

    private fun applyStatus(status: GemmaClient.Status) {
        when (status) {
            GemmaClient.Status.ABSENT -> {
                setStatus("● Model needed", "#F78166")
                btnAsk.isEnabled = false
                showDownloadPanel()
            }
            GemmaClient.Status.LOADING -> {
                setStatus("● Loading model…", "#D29922")
                btnAsk.isEnabled = false
                downloadPanel.visibility = View.GONE
            }
            GemmaClient.Status.READY -> {
                setStatus("● Ready", "#3FB950")
                btnAsk.isEnabled = !generating
                downloadPanel.visibility = View.GONE
            }
            GemmaClient.Status.ERROR -> {
                setStatus("● Error", "#F78166")
                btnAsk.isEnabled = false
                downloadPanel.visibility = View.GONE
                tvAnswer.text = "Failed to load the model:\n${GemmaClient.lastError ?: "unknown error"}"
            }
        }
    }

    // ── Model download (CODM / PUBG-style in-app fetch) ───────────────────────

    private fun showDownloadPanel() {
        downloadPanel.visibility = View.VISIBLE
        tvAnswer.text = "The offline assistant needs a language model.\n\n" +
            "Tap “Download model” below to fetch it once while you have internet — " +
            "after that it runs fully offline.\n\n" +
            "Advanced: you can also sideload it manually to\n" +
            GemmaClient.modelFile(this).absolutePath

        // If a download is already running (the screen was reopened), re-attach to its progress.
        if (ModelDownloader.isBusy()) {
            ModelDownloader.attach { p -> renderDownload(p) }
        } else {
            renderDownload(ModelDownloader.current)
        }
    }

    private fun onDownloadButton() {
        when (ModelDownloader.current.state) {
            ModelDownloader.State.DOWNLOADING,
            ModelDownloader.State.VERIFYING -> ModelDownloader.cancel()

            else -> {
                if (!ModelDownloader.isConfigured()) {
                    Toast.makeText(
                        this,
                        "No model URL configured — set ModelDownloader.MODEL_URL",
                        Toast.LENGTH_LONG
                    ).show()
                    tvDownloadInfo.text = "⚠ No download URL is configured.\n\n" +
                        "Set ModelDownloader.MODEL_URL to your hosted Gemma .task file, " +
                        "or sideload the model manually to the path shown above."
                    return
                }
                ModelDownloader.start(this) { p -> renderDownload(p) }
            }
        }
    }

    private fun renderDownload(p: ModelDownloader.Progress) {
        when (p.state) {
            ModelDownloader.State.IDLE -> {
                tvDownloadInfo.text = "${ModelDownloader.MODEL_DISPLAY_NAME} — " +
                    "${ModelDownloader.APPROX_SIZE_LABEL}.\n" +
                    "Download once on Wi-Fi; the assistant then works fully offline."
                progressDownload.visibility = View.GONE
                tvDownloadProgress.visibility = View.GONE
                btnDownload.text = "Download model"
                btnDownload.isEnabled = true
            }

            ModelDownloader.State.DOWNLOADING -> {
                tvDownloadInfo.text = "Downloading ${ModelDownloader.MODEL_DISPLAY_NAME}…"
                progressDownload.visibility = View.VISIBLE
                tvDownloadProgress.visibility = View.VISIBLE
                if (p.bytesTotal > 0L) {
                    progressDownload.isIndeterminate = false
                    progressDownload.progress = p.percent
                    tvDownloadProgress.text =
                        "${formatSize(p.bytesDownloaded)} / ${formatSize(p.bytesTotal)}  (${p.percent}%)"
                } else {
                    progressDownload.isIndeterminate = true
                    tvDownloadProgress.text = "${formatSize(p.bytesDownloaded)} downloaded…"
                }
                btnDownload.text = "Cancel"
                btnDownload.isEnabled = true
            }

            ModelDownloader.State.VERIFYING -> {
                tvDownloadInfo.text = "Verifying download…"
                progressDownload.visibility = View.VISIBLE
                progressDownload.isIndeterminate = true
                btnDownload.isEnabled = false
            }

            ModelDownloader.State.DONE -> {
                downloadPanel.visibility = View.GONE
                Toast.makeText(this, "Model ready", Toast.LENGTH_SHORT).show()
                // Hand off to the LLM bridge to load the freshly downloaded file.
                GemmaClient.warmUp(this) { status -> applyStatus(status) }
            }

            ModelDownloader.State.FAILED -> {
                tvDownloadInfo.text =
                    "Download failed — tap Retry. It resumes from where it stopped."
                progressDownload.visibility = View.GONE
                tvDownloadProgress.visibility = View.VISIBLE
                tvDownloadProgress.text = p.error ?: "Unknown error"
                btnDownload.text = "Retry"
                btnDownload.isEnabled = true
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) String.format("%.2f GB", mb / 1024.0)
        else String.format("%.0f MB", mb)
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    private fun ask() {
        val question = etQuestion.text.toString().trim()
        if (question.isEmpty() || generating) return
        if (GemmaClient.status != GemmaClient.Status.READY) {
            applyStatus(GemmaClient.status)
            return
        }

        generating = true
        btnAsk.isEnabled = false
        setStatus("● Thinking…", "#D29922")
        tvAnswer.text = "…"
        scrollAnswer.scrollTo(0, 0)

        val prompt = PromptTemplates.helpAssistant(question)
        GemmaClient.generate(
            prompt,
            systemInstruction = PromptTemplates.ASSISTANT_SYSTEM_INSTRUCTION,
            onResult = { answer ->
                generating = false
                btnAsk.isEnabled = true
                setStatus("● Ready", "#3FB950")
                tvAnswer.text = answer
                etQuestion.text.clear()
            },
            onError = { message ->
                generating = false
                btnAsk.isEnabled = true
                setStatus("● Error", "#F78166")
                tvAnswer.text = "Could not generate an answer:\n$message"
            }
        )
    }

    private fun setStatus(text: String, colorHex: String) {
        tvStatus.text = text
        tvStatus.setTextColor(Color.parseColor(colorHex))
    }

    // Note: GemmaClient is an app-wide singleton; we intentionally keep the model loaded across
    // screen open/close so re-entry is instant. A future BatteryWatchdog (post-V1) will own unload.
}
