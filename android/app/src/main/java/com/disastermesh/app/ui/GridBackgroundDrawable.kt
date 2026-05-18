package com.disastermesh.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * Draws the 24dp × 24dp cyber-grid at ~2.4% white opacity over pure black.
 * Applied programmatically to fragment root views instead of the unreliable
 * <bitmap tileMode="repeat"> approach (which crashes when the source is a VectorDrawable).
 */
class GridBackgroundDrawable(context: Context) : Drawable() {

    private val gridPx: Float = 24f * context.resources.displayMetrics.density

    private val basePaint = Paint().apply {
        color = 0xFF000000.toInt()
        style = Paint.Style.FILL
    }

    private val linePaint = Paint().apply {
        color = 0x06FFFFFF
        strokeWidth = 1f
        style = Paint.Style.STROKE
        isAntiAlias = false
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val l = b.left.toFloat()
        val t = b.top.toFloat()
        val r = b.right.toFloat()
        val bot = b.bottom.toFloat()

        canvas.drawRect(l, t, r, bot, basePaint)

        var x = l
        while (x <= r) {
            canvas.drawLine(x, t, x, bot, linePaint)
            x += gridPx
        }
        var y = t
        while (y <= bot) {
            canvas.drawLine(l, y, r, y, linePaint)
            y += gridPx
        }
    }

    override fun setAlpha(alpha: Int) {
        linePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        linePaint.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
