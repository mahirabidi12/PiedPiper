package com.disastermesh.app.ui.comms

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.disastermesh.app.R
import com.disastermesh.app.adapter.ChatRoomAdapter
import com.disastermesh.app.core.UserSession
import com.disastermesh.app.databinding.FragmentChatBinding
import com.disastermesh.app.model.ChatRooms
import com.disastermesh.app.model.Role

/**
 * CIVILIAN: hides tab strip + room list, goes straight into room-all.
 * VOLUNTEER/AUTHORITY: shows CHANNELS tab with room list.
 */
class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.background = com.disastermesh.app.ui.GridBackgroundDrawable(requireContext())

        val session = UserSession.get(requireContext()) ?: return

        if (session.role == Role.CIVILIAN) {
            binding.tabStrip.visibility    = View.GONE
            binding.rvRooms.visibility     = View.GONE
            binding.chatRoomContainer.visibility = View.VISIBLE
            openRoom("room-all", showBack = false)
        } else {
            binding.tabStrip.visibility = View.VISIBLE
            setupRoomList(session.role)
            setChannelsTab()
        }
    }

    private fun setChannelsTab() {
        binding.rvRooms.visibility = View.VISIBLE
        binding.tabChannels.setTextColor(requireContext().getColor(R.color.volunteer))
        binding.tabChannels.setBackgroundColor(requireContext().getColor(R.color.volunteer_tint))
    }

    private fun setupRoomList(role: Role) {
        val rooms = ChatRooms.roomsForRole(role)
        val adapter = ChatRoomAdapter(rooms) { roomDef ->
            binding.rvRooms.visibility           = View.GONE
            binding.tabStrip.visibility          = View.GONE
            binding.chatRoomContainer.visibility = View.VISIBLE
            openRoom(roomDef.id, showBack = true)
        }
        binding.rvRooms.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRooms.adapter = adapter
    }

    private fun openRoom(roomId: String, showBack: Boolean) {
        val fragment = ChatRoomFragment.newInstance(roomId, showBack)
        childFragmentManager.beginTransaction()
            .replace(binding.chatRoomContainer.id, fragment)
            .commit()
    }

    fun onRoomBackPressed() {
        val session = UserSession.get(requireContext())
        binding.chatRoomContainer.visibility = View.GONE
        if (session?.role == Role.CIVILIAN) return
        binding.tabStrip.visibility = View.VISIBLE
        setChannelsTab()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
