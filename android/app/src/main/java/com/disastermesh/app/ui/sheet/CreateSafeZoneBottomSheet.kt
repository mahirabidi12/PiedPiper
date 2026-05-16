package com.disastermesh.app.ui.sheet

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.disastermesh.app.databinding.BottomSheetCreateSafeZoneBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class CreateSafeZoneBottomSheet(
    private val onCreateZone: (zoneName: String, radiusMeters: Int) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetCreateSafeZoneBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetCreateSafeZoneBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.sliderRadius.addOnChangeListener { _, value, _ ->
            binding.tvRadiusValue.text = "${value.toInt()} m"
        }
        binding.tvRadiusValue.text = "${binding.sliderRadius.value.toInt()} m"

        binding.btnCloseSafeZone.setOnClickListener { dismiss() }
        binding.btnCancelSafeZone.setOnClickListener { dismiss() }
        binding.btnCreateSafeZone.setOnClickListener {
            val zoneName = binding.etZoneName.text?.toString()?.trim().orEmpty()
                .ifBlank { "Safe Zone" }
            val radiusMeters = binding.sliderRadius.value.toInt()
            onCreateZone(zoneName, radiusMeters)
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
