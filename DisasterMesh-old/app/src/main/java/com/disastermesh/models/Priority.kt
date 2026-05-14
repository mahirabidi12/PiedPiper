package com.disastermesh.models

import android.graphics.Color

enum class Priority(
    val label: String,
    val colorHex: String,
    val level: Int          // higher = more urgent, used for sorting
) {
    CRITICAL("Critical", "#EF4444", 4),
    HIGH("High",     "#F97316", 3),
    NORMAL("Normal", "#3B82F6", 2),
    LOW("Low",       "#6B7280", 1);

    fun color(): Int = Color.parseColor(colorHex)

    companion object {
        fun fromString(value: String): Priority =
            values().firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: NORMAL
    }
}
