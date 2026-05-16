package com.disastermesh.app.ui.zone

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.disastermesh.app.databinding.BottomSheetZoneAnalysisBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ZoneAnalysisBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetZoneAnalysisBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetZoneAnalysisBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvZoneAnalysisAreaLabel.text = requireArguments().getString(ARG_AREA_LABEL)
        binding.tvZoneAnalysisResult.text    = requireArguments().getString(ARG_ANALYSIS)
        binding.btnZoneAnalysisClose.setOnClickListener { dismiss() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_AREA_LABEL = "area_label"
        private const val ARG_ANALYSIS   = "analysis"

        fun newInstance(areaLabel: String, analysis: String) =
            ZoneAnalysisBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_AREA_LABEL, areaLabel)
                    putString(ARG_ANALYSIS, analysis)
                }
            }
    }
}
