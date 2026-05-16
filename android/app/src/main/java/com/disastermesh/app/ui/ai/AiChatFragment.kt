package com.disastermesh.app.ui.ai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.disastermesh.app.R
import com.disastermesh.app.adapter.AiChatAdapter
import com.disastermesh.app.ai.AiHistory
import com.disastermesh.app.ai.GemmaClient
import com.disastermesh.app.ai.LanguagePreference
import com.disastermesh.app.ai.LanguagePromptWrapper
import com.disastermesh.app.ai.ModelDownloader
import com.disastermesh.app.ai.PromptTemplates
import com.disastermesh.app.ai.SignalProcessor
import com.disastermesh.app.ai.SurvivalLanguage
import com.disastermesh.app.databinding.FragmentAiBinding
import com.disastermesh.app.db.AppDatabase
import com.disastermesh.app.db.entities.AiMessageEntity
import com.disastermesh.app.db.entities.AiSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class AiChatFragment : Fragment() {

    private var _binding: FragmentAiBinding? = null
    private val binding get() = _binding!!

    private var isLoading = false

    private lateinit var db: AppDatabase
    private lateinit var adapter: AiChatAdapter

    private val viewModel: AiChatViewModel by viewModels()

    /** ID of the conversation thread we're currently rendering. */
    private var activeSessionId: String = DEFAULT_SESSION_ID

    /** Handle to the active observe-session coroutine — cancelled on session switch. */
    private var observerJob: Job? = null

    /** True while the input field is empty — feeds the suggestion-panel visibility. */
    private val inputIsEmpty = kotlinx.coroutines.flow.MutableStateFlow(true)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAiBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = AppDatabase.getInstance(requireContext())

        setupChatList()
        setupSuggestions()
        setupAskButton()
        setupLanguageButton()
        setupNewSessionButton()
        ensureSessionAndObserve(DEFAULT_SESSION_ID)
        warmUpModel()
    }

    // ── Chat list ─────────────────────────────────────────────────────────────

    private fun setupChatList() {
        adapter = AiChatAdapter(onPinToggle = { msg ->
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                AiHistory.setPinned(db, msg.id, !msg.pinned)
            }
        })
        binding.rvAiMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvAiMessages.adapter = adapter
    }

    /**
     * Seed the session if missing, then observe the merged stream:
     *   committed messages (DAO Flow)  +  volatile streaming bubble (ViewModel)
     *
     * Combining inside the Fragment means a chunk update on the ViewModel's
     * StateFlow re-emits the merged list every time the AI types another token
     * — Room is NEVER touched during streaming. The streaming bubble is a
     * synthetic AiMessageEntity with a sentinel id so DiffUtil treats it as
     * the same row across updates (only its text changes).
     *
     * The block is wrapped in [repeatOnLifecycle] so collection pauses when
     * the Fragment stops and resumes with the latest snapshot on return.
     */
    private fun ensureSessionAndObserve(sessionId: String) {
        activeSessionId = sessionId
        observerJob?.cancel()
        observerJob = viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (db.aiSessionDao().getById(sessionId) == null) {
                    val now = System.currentTimeMillis()
                    db.aiSessionDao().upsert(
                        AiSessionEntity(
                            id            = sessionId,
                            topic         = "FREEFORM",
                            title         = "Survival Chat",
                            createdAt     = now,
                            lastMessageAt = now
                        )
                    )
                }
            }
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    db.aiMessageDao().observeSession(sessionId),
                    viewModel.streaming,
                    inputIsEmpty
                ) { committed, draft, inputEmpty ->
                    val merged = if (draft != null && draft.sessionId == sessionId) {
                        committed + AiMessageEntity(
                            id        = STREAMING_DRAFT_ID,
                            sessionId = sessionId,
                            isUser    = false,
                            text      = draft.text,
                            createdAt = draft.startedAt
                        )
                    } else {
                        committed
                    }
                    // Suggestion panel is only mounted on first-run state:
                    // no committed messages, no streaming bubble, no typed input.
                    val showSuggestions = merged.isEmpty() && inputEmpty
                    merged to showSuggestions
                }.collectLatest { (merged, showSuggestions) ->
                    val b = _binding ?: return@collectLatest
                    b.suggestionPanel.visibility = if (showSuggestions) View.VISIBLE else View.GONE
                    adapter.submitList(merged) {
                        val bind = _binding ?: return@submitList
                        if (merged.isNotEmpty()) bind.rvAiMessages.scrollToPosition(merged.size - 1)
                    }
                }
            }
        }
    }

    private fun setupNewSessionButton() {
        binding.btnNewSession.setOnClickListener {
            // Fresh thread — new id, observation switches to it.
            val newId = "ai-session-${UUID.randomUUID()}"
            ensureSessionAndObserve(newId)
        }
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

    // ── First-run suggestion panel ────────────────────────────────────────────

    /**
     * Suggestion panel lifecycle:
     *  - Mounted ONLY when (chat is empty) AND (input is empty) AND (no streaming).
     *  - Auto-dismisses the instant the user types one character (TextWatcher
     *    hides the panel via the combine() observer re-evaluating empty-input
     *    state) OR taps a row (we fire the query directly — chat becomes
     *    non-empty → observer hides the panel).
     */
    private fun setupSuggestions() {
        binding.suggestion1.setOnClickListener { runSuggestion("How do I treat a deep cut?") }
        binding.suggestion2.setOnClickListener { runSuggestion("What supplies do I need for a flood?") }
        binding.suggestion3.setOnClickListener { runSuggestion("How can I make water safer to drink?") }
        binding.suggestion4.setOnClickListener { runSuggestion("What is the safest shelter during an aftershock?") }

        // Single character of input → hide the panel immediately. We trigger
        // a fresh evaluation via the StateFlow the combine() observer reads.
        binding.etQuestion.addTextChangedListener { editable ->
            inputIsEmpty.value = editable.isNullOrEmpty()
        }
    }

    /** Tap-to-send: stuffs the suggestion into the input field and runs ask(). */
    private fun runSuggestion(text: String) {
        binding.etQuestion.setText(text)
        binding.etQuestion.setSelection(text.length)
        ask()
    }

    // ── Ask ───────────────────────────────────────────────────────────────────

    private fun setupAskButton() {
        binding.btnAsk.setOnClickListener { ask() }
        binding.etQuestion.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { ask(); true } else false
        }
    }

    /**
     * Streaming ask flow:
     *   Main → IO: persist user turn → ViewModel.startStream() →
     *   collect generateStream() pushing each chunk to ViewModel →
     *   on completion: IO: persist FINAL text → ViewModel.finishStream()
     *
     * Room is written exactly TWICE per turn (user + final AI) — never per
     * chunk. The bubble grows in real time off the ViewModel's StateFlow.
     */
    private fun ask() {
        val question = binding.etQuestion.text.toString().trim()
        if (question.isEmpty() || isLoading) return
        if (GemmaClient.status != GemmaClient.Status.READY) {
            warmUpModel()
            return
        }

        val lang      = LanguagePreference.current
        val sessionId = activeSessionId

        viewLifecycleOwner.lifecycleScope.launch {
            setLoading(true)
            binding.etQuestion.text.clear()

            // 1. Persist user turn — Flow re-emits, user bubble appears.
            withContext(Dispatchers.IO) {
                AiHistory.appendMessage(db, sessionId, isUser = true, text = question)
            }

            // 2. Open the volatile bubble — empty for now, the chunks will
            //    fill it. The chat list re-renders via combine() on every
            //    pushChunk(); Room is untouched during this loop.
            viewModel.startStream(sessionId)
            var finalText = ""

            GemmaClient.generateStream(
                prompt            = LanguagePromptWrapper.wrap(question, lang),
                systemInstruction = PromptTemplates.assistantSystemInstruction(lang)
            )
                .catch { t ->
                    finalText = "⚠ Could not generate an answer:\n${t.message ?: "unknown error"}"
                    viewModel.pushChunk(finalText)
                }
                .onCompletion {
                    // 3. Finalise: write exactly ONE row to Room with the full
                    //    text, then drop the volatile bubble. The DAO Flow
                    //    re-emits and the committed bubble takes the draft's
                    //    place in the same RecyclerView slot.
                    if (finalText.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            AiHistory.appendMessage(db, sessionId, isUser = false, text = finalText)
                        }
                    }
                    viewModel.finishStream()
                    setLoading(false)
                }
                .collect { cumulativeText ->
                    finalText = cumulativeText
                    viewModel.pushChunk(cumulativeText)
                }
        }
    }

    /**
     * Light loading state — just disables the send button now. The growing
     * AI bubble itself is the visible "typing" indicator; the dedicated
     * pulsing pill was removed.
     */
    private fun setLoading(loading: Boolean) {
        isLoading = loading
        setAskEnabled(!loading)
        setStatus(if (loading) "STREAMING" else "READY",
                  if (loading) R.color.mesh_searching else R.color.ai_ready)
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
                    setAskEnabled(!isLoading)
                    binding.downloadPanel.visibility = View.GONE
                    SignalProcessor.drainUnclassified(requireContext())
                }
                GemmaClient.Status.ERROR -> {
                    setStatus("AI ERROR", R.color.priority_critical)
                    setAskEnabled(false)
                    binding.downloadPanel.visibility = View.GONE
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
        observerJob?.cancel()
        observerJob = null
        ModelDownloader.detach()
        _binding = null
    }

    companion object {
        /** Default freeform session every user lands on. New threads use a fresh UUID. */
        private const val DEFAULT_SESSION_ID = "ai-default"

        /** Sentinel id for the volatile streaming bubble. Stable across
         *  chunk emissions so DiffUtil treats it as the same row and only
         *  rebinds its text instead of inserting/removing the bubble. */
        private const val STREAMING_DRAFT_ID = "ai-streaming-draft"
    }
}
