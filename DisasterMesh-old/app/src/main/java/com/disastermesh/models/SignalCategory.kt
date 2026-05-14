package com.disastermesh.models

import android.graphics.Color

enum class SignalCategory(
    val label: String,
    val emoji: String,
    val colorHex: String
) {
    MEDICAL("Medical", "🔴", "#EF4444"),
    RESCUE("Rescue", "🟠", "#F97316"),
    RESOURCE("Resource", "🔵", "#3B82F6"),
    SAFETY("Safety", "🟢", "#22C55E"),
    OTHER("Other", "⚪", "#6B7280");

    fun color(): Int = Color.parseColor(colorHex)

    companion object {
        fun fromString(value: String): SignalCategory =
            values().firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: OTHER
    }
}
