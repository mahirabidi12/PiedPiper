package com.disastermesh

import android.graphics.Color

enum class Role(
    val displayName: String,
    val subtitle: String,
    val emoji: String,
    val colorHex: String,
    val bgColorHex: String,
    val badgeLabel: String
) {
    USER(
        displayName  = "Civilian",
        subtitle     = "I need help or want to report",
        emoji        = "👤",
        colorHex     = "#58A6FF",
        bgColorHex   = "#1A2844",
        badgeLabel   = "CIV"
    ),
    VOLUNTEER(
        displayName  = "Volunteer",
        subtitle     = "I'm here to help people",
        emoji        = "🟢",
        colorHex     = "#3FB950",
        bgColorHex   = "#16362A",
        badgeLabel   = "VOL"
    ),
    AUTHORITY(
        displayName  = "Authority",
        subtitle     = "Official emergency responder",
        emoji        = "🔴",
        colorHex     = "#F78166",
        bgColorHex   = "#3D1A14",
        badgeLabel   = "AUTH"
    );

    fun color(): Int = Color.parseColor(colorHex)
    fun bgColor(): Int = Color.parseColor(bgColorHex)

    companion object {
        fun fromName(name: String?): Role =
            values().firstOrNull { it.name == name } ?: USER
    }
}
