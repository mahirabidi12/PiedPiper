package com.disastermesh.app.ai

/**
 * Languages exposed in the AI chatbot picker.
 *
 * ─── IMPORTANT — DO NOT ADD DOWNLOAD LOGIC FOR LANGUAGES ──────────────────────
 * Gemma 3n E2B supports every language in this list natively. There are NO
 * external language packs, no LoRA adapters, no separate weights to fetch.
 * Switching language is a pure prompt-prefix injection (see
 * [LanguagePromptWrapper]). Adding a new language is a one-line enum addition;
 * never wire it to a network call.
 *
 * Curated from the world's most-spoken languages by native + L2 speakers.
 * Ordering is roughly speaker-count descending so common picks surface first.
 */
enum class SurvivalLanguage(
    val code: String,
    val displayName: String,
    val nativeName: String
) {
    // ── Top 20 by global speaker count ────────────────────────────────────────
    ENGLISH    ("en",  "English",            "English"),
    MANDARIN   ("zh",  "Mandarin Chinese",   "中文"),
    HINDI      ("hi",  "Hindi",              "हिन्दी"),
    SPANISH    ("es",  "Spanish",            "Español"),
    ARABIC     ("ar",  "Arabic",             "العربية"),
    BENGALI    ("bn",  "Bengali",            "বাংলা"),
    PORTUGUESE ("pt",  "Portuguese",         "Português"),
    RUSSIAN    ("ru",  "Russian",            "Русский"),
    JAPANESE   ("ja",  "Japanese",           "日本語"),
    PUNJABI    ("pa",  "Punjabi",            "ਪੰਜਾਬੀ"),
    GERMAN     ("de",  "German",             "Deutsch"),
    JAVANESE   ("jv",  "Javanese",           "Basa Jawa"),
    KOREAN     ("ko",  "Korean",             "한국어"),
    FRENCH     ("fr",  "French",             "Français"),
    TELUGU     ("te",  "Telugu",             "తెలుగు"),
    MARATHI    ("mr",  "Marathi",            "मराठी"),
    TURKISH    ("tr",  "Turkish",            "Türkçe"),
    TAMIL      ("ta",  "Tamil",              "தமிழ்"),
    VIETNAMESE ("vi",  "Vietnamese",         "Tiếng Việt"),
    URDU       ("ur",  "Urdu",               "اردو"),

    // ── Major regional languages 21–50 ────────────────────────────────────────
    INDONESIAN ("id",  "Indonesian",         "Bahasa Indonesia"),
    ITALIAN    ("it",  "Italian",            "Italiano"),
    PERSIAN    ("fa",  "Persian (Farsi)",    "فارسی"),
    POLISH     ("pl",  "Polish",             "Polski"),
    UKRAINIAN  ("uk",  "Ukrainian",          "Українська"),
    GUJARATI   ("gu",  "Gujarati",           "ગુજરાતી"),
    KANNADA    ("kn",  "Kannada",            "ಕನ್ನಡ"),
    MALAYALAM  ("ml",  "Malayalam",          "മലയാളം"),
    THAI       ("th",  "Thai",               "ไทย"),
    SWAHILI    ("sw",  "Swahili",            "Kiswahili"),
    HAUSA      ("ha",  "Hausa",              "Hausa"),
    YORUBA     ("yo",  "Yoruba",             "Yorùbá"),
    IGBO       ("ig",  "Igbo",               "Igbo"),
    AMHARIC    ("am",  "Amharic",            "አማርኛ"),
    DUTCH      ("nl",  "Dutch",              "Nederlands"),
    ROMANIAN   ("ro",  "Romanian",           "Română"),
    GREEK      ("el",  "Greek",              "Ελληνικά"),
    HEBREW     ("he",  "Hebrew",             "עברית"),
    HUNGARIAN  ("hu",  "Hungarian",          "Magyar"),
    CZECH      ("cs",  "Czech",              "Čeština"),
    SWEDISH    ("sv",  "Swedish",            "Svenska"),
    DANISH     ("da",  "Danish",             "Dansk"),
    FINNISH    ("fi",  "Finnish",            "Suomi"),
    NORWEGIAN  ("no",  "Norwegian",          "Norsk"),
    BULGARIAN  ("bg",  "Bulgarian",          "Български"),
    SERBIAN    ("sr",  "Serbian",            "Српски"),
    CROATIAN   ("hr",  "Croatian",           "Hrvatski"),
    SLOVAK     ("sk",  "Slovak",             "Slovenčina"),
    CATALAN    ("ca",  "Catalan",            "Català"),
    BURMESE    ("my",  "Burmese",            "မြန်မာ"),

    // ── Languages 51–80 ───────────────────────────────────────────────────────
    SINHALA    ("si",  "Sinhala",            "සිංහල"),
    NEPALI     ("ne",  "Nepali",             "नेपाली"),
    KHMER      ("km",  "Khmer",              "ខ្មែរ"),
    LAO        ("lo",  "Lao",                "ລາວ"),
    MONGOLIAN  ("mn",  "Mongolian",          "Монгол"),
    KAZAKH     ("kk",  "Kazakh",             "Қазақша"),
    UZBEK      ("uz",  "Uzbek",              "O‘zbek"),
    AZERBAIJANI("az",  "Azerbaijani",        "Azərbaycanca"),
    ARMENIAN   ("hy",  "Armenian",           "Հայերեն"),
    GEORGIAN   ("ka",  "Georgian",           "ქართული"),
    PASHTO     ("ps",  "Pashto",             "پښتو"),
    SUNDANESE  ("su",  "Sundanese",          "Basa Sunda"),
    MALAY      ("ms",  "Malay",              "Bahasa Melayu"),
    TAGALOG    ("tl",  "Tagalog (Filipino)", "Filipino"),
    CEBUANO    ("ceb", "Cebuano",            "Cebuano"),
    ZULU       ("zu",  "Zulu",               "isiZulu"),
    XHOSA      ("xh",  "Xhosa",              "isiXhosa"),
    AFRIKAANS  ("af",  "Afrikaans",          "Afrikaans"),
    SOMALI     ("so",  "Somali",             "Soomaali"),
    MALAGASY   ("mg",  "Malagasy",           "Malagasy"),
    HAITIAN    ("ht",  "Haitian Creole",     "Kreyòl Ayisyen"),
    QUECHUA    ("qu",  "Quechua",            "Runa Simi"),
    MAORI      ("mi",  "Maori",              "Te Reo Māori"),
    SAMOAN     ("sm",  "Samoan",             "Gagana Samoa"),
    HAWAIIAN   ("haw", "Hawaiian",           "ʻŌlelo Hawaiʻi"),
    WELSH      ("cy",  "Welsh",              "Cymraeg"),
    IRISH      ("ga",  "Irish",              "Gaeilge"),
    BASQUE     ("eu",  "Basque",             "Euskara"),
    ICELANDIC  ("is",  "Icelandic",          "Íslenska"),
    ESPERANTO  ("eo",  "Esperanto",          "Esperanto");

    companion object {
        /** Case-insensitive prefix / substring search over display + native names. */
        fun search(query: String): List<SurvivalLanguage> {
            val q = query.trim()
            if (q.isEmpty()) return entries
            return entries.filter {
                it.displayName.contains(q, ignoreCase = true) ||
                it.nativeName.contains(q, ignoreCase = true)  ||
                it.code.startsWith(q, ignoreCase = true)
            }
        }
    }
}

object LanguagePreference {
    var current: SurvivalLanguage = SurvivalLanguage.ENGLISH
}
