package com.disastermesh.app.ui.ai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import com.disastermesh.app.R
import com.disastermesh.app.ai.GemmaClient
import com.disastermesh.app.ai.LanguagePreference
import com.disastermesh.app.ai.ModelDownloader
import com.disastermesh.app.ai.PromptTemplates
import com.disastermesh.app.ai.SignalProcessor
import com.disastermesh.app.ai.SurvivalLanguage
import com.disastermesh.app.databinding.FragmentAiBinding

class AiChatFragment : Fragment() {

    private var _binding: FragmentAiBinding? = null
    private val binding get() = _binding!!

    private var generating = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAiBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupQuickChips()
        setupAskButton()
        setupLanguageButton()
        warmUpModel()
    }

    // ── Language switcher ─────────────────────────────────────────────────────

    private fun setupLanguageButton() {
        updateLanguageLabel(LanguagePreference.current)
        binding.btnLanguage.setOnClickListener {
            LanguageBottomSheet { lang ->
                updateLanguageLabel(lang)
            }.show(parentFragmentManager, "lang")
        }
    }

    private fun updateLanguageLabel(lang: SurvivalLanguage) {
        binding.tvCurrentLanguage.text = lang.code.uppercase()
    }

    // ── Quick-prompt chips ────────────────────────────────────────────────────

    private fun setupQuickChips() {
        binding.chipCut.setOnClickListener {
            binding.etQuestion.setText("How do I treat a deep cut with no first-aid kit?")
            binding.etQuestion.setSelection(binding.etQuestion.text.length)
        }
        binding.chipShelter.setOnClickListener {
            binding.etQuestion.setText("What is the safest shelter during an aftershock?")
            binding.etQuestion.setSelection(binding.etQuestion.text.length)
        }
        binding.chipWater.setOnClickListener {
            binding.etQuestion.setText("How can I make water safer to drink?")
            binding.etQuestion.setSelection(binding.etQuestion.text.length)
        }
    }

    // ── Ask ───────────────────────────────────────────────────────────────────

    private fun setupAskButton() {
        binding.btnAsk.setOnClickListener { ask() }
        binding.etQuestion.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { ask(); true } else false
        }
    }

    private fun ask() {
        val question = binding.etQuestion.text.toString().trim()
        if (question.isEmpty() || generating) return
        if (GemmaClient.status != GemmaClient.Status.READY) {
            warmUpModel()
            return
        }

        val lang = LanguagePreference.current
        generating = true
        setAskEnabled(false)
        setStatus("THINKING", R.color.mesh_searching)
        binding.tvAnswer.text = "Thinking…"
        binding.scrollAnswer.scrollTo(0, 0)

        GemmaClient.generate(
            prompt            = PromptTemplates.helpAssistant(question),
            systemInstruction = PromptTemplates.assistantSystemInstruction(lang),
            onResult          = { answer ->
                generating = false
                setAskEnabled(true)
                setStatus("READY", R.color.ai_ready)
                val finalAnswer = if (lang != SurvivalLanguage.ENGLISH) {
                    "[Translating to ${lang.displayName}…]\n\n$answer"
                } else {
                    answer
                }
                binding.tvAnswer.text = finalAnswer
                binding.etQuestion.text.clear()
            },
            onError           = { err ->
                generating = false
                setAskEnabled(true)
                setStatus("ERROR", R.color.priority_critical)
                binding.tvAnswer.text = "Could not generate an answer:\n$err"
            }
        )
    }

    // ── Model management ──────────────────────────────────────────────────────

    private fun warmUpModel() {
        GemmaClient.warmUp(requireContext()) { status ->
            if (!isAdded) return@warmUp
            when (status) {
                GemmaClient.Status.ABSENT -> {
                    setStatus("MODEL ABSENT", R.color.ai_absent)
                    setAskEnabled(false)
                    showDownloadPanel()
                }
                GemmaClient.Status.LOADING -> {
                    setStatus("LOADING MODEL…", R.color.ai_loading)
                    setAskEnabled(false)
                    binding.downloadPanel.visibility = View.GONE
                }
                GemmaClient.Status.READY -> {
                    setStatus("AI READY", R.color.ai_ready)
                    setAskEnabled(!generating)
                    binding.downloadPanel.visibility = View.GONE
                    SignalProcessor.drainUnclassified(requireContext())
                }
                GemmaClient.Status.ERROR -> {
                    setStatus("AI ERROR", R.color.priority_critical)
                    setAskEnabled(false)
                    binding.downloadPanel.visibility = View.GONE
                    binding.tvAnswer.text =
                        "Failed to load model:\n${GemmaClient.lastError ?: "unknown error"}"
                }
            }
        }
    }

    private fun showDownloadPanel() {
        binding.downloadPanel.visibility = View.VISIBLE
        binding.tvDownloadInfo.text =
            "${ModelDownloader.MODEL_DISPLAY_NAME}  /  ${ModelDownloader.APPROX_SIZE_LABEL}\n" +
            "Download once on Wi-Fi. The assistant works fully offline afterward.\n\n" +
            "Sideload path:\n${GemmaClient.modelFile(requireContext()).absolutePath}"

        if (ModelDownloader.isBusy()) {
            ModelDownloader.attach { renderDownload(it) }
        } else {
            renderDownload(ModelDownloader.current)
        }

        binding.btnDownload.setOnClickListener {
            when (ModelDownloader.current.state) {
                ModelDownloader.State.DOWNLOADING,
                ModelDownloader.State.VERIFYING -> ModelDownloader.cancel()
                else -> ModelDownloader.start(requireContext()) { renderDownload(it) }
            }
        }
    }

    private fun renderDownload(progress: ModelDownloader.Progress) {
        if (!isAdded) return
        when (progress.state) {
            ModelDownloader.State.IDLE -> {
                binding.tvDownloadInfo.text =
                    "${ModelDownloader.MODEL_DISPLAY_NAME}  /  ${ModelDownloader.APPROX_SIZE_LABEL}\n" +
                    "Download once on Wi-Fi."
                binding.progressDownload.visibility  = View.GONE
                binding.tvDownloadProgress.visibility = View.GONE
                binding.tvDownloadBtnLabel.text      = "DOWNLOAD MODEL (~2.6 GB)"
            }

            ModelDownloader.State.DOWNLOADING -> {
                binding.tvDownloadInfo.text = "Downloading ${ModelDownloader.MODEL_DISPLAY_NAME}…"
                binding.progressDownload.visibility   = View.VISIBLE
                binding.tvDownloadProgress.visibility = View.VISIBLE
                if (progress.bytesTotal > 0L) {
                    binding.progressDownload.isIndeterminate = false
                    binding.progressDownload.progress        = progress.percent
                    binding.tvDownloadProgress.text =
                        "${formatSize(progress.bytesDownloaded)} / ${formatSize(progress.bytesTotal)}  (${progress.percent}%)"
                } else {
                    binding.progressDownload.isIndeterminate = true
                    binding.tvDownloadProgress.text = "${formatSize(progress.bytesDownloaded)} downloaded…"
                }
                binding.tvDownloadBtnLabel.text = "CANCEL"
            }

            ModelDownloader.State.VERIFYING -> {
                binding.tvDownloadInfo.text = "Verifying download…"
                binding.progressDownload.visibility   = View.VISIBLE
                binding.progressDownload.isIndeterminate = true
                binding.tvDownloadProgress.visibility = View.GONE
                binding.tvDownloadBtnLabel.text = "VERIFYING…"
            }

            ModelDownloader.State.DONE -> {
                binding.downloadPanel.visibility = View.GONE
                warmUpModel()
            }

            ModelDownloader.State.FAILED -> {
                binding.tvDownloadInfo.text = "Download failed. Tap Retry to resume from last byte."
                binding.progressDownload.visibility   = View.GONE
                binding.tvDownloadProgress.visibility = View.VISIBLE
                binding.tvDownloadProgress.text = progress.error ?: "Unknown error"
                binding.tvDownloadBtnLabel.text = "RETRY"
            }
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun setStatus(label: String, colorRes: Int) {
        val color = requireContext().getColor(colorRes)
        binding.tvAiStatus.text = label
        binding.tvAiStatus.setTextColor(color)
        binding.aiStatusDot.setBackgroundColor(color)
    }

    private fun setAskEnabled(enabled: Boolean) {
        binding.btnAsk.alpha = if (enabled) 1.0f else 0.4f
        binding.btnAsk.isEnabled = enabled
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) "%.2f GB".format(mb / 1024.0)
        else "%.0f MB".format(mb)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ModelDownloader.detach()
        _binding = null
    }
}
