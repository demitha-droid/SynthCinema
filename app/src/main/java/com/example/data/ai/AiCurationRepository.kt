package com.example.data.ai

import com.example.data.model.MovieWithHistory
import com.example.data.model.TasteProfile
import kotlinx.coroutines.delay

data class CuratorAnalysisResult(
    val query: String,
    val thinkingSummary: String,
    val editorialResponse: String,
    val recommendedMovieIds: List<String>,
    val isPlaceholderSample: Boolean = true,
    val modelNotice: String =
        "Sample preview response — Connect Firebase AI Logic to unlock live Gemini (gemini-4-carbon, High Thinking) deep curation."
)

/**
 * Abstraction for AI-powered deep film curation, mood matching, and complex query analysis.
 * Backed by PlaceholderAiCurationRepository while Firebase AI Logic is not enabled.
 */
interface AiCurationRepository {
    suspend fun analyzeComplexQuery(
        query: String,
        catalog: List<MovieWithHistory>,
        tasteProfile: TasteProfile
    ): CuratorAnalysisResult
}

class PlaceholderAiCurationRepository : AiCurationRepository {

    override suspend fun analyzeComplexQuery(
        query: String,
        catalog: List<MovieWithHistory>,
        tasteProfile: TasteProfile
    ): CuratorAnalysisResult {
        delay(650L)

        val lower = query.lowercase()
        val keywords = lower.split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }

        val ranked = catalog.sortedByDescending { item ->
            var score = 0
            val searchable = "${item.movie.title} ${item.movie.genre} ${item.movie.synopsis} ${item.movie.aiTools} ${item.movie.source}".lowercase()
            keywords.forEach { kw ->
                if (kw in searchable) score += 3
            }
            if (tasteProfile.favoriteGenre != null && item.movie.movieGenre == tasteProfile.favoriteGenre) {
                score += 2
            }
            if ("short" in lower || "under 5" in lower) {
                if (item.movie.durationSeconds <= 300) score += 4
            }
            if ("facebook" in lower && item.movie.source.equals("FACEBOOK", ignoreCase = true)) {
                score += 5
            }
            if ("youtube" in lower && item.movie.source.equals("YOUTUBE", ignoreCase = true)) {
                score += 5
            }
            if (!item.hasWatched) score += 1
            score
        }

        val topPicks = ranked.take(4)
        val favoriteGenreText = tasteProfile.favoriteGenre?.displayName ?: "Sci-Fi & Cyberpunk"
        val watchedSummary = if (tasteProfile.totalWatchedCount > 0) {
            "Based on your ${tasteProfile.totalWatchedCount} watched films (leaning toward $favoriteGenreText)"
        } else {
            "Starting from our highest-rated AI cinema selections across YouTube & Facebook"
        }

        val thinkingTrace = buildString {
            appendLine("1. Parsed query intent: \"$query\"")
            appendLine("2. Evaluated ${catalog.size} vaulted AI movies across YouTube & Facebook Watch.")
            appendLine("3. Cross-referenced watch history affinity ($favoriteGenreText) and runtime pacing.")
            append("4. Ranked ${topPicks.size} cohesive screening picks for immediate playback.")
        }

        val editorial = buildString {
            appendLine("[Sample Preview Response]")
            appendLine()
            append("$watchedSummary, here is a curated screening sequence tailored to \"$query\". ")
            if (topPicks.isNotEmpty()) {
                val lead = topPicks.first().movie
                append("Start with \"${lead.title}\" (${lead.movieGenre.displayName}, ${lead.durationText}) on ${lead.movieSource.label} for strong visual worldbuilding")
                if (lead.aiTools.isNotBlank()) {
                    append(" crafted with ${lead.aiTools}")
                }
                append(". ")
                if (topPicks.size > 1) {
                    val second = topPicks[1].movie
                    append("Follow it with \"${second.title}\" to contrast pacing and generative lighting style.")
                }
            } else {
                append("Tap 'Scrape Fresh' on the Discover tab to populate your vault with live YouTube and Facebook AI films.")
            }
        }

        return CuratorAnalysisResult(
            query = query,
            thinkingSummary = thinkingTrace,
            editorialResponse = editorial,
            recommendedMovieIds = topPicks.map { it.movie.id },
            isPlaceholderSample = true
        )
    }
}
