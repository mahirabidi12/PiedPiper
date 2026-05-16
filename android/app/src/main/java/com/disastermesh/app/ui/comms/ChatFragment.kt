package com.disastermesh.app.ui.comms

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.disastermesh.app.R
import com.disastermesh.app.adapter.ChatRoomAdapter
import com.disastermesh.app.adapter.DmPeerAdapter
import com.disastermesh.app.core.NodeIdentity
import com.disastermesh.app.core.UserSession
import com.disastermesh.app.databinding.FragmentChatBinding
import com.disastermesh.app.model.ChatRooms
import com.disastermesh.app.model.Role
import com.disastermesh.app.ui.AppViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * CIVILIAN: hides tab strip + room list, goes straight into room-all.
 * VOLUNTEER/AUTHORITY: CHANNELS tab = room list; DIRECT tab = DM peer inbox.
 */
class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private val appViewModel: AppViewModel by activityViewModels()

    private var showingDirectTab = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val session = UserSession.get(requireContext()) ?: return

        if (session.role == Role.CIVILIAN) {
            binding.tabStrip.visibility    = View.GONE
            binding.rvRooms.visibility     = View.GONE
            binding.rvPeers.visibility     = View.GONE
            binding.chatRoomContainer.visibility = View.VISIBLE
            openRoom("room-all", showBack = false)
        } else {
            binding.tabStrip.visibility = View.VISIBLE
            setupRoomList(session.role)
            setupDmPeerList()
            setChannelsTab()

            binding.tabChannels.setOnClickListener { setChannelsTab() }
            binding.tabDirect.setOnClickListener   { setDirectTab() }
        }
    }

    private fun setChannelsTab() {
        showingDirectTab = false
        binding.rvRooms.visibility  = View.VISIBLE
        binding.rvPeers.visibility  = View.GONE
        binding.tabChannels.setTextColor(requireContext().getColor(R.color.volunteer))
        binding.tabChannels.setBackgroundColor(requireContext().getColor(R.color.volunteer_tint))
        binding.tabDirect.setTextColor(requireContext().getColor(R.color.text_muted))
        binding.tabDirect.setBackgroundColor(requireContext().getColor(R.color.ink_800))
    }

    private fun setDirectTab() {
        showingDirectTab = true
        binding.rvPeers.visibility  = View.VISIBLE
        binding.rvRooms.visibility  = View.GONE
        binding.tabDirect.setTextColor(requireContext().getColor(R.color.volunteer))
        binding.tabDirect.setBackgroundColor(requireContext().getColor(R.color.volunteer_tint))
        binding.tabChannels.setTextColor(requireContext().getColor(R.color.text_muted))
        binding.tabChannels.setBackgroundColor(requireContext().getColor(R.color.ink_800))
    }

    private fun setupRoomList(role: Role) {
        val rooms = ChatRooms.roomsForRole(role)
        val adapter = ChatRoomAdapter(rooms) { roomDef ->
            binding.rvRooms.visibility           = View.GONE
            binding.rvPeers.visibility           = View.GONE
            binding.tabStrip.visibility          = View.GONE
            binding.chatRoomContainer.visibility = View.VISIBLE
            openRoom(roomDef.id, showBack = true)
        }
        binding.rvRooms.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRooms.adapter = adapter
    }

    private fun setupDmPeerList() {
        val localNodeId = NodeIdentity.get(requireContext())
        val dmAdapter = DmPeerAdapter(localNodeId) { peerId, peerName ->
            binding.rvRooms.visibility           = View.GONE
            binding.rvPeers.visibility           = View.GONE
            binding.tabStrip.visibility          = View.GONE
            binding.chatRoomContainer.visibility = View.VISIBLE
            openDm(peerId, peerName)
        }
        binding.rvPeers.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPeers.adapter = dmAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            appViewModel.dmInbox.collectLatest { threads ->
                dmAdapter.submitList(threads)
            }
        }
    }

    private fun openRoom(roomId: String, showBack: Boolean) {
        val fragment = ChatRoomFragment.newInstance(roomId, showBack)
        childFragmentManager.beginTransaction()
            .replace(binding.chatRoomContainer.id, fragment)
            .commit()
    }

    private fun openDm(peerId: String, peerName: String) {
        val fragment = DirectConversationFragment.newInstance(peerId, peerName)
        childFragmentManager.beginTransaction()
            .replace(binding.chatRoomContainer.id, fragment)
            .addToBackStack("dm_$peerId")
            .commit()
    }

    fun onRoomBackPressed() {
        val session = UserSession.get(requireContext())
        binding.chatRoomContainer.visibility = View.GONE
        if (session?.role == Role.CIVILIAN) return
        binding.tabStrip.visibility = View.VISIBLE
        if (showingDirectTab) setDirectTab() else setChannelsTab()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
