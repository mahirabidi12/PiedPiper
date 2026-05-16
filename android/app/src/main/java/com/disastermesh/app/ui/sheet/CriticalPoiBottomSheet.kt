package com.disastermesh.app.ui.sheet

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.disastermesh.app.databinding.BottomSheetCriticalPoiBinding
import com.disastermesh.app.db.entities.CriticalPoiAmenity
import com.disastermesh.app.db.entities.CriticalPoiStatus
import com.disastermesh.app.map.CriticalPoiMath
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class CriticalPoiBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetCriticalPoiBinding? = null
    private val binding get() = _binding!!

    private var onStatusSelected: ((String) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetCriticalPoiBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()

        val name = args.getString(ARG_NAME).orEmpty()
        val amenityType = args.getString(ARG_AMENITY_TYPE).orEmpty()
        val status = args.getString(ARG_STATUS).orEmpty()
        val isVerified = args.getBoolean(ARG_IS_VERIFIED)
        val canUpdate = args.getBoolean(ARG_CAN_UPDATE)
        val distanceMeters = if (args.containsKey(ARG_DISTANCE_METERS)) {
            args.getDouble(ARG_DISTANCE_METERS)
        } else {
            null
        }

        binding.tvPoiName.text = name
        binding.tvPoiAmenity.text = amenityType.replace('_', ' ').uppercase()
        binding.tvPoiStatus.text = status.replace('_', ' ')
        binding.tvPoiDistance.text =
            "Distance: ${CriticalPoiMath.formatDistance(distanceMeters)}"
        binding.tvPoiVerifyState.text = if (isVerified) "VERIFIED" else "UNVERIFIED"
        binding.tvPoiSymbol.text = symbolForAmenity(amenityType)

        binding.btnPoiClose.setOnClickListener { dismiss() }

        binding.btnUpdatePoiStatus.visibility = if (canUpdate) View.VISIBLE else View.GONE
        binding.tvPoiAuthorityHint.visibility = if (canUpdate) View.GONE else View.VISIBLE

        binding.btnUpdatePoiStatus.setOnClickListener {
            showStatusPicker(status)
        }
    }

    private fun showStatusPicker(currentStatus: String) {
        val options = arrayOf(
            CriticalPoiStatus.OPERATIONAL,
            CriticalPoiStatus.FLOODED,
            CriticalPoiStatus.OUT_OF_MEDICAL_SUPPLIES,
            CriticalPoiStatus.TEMP_TRIAGE_CAMP
        )
        val currentIndex = options.indexOf(currentStatus).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle("Infrastructure Status")
            .setSingleChoiceItems(options.map { it.replace('_', ' ') }.toTypedArray(), currentIndex) { dialog, which ->
                onStatusSelected?.invoke(options[which])
                dialog.dismiss()
                dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun symbolForAmenity(amenityType: String): String = when (amenityType) {
        CriticalPoiAmenity.HOSPITAL,
        CriticalPoiAmenity.CLINIC,
        CriticalPoiAmenity.PHARMACY,
        CriticalPoiAmenity.BLOOD_BANK -> "✚"
        CriticalPoiAmenity.FIRE_STATION -> "▲"
        CriticalPoiAmenity.POLICE -> "⬟"
        else -> "•"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_NAME = "name"
        private const val ARG_AMENITY_TYPE = "amenityType"
        private const val ARG_STATUS = "status"
        private const val ARG_IS_VERIFIED = "isVerified"
        private const val ARG_CAN_UPDATE = "canUpdate"
        private const val ARG_DISTANCE_METERS = "distanceMeters"

        fun newInstance(
            name: String,
            amenityType: String,
            status: String,
            isVerified: Boolean,
            canUpdate: Boolean,
            distanceMeters: Double?,
            onStatusSelected: (String) -> Unit
        ): CriticalPoiBottomSheet {
            return CriticalPoiBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_NAME, name)
                    putString(ARG_AMENITY_TYPE, amenityType)
                    putString(ARG_STATUS, status)
                    putBoolean(ARG_IS_VERIFIED, isVerified)
                    putBoolean(ARG_CAN_UPDATE, canUpdate)
                    if (distanceMeters != null) putDouble(ARG_DISTANCE_METERS, distanceMeters)
                }
                this.onStatusSelected = onStatusSelected
            }
        }
    }
}
