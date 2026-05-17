package com.disastermesh.app.ui.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.activityViewModels
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
import java.util.Locale
import java.util.UUID

class AiChatFragment : Fragment() {

    private var _binding: FragmentAiBinding? = null
    private val binding get() = _binding!!

    private var isLoading = false

    private lateinit var db: AppDatabase
    private lateinit var adapter: AiChatAdapter
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var listening = false

    private val viewModel: AiChatViewModel by activityViewModels()

    /** ID of the conversation thread we're currently rendering. */
    private var activeSessionId: String = ""

    /** Handle to the active observe-session coroutine — cancelled on session switch. */
    private var observerJob: Job? = null

    /** True while the input field is empty — feeds the suggestion-panel visibility. */
    private val inputIsEmpty = kotlinx.coroutines.flow.MutableStateFlow(true)

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startSpeechToText()
        else Toast.makeText(requireContext(), "Microphone permission denied", Toast.LENGTH_SHORT).show()
    }

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
        setupSpeech()
        observeActiveSession()
        warmUpModel()
    }

    // ── Chat list ─────────────────────────────────────────────────────────────

    private fun setupChatList() {
        adapter = AiChatAdapter(
            onPinToggle = { msg ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    AiHistory.setPinned(db, msg.id, !msg.pinned)
                }
            },
            onSpeak = { text -> speakMessage(text) }
        )
        binding.rvAiMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvAiMessages.adapter = adapter
    }

    private fun setupSpeech() {
        textToSpeech = TextToSpeech(requireContext()) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            Log.d(TAG, "AI TTS init status=$status ready=$ttsReady")
            if (ttsReady) {
                val localeStatus = textToSpeech?.setLanguage(currentSpeechLocale())
                Log.d(TAG, "AI TTS locale=${currentSpeechLocale()} status=$localeStatus")
            }
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "AI TTS started id=$utteranceId")
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "AI TTS done id=$utteranceId")
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "AI TTS error id=$utteranceId")
                }
            })
        }

        if (SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext()).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        listening = true
                        binding.btnAiMic.text = "..."
                        binding.btnAiMic.setTextColor(requireContext().getColor(R.color.ai_loading))
                    }

                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() {
                        listening = false
                        resetMicButton()
                    }

                    override fun onError(error: Int) {
                        listening = false
                        resetMicButton()
                        Toast.makeText(requireContext(), speechErrorLabel(error), Toast.LENGTH_SHORT).show()
                    }

                    override fun onResults(results: Bundle?) {
                        listening = false
                        resetMicButton()
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                        if (text.isNotBlank()) {
                            binding.etQuestion.setText(text)
                            binding.etQuestion.setSelection(text.length)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val text = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                        if (text.isNotBlank()) {
                            binding.etQuestion.setText(text)
                            binding.etQuestion.setSelection(text.length)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
            }
        }
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
            viewModel.setActiveSessionId(newId)
        }
    }

    private fun observeActiveSession() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.activeSessionId
                    .collectLatest { sessionId ->
                        if (sessionId != activeSessionId || observerJob == null) {
                            ensureSessionAndObserve(sessionId)
                        }
                    }
            }
        }
    }

    // ── Language switcher ─────────────────────────────────────────────────────

    private fun setupLanguageButton() {
        updateLanguageLabel(LanguagePreference.current)
        binding.btnLanguage.setOnClickListener {
            LanguageBottomSheet { lang ->
                updateLanguageLabel(lang)
                textToSpeech?.setLanguage(currentSpeechLocale())
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
        binding.btnAiMic.setOnClickListener { requestMicAndListen() }
        binding.etQuestion.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { ask(); true } else false
        }
    }

    private fun requestMicAndListen() {
        if (listening) {
            speechRecognizer?.stopListening()
            listening = false
            resetMicButton()
            return
        }
        if (speechRecognizer == null) {
            Toast.makeText(requireContext(), "Speech recognition unavailable on this phone", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) {
            startSpeechToText()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startSpeechToText() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentSpeechLocale().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your question")
        }
        speechRecognizer?.startListening(intent)
    }

    private fun resetMicButton() {
        if (_binding == null) return
        binding.btnAiMic.text = "MIC"
        binding.btnAiMic.setTextColor(requireContext().getColor(R.color.ai_ready))
    }

    private fun speakMessage(text: String) {
        Log.d(TAG, "AI speaker tapped ready=$ttsReady textLen=${text.length}")
        if (!ttsReady) {
            Toast.makeText(requireContext(), "Text-to-speech not ready", Toast.LENGTH_SHORT).show()
            return
        }
        val engine = textToSpeech ?: run {
            Toast.makeText(requireContext(), "Text-to-speech engine missing", Toast.LENGTH_SHORT).show()
            return
        }
        engine.setLanguage(currentSpeechLocale())
        val utteranceId = "ai-chat-${System.currentTimeMillis()}"
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        Log.d(TAG, "AI TTS speak result=$result id=$utteranceId")
        if (result == TextToSpeech.ERROR) {
            Toast.makeText(requireContext(), "Text-to-speech failed on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun currentSpeechLocale(): Locale =
        Locale.forLanguageTag(LanguagePreference.current.code)

    private fun speechErrorLabel(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Speech cancelled"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission missing"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech network unavailable"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Speech service error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timed out"
        else -> "Speech recognition failed"
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

        val lang = LanguagePreference.current
        val sessionId = viewModel.activeSessionId.value

        viewLifecycleOwner.lifecycleScope.launch {
            setLoading(true)
            binding.etQuestion.text.clear()

            // 1. Persist user turn — Flow re-emits, user bubble appears.
            withContext(Dispatchers.IO) {
                AiHistory.appendMessage(db, sessionId, isUser = true, text = question)
            }

            // 2. Open the volatile bubble with a visible placeholder. The
            //    first non-empty chunk replaces it in-place. The chat list
            //    re-renders via combine() on every
            //    pushChunk(); Room is untouched during this loop.
            viewModel.startStream(
                sessionId,
                getString(R.string.ai_thinking_label)
            )
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
                    if (cumulativeText.isNotBlank()) {
                        finalText = cumulativeText
                        viewModel.pushChunk(cumulativeText)
                    }
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
        if (_binding == null) return
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
        speechRecognizer?.destroy()
        speechRecognizer = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        _binding = null
    }

    companion object {
        private const val TAG = "AiChatFragment"

        /** Sentinel id for the volatile streaming bubble. Stable across
         *  chunk emissions so DiffUtil treats it as the same row and only
         *  rebinds its text instead of inserting/removing the bubble. */
        private const val STREAMING_DRAFT_ID = "ai-streaming-draft"
    }
}
