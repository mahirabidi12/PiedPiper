package com.disastermesh.ui

import android.content.Context
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.disastermesh.R
import com.disastermesh.ai.GemmaClient

object GemmaStatusHelper {

    fun bind(context: Context, textView: TextView, verbose: Boolean = false) {
        GemmaClient.warmUp(context) { status ->
            textView.text = statusText(context, status, verbose)
            textView.setTextColor(statusColor(context, status))
        }
    }

    fun statusText(context: Context, status: GemmaClient.Status, verbose: Boolean = false): String =
        context.getString(
            if (verbose) when (status) {
                GemmaClient.Status.READY   -> R.string.ai_status_ready_verbose
                GemmaClient.Status.LOADING -> R.string.ai_status_loading_verbose
                GemmaClient.Status.ABSENT  -> R.string.ai_status_absent_verbose
                GemmaClient.Status.ERROR   -> R.string.ai_status_error_verbose
            } else when (status) {
                GemmaClient.Status.READY   -> R.string.ai_status_ready
                GemmaClient.Status.LOADING -> R.string.ai_status_loading
                GemmaClient.Status.ABSENT  -> R.string.ai_status_absent
                GemmaClient.Status.ERROR   -> R.string.ai_status_error
            }
        )

    fun statusColor(context: Context, status: GemmaClient.Status): Int =
        ContextCompat.getColor(
            context,
            if (status == GemmaClient.Status.READY) R.color.color_ai_ready else R.color.color_ai_inactive
        )
}
