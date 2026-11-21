package com.example.data.repository

import com.example.data.local.CinemaDao
import com.example.data.local.StarterCatalog
import com.example.data.model.AiMovieEntity
import com.example.data.model.ChannelScrapeResult
import com.example.data.model.CreatorChannelEntity
import com.example.data.model.MIN_FEATURE_DURATION_SECONDS
import com.example.data.model.MovieGenre
import com.example.data.model.MovieSource
import com.example.data.model.MovieWithHistory
import com.example.data.model.ScrapeReport
import com.example.data.model.WatchHistoryEntity
import com.example.data.model.YouTubeChannelSearchResult
import com.example.data.remote.AiMovieScraperEngine
import com.example.data.remote.YouTubeMovieSearchBatch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class CinemaRepository(
    private val dao: CinemaDao,
    private val scraper: AiMovieScraperEngine = AiMovieScraperEngine()
) {

    val moviesWithHistory: Flow<List<MovieWithHistory>> = combine(
        dao.observeAllMovies(MIN_FEATURE_DURATION_SECONDS),
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
        // Enforce the >45 minute filter rule on any previously cached short movies in Room
        dao.pruneShortMovies(MIN_FEATURE_DURATION_SECONDS)
        val validStarters = StarterCatalog.initialMovies()
            .filter { it.durationSeconds > MIN_FEATURE_DURATION_SECONDS }
        if (validStarters.isNotEmpty()) {
            dao.insertMoviesIgnoreConflict(validStarters)
        }
    }

    suspend fun scrapeFreshMovies(genreFilter: MovieGenre = MovieGenre.ALL): ScrapeReport {
        val targetGenres = if (genreFilter == MovieGenre.ALL) {
            MovieGenre.browsableGenres
        } else {
            listOf(genreFilter)
        }

        val batch = scraper.scrapeAllPlatforms(targetGenres)
        val combined = batch.movies.toMutableList()
        var skippedShort = batch.skippedUnderMinDuration

        // Also sync all user-followed YouTube channels and Facebook profiles
        val followed = dao.getAllChannels()
        for (channel in followed) {
            val res = scrapeChannelInternal(channel.source, channel.handleOrUrl, channel.preferredGenre)
            res.getOrNull()?.let { result ->
                skippedShort += result.skippedUnderMinDuration
                val validChannelMovies = result.movies.filter { it.durationSeconds > MIN_FEATURE_DURATION_SECONDS }
                if (validChannelMovies.isNotEmpty()) {
                    combined.addAll(validChannelMovies)
                    dao.upsertChannel(
                        channel.copy(
                            moviesImportedCount = maxOf(channel.moviesImportedCount, validChannelMovies.size),
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }

        val unique = combined
            .filter { it.durationSeconds > MIN_FEATURE_DURATION_SECONDS }
            .distinctBy { it.id }
        if (unique.isNotEmpty()) {
            dao.insertMovies(unique)
        }
        val ytCount = unique.count { it.movieSource == MovieSource.YOUTUBE }
        val fbCount = unique.count { it.movieSource == MovieSource.FACEBOOK }
        return ScrapeReport(
            totalAdded = unique.size,
            youtubeFound = ytCount,
            facebookFound = fbCount,
            skippedUnderMinDuration = skippedShort,
            errorNote = batch.errorNote
        )
    }

    suspend fun searchYouTubeChannels(keyword: String): Result<List<YouTubeChannelSearchResult>> =
        scraper.searchYouTubeChannels(keyword)

    suspend fun searchYouTubeAiMovies(keyword: String): Result<YouTubeMovieSearchBatch> =
        scraper.searchYouTubeAiMovies(keyword)

    suspend fun saveMoviesToVault(movies: List<AiMovieEntity>): Int {
        val valid = movies
            .filter { it.durationSeconds > MIN_FEATURE_DURATION_SECONDS }
            .distinctBy { it.id }
        if (valid.isNotEmpty()) {
            dao.insertMovies(valid)
        }
        return valid.size
    }

    suspend fun addAndScrapeChannel(
        source: MovieSource,
        handleOrUrl: String,
        preferredGenre: MovieGenre
    ): Result<ChannelScrapeResult> {
        val result = scrapeChannelInternal(source.name, handleOrUrl, preferredGenre)
        return result.map { scrapeResult ->
            val validMovies = scrapeResult.movies.filter { it.durationSeconds > MIN_FEATURE_DURATION_SECONDS }
            val updatedChannel = scrapeResult.channel.copy(moviesImportedCount = validMovies.size)
            dao.upsertChannel(updatedChannel)
            if (validMovies.isNotEmpty()) {
                dao.insertMovies(validMovies)
            }
            scrapeResult.copy(channel = updatedChannel, movies = validMovies)
        }
    }

    suspend fun addPickedYouTubeChannel(
        candidate: YouTubeChannelSearchResult,
        preferredGenre: MovieGenre
    ): Result<ChannelScrapeResult> {
        val result = scraper.scrapeYouTubeChannel(
            rawInput = candidate.channelUrl,
            preferredGenre = preferredGenre,
            fallbackName = candidate.name,
            fallbackAvatarUrl = candidate.avatarUrl
        )
        return result.map { scrapeResult ->
            val validMovies = scrapeResult.movies.filter { it.durationSeconds > MIN_FEATURE_DURATION_SECONDS }
            val updatedChannel = scrapeResult.channel.copy(
                name = scrapeResult.channel.name.ifBlank { candidate.name },
                avatarUrl = scrapeResult.channel.avatarUrl.ifBlank { candidate.avatarUrl },
                moviesImportedCount = validMovies.size
            )
            dao.upsertChannel(updatedChannel)
            if (validMovies.isNotEmpty()) {
                dao.insertMovies(validMovies)
            }
            scrapeResult.copy(channel = updatedChannel, movies = validMovies)
        }
    }

    suspend fun syncChannel(channel: CreatorChannelEntity): Result<ChannelScrapeResult> {
        val result = scrapeChannelInternal(channel.source, channel.handleOrUrl, channel.preferredGenre)
        return result.map { scrapeResult ->
            val validMovies = scrapeResult.movies.filter { it.durationSeconds > MIN_FEATURE_DURATION_SECONDS }
            val updatedChannel = channel.copy(
                name = scrapeResult.channel.name.ifBlank { channel.name },
                avatarUrl = scrapeResult.channel.avatarUrl.ifBlank { channel.avatarUrl },
                moviesImportedCount = maxOf(channel.moviesImportedCount, validMovies.size),
                lastSyncedAt = System.currentTimeMillis()
            )
            dao.upsertChannel(updatedChannel)
            if (validMovies.isNotEmpty()) {
                dao.insertMovies(validMovies)
            }
            scrapeResult.copy(channel = updatedChannel, movies = validMovies)
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
        return scraper.scrapeSingleUrl(url).mapCatching { scraped ->
            if (scraped.durationSeconds <= MIN_FEATURE_DURATION_SECONDS) {
                throw IllegalArgumentException(
                    "Filtered out: \"${scraped.title}\" (${scraped.durationText}). Only movies longer than 45 minutes can be added."
                )
            }
            dao.insertMovie(scraped)
            scraped
        }
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
                watchedSeconds = if (nextCompleted) (movie?.durationSeconds ?: 3000) else existing.watchedSeconds,
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
