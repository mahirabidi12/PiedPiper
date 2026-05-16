package com.disastermesh.app.ui.login

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.disastermesh.app.R
import com.disastermesh.app.core.UserSession
import com.disastermesh.app.databinding.FragmentLoginBinding
import com.disastermesh.app.model.Role
import com.disastermesh.app.ui.MainActivity

class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private var selectedRole: Role? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRoleCards()
        setupNameInput()
        setupJoinButton()
    }

    private fun setupRoleCards() {
        binding.cardCivilian.setOnClickListener  { selectRole(Role.CIVILIAN) }
        binding.cardVolunteer.setOnClickListener { selectRole(Role.VOLUNTEER) }
        binding.cardAuthority.setOnClickListener { selectRole(Role.AUTHORITY) }
    }

    private fun selectRole(role: Role) {
        selectedRole = role

        // Reset all cards to default background
        binding.cardCivilian.setBackgroundResource(R.drawable.bg_role_card_default)
        binding.cardVolunteer.setBackgroundResource(R.drawable.bg_role_card_default)
        binding.cardAuthority.setBackgroundResource(R.drawable.bg_role_card_default)

        // Hide all indicators
        binding.civilianSelectedIndicator.visibility  = View.INVISIBLE
        binding.volunteerSelectedIndicator.visibility = View.INVISIBLE
        binding.authoritySelectedIndicator.visibility = View.INVISIBLE

        // Highlight selected
        val selectedCard = when (role) {
            Role.CIVILIAN  -> { binding.civilianSelectedIndicator.visibility  = View.VISIBLE; binding.cardCivilian }
            Role.VOLUNTEER -> { binding.volunteerSelectedIndicator.visibility = View.VISIBLE; binding.cardVolunteer }
            Role.AUTHORITY -> { binding.authoritySelectedIndicator.visibility = View.VISIBLE; binding.cardAuthority }
        }
        selectedCard.setBackgroundResource(R.drawable.bg_role_card_selected)

        validateForm()
    }

    private fun setupNameInput() {
        binding.etDisplayName.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = validateForm()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun validateForm() {
        val nameOk = binding.etDisplayName.text?.isNotBlank() == true
        val roleOk = selectedRole != null
        binding.btnJoinMesh.isEnabled = nameOk && roleOk
    }

    private fun setupJoinButton() {
        binding.btnJoinMesh.setOnClickListener {
            val name = binding.etDisplayName.text?.toString()?.trim() ?: return@setOnClickListener
            val role = selectedRole ?: return@setOnClickListener

            UserSession.save(requireContext(), name, role)

            val activity = requireActivity() as MainActivity
            activity.showMainUI()
            activity.requestPermissionsAndStartMesh()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
