package com.disastermesh.app.ai

import android.util.Log
import com.disastermesh.app.model.SignalCategory
import com.disastermesh.app.model.SignalPriority
import org.json.JSONArray
import org.json.JSONObject

object SignalClassifier {

    private const val TAG = "SignalClassifier"

    data class ClassificationResult(
        val category: SignalCategory,
        val priority: SignalPriority,
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
            Log.w(TAG, "Gemma not ready — classification skipped")
            onError("Gemma not ready")
            return
        }

        GemmaClient.generate(
            prompt            = PromptTemplates.classifySignal(rawMessage),
            systemInstruction = PromptTemplates.CLASSIFIER_SYSTEM_INSTRUCTION,
            onResult          = { response ->
                Log.d(TAG, "Classifier raw response: $response")
                val result = runCatching { parseJson(response, rawMessage) }
                    .getOrElse { e ->
                        Log.e(TAG, "JSON parse failed: ${e.message}")
                        onError("Failed to parse Gemma response: ${e.message}")
                        return@generate
                    }
                onResult(result)
            },
            onError = { err ->
                Log.e(TAG, "Classifier inference error: $err")
                onError(err)
            }
        )
    }

    private fun parseJson(response: String, rawMessage: String): ClassificationResult {
        val cleaned = response
            .replace(Regex("```json\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("```\\s*"), "")
            .trim()

        val start = cleaned.indexOf('{')
        val end   = cleaned.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start)
            throw IllegalArgumentException("No JSON object found in response")

        val obj        = JSONObject(cleaned.substring(start, end + 1))
        val tagsArray  = obj.optJSONArray("tags") ?: JSONArray()
        val tags       = (0 until tagsArray.length()).map { tagsArray.getString(it) }

        return ClassificationResult(
            category    = SignalCategory.fromString(obj.optString("category")),
            priority    = SignalPriority.fromString(obj.optString("priority")),
            tags        = tags.take(4),
            summary     = obj.optString("summary", rawMessage.take(80)).trim(),
            peopleCount = obj.optInt("peopleCount", 0).takeIf { it > 0 }
        )
    }
}
