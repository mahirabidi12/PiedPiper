package com.disastermesh.app.ui.sheet

import android.annotation.SuppressLint
import android.location.Location
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.disastermesh.app.core.NodeIdentity
import com.disastermesh.app.core.UserSession
import com.disastermesh.app.databinding.BottomSheetComposeSignalBinding
import com.disastermesh.app.model.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.util.UUID

/**
 * Signal compose sheet — no category picker.
 * The user describes their situation in plain text; Gemma 4 classifies it automatically.
 * Layer-1 keyword triage sets an initial priority as a fallback until AI runs.
 */
class ComposeSignalBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetComposeSignalBinding? = null
    private val binding get() = _binding!!

    private var onSend: ((Signal) -> Unit)? = null
    private var prefillMessage: String? = null
    private var meshConnected: Boolean = true
    private var lastLocation: Location? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetComposeSignalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupCategoryDisplay()
        setupTextWatcher()
        setupButtons()
        startGps()
        prefillMessage?.let { text ->
            binding.etMessage.setText(text)
            binding.etMessage.setSelection(text.length)
        }
        if (!meshConnected) {
            binding.tvOfflineWarning.visibility = View.VISIBLE
        }
    }

    /** Show "AI will categorize" label instead of a picked category. */
    private fun setupCategoryDisplay() {
        binding.tvCategoryIcon.text = "?"
        binding.tvCategoryName.text = "AI will categorize"
        // Use a neutral colour — the category display is informational
        val dimColor = requireContext().getColor(com.disastermesh.app.R.color.text_muted)
        binding.tvCategoryIcon.setTextColor(dimColor)
        binding.tvCategoryName.setTextColor(dimColor)
    }

    private fun setupTextWatcher() {
        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                binding.btnRaiseSignal.isEnabled = s?.isNotBlank() == true
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun setupButtons() {
        binding.btnClose.setOnClickListener { dismiss() }
        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnRaiseSignal.setOnClickListener { raiseSignal() }
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        try {
            val lm  = requireContext().getSystemService(android.location.LocationManager::class.java)
            val loc = lm?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: lm?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            if (loc != null) {
                lastLocation = loc
                binding.gpsDot.setBackgroundColor(
                    requireContext().getColor(com.disastermesh.app.R.color.mesh_online)
                )
                binding.tvGpsStatus.text = getString(com.disastermesh.app.R.string.label_gps_locked)
                binding.tvGpsCoords.visibility = View.VISIBLE
                binding.tvGpsCoords.text = "%.4f, %.4f".format(loc.latitude, loc.longitude)
            }
        } catch (_: SecurityException) {}
    }

    private fun raiseSignal() {
        val text    = binding.etMessage.text?.toString()?.trim() ?: return
        val people  = binding.etPeopleCount.text?.toString()?.toIntOrNull()
        val session = UserSession.get(requireContext()) ?: return
        val nodeId  = NodeIdentity.get(requireContext())
        val now     = System.currentTimeMillis()

        val signal = Signal(
            id             = UUID.randomUUID().toString(),
            senderNodeId   = nodeId,
            senderName     = session.name,
            senderRole     = session.role,
            category       = SignalCategory.UNCLASSIFIED,    // Gemma will update this
            priority       = inferPriorityFallback(text),    // Layer-1 fallback until AI runs
            message        = text,
            peopleCount    = people,
            latitude       = lastLocation?.latitude,
            longitude      = lastLocation?.longitude,
            manualLocation = null,
            status         = if (meshConnected) SignalStatus.NEW else SignalStatus.QUEUED,
            createdAt      = now,
            updatedAt      = now
        )

        onSend?.invoke(signal)
        dismiss()
    }

    /**
     * Layer-1 keyword triage — runs instantly with no model, sets a temporary priority.
     * SignalProcessor overwrites this with Gemma's answer once the model is ready.
     */
    private fun inferPriorityFallback(text: String): SignalPriority {
        val lower = text.lowercase()
        val critical = listOf("dying", "dead", "critical", "unconscious", "not breathing",
            "severe", "trapped", "collapse", "fire", "flood", "urgent")
        val high = listOf("injured", "bleeding", "hurt", "sick", "need help",
            "danger", "emergency", "stranded", "missing")
        return when {
            critical.any { lower.contains(it) } -> SignalPriority.CRITICAL
            high.any     { lower.contains(it) } -> SignalPriority.HIGH
            else                                 -> SignalPriority.NORMAL
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(prefill: String? = null, meshConnected: Boolean = true, onSend: (Signal) -> Unit) =
            ComposeSignalBottomSheet().apply {
                this.prefillMessage = prefill
                this.meshConnected  = meshConnected
                this.onSend = onSend
            }
    }
}
