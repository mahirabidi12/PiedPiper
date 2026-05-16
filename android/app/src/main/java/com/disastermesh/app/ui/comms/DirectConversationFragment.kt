package com.disastermesh.app.ui.comms

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.disastermesh.app.R
import com.disastermesh.app.core.NodeIdentity
import com.disastermesh.app.databinding.FragmentDirectConversationBinding
import com.disastermesh.app.databinding.ItemChatMessageInBinding
import com.disastermesh.app.databinding.ItemChatMessageOutBinding
import com.disastermesh.app.db.entities.DirectMessageEntity
import com.disastermesh.app.ui.AppViewModel
import com.disastermesh.app.ui.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class DirectConversationFragment private constructor() : Fragment() {

    private var _binding: FragmentDirectConversationBinding? = null
    private val binding get() = _binding!!

    private val appViewModel: AppViewModel by activityViewModels()

    private lateinit var localNodeId: String
    private lateinit var peerId: String
    private lateinit var peerName: String

    private val adapter = DmAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDirectConversationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        localNodeId = NodeIdentity.get(requireContext())
        peerId   = requireArguments().getString(ARG_PEER_ID)!!
        peerName = requireArguments().getString(ARG_PEER_NAME)!!

        binding.tvPeerName.text = peerName
        binding.tvPeerRole.text = "DIRECT MESSAGE"
        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
            (parentFragment as? ChatFragment)?.onRoomBackPressed()
        }

        val llm = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        binding.rvMessages.layoutManager = llm
        binding.rvMessages.adapter = adapter

        binding.etDmInput.addTextChangedListener {
            binding.btnSend.alpha = if (it.isNullOrBlank()) 0.4f else 1f
        }
        binding.btnSend.setOnClickListener { sendMessage() }

        observeThread()
        observeMeshStatus()
    }

    private fun sendMessage() {
        val text = binding.etDmInput.text?.toString()?.trim() ?: return
        if (text.isBlank()) return
        (requireActivity() as MainActivity).meshService?.sendDm(peerId, text)
        binding.etDmInput.text?.clear()
    }

    private fun observeThread() {
        viewLifecycleOwner.lifecycleScope.launch {
            appViewModel.dmThread(peerId, localNodeId).collectLatest { messages ->
                adapter.submitList(messages)
                if (messages.isNotEmpty()) {
                    binding.rvMessages.scrollToPosition(messages.size - 1)
                }
            }
        }
    }

    private fun observeMeshStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            val svc = (requireActivity() as MainActivity).meshService ?: return@launch
            svc.peerCount.collectLatest { count ->
                binding.viewMeshDot.setBackgroundColor(
                    requireContext().getColor(
                        if (count > 0) R.color.mesh_online else R.color.mesh_offline
                    )
                )
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Adapter ──────────────────────────────────────────────────────────────

    private inner class DmAdapter : androidx.recyclerview.widget.ListAdapter<DirectMessageEntity, RecyclerView.ViewHolder>(
        object : androidx.recyclerview.widget.DiffUtil.ItemCallback<DirectMessageEntity>() {
            override fun areItemsTheSame(a: DirectMessageEntity, b: DirectMessageEntity) = a.id == b.id
            override fun areContentsTheSame(a: DirectMessageEntity, b: DirectMessageEntity) = a == b
        }
    ) {
        private val OUT = 0
        private val IN  = 1

        override fun getItemViewType(position: Int) =
            if (getItem(position).senderNodeId == localNodeId) OUT else IN

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == OUT) {
                OutVH(ItemChatMessageOutBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            } else {
                InVH(ItemChatMessageInBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val msg = getItem(position)
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.createdAt))
            when (holder) {
                is OutVH -> {
                    holder.b.tvOutMessageText.text = msg.text
                    holder.b.tvOutTimestamp.text   = time
                }
                is InVH -> {
                    holder.b.tvRoleBadge.text   = msg.senderName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                    holder.b.tvSenderName.text  = msg.senderName
                    holder.b.tvTimestamp.text   = time
                    holder.b.tvMessageText.text = msg.text
                }
            }
        }

        inner class OutVH(val b: ItemChatMessageOutBinding) : RecyclerView.ViewHolder(b.root)
        inner class InVH(val b: ItemChatMessageInBinding)   : RecyclerView.ViewHolder(b.root)
    }

    companion object {
        private const val ARG_PEER_ID   = "peer_id"
        private const val ARG_PEER_NAME = "peer_name"

        fun newInstance(peerId: String, peerName: String) =
            DirectConversationFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PEER_ID,   peerId)
                    putString(ARG_PEER_NAME, peerName)
                }
            }
    }
}
