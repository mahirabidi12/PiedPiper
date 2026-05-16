package com.disastermesh.app.ui.sheet

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.disastermesh.app.R
import com.disastermesh.app.core.NodeIdentity
import com.disastermesh.app.core.UserSession
import com.disastermesh.app.databinding.BottomSheetSignalDetailBinding
import com.disastermesh.app.model.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class SignalDetailBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSignalDetailBinding? = null
    private val binding get() = _binding!!

    private var signal: Signal? = null
    private var onAction: ((Signal, TicketAction) -> Unit)? = null

    enum class TicketAction {
        ACKNOWLEDGE, IN_ROUTE, RESOLVE,
        ACCEPT, REJECT, START_WORK, FAIL,
        CANCEL
    }

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

        val statusColor = statusColor(s.status)
        binding.tvDetailStatus.text = s.status.name.replace('_', ' ')
        binding.tvDetailStatus.setTextColor(statusColor)
        binding.tvDetailStatus.setBackgroundColor(statusColor.withAlpha(25))

        binding.tvDetailMessage.text = s.message

        when {
            s.aiSummary != null -> binding.tvDetailAiSummary.text = s.aiSummary
            s.aiClassified      -> binding.containerAiSummary.visibility = View.GONE
            else                -> { /* keep "pending" placeholder */ }
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

        // Assignment section
        renderAssignmentSection(s)

        // Action buttons based on role
        val session = UserSession.get(requireContext())
        val localNodeId = NodeIdentity.get(requireContext())

        when (session?.role ?: Role.CIVILIAN) {
            Role.CIVILIAN -> {
                binding.containerActions.visibility = View.GONE
                binding.containerVolunteerActions.visibility = View.GONE
                binding.btnCancel.visibility = View.GONE
                binding.tvDetailCivilianNote.visibility = View.VISIBLE
            }
            Role.VOLUNTEER -> {
                binding.tvDetailCivilianNote.visibility = View.GONE
                binding.containerActions.visibility = View.GONE
                // Show volunteer actions only if this volunteer is assigned
                val isAssigned = s.volunteerIds?.contains("\"$localNodeId\"") == true
                setupVolunteerActions(s, isAssigned)
            }
            Role.AUTHORITY -> {
                binding.tvDetailCivilianNote.visibility = View.GONE
                binding.containerVolunteerActions.visibility = View.GONE
                setupAuthorityActions(s)
            }
        }
    }

    private fun renderAssignmentSection(s: Signal) {
        val hasAssignment = !s.volunteerIds.isNullOrBlank() && s.volunteerIds != "[]"
        if (!hasAssignment) {
            binding.containerAssignment.visibility = View.GONE
            return
        }

        binding.containerAssignment.visibility = View.VISIBLE

        // Volunteer names
        val namesDisplay = try {
            val arr = JSONArray(s.volunteerNames ?: "[]")
            (0 until arr.length()).joinToString(", ") { arr.getString(it) }
        } catch (_: Exception) {
            s.assignedVolunteerName ?: "—"
        }
        binding.tvDetailVolunteers.text = namesDisplay

        // Instructions
        if (!s.instructions.isNullOrBlank()) {
            binding.rowInstructions.visibility = View.VISIBLE
            binding.tvDetailInstructions.text = s.instructions
        } else {
            binding.rowInstructions.visibility = View.GONE
        }

        // Inventory allocated
        val inv = s.inventoryAllocated
        if (!inv.isNullOrBlank() && inv != "{}") {
            binding.rowInventory.visibility = View.VISIBLE
            val sb = StringBuilder()
            try {
                val obj = JSONObject(inv)
                obj.keys().forEach { key ->
                    if (sb.isNotEmpty()) sb.append(", ")
                    sb.append("${obj.getInt(key)}× $key")
                }
            } catch (_: Exception) {
                sb.append(inv)
            }
            binding.tvDetailInventory.text = sb.toString()
        } else {
            binding.rowInventory.visibility = View.GONE
        }
    }

    private fun setupVolunteerActions(s: Signal, isAssigned: Boolean) {
        if (!isAssigned || s.status.isTerminal) {
            binding.containerVolunteerActions.visibility = View.GONE
            return
        }

        binding.containerVolunteerActions.visibility = View.VISIBLE

        when (s.status) {
            SignalStatus.ASSIGNED, SignalStatus.WAITING_FOR_INVENTORY -> {
                binding.rowAcceptReject.visibility = View.VISIBLE
                binding.btnStartWork.visibility    = View.GONE
                binding.rowResolveFail.visibility  = View.GONE
            }
            SignalStatus.ACCEPTED -> {
                binding.rowAcceptReject.visibility = View.GONE
                binding.btnStartWork.visibility    = View.VISIBLE
                binding.rowResolveFail.visibility  = View.GONE
            }
            SignalStatus.IN_PROGRESS, SignalStatus.ON_HOLD -> {
                binding.rowAcceptReject.visibility = View.GONE
                binding.btnStartWork.visibility    = View.GONE
                binding.rowResolveFail.visibility  = View.VISIBLE
            }
            else -> binding.containerVolunteerActions.visibility = View.GONE
        }

        binding.btnAccept.setOnClickListener {
            onAction?.invoke(s, TicketAction.ACCEPT); dismiss()
        }
        binding.btnReject.setOnClickListener {
            onAction?.invoke(s, TicketAction.REJECT); dismiss()
        }
        binding.btnStartWork.setOnClickListener {
            onAction?.invoke(s, TicketAction.START_WORK); dismiss()
        }
        binding.btnVolResolve.setOnClickListener {
            onAction?.invoke(s, TicketAction.RESOLVE); dismiss()
        }
        binding.btnFail.setOnClickListener {
            onAction?.invoke(s, TicketAction.FAIL); dismiss()
        }
    }

    private fun setupAuthorityActions(s: Signal) {
        // Show cancel button for any active (non-terminal) assigned state
        val isActive = !s.status.isTerminal
        val isAssigned = !s.volunteerIds.isNullOrBlank() && s.volunteerIds != "[]"
        binding.btnCancel.visibility = if (isActive && isAssigned) View.VISIBLE else View.GONE
        binding.btnCancel.setOnClickListener {
            onAction?.invoke(s, TicketAction.CANCEL); dismiss()
        }

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
            SignalStatus.ASSIGNED, SignalStatus.ACCEPTED, SignalStatus.WAITING_FOR_INVENTORY,
            SignalStatus.ON_HOLD -> {
                binding.containerActions.visibility = View.VISIBLE
                binding.btnDetailAcknowledge.visibility = View.GONE
                binding.btnDetailResolve.visibility = View.VISIBLE
                binding.tvResolveLabel.text = "FORCE RESOLVE"
            }
            SignalStatus.IN_PROGRESS -> {
                binding.containerActions.visibility = View.VISIBLE
                binding.btnDetailAcknowledge.visibility = View.GONE
                binding.btnDetailResolve.visibility = View.VISIBLE
                binding.tvResolveLabel.text = "MARK RESOLVED"
            }
            SignalStatus.RESOLVED, SignalStatus.EXPIRED, SignalStatus.QUEUED,
            SignalStatus.REJECTED, SignalStatus.CANCELLED, SignalStatus.FAILED -> {
                binding.containerActions.visibility = View.GONE
            }
        }

        binding.btnDetailAcknowledge.setOnClickListener {
            val next = if (s.status == SignalStatus.NEW) TicketAction.ACKNOWLEDGE
                       else TicketAction.IN_ROUTE
            onAction?.invoke(s, next); dismiss()
        }
        binding.btnDetailResolve.setOnClickListener {
            onAction?.invoke(s, TicketAction.RESOLVE); dismiss()
        }
    }

    private fun statusColor(status: SignalStatus): Int {
        val ctx = requireContext()
        return when (status) {
            SignalStatus.NEW                   -> ctx.getColor(R.color.status_new)
            SignalStatus.ACKNOWLEDGED          -> ctx.getColor(R.color.status_acknowledged)
            SignalStatus.ASSIGNED              -> ctx.getColor(R.color.status_assigned)
            SignalStatus.ACCEPTED              -> ctx.getColor(R.color.status_accepted)
            SignalStatus.IN_PROGRESS           -> ctx.getColor(R.color.status_in_progress)
            SignalStatus.WAITING_FOR_INVENTORY -> ctx.getColor(R.color.status_waiting)
            SignalStatus.ON_HOLD               -> ctx.getColor(R.color.status_on_hold)
            SignalStatus.RESOLVED              -> ctx.getColor(R.color.status_resolved)
            SignalStatus.REJECTED              -> ctx.getColor(R.color.status_rejected)
            SignalStatus.CANCELLED             -> ctx.getColor(R.color.status_cancelled)
            SignalStatus.FAILED                -> ctx.getColor(R.color.status_failed)
            SignalStatus.EXPIRED               -> ctx.getColor(R.color.text_dim)
            SignalStatus.QUEUED                -> ctx.getColor(R.color.text_muted)
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
        fun newInstance(
            signal: Signal,
            onAction: (Signal, TicketAction) -> Unit
        ) = SignalDetailBottomSheet().apply {
            this.signal = signal
            this.onAction = onAction
        }
    }
}

private fun Int.withAlpha(alpha: Int): Int =
    Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))
