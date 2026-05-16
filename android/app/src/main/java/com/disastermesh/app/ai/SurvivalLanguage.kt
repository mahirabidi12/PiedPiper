package com.disastermesh.app.ai

enum class SurvivalLanguage(
    val code: String,
    val displayName: String,
    val nativeName: String
) {
    ENGLISH    ("en", "English",    "English"),
    SPANISH    ("es", "Spanish",    "Español"),
    HINDI      ("hi", "Hindi",      "हिन्दी"),
    MANDARIN   ("zh", "Mandarin",   "中文"),
    ARABIC     ("ar", "Arabic",     "العربية"),
    BENGALI    ("bn", "Bengali",    "বাংলা"),
    FRENCH     ("fr", "French",     "Français"),
    SWAHILI    ("sw", "Swahili",    "Kiswahili"),
    PORTUGUESE ("pt", "Portuguese", "Português"),
    INDONESIAN ("id", "Indonesian", "Bahasa Indonesia");
}

object LanguagePreference {
    var current: SurvivalLanguage = SurvivalLanguage.ENGLISH
}
