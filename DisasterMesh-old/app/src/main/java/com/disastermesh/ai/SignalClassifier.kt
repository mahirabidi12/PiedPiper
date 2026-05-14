package com.disastermesh.ai

import android.util.Log
import com.disastermesh.models.Priority
import com.disastermesh.models.SignalCategory
import org.json.JSONArray
import org.json.JSONObject

/**
 * SignalClassifier — calls GemmaClient with a classification prompt and parses the result.
 *
 * Usage (civilian screen, after the user hits Send):
 *
 *   SignalClassifier.classify(
 *       rawMessage = etMessage.text.toString(),
 *       onResult   = { result -> buildAndSendSignal(result) },
 *       onError    = { _ -> buildAndSendSignalWithDefaults() }
 *   )
 *
 * Rules:
 * - Never call GemmaClient directly from feature code — always go through here.
 * - If the model is absent or returns unparseable output, [onError] is called and the
 *   caller should fall back to a default Signal with NORMAL priority / OTHER category
 *   so the civilian's message is never silently dropped.
 */
object SignalClassifier {

    private const val TAG = "SignalClassifier"

    data class ClassificationResult(
        val category: SignalCategory,
        val priority: Priority,
        val tags: List<String>,
        val summary: String,
        val peopleCount: Int?
    )

    fun classify(
        rawMessage: String,
        onResult: (ClassificationResult) -> Unit,
        onError: (String) -> Unit
    ) {
        if (GemmaClient.status != GemmaClient.Status.READY) {
            Log.w(TAG, "Model not ready — returning keyword fallback")
            onResult(keywordFallback(rawMessage))
            return
        }

        GemmaClient.generate(
            prompt             = PromptTemplates.classifySignal(rawMessage),
            systemInstruction  = PromptTemplates.CLASSIFIER_SYSTEM_INSTRUCTION,
            onResult           = { response ->
                Log.d(TAG, "Raw classifier response: $response")
                val result = runCatching { parseJson(response, rawMessage) }
                    .getOrElse  { e ->
                        Log.w(TAG, "JSON parse failed (${e.message}) — using keyword fallback")
                        keywordFallback(rawMessage)
                    }
                onResult(result)
            },
            onError = { err ->
                Log.e(TAG, "Classifier inference error: $err")
                // Don't surface the error to the UI — silently fall back so the message is sent
                onResult(keywordFallback(rawMessage))
            }
        )
    }

    // ── JSON parsing ──────────────────────────────────────────────────────────

    private fun parseJson(response: String, rawMessage: String): ClassificationResult {
        // Gemma sometimes wraps JSON in markdown fences — strip them first
        val cleaned = response
            .replace(Regex("```json\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("```\\s*"), "")
            .trim()

        // Find the outermost { } block in case there's leading/trailing prose
        val start = cleaned.indexOf('{')
        val end   = cleaned.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) {
            throw IllegalArgumentException("No JSON object found in response")
        }
        val jsonStr = cleaned.substring(start, end + 1)
        val obj = JSONObject(jsonStr)

        val tagsArray = obj.optJSONArray("tags") ?: JSONArray()
        val tags = (0 until tagsArray.length()).map { tagsArray.getString(it) }

        return ClassificationResult(
            category    = SignalCategory.fromString(obj.optString("category")),
            priority    = Priority.fromString(obj.optString("priority")),
            tags        = tags.take(4),
            summary     = obj.optString("summary", rawMessage.take(80)).trim(),
            peopleCount = obj.optInt("peopleCount", 0).takeIf { it > 0 }
        )
    }

    // ── Keyword fallback (no model / parse failure) ───────────────────────────

    private fun keywordFallback(message: String): ClassificationResult {
        val lower = message.lowercase()

        val category = when {
            lower.containsAny("medical","medicine","doctor","hospital","injury",
                "blood","pain","heart","attack","insulin","fever","wound") -> SignalCategory.MEDICAL
            lower.containsAny("trap","stuck","rescue","flood","fire","collapse",
                "missing","stranded","help","save") -> SignalCategory.RESCUE
            lower.containsAny("food","water","shelter","clothes","fuel",
                "supply","supplies","resource","blanket") -> SignalCategory.RESOURCE
            lower.containsAny("safe","evacuate","danger","threat","zone") -> SignalCategory.SAFETY
            else -> SignalCategory.OTHER
        }

        val priority = when {
            lower.containsAny("critical","urgent","immediately","dying","dead",
                "cardiac","trapped","sos","emergency") -> Priority.CRITICAL
            lower.containsAny("serious","severe","broken","unconscious",
                "bleeding","rescue") -> Priority.HIGH
            else -> Priority.NORMAL
        }

        return ClassificationResult(
            category    = category,
            priority    = priority,
            tags        = emptyList(),
            summary     = message.take(80),
            peopleCount = extractPeopleCount(lower)
        )
    }

    private fun extractPeopleCount(lower: String): Int? {
        val words = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4,
            "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
            "nine" to 9, "ten" to 10
        )
        words.forEach { (word, num) -> if (lower.contains(word)) return num }
        val match = Regex("(\\d+)\\s*(people|persons|individuals|civilians|victims)")
            .find(lower)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun String.containsAny(vararg keywords: String) =
        keywords.any { this.contains(it) }
}
