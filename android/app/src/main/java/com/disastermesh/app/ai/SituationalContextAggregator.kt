package com.disastermesh.app.ai

import com.disastermesh.app.db.AppDatabase
import com.disastermesh.app.db.entities.InventoryEntity
import com.disastermesh.app.db.entities.SignalEntity
import com.disastermesh.app.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object SituationalContextAggregator {

    private val STALE_WINDOW_MS = TimeUnit.MINUTES.toMillis(30)
    private const val MAX_SIGNALS = 10
    private const val MAX_INTEL   = 12

    /**
     * Compiles a role-scoped plaintext snapshot of live mesh state, inventory,
     * and signal intel. The three blocks are injected verbatim into the Gemma
     * prompt so the model can answer questions from real data.
     *
     * Role isolation is enforced here — volunteers and civilians never receive
     * the [LIVE INVENTORY STATE] block.
     */
    suspend fun compile(db: AppDatabase, role: Role, nodeId: String): String =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val sb  = StringBuilder()

            // ── Block 1: LIVE MESH STATE ─────────────────────────────────────
            sb.appendLine("[LIVE MESH STATE]")
            val connected = db.peerDao().getConnected()
            sb.appendLine("Active Peers Connected: ${connected.size}")

            when (role) {
                Role.AUTHORITY -> {
                    val stale      = db.peerDao().getRecentlyLost(now - STALE_WINDOW_MS)
                    val active     = db.signalDao().getActive()
                    val unassigned = db.signalDao().getUnassignedActive()
                    val inventory  = db.inventoryDao().getActiveItems()

                    appendAuthorityMesh(stale.size, active, unassigned.size, now, sb)

                    // ── Block 2: LIVE INVENTORY STATE ────────────────────────
                    sb.appendLine()
                    appendInventoryBlock(inventory, sb)

                    // ── Block 3: LOGGED SIGNALS & INTEL ─────────────────────
                    sb.appendLine()
                    appendAuthorityIntel(active, now, sb)
                }

                Role.VOLUNTEER -> {
                    val stale    = db.peerDao().getRecentlyLost(now - STALE_WINDOW_MS)
                    val assigned = db.signalDao().getActiveAssignedTo(nodeId)

                    appendVolunteerMesh(stale.size, assigned, now, sb)

                    // ── Block 3: LOGGED SIGNALS & INTEL ─────────────────────
                    sb.appendLine()
                    appendVolunteerIntel(assigned, now, sb)
                }

                Role.CIVILIAN -> {
                    val own = db.signalDao().getBySender(nodeId)

                    appendCivilianMesh(own, now, sb)

                    // ── Block 3: LOGGED SIGNALS & INTEL ─────────────────────
                    sb.appendLine()
                    appendCivilianIntel(own, now, sb)
                }
            }

            sb.toString().trim()
        }

    // ── AUTHORITY ─────────────────────────────────────────────────────────────

    private fun appendAuthorityMesh(
        staleCount: Int,
        active: List<SignalEntity>,
        unassignedCount: Int,
        now: Long,
        sb: StringBuilder
    ) {
        sb.appendLine("Stale/Lost Nodes: $staleCount")

        if (active.isEmpty()) {
            sb.appendLine("Active SITREPs: none")
        } else {
            sb.appendLine("Active SITREPs:")
            active.take(MAX_SIGNALS).forEach { s -> sb.appendLine(formatSignalLine(s, now)) }
            if (active.size > MAX_SIGNALS)
                sb.appendLine("  ...and ${active.size - MAX_SIGNALS} more")
        }

        sb.appendLine("Unassigned Volunteer Tasks: $unassignedCount")
    }

    private fun appendInventoryBlock(inventory: List<InventoryEntity>, sb: StringBuilder) {
        sb.appendLine("[LIVE INVENTORY STATE]")
        if (inventory.isEmpty()) {
            sb.appendLine("No inventory items recorded.")
            return
        }
        inventory.forEach { item ->
            val tag = when {
                item.count == 0  -> " (OUT OF STOCK)"
                item.count <= 10 -> " (LOW STOCK)"
                else             -> ""
            }
            sb.appendLine("- ${item.label}: ${item.count} ${item.unit} available$tag")
        }
    }

    private fun appendAuthorityIntel(
        active: List<SignalEntity>,
        now: Long,
        sb: StringBuilder
    ) {
        sb.appendLine("[LOGGED SIGNALS & INTEL]")
        if (active.isEmpty()) {
            sb.appendLine("No active signal intel.")
            return
        }
        active.sortedByDescending { it.updatedAt }.take(MAX_INTEL)
            .forEach { s -> sb.append(formatIntelLine(s, now)) }
    }

    // ── VOLUNTEER ─────────────────────────────────────────────────────────────

    private fun appendVolunteerMesh(
        staleCount: Int,
        assigned: List<SignalEntity>,
        now: Long,
        sb: StringBuilder
    ) {
        sb.appendLine("Stale/Lost Nodes: $staleCount")

        if (assigned.isEmpty()) {
            sb.appendLine("Your Assigned Tasks: none")
        } else {
            sb.appendLine("Your Assigned Tasks: ${assigned.size}")
            assigned.take(MAX_SIGNALS).forEach { s -> sb.appendLine(formatSignalLine(s, now)) }
        }
    }

    private fun appendVolunteerIntel(
        assigned: List<SignalEntity>,
        now: Long,
        sb: StringBuilder
    ) {
        sb.appendLine("[LOGGED SIGNALS & INTEL]")
        if (assigned.isEmpty()) {
            sb.appendLine("No active assignments.")
            return
        }
        assigned.take(MAX_INTEL).forEach { s -> sb.append(formatIntelLine(s, now)) }
    }

    // ── CIVILIAN ──────────────────────────────────────────────────────────────

    private fun appendCivilianMesh(
        own: List<SignalEntity>,
        now: Long,
        sb: StringBuilder
    ) {
        if (own.isEmpty()) {
            sb.appendLine("Your Submitted Signals: none")
        } else {
            sb.appendLine("Your Submitted Signals: ${own.size}")
            own.take(5).forEach { s -> sb.appendLine(formatSignalLine(s, now)) }
        }
    }

    private fun appendCivilianIntel(
        own: List<SignalEntity>,
        now: Long,
        sb: StringBuilder
    ) {
        sb.appendLine("[LOGGED SIGNALS & INTEL]")
        if (own.isEmpty()) {
            sb.appendLine("No submitted signal history.")
            return
        }
        own.take(5).forEach { s -> sb.append(formatIntelLine(s, now)) }
    }

    // ── Formatters ────────────────────────────────────────────────────────────

    /** Short one-liner used inside the [LIVE MESH STATE] block. */
    private fun formatSignalLine(s: SignalEntity, now: Long): String {
        val zone = s.manualLocation?.takeIf { it.isNotBlank() }
            ?: if (s.latitude != null && s.longitude != null)
                "%.3f,%.3f".format(s.latitude, s.longitude)
            else "Unknown"
        val shortId = s.id.takeLast(4).uppercase()
        return "  - ID: $shortId | Zone: $zone | Type: ${s.category} | Priority: ${s.priority} | ${ageLabel(now - s.updatedAt)} | Status: ${s.status}"
    }

    /**
     * Rich multi-line entry used inside the [LOGGED SIGNALS & INTEL] block.
     * Includes the full reporter message, volunteer assignment, and instructions
     * so the model can answer resource-correlation questions accurately.
     */
    private fun formatIntelLine(s: SignalEntity, now: Long): String {
        val shortId = s.id.takeLast(4).uppercase()
        val age     = ageLabel(now - s.updatedAt)
        val out     = StringBuilder()

        out.appendLine("- Signal $shortId [${s.category}/${s.priority}] — ${s.message.take(120)}")

        if (!s.assignedVolunteerName.isNullOrBlank()) {
            out.appendLine("  Assigned to: ${s.assignedVolunteerName} — Status: ${s.status} ($age)")
        } else {
            out.appendLine("  Status: ${s.status} ($age)")
        }

        if (!s.instructions.isNullOrBlank())
            out.appendLine("  Instructions: ${s.instructions.take(100)}")

        if (!s.inventoryAllocated.isNullOrBlank() && s.inventoryAllocated != "[]")
            out.appendLine("  Inventory Allocated: ${s.inventoryAllocated}")

        return out.toString()
    }

    private fun ageLabel(ms: Long): String {
        val min = ms / 60_000L
        return when {
            min < 1  -> "just now"
            min < 60 -> "${min}m ago"
            else     -> "${min / 60}h ${min % 60}m ago"
        }
    }
}
