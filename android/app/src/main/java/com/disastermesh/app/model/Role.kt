package com.disastermesh.app.model

enum class Role(
    val badge: String,
    val displayName: String,
    val colorRes: Int = 0   // set in UI layer; 0 is a placeholder
) {
    CIVILIAN("CIV", "Civilian"),
    VOLUNTEER("VOL", "Volunteer"),
    AUTHORITY("AUTH", "Authority");

    companion object {
        fun fromString(value: String): Role =
            entries.firstOrNull { it.name == value } ?: CIVILIAN
    }
}
