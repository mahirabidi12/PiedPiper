package com.disastermesh.app.ui.comms

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.disastermesh.app.adapter.ChatMessageAdapter
import com.disastermesh.app.core.NodeIdentity
import com.disastermesh.app.databinding.FragmentChatRoomBinding
import com.disastermesh.app.model.ChatRooms
import com.disastermesh.app.ui.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ChatRoomFragment : Fragment() {

    private var _binding: FragmentChatRoomBinding? = null
    private val binding get() = _binding!!

    private lateinit var roomId: String
    private var showBack: Boolean = false
    private lateinit var messageAdapter: ChatMessageAdapter

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
        messageAdapter = ChatMessageAdapter(localNodeId = nodeId)
        binding.rvMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvMessages.adapter = messageAdapter

        // Set up send
        binding.btnSend.setOnClickListener { sendMessage() }

        observeMessages()
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
        _binding = null
    }

    companion object {
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
