package com.disastermesh.app.ai

/**
 * Prompt-only language routing.
 *
 * IMPORTANT: language support in DisasterMesh does NOT load separate language
 * weights, LoRA adapters, tokenizer extensions, or any other external module
 * into the Gemma engine. The model is single-binary, multilingual-capable, and
 * stays exactly as downloaded.
 *
 * Switching language = injecting a system directive in front of the user's
 * query. That's it. No engine reload, no memory cost, no model swap.
 *
 * Call [wrap] right before handing the prompt to GemmaClient.generate(). Every
 * selected language, including English, is represented by an explicit
 * directive prefix so the model receives one consistent instruction format.
 */
object LanguagePromptWrapper {

    private const val DIRECTIVE_TEMPLATE =
        "[System Directive: Respond strictly in %s]\nUser: %s"

    /**
     * Wraps [query] with a strict system directive forcing [language] for the response.
     *
        * Example output (HINDI):
     * ```
        * [System Directive: Respond strictly in Hindi]
     * User: How do I treat a deep cut?
     * ```
     */
    fun wrap(query: String, language: SurvivalLanguage): String {
        val trimmed = query.trim()
        return DIRECTIVE_TEMPLATE.format(language.displayName, trimmed)
    }

    /** Convenience overload that reads the current global pref. */
    fun wrap(query: String): String = wrap(query, LanguagePreference.current)
}
