package com.disastermesh.ai

/**
 * PromptTemplates — all Gemma prompt strings in one place, easy to tune.
 *
 * The LiteRT-LM engine applies Gemma's chat format (the `<start_of_turn>` / `<end_of_turn>` turn
 * tokens) internally: the system framing is passed as [GemmaClient.generate]'s `systemInstruction`
 * and the user's text is passed as the plain message. So this file holds **plain text only** — no
 * hand-written turn tokens (adding them here would double them up).
 *
 * Per ai/README.txt, only this file holds prompt text — feature code builds prompts from here.
 * V1 ships one template (the Help Assistant); classifier / translator / summarizer templates
 * will be added alongside their features.
 */
object PromptTemplates {

    /**
     * System instruction for the offline survival Help Assistant: a civilian asks a practical
     * emergency question and Gemma answers with calm, concrete, low-resource guidance.
     *
     * Pass this as the `systemInstruction` argument to [GemmaClient.generate].
     */
    val ASSISTANT_SYSTEM_INSTRUCTION = """
        You are an offline emergency survival assistant on a disaster-relief phone app.
        The user has no internet and possibly no help nearby. Answer their question with
        clear, calm, step-by-step practical guidance that assumes limited supplies.
        Be concise. If the situation is life-threatening, say so first and give the most
        important action immediately. Do not invent facts; if unsure, say what is safest.
    """.trimIndent()

    /**
     * Prepares a civilian's raw Help Assistant question for the model. The chat template is
     * applied by LiteRT-LM, so this is just the cleaned user turn — pair it with
     * [ASSISTANT_SYSTEM_INSTRUCTION] when calling [GemmaClient.generate].
     */
    fun helpAssistant(question: String): String = question.trim()

    // ── Signal Classifier ─────────────────────────────────────────────────────

    /**
     * System instruction for the signal classifier.
     * Gemma reads a civilian's emergency message and returns structured JSON.
     * Pair with [classifySignal] as the user prompt.
     */
    val CLASSIFIER_SYSTEM_INSTRUCTION = """
        You are an emergency signal classifier for a disaster response app.
        Analyze the emergency message and return ONLY a valid JSON object — no explanation,
        no markdown, no code block. Just raw JSON.

        JSON schema:
        {
          "category": "MEDICAL" | "RESCUE" | "RESOURCE" | "SAFETY" | "OTHER",
          "priority": "CRITICAL" | "HIGH" | "NORMAL" | "LOW",
          "tags": [<up to 4 short keyword strings>],
          "summary": "<one short sentence for the authority dashboard>",
          "peopleCount": <integer or null>
        }

        Category rules:
        - MEDICAL: injuries, illness, medicine, hospital, doctor, blood, pain
        - RESCUE: trapped, stuck, stranded, flood, fire, collapse, missing
        - RESOURCE: food, water, shelter, clothes, fuel, electricity, supplies
        - SAFETY: safe zone, evacuation, danger area, threat, violence
        - OTHER: anything that does not fit above

        Priority rules:
        - CRITICAL: immediate life threat, person trapped, severe injury, cardiac
        - HIGH: urgent but not immediately life-threatening
        - NORMAL: needs help but stable
        - LOW: informational, no immediate danger
    """.trimIndent()

    /**
     * User prompt for the classifier. Pass the civilian's raw message here.
     * Pair with [CLASSIFIER_SYSTEM_INSTRUCTION] when calling [GemmaClient.generate].
     */
    fun classifySignal(rawMessage: String): String =
        "Classify this emergency message: ${rawMessage.trim()}"
}
