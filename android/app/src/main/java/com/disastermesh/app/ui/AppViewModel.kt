package com.disastermesh.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.disastermesh.app.db.AppDatabase
import com.disastermesh.app.db.entities.DirectMessageEntity
import com.disastermesh.app.db.entities.InventoryEntity
import com.disastermesh.app.db.entities.SignalEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SafeZone(val lat: Double, val lon: Double, val type: String)

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

    val dmInbox: StateFlow<List<DirectMessageEntity>> = db.directMessageDao().observeLatestPerThread()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun dmThread(peerId: String, localNodeId: String): Flow<List<DirectMessageEntity>> {
        val threadId = dmThreadId(localNodeId, peerId)
        return db.directMessageDao().observeThread(threadId)
    }

    fun adjustInventory(key: String, delta: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            db.inventoryDao().adjustCount(key, delta)
        }
    }

    private val _safeZones = MutableStateFlow<List<SafeZone>>(emptyList())
    val safeZones: StateFlow<List<SafeZone>> = _safeZones.asStateFlow()

    fun addSafeZone(lat: Double, lon: Double, type: String) {
        _safeZones.value = _safeZones.value + SafeZone(lat, lon, type)
    }

    private fun dmThreadId(a: String, b: String) =
        "dm-${listOf(a, b).sorted().joinToString("-")}"
}
