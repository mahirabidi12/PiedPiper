package com.disastermesh.app.ui.ai

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the *volatile* AI-streaming bubble.
 *
 * CRITICAL PERFORMANCE RULE
 *   The currently-generating response NEVER touches Room while the model is
 *   producing tokens. Writing 30+ deltas/sec to SQLite would lock the writer
 *   thread and choke the chat Flow into a stutter. Instead we hold the
 *   in-progress text right here in a [MutableStateFlow] and the Fragment
 *   merges it into the rendered list. Once the stream completes, the
 *   Fragment writes the FINAL string to Room exactly once and clears this
 *   draft — the DAO Flow then re-emits and the committed bubble takes the
 *   draft's place seamlessly.
 *
 * Why ViewModel: the draft survives configuration changes (rotation) and
 * outlives the Fragment view, so the bubble doesn't disappear if the user
 * rotates mid-generation.
 */
class AiChatViewModel : ViewModel() {

    /** `null` ⇒ no AI is currently streaming. Non-null ⇒ render this bubble. */
    private val _streaming = MutableStateFlow<Draft?>(null)
    val streaming: StateFlow<Draft?> = _streaming.asStateFlow()

    /** Volatile in-flight AI bubble. `text` grows monotonically with each chunk. */
    data class Draft(
        val sessionId: String,
        val text: String,
        val startedAt: Long
    )

    /** Open a new streaming bubble against [sessionId]. */
    fun startStream(sessionId: String) {
        _streaming.value = Draft(sessionId, "", System.currentTimeMillis())
    }

    /**
     * Push the latest cumulative text. GemmaClient emits cumulative chunks,
     * so each call here simply replaces the bubble's contents — no
     * accumulation logic, no risk of desync.
     */
    fun pushChunk(text: String) {
        val current = _streaming.value ?: return
        _streaming.value = current.copy(text = text)
    }

    /** Stream finished (success OR error). Clears the volatile bubble. */
    fun finishStream() {
        _streaming.value = null
    }
}
