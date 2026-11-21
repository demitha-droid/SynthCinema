package com.example.data.repository

import com.example.data.local.CinemaDao
import com.example.data.local.StarterCatalog
import com.example.data.model.AiMovieEntity
import com.example.data.model.ChannelScrapeResult
import com.example.data.model.CreatorChannelEntity
import com.example.data.model.MovieGenre
import com.example.data.model.MovieSource
import com.example.data.model.MovieWithHistory
import com.example.data.model.ScrapeReport
import com.example.data.model.WatchHistoryEntity
import com.example.data.remote.AiMovieScraperEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class CinemaRepository(
    private val dao: CinemaDao,
    private val scraper: AiMovieScraperEngine = AiMovieScraperEngine()
) {

    val moviesWithHistory: Flow<List<MovieWithHistory>> = combine(
        dao.observeAllMovies(),
        dao.observeAllHistory()
    ) { movies, historyList ->
        val historyMap = historyList.associateBy { it.movieId }
        movies.map { movie ->
            MovieWithHistory(movie = movie, history = historyMap[movie.id])
        }
    }

    val channels: Flow<List<CreatorChannelEntity>> = dao.observeAllChannels()

    fun observeMovieWithHistory(movieId: String): Flow<MovieWithHistory?> = combine(
        dao.observeMovieById(movieId),
        dao.observeAllHistory()
    ) { movie, historyList ->
        movie?.let {
            val hist = historyList.firstOrNull { h -> h.movieId == it.id }
            MovieWithHistory(movie = it, history = hist)
        }
    }

    suspend fun ensureSeeded() {
        dao.insertMoviesIgnoreConflict(StarterCatalog.initialMovies())
    }

    suspend fun scrapeFreshMovies(genreFilter: MovieGenre = MovieGenre.ALL): ScrapeReport {
        val targetGenres = if (genreFilter == MovieGenre.ALL) {
            MovieGenre.browsableGenres
        } else {
            listOf(genreFilter)
        }

        val (scraped, errorNote) = scraper.scrapeAllPlatforms(targetGenres)
        val combined = scraped.toMutableList()

        // Also sync all user-followed YouTube channels and Facebook profiles
        val followed = dao.getAllChannels()
        for (channel in followed) {
            val res = scrapeChannelInternal(channel.source, channel.handleOrUrl, channel.preferredGenre)
            res.getOrNull()?.let { result ->
                if (result.movies.isNotEmpty()) {
                    combined.addAll(result.movies)
                    dao.upsertChannel(
                        channel.copy(
                            moviesImportedCount = maxOf(channel.moviesImportedCount, result.movies.size),
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }

        val unique = combined.distinctBy { it.id }
        if (unique.isNotEmpty()) {
            dao.insertMovies(unique)
        }
        val ytCount = unique.count { it.movieSource == MovieSource.YOUTUBE }
        val fbCount = unique.count { it.movieSource == MovieSource.FACEBOOK }
        return ScrapeReport(
            totalAdded = unique.size,
            youtubeFound = ytCount,
            facebookFound = fbCount,
            errorNote = errorNote
        )
    }

    suspend fun addAndScrapeChannel(
        source: MovieSource,
        handleOrUrl: String,
        preferredGenre: MovieGenre
    ): Result<ChannelScrapeResult> {
        val result = scrapeChannelInternal(source.name, handleOrUrl, preferredGenre)
        return result.map { scrapeResult ->
            dao.upsertChannel(scrapeResult.channel)
            if (scrapeResult.movies.isNotEmpty()) {
                dao.insertMovies(scrapeResult.movies)
            }
            scrapeResult
        }
    }

    suspend fun syncChannel(channel: CreatorChannelEntity): Result<ChannelScrapeResult> {
        val result = scrapeChannelInternal(channel.source, channel.handleOrUrl, channel.preferredGenre)
        return result.map { scrapeResult ->
            val updatedChannel = channel.copy(
                name = scrapeResult.channel.name.ifBlank { channel.name },
                avatarUrl = scrapeResult.channel.avatarUrl.ifBlank { channel.avatarUrl },
                moviesImportedCount = maxOf(channel.moviesImportedCount, scrapeResult.movies.size),
                lastSyncedAt = System.currentTimeMillis()
            )
            dao.upsertChannel(updatedChannel)
            if (scrapeResult.movies.isNotEmpty()) {
                dao.insertMovies(scrapeResult.movies)
            }
            scrapeResult.copy(channel = updatedChannel)
        }
    }

    suspend fun syncAllChannels(): Int {
        val followed = dao.getAllChannels()
        var total = 0
        for (channel in followed) {
            syncChannel(channel).getOrNull()?.let { res ->
                total += res.movies.size
            }
        }
        return total
    }

    suspend fun deleteChannel(channelId: String) {
        dao.deleteChannelById(channelId)
    }

    private suspend fun scrapeChannelInternal(
        sourceName: String,
        handleOrUrl: String,
        preferredGenre: MovieGenre
    ): Result<ChannelScrapeResult> {
        return when (MovieSource.fromString(sourceName)) {
            MovieSource.YOUTUBE -> scraper.scrapeYouTubeChannel(handleOrUrl, preferredGenre)
            MovieSource.FACEBOOK -> scraper.scrapeFacebookProfile(handleOrUrl, preferredGenre)
        }
    }

    suspend fun importMovieFromUrl(url: String): Result<AiMovieEntity> {
        val scraped = scraper.scrapeSingleUrl(url)
            ?: return Result.failure(
                IllegalArgumentException("Please enter a valid YouTube or Facebook video URL.")
            )
        dao.insertMovie(scraped)
        return Result.success(scraped)
    }

    suspend fun recordWatchStart(movieId: String) {
        val existing = dao.getHistoryByMovieId(movieId)
        val now = System.currentTimeMillis()
        val updated = if (existing == null) {
            WatchHistoryEntity(
                movieId = movieId,
                lastWatchedAt = now,
                watchCount = 1,
                watchedSeconds = 5,
                progressPercent = 0.04f,
                isCompleted = false
            )
        } else {
            existing.copy(
                lastWatchedAt = now,
                watchCount = existing.watchCount + 1,
                progressPercent = existing.progressPercent.coerceAtLeast(0.05f)
            )
        }
        dao.upsertHistory(updated)
    }

    suspend fun updateWatchProgress(movieId: String, watchedSeconds: Int, durationSeconds: Int) {
        val existing = dao.getHistoryByMovieId(movieId) ?: WatchHistoryEntity(
            movieId = movieId,
            lastWatchedAt = System.currentTimeMillis(),
            watchCount = 1
        )
        val safeDuration = durationSeconds.coerceAtLeast(30)
        val newWatched = maxOf(existing.watchedSeconds, watchedSeconds).coerceAtMost(safeDuration)
        val progress = (newWatched.toFloat() / safeDuration.toFloat()).coerceIn(0.05f, 1f)
        val completed = existing.isCompleted || progress >= 0.90f
        dao.upsertHistory(
            existing.copy(
                lastWatchedAt = System.currentTimeMillis(),
                watchedSeconds = newWatched,
                progressPercent = if (completed) 1f else progress,
                isCompleted = completed
            )
        )
    }

    suspend fun markCompleted(movieId: String) {
        val movie = dao.getMovieById(movieId)
        val existing = dao.getHistoryByMovieId(movieId) ?: WatchHistoryEntity(
            movieId = movieId,
            watchCount = 1
        )
        val nextCompleted = !existing.isCompleted
        dao.upsertHistory(
            existing.copy(
                lastWatchedAt = System.currentTimeMillis(),
                watchCount = existing.watchCount.coerceAtLeast(1),
                watchedSeconds = if (nextCompleted) (movie?.durationSeconds ?: 300) else existing.watchedSeconds,
                progressPercent = if (nextCompleted) 1f else 0.5f,
                isCompleted = nextCompleted
            )
        )
    }

    suspend fun toggleLike(movieId: String) {
        val existing = dao.getHistoryByMovieId(movieId) ?: WatchHistoryEntity(movieId = movieId)
        dao.upsertHistory(existing.copy(isLiked = !existing.isLiked))
    }

    suspend fun toggleWatchlist(movieId: String) {
        val existing = dao.getHistoryByMovieId(movieId) ?: WatchHistoryEntity(movieId = movieId)
        dao.upsertHistory(existing.copy(isWatchlisted = !existing.isWatchlisted))
    }

    suspend fun removeFromHistory(movieId: String) {
        dao.clearWatchRecordForMovie(movieId)
    }

    suspend fun clearAllHistory() {
        dao.clearAllWatchLogs()
    }
}
