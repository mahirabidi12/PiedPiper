package com.disastermesh.app.ui.comms

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.disastermesh.app.R
import com.disastermesh.app.adapter.ChatMessageAdapter
import com.disastermesh.app.core.NodeIdentity
import com.disastermesh.app.databinding.FragmentChatRoomBinding
import com.disastermesh.app.model.ChatRooms
import com.disastermesh.app.ui.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class ChatRoomFragment : Fragment() {

    private var _binding: FragmentChatRoomBinding? = null
    private val binding get() = _binding!!

    private lateinit var roomId: String
    private var showBack: Boolean = false
    private lateinit var messageAdapter: ChatMessageAdapter
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var listening = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startSpeechToText()
        else Toast.makeText(requireContext(), "Microphone permission denied", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatRoomBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        roomId   = requireArguments().getString(ARG_ROOM_ID)!!
        showBack = requireArguments().getBoolean(ARG_SHOW_BACK)

        val roomDef = ChatRooms.ALL.firstOrNull { it.id == roomId }
        val nodeId  = NodeIdentity.get(requireContext())

        // Set up header
        binding.tvRoomName.text = roomDef?.name ?: roomId
        binding.btnRoomBack.visibility = if (showBack) View.VISIBLE else View.GONE
        binding.btnRoomBack.setOnClickListener {
            (parentFragment as? ChatFragment)?.onRoomBackPressed()
        }

        // Set up recycler
        setupSpeech()
        messageAdapter = ChatMessageAdapter(localNodeId = nodeId) { text ->
            speakMessage(text)
        }
        binding.rvMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvMessages.adapter = messageAdapter

        // Set up send
        binding.btnSend.setOnClickListener { sendMessage() }
        binding.btnMic.setOnClickListener { requestMicAndListen() }

        observeMessages()
    }

    private fun setupSpeech() {
        textToSpeech = TextToSpeech(requireContext()) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            Log.d(TAG, "TTS init status=$status ready=$ttsReady")
            if (ttsReady) {
                val localeStatus = textToSpeech?.setLanguage(Locale.getDefault())
                Log.d(TAG, "TTS locale=${Locale.getDefault()} status=$localeStatus")
            }
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS started id=$utteranceId")
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS done id=$utteranceId")
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS error id=$utteranceId")
                }
            })
        }

        if (isOfflineSpeechAvailable()) {
            speechRecognizer = createOfflineSpeechRecognizer()?.apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        listening = true
                        binding.btnMic.imageTintList =
                            ColorStateList.valueOf(requireContext().getColor(R.color.ai_loading))
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
                            binding.etChatInput.setText(text)
                            binding.etChatInput.setSelection(text.length)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val text = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                        if (text.isNotBlank()) {
                            binding.etChatInput.setText(text)
                            binding.etChatInput.setSelection(text.length)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
            }
        }
    }

    private fun isOfflineSpeechAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(requireContext())

    private fun createOfflineSpeechRecognizer(): SpeechRecognizer? =
        if (isOfflineSpeechAvailable()) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(requireContext())
        } else {
            null
        }

    private fun requestMicAndListen() {
        if (listening) {
            speechRecognizer?.stopListening()
            listening = false
            resetMicButton()
            return
        }
        if (speechRecognizer == null) {
            Toast.makeText(
                requireContext(),
                "Offline speech unavailable. Install offline speech recognition for this language.",
                Toast.LENGTH_LONG
            ).show()
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your message")
        }
        speechRecognizer?.startListening(intent)
    }

    private fun resetMicButton() {
        if (_binding == null) return
        binding.btnMic.imageTintList =
            ColorStateList.valueOf(requireContext().getColor(R.color.ai_ready))
    }

    private fun speakMessage(text: String) {
        Log.d(TAG, "Speaker tapped ready=$ttsReady textLen=${text.length}")
        if (!ttsReady) {
            Toast.makeText(requireContext(), "Text-to-speech not ready", Toast.LENGTH_SHORT).show()
            return
        }
        val engine = textToSpeech
        if (engine == null) {
            Toast.makeText(requireContext(), "Text-to-speech engine missing", Toast.LENGTH_SHORT).show()
            return
        }
        val utteranceId = "chat-${System.currentTimeMillis()}"
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        Log.d(TAG, "TTS speak result=$result id=$utteranceId")
        if (result == TextToSpeech.ERROR) {
            Toast.makeText(requireContext(), "Text-to-speech failed on this device", Toast.LENGTH_SHORT).show()
        }
    }

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

    private fun observeMessages() {
        val svc = (requireActivity() as MainActivity).meshService ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            svc.observeRoom(roomId).collectLatest { entities ->
                val messages = entities.map { it.toDomain() }
                binding.tvMsgCount.text = "${messages.size} msgs"
                // submitList is async (DiffUtil runs off-thread). Scroll inside the
                // commit callback so the new last item is actually bound before we
                // ask the RecyclerView to scroll to it.
                messageAdapter.submitList(messages) {
                    val b = _binding ?: return@submitList
                    if (messages.isNotEmpty()) {
                        b.rvMessages.scrollToPosition(messages.size - 1)
                    }
                }

                val latestBroadcast = messages.lastOrNull { it.text.startsWith("[BROADCAST") }
                if (latestBroadcast != null) {
                    binding.pinnedBroadcastBanner.visibility = View.VISIBLE
                    binding.tvPinnedSender.text = "${latestBroadcast.senderRole.badge} ${latestBroadcast.senderName}"
                    binding.tvPinnedText.text   = latestBroadcast.text
                } else {
                    binding.pinnedBroadcastBanner.visibility = View.GONE
                }
            }
        }
    }

    private fun sendMessage() {
        val text = binding.etChatInput.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return
        (requireActivity() as MainActivity).meshService?.sendChat(roomId, text)
        binding.etChatInput.setText("")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        speechRecognizer?.destroy()
        speechRecognizer = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        _binding = null
    }

    companion object {
        private const val TAG = "ChatRoomFragment"
        private const val ARG_ROOM_ID   = "room_id"
        private const val ARG_SHOW_BACK = "show_back"

        fun newInstance(roomId: String, showBack: Boolean) = ChatRoomFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_ROOM_ID, roomId)
                putBoolean(ARG_SHOW_BACK, showBack)
            }
        }
    }
}
