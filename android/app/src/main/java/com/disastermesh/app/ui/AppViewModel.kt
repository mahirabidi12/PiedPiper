package com.disastermesh.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.disastermesh.app.db.AppDatabase
import com.disastermesh.app.db.entities.AuditLogEntity
import com.disastermesh.app.db.entities.DirectMessageEntity
import com.disastermesh.app.db.entities.InventoryEntity
import com.disastermesh.app.db.entities.SafeZoneEntity
import com.disastermesh.app.db.entities.SignalEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Activity-scoped ViewModel.  Fragments use `by activityViewModels()` so
 * switching tabs never drops the Flow subscription.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)

    val signals: StateFlow<List<SignalEntity>> = db.signalDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val inventory: StateFlow<List<InventoryEntity>> = db.inventoryDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val auditLog: StateFlow<List<AuditLogEntity>> = db.auditLogDao().observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val dmInbox: StateFlow<List<DirectMessageEntity>> = db.directMessageDao().observeLatestPerThread()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

<<<<<<< HEAD
    val criticalPoiTick: StateFlow<Long> = db.criticalPoiDao().observeLatestUpdateTick()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val safeZones: StateFlow<List<SafeZoneEntity>> = db.safeZoneDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
=======
    /** Tickets assigned to a specific volunteer node (all statuses, for history). */
    fun assignedSignals(nodeId: String): Flow<List<SignalEntity>> =
        db.signalDao().observeAssignedTo(nodeId)

    /** Active tickets assigned to a specific volunteer (excludes terminal statuses). */
    fun activeAssignedSignals(nodeId: String): Flow<List<SignalEntity>> =
        db.signalDao().observeActiveAssignedTo(nodeId)
>>>>>>> e6d9e370ca6ed1f6dfaae651c40668384b66595a

    fun dmThread(peerId: String, localNodeId: String): Flow<List<DirectMessageEntity>> {
        val threadId = dmThreadId(localNodeId, peerId)
        return db.directMessageDao().observeThread(threadId)
    }

    fun adjustInventory(key: String, delta: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            db.inventoryDao().adjustCount(key, delta)
        }
    }

    fun addInventory(key: String, label: String, unit: String, count: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            db.inventoryDao().upsert(InventoryEntity(key, label, unit, count))
        }
    }

    fun deleteInventory(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.inventoryDao().markDeleted(key, by = "", byName = "")
        }
    }

    fun upsertSafeZone(zone: SafeZoneEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            db.safeZoneDao().upsertIfNewer(zone)
        }
    }

    private fun dmThreadId(a: String, b: String) =
        "dm-${listOf(a, b).sorted().joinToString("-")}"
}
