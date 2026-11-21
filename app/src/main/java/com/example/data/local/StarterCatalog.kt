package com.example.data.local

import com.example.data.model.AiMovieEntity
import com.example.data.model.MIN_FEATURE_DURATION_SECONDS

/**
 * Starter catalog enforces the >45 minute feature-length filter rule.
 * Live scraping automatically populates verified >45-minute full-length AI movies
 * from YouTube (using YouTube's long-video filter) and Facebook on startup.
 */
object StarterCatalog {

    fun initialMovies(): List<AiMovieEntity> {
        // Strictly enforce the >45 minute rule: never pre-seed short films under 45 minutes.
        return emptyList<AiMovieEntity>().filter { it.durationSeconds > MIN_FEATURE_DURATION_SECONDS }
    }
}
