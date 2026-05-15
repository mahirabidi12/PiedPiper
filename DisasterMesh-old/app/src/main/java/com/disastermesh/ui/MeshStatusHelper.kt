package com.disastermesh.ui

import android.content.Context
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.disastermesh.AppConstants
import com.disastermesh.R

object MeshStatusHelper {

    fun peerText(count: Int): String =
        "${AppConstants.PEER_STATUS_PREFIX} ${count.toString().padStart(2, '0')}"

    fun peerColor(context: Context, count: Int): Int =
        ContextCompat.getColor(
            context,
            if (count > 0) R.color.color_peer_connected else R.color.color_peer_disconnected
        )

    fun update(context: Context, textView: TextView, count: Int) {
        textView.text = peerText(count)
        textView.setTextColor(peerColor(context, count))
    }
}
