package com.disastermesh.app.ai

import android.content.Context
import android.util.Log
import com.disastermesh.app.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * SignalProcessor — the AI processing layer for each incoming signal ticket.
 *
 * Flow:
 *  1. GossipRouter (or local compose) calls [enqueue] with a signal ID after DB upsert.
 *  2. On the IO dispatcher, this loads the signal text from DB and calls SignalClassifier.
 *  3. Gemma returns category / priority / summary → written back to [SignalDao.applyAiClassification].
 *  4. Authority's reactive Flow sees the updated record automatically.
 *
 * If Gemma is not ready the signal stays UNCLASSIFIED in the DB until [drainUnclassified] is
 * called (which MeshService triggers when GemmaClient transitions to READY).
 *
 * This object owns no UI dependencies and no Android context beyond AppDatabase.
 */
object SignalProcessor {

    private const val TAG = "SignalProcessor"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Enqueue a single signal for AI classification. Returns immediately.
     * If Gemma is not ready the call is silently dropped — the signal remains UNCLASSIFIED
     * and will be picked up by [drainUnclassified] when the model loads.
     */
    fun enqueue(context: Context, signalId: String) {
        if (GemmaClient.status != GemmaClient.Status.READY) return
        scope.launch { processOne(context, signalId) }
    }

    /**
     * Process every unclassified signal in the DB.
     * Call this once Gemma becomes READY so signals that arrived before the model loaded
     * are not stuck as UNCLASSIFIED forever.
     */
    fun drainUnclassified(context: Context) {
        if (GemmaClient.status != GemmaClient.Status.READY) return
        scope.launch {
            val db       = AppDatabase.getInstance(context.applicationContext)
            val pending  = db.signalDao().getAllUnclassified()
            Log.d(TAG, "drainUnclassified: ${pending.size} signals pending")
            pending.forEach { entity -> processOne(context, entity.id) }
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private suspend fun processOne(context: Context, signalId: String) {
        val db     = AppDatabase.getInstance(context.applicationContext)
        val entity = db.signalDao().getById(signalId) ?: run {
            Log.w(TAG, "Signal $signalId not found in DB — skipping")
            return
        }
        if (entity.aiClassified) return  // already done, nothing to do

        Log.d(TAG, "Classifying signal ${entity.id.take(8)}: ${entity.message.take(60)}")

        // SignalClassifier delivers result on main thread; we bridge back to IO via a callback.
        var done = false
        SignalClassifier.classify(
            rawMessage = entity.message,
            onResult   = { result ->
                scope.launch {
                    db.signalDao().applyAiClassification(
                        id       = signalId,
                        category = result.category.name,
                        priority = result.priority.name,
                        summary  = result.summary,
                        now      = System.currentTimeMillis()
                    )
                    Log.d(TAG, "Signal ${signalId.take(8)} classified → ${result.category} / ${result.priority}")
                    done = true
                }
            },
            onError    = { err ->
                Log.e(TAG, "Classification failed for ${signalId.take(8)}: $err")
                done = true
            }
        )
    }
}
