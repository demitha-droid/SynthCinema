package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MovieSource(val label: String) {
    YOUTUBE("YouTube"),
    FACEBOOK("Facebook");

    companion object {
        fun fromString(value: String): MovieSource =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: YOUTUBE
    }
}

enum class MovieGenre(val displayName: String, val scrapeQuery: String) {
    ALL("All", "AI generated short film 4k"),
    SCI_FI("Sci-Fi", "AI sci-fi short film movie 4k"),
    CYBERPUNK("Cyberpunk", "AI cyberpunk short film neon"),
    HORROR("Horror", "AI horror thriller short film"),
    FANTASY("Fantasy", "AI dark fantasy short film epic"),
    ANIMATION("Animation", "AI animated short movie 3D"),
    DOCUMENTARY("Documentary", "AI documentary short film future"),
    COMEDY("Comedy", "AI comedy satire short film Neural Viz"),
    EXPERIMENTAL("Experimental", "experimental AI cinema Runway Sora Kling Veo");

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
    val note: String? = null
)
