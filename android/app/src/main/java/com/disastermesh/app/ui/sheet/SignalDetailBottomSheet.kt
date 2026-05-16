package com.disastermesh.app.ui.sheet

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.disastermesh.app.R
import com.disastermesh.app.core.UserSession
import com.disastermesh.app.databinding.BottomSheetSignalDetailBinding
import com.disastermesh.app.model.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class SignalDetailBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSignalDetailBinding? = null
    private val binding get() = _binding!!

    private var signal: Signal? = null
    private var onStatusChanged: ((Signal, SignalStatus) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSignalDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        signal?.let { render(it) }
    }

    private fun render(s: Signal) {
        val ctx = requireContext()

        val priorityColor = when (s.priority) {
            SignalPriority.CRITICAL -> ctx.getColor(R.color.priority_critical)
            SignalPriority.HIGH     -> ctx.getColor(R.color.priority_high)
            SignalPriority.NORMAL   -> ctx.getColor(R.color.priority_normal)
            SignalPriority.LOW      -> ctx.getColor(R.color.priority_low)
        }

        binding.tvDetailCategoryIcon.text = s.category.icon
        binding.tvDetailCategoryIcon.setTextColor(priorityColor)
        binding.tvDetailCategory.text = s.category.label
        binding.tvDetailCategory.setTextColor(priorityColor)
        binding.tvDetailPriority.text = "${s.priority.name} PRIORITY"
        binding.signalDetailHeader.setBackgroundColor(
            when (s.priority) {
                SignalPriority.CRITICAL -> ctx.getColor(R.color.critical_tint)
                SignalPriority.HIGH     -> Color.argb(26, 0x33, 0x22, 0x00)
                else                   -> ctx.getColor(R.color.ink_800)
            }
        )
        binding.detailIconBox.setBackgroundColor(priorityColor.withAlpha(20))

        val statusColor = when (s.status) {
            SignalStatus.NEW          -> ctx.getColor(R.color.status_new)
            SignalStatus.ACKNOWLEDGED -> ctx.getColor(R.color.status_acknowledged)
            SignalStatus.IN_PROGRESS  -> ctx.getColor(R.color.status_in_progress)
            SignalStatus.RESOLVED     -> ctx.getColor(R.color.status_resolved)
            SignalStatus.EXPIRED      -> ctx.getColor(R.color.text_dim)
            SignalStatus.QUEUED       -> ctx.getColor(R.color.text_muted)
        }
        binding.tvDetailStatus.text = s.status.name.replace('_', ' ')
        binding.tvDetailStatus.setTextColor(statusColor)
        binding.tvDetailStatus.setBackgroundColor(statusColor.withAlpha(25))

        binding.tvDetailMessage.text = s.message

        when {
            s.aiSummary != null -> binding.tvDetailAiSummary.text = s.aiSummary
            s.aiClassified      -> binding.containerAiSummary.visibility = View.GONE
        }

        val roleColor = when (s.senderRole) {
            Role.CIVILIAN  -> ctx.getColor(R.color.civilian)
            Role.VOLUNTEER -> ctx.getColor(R.color.volunteer)
            Role.AUTHORITY -> ctx.getColor(R.color.authority)
        }
        binding.tvDetailSender.text = "${s.senderRole.badge} ${s.senderName}"
        binding.tvDetailSender.setTextColor(roleColor)

        binding.tvDetailLocation.text = when {
            s.latitude != null && s.longitude != null ->
                "%.4f, %.4f".format(s.latitude, s.longitude)
            !s.manualLocation.isNullOrBlank() -> s.manualLocation
            else -> "Not reported"
        }
        binding.tvDetailPeople.text = if (s.peopleCount != null && s.peopleCount > 0)
            "${s.peopleCount} people" else "Not reported"
        binding.tvDetailTime.text = timeAgo(s.createdAt)
        binding.tvDetailMesh.text = "${s.hopCount} hop${if (s.hopCount != 1) "s" else ""} · TTL ${s.ttl}"

        val session = UserSession.get(requireContext())
        when (session?.role ?: Role.CIVILIAN) {
            Role.CIVILIAN -> {
                binding.containerActions.visibility = View.GONE
                binding.tvDetailCivilianNote.visibility = View.VISIBLE
            }
            Role.VOLUNTEER, Role.AUTHORITY -> {
                binding.tvDetailCivilianNote.visibility = View.GONE
                setupActionButtons(s)
            }
        }
    }

    private fun setupActionButtons(s: Signal) {
        when (s.status) {
            SignalStatus.NEW -> {
                binding.containerActions.visibility = View.VISIBLE
                binding.btnDetailAcknowledge.visibility = View.VISIBLE
                binding.tvAckLabel.text = "ACKNOWLEDGE"
                binding.btnDetailResolve.visibility = View.VISIBLE
                binding.tvResolveLabel.text = "RESOLVE"
            }
            SignalStatus.ACKNOWLEDGED -> {
                binding.containerActions.visibility = View.VISIBLE
                binding.btnDetailAcknowledge.visibility = View.VISIBLE
                binding.tvAckLabel.text = "EN ROUTE"
                binding.btnDetailResolve.visibility = View.VISIBLE
                binding.tvResolveLabel.text = "RESOLVE"
            }
            SignalStatus.IN_PROGRESS -> {
                binding.containerActions.visibility = View.VISIBLE
                binding.btnDetailAcknowledge.visibility = View.GONE
                binding.btnDetailResolve.visibility = View.VISIBLE
                binding.tvResolveLabel.text = "MARK RESOLVED"
            }
            SignalStatus.RESOLVED, SignalStatus.EXPIRED, SignalStatus.QUEUED -> {
                binding.containerActions.visibility = View.GONE
            }
        }

        binding.btnDetailAcknowledge.setOnClickListener {
            val next = if (s.status == SignalStatus.NEW) SignalStatus.ACKNOWLEDGED
                       else SignalStatus.IN_PROGRESS
            onStatusChanged?.invoke(s, next)
            dismiss()
        }
        binding.btnDetailResolve.setOnClickListener {
            onStatusChanged?.invoke(s, SignalStatus.RESOLVED)
            dismiss()
        }
    }

    private fun timeAgo(epochMs: Long): String {
        val diff = System.currentTimeMillis() - epochMs
        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "just now"
            diff < TimeUnit.HOURS.toMillis(1)   -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m ago"
            diff < TimeUnit.DAYS.toMillis(1)    -> "${TimeUnit.MILLISECONDS.toHours(diff)}h ago"
            else -> SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(epochMs))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(signal: Signal, onStatusChanged: (Signal, SignalStatus) -> Unit) =
            SignalDetailBottomSheet().apply {
                this.signal = signal
                this.onStatusChanged = onStatusChanged
            }
    }
}

private fun Int.withAlpha(alpha: Int): Int =
    Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))
