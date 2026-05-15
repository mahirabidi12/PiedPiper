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
import com.disastermesh.ui.BottomNavHelper
import com.disastermesh.ui.NavItem

class AssistantActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvAnswer: TextView
    private lateinit var scrollAnswer: ScrollView
    private lateinit var etQuestion: EditText
    private lateinit var btnAsk: Button

    private lateinit var downloadPanel: LinearLayout
    private lateinit var tvDownloadInfo: TextView
    private lateinit var progressDownload: ProgressBar
    private lateinit var tvDownloadProgress: TextView
    private lateinit var btnDownload: Button

    private var generating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_assistant)

        tvStatus = findViewById(R.id.tvStatus)
        tvAnswer = findViewById(R.id.tvAnswer)
        scrollAnswer = findViewById(R.id.scrollAnswer)
        etQuestion = findViewById(R.id.etQuestion)
        btnAsk = findViewById(R.id.btnAsk)

        downloadPanel = findViewById(R.id.downloadPanel)
        tvDownloadInfo = findViewById(R.id.tvDownloadInfo)
        progressDownload = findViewById(R.id.progressDownload)
        tvDownloadProgress = findViewById(R.id.tvDownloadProgress)
        btnDownload = findViewById(R.id.btnDownload)

        btnAsk.setOnClickListener { ask() }
        btnDownload.setOnClickListener { onDownloadButton() }
        etQuestion.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                ask()
                true
            } else {
                false
            }
        }

        bindPromptButtons()
        GemmaClient.warmUp(this) { status -> applyStatus(status) }
        BottomNavHelper.bind(this, NavItem.ASSISTANT)
    }

    override fun onDestroy() {
        super.onDestroy()
        ModelDownloader.detach()
    }

    private fun bindPromptButtons() {
        findViewById<Button>(R.id.btnPromptCut).setOnClickListener {
            etQuestion.setText("How do I treat a deep cut with no first-aid kit?")
            etQuestion.setSelection(etQuestion.text.length)
        }
        findViewById<Button>(R.id.btnPromptShelter).setOnClickListener {
            etQuestion.setText("What is the safest shelter during an aftershock?")
            etQuestion.setSelection(etQuestion.text.length)
        }
        findViewById<Button>(R.id.btnPromptWater).setOnClickListener {
            etQuestion.setText("How can I make water safer to drink?")
            etQuestion.setSelection(etQuestion.text.length)
        }
    }

    private fun applyStatus(status: GemmaClient.Status) {
        when (status) {
            GemmaClient.Status.ABSENT -> {
                setStatus("MODEL NEEDED", "#F78166")
                btnAsk.isEnabled = false
                showDownloadPanel()
            }

            GemmaClient.Status.LOADING -> {
                setStatus("LOADING MODEL", "#D29922")
                btnAsk.isEnabled = false
                downloadPanel.visibility = View.GONE
            }

            GemmaClient.Status.READY -> {
                setStatus("READY", "#3FB950")
                btnAsk.isEnabled = !generating
                downloadPanel.visibility = View.GONE
            }

            GemmaClient.Status.ERROR -> {
                setStatus("ERROR", "#F78166")
                btnAsk.isEnabled = false
                downloadPanel.visibility = View.GONE
                tvAnswer.text = "Failed to load the model:\n${GemmaClient.lastError ?: "unknown error"}"
            }
        }
    }

    private fun showDownloadPanel() {
        downloadPanel.visibility = View.VISIBLE
        tvAnswer.text = "The assistant needs a local language model.\n\n" +
            "Download it once while you have internet access and the screen will run fully offline afterward.\n\n" +
            "Manual sideload path:\n${GemmaClient.modelFile(this).absolutePath}"

        if (ModelDownloader.isBusy()) {
            ModelDownloader.attach { progress -> renderDownload(progress) }
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
                        "No model URL configured. Set ModelDownloader.MODEL_URL.",
                        Toast.LENGTH_LONG
                    ).show()
                    tvDownloadInfo.text = "No download URL is configured.\n\n" +
                        "Set ModelDownloader.MODEL_URL to your hosted Gemma .task file or sideload it manually."
                    return
                }
                ModelDownloader.start(this) { progress -> renderDownload(progress) }
            }
        }
    }

    private fun renderDownload(progress: ModelDownloader.Progress) {
        when (progress.state) {
            ModelDownloader.State.IDLE -> {
                tvDownloadInfo.text = "${ModelDownloader.MODEL_DISPLAY_NAME} / ${ModelDownloader.APPROX_SIZE_LABEL}\n" +
                    "Download once on Wi-Fi. The assistant then works fully offline."
                progressDownload.visibility = View.GONE
                tvDownloadProgress.visibility = View.GONE
                btnDownload.text = "Download model"
                btnDownload.isEnabled = true
            }

            ModelDownloader.State.DOWNLOADING -> {
                tvDownloadInfo.text = "Downloading ${ModelDownloader.MODEL_DISPLAY_NAME}..."
                progressDownload.visibility = View.VISIBLE
                tvDownloadProgress.visibility = View.VISIBLE
                if (progress.bytesTotal > 0L) {
                    progressDownload.isIndeterminate = false
                    progressDownload.progress = progress.percent
                    tvDownloadProgress.text =
                        "${formatSize(progress.bytesDownloaded)} / ${formatSize(progress.bytesTotal)} (${progress.percent}%)"
                } else {
                    progressDownload.isIndeterminate = true
                    tvDownloadProgress.text = "${formatSize(progress.bytesDownloaded)} downloaded..."
                }
                btnDownload.text = "Cancel"
                btnDownload.isEnabled = true
            }

            ModelDownloader.State.VERIFYING -> {
                tvDownloadInfo.text = "Verifying download..."
                progressDownload.visibility = View.VISIBLE
                progressDownload.isIndeterminate = true
                tvDownloadProgress.visibility = View.GONE
                btnDownload.isEnabled = false
            }

            ModelDownloader.State.DONE -> {
                downloadPanel.visibility = View.GONE
                Toast.makeText(this, "Model ready", Toast.LENGTH_SHORT).show()
                GemmaClient.warmUp(this) { status -> applyStatus(status) }
            }

            ModelDownloader.State.FAILED -> {
                tvDownloadInfo.text = "Download failed. Tap Retry to continue from the last byte."
                progressDownload.visibility = View.GONE
                tvDownloadProgress.visibility = View.VISIBLE
                tvDownloadProgress.text = progress.error ?: "Unknown error"
                btnDownload.text = "Retry"
                btnDownload.isEnabled = true
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format("%.2f GB", mb / 1024.0)
        } else {
            String.format("%.0f MB", mb)
        }
    }

    private fun ask() {
        val question = etQuestion.text.toString().trim()
        if (question.isEmpty() || generating) return
        if (GemmaClient.status != GemmaClient.Status.READY) {
            applyStatus(GemmaClient.status)
            return
        }

        generating = true
        btnAsk.isEnabled = false
        setStatus("THINKING", "#D29922")
        tvAnswer.text = "Thinking..."
        scrollAnswer.scrollTo(0, 0)

        val prompt = PromptTemplates.helpAssistant(question)
        GemmaClient.generate(
            prompt = prompt,
            systemInstruction = PromptTemplates.ASSISTANT_SYSTEM_INSTRUCTION,
            onResult = { answer ->
                generating = false
                btnAsk.isEnabled = true
                setStatus("READY", "#3FB950")
                tvAnswer.text = answer
                etQuestion.text.clear()
            },
            onError = { message ->
                generating = false
                btnAsk.isEnabled = true
                setStatus("ERROR", "#F78166")
                tvAnswer.text = "Could not generate an answer:\n$message"
            }
        )
    }

    private fun setStatus(text: String, colorHex: String) {
        tvStatus.text = text
        tvStatus.setTextColor(Color.parseColor(colorHex))
    }
}
