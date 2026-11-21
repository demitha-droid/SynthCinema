package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Filter rule: Only feature-length movies strictly longer than 45 minutes (2,700 seconds)
 * are allowed to be added to the database.
 */
const val MIN_FEATURE_DURATION_SECONDS: Int = 45 * 60

enum class MovieSource(val label: String) {
    YOUTUBE("YouTube"),
    FACEBOOK("Facebook");

    companion object {
        fun fromString(value: String): MovieSource =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: YOUTUBE
    }
}

enum class MovieGenre(val displayName: String, val scrapeQuery: String) {
    ALL("All", "AI generated full movie 4k"),
    SCI_FI("Sci-Fi", "AI sci-fi full movie 4k"),
    CYBERPUNK("Cyberpunk", "AI cyberpunk full movie"),
    HORROR("Horror", "AI horror thriller full movie"),
    FANTASY("Fantasy", "AI dark fantasy full movie 4k"),
    ANIMATION("Animation", "AI animated full movie"),
    DOCUMENTARY("Documentary", "AI documentary full movie"),
    COMEDY("Comedy", "AI comedy sci-fi full movie"),
    EXPERIMENTAL("Experimental", "AI feature film full movie");

    companion object {
        val browsableGenres: List<MovieGenre> = entries.filter { it != ALL }

        fun fromString(value: String): MovieGenre =
            entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) ||
                    it.displayName.equals(value, ignoreCase = true)
            } ?: SCI_FI
    }
}

@Entity(tableName = "ai_movies")
data class AiMovieEntity(
    @PrimaryKey val id: String,
    val title: String,
    val director: String,
    val source: String, // MovieSource.name
    val videoId: String,
    val watchUrl: String,
    val thumbnailUrl: String,
    val genre: String, // MovieGenre.name
    val durationText: String,
    val durationSeconds: Int,
    val viewCountText: String,
    val synopsis: String,
    val aiTools: String, // Comma-separated tools e.g. "Runway, Kling, Midjourney"
    val publishedText: String,
    val ratingScore: Float,
    val isFeatured: Boolean = false,
    val isScrapedLive: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
) {
    val movieSource: MovieSource
        get() = MovieSource.fromString(source)

    val movieGenre: MovieGenre
        get() = MovieGenre.fromString(genre)

    val aiToolList: List<String>
        get() = aiTools.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    val meetsFeatureLengthRule: Boolean
        get() = durationSeconds > MIN_FEATURE_DURATION_SECONDS
}

@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey val movieId: String,
    val lastWatchedAt: Long = 0L,
    val watchCount: Int = 0,
    val watchedSeconds: Int = 0,
    val progressPercent: Float = 0f,
    val isCompleted: Boolean = false,
    val isLiked: Boolean = false,
    val isWatchlisted: Boolean = false
)

data class MovieWithHistory(
    val movie: AiMovieEntity,
    val history: WatchHistoryEntity? = null
) {
    val hasWatched: Boolean
        get() = (history?.watchCount ?: 0) > 0 && (history?.lastWatchedAt ?: 0L) > 0L

    val isLiked: Boolean
        get() = history?.isLiked == true

    val isWatchlisted: Boolean
        get() = history?.isWatchlisted == true

    val progressPercent: Float
        get() = history?.progressPercent ?: 0f

    val isCompleted: Boolean
        get() = history?.isCompleted == true
}

data class TasteProfile(
    val totalWatchedCount: Int = 0,
    val totalMinutesWatched: Int = 0,
    val favoriteGenre: MovieGenre? = null,
    val youtubeWatchedCount: Int = 0,
    val facebookWatchedCount: Int = 0,
    val completedCount: Int = 0,
    val likedCount: Int = 0,
    val watchlistCount: Int = 0
)

data class ScrapeReport(
    val totalAdded: Int,
    val youtubeFound: Int,
    val facebookFound: Int,
    val skippedUnderMinDuration: Int = 0,
    val errorNote: String? = null
)

@Entity(tableName = "creator_channels")
data class CreatorChannelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val handleOrUrl: String,
    val source: String, // MovieSource.name
    val externalId: String,
    val avatarUrl: String = "",
    val defaultGenre: String = MovieGenre.ALL.name, // ALL = Auto-detect
    val moviesImportedCount: Int = 0,
    val lastSyncedAt: Long = System.currentTimeMillis(),
    val addedAt: Long = System.currentTimeMillis()
) {
    val movieSource: MovieSource
        get() = MovieSource.fromString(source)

    val preferredGenre: MovieGenre
        get() = MovieGenre.fromString(defaultGenre)
}

data class ChannelScrapeResult(
    val channel: CreatorChannelEntity,
    val movies: List<AiMovieEntity>,
    val skippedUnderMinDuration: Int = 0,
    val note: String? = null
)

data class YouTubeChannelSearchResult(
    val channelId: String,
    val name: String,
    val handle: String,
    val channelUrl: String,
    val avatarUrl: String,
    val statsText: String,
    val description: String
)
