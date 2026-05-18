package com.disastermesh.app.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keyword-based RAG over the bundled emergency_kb/ asset directory.
 *
 * Each .md file is one article. Articles are loaded once on first call and
 * cached in memory for the session. Retrieval scores articles by keyword overlap
 * with the query and returns the top-N article bodies.
 */
object KnowledgeBaseRetriever {

    private const val TAG       = "KnowledgeBaseRetriever"
    private const val KB_DIR    = "emergency_kb"
    private const val TOP_N     = 2
    private const val MIN_SCORE = 1

    data class Article(val title: String, val body: String, val keywords: Set<String>)

    @Volatile private var cache: List<Article>? = null

    suspend fun load(context: Context): List<Article> = withContext(Dispatchers.IO) {
        cache?.let { return@withContext it }
        val assets = context.assets
        val files  = try { assets.list(KB_DIR) ?: emptyArray() } catch (e: Exception) {
            Log.e(TAG, "KB dir not found: $e")
            return@withContext emptyList()
        }
        val articles = files.filter { it.endsWith(".md") }.mapNotNull { file ->
            try {
                val raw = assets.open("$KB_DIR/$file").bufferedReader().readText()
                parseArticle(raw)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse $file: $e")
                null
            }
        }
        cache = articles
        Log.d(TAG, "Loaded ${articles.size} KB articles")
        articles
    }

    fun retrieve(query: String, articles: List<Article>): String {
        if (articles.isEmpty()) return ""
        val queryTokens = tokenize(query)
        val scored = articles.map { article ->
            val score = queryTokens.count { token ->
                article.keywords.any { kw -> kw.contains(token) || token.contains(kw) }
            }
            score to article
        }.filter { it.first >= MIN_SCORE }
            .sortedByDescending { it.first }
            .take(TOP_N)
            .map { it.second }

        if (scored.isEmpty()) return ""
        return scored.joinToString("\n\n---\n\n") { "## ${it.title}\n${it.body}" }
    }

    fun parseArticle(raw: String): Article? {
        val lines  = raw.trim().lines()
        val title  = lines.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
            ?: return null
        val body   = raw.trim()
        val keywords = tokenize(raw).toSet()
        return Article(title = title, body = body, keywords = keywords)
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 3 }
            .distinct()
}
