package com.disastermesh.app.ui.sheet

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.disastermesh.app.R
import com.disastermesh.app.databinding.BottomSheetBroadcastBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class BroadcastBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetBroadcastBinding? = null
    private val binding get() = _binding!!

    private var onBroadcast: ((String) -> Unit)? = null
    private var selectedAudience = "ALL"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetBroadcastBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBroadcastClose.setOnClickListener { dismiss() }

        setupAudienceChips()

        binding.btnSendBroadcast.setOnClickListener {
            val text = binding.etBroadcastMessage.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(requireContext(), "Enter a message to broadcast", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val prefix = when (selectedAudience) {
                "VOLUNTEERS" -> "[BROADCAST TO VOLUNTEERS] "
                "CIVILIANS"  -> "[BROADCAST TO CIVILIANS] "
                else         -> "[BROADCAST] "
            }
            onBroadcast?.invoke("$prefix$text")
            dismiss()
        }
    }

    private fun setupAudienceChips() {
        fun select(audience: String) {
            selectedAudience = audience
            val ctx = requireContext()
            val onColor  = ctx.getColor(R.color.authority)
            val onBg     = ctx.getColor(R.color.authority_tint)
            val offColor = ctx.getColor(R.color.text_muted)
            val offBg    = ctx.getColor(R.color.ink_800)

            binding.chipAll.setTextColor(if (audience == "ALL") onColor else offColor)
            binding.chipAll.setBackgroundColor(if (audience == "ALL") onBg else offBg)

            binding.chipVolunteers.setTextColor(if (audience == "VOLUNTEERS") onColor else offColor)
            binding.chipVolunteers.setBackgroundColor(if (audience == "VOLUNTEERS") onBg else offBg)

            binding.chipCivilians.setTextColor(if (audience == "CIVILIANS") onColor else offColor)
            binding.chipCivilians.setBackgroundColor(if (audience == "CIVILIANS") onBg else offBg)
        }

        binding.chipAll.setOnClickListener       { select("ALL") }
        binding.chipVolunteers.setOnClickListener { select("VOLUNTEERS") }
        binding.chipCivilians.setOnClickListener  { select("CIVILIANS") }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(onBroadcast: (String) -> Unit) =
            BroadcastBottomSheet().apply { this.onBroadcast = onBroadcast }
    }
}
