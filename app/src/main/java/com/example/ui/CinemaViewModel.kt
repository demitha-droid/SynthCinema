package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ai.AiCurationRepository
import com.example.data.ai.CuratorAnalysisResult
import com.example.data.ai.PlaceholderAiCurationRepository
import com.example.data.model.CreatorChannelEntity
import com.example.data.model.MovieGenre
import com.example.data.model.MovieSource
import com.example.data.model.MovieWithHistory
import com.example.data.model.TasteProfile
import com.example.data.repository.CinemaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SourceFilter(val label: String) {
    ALL("All"),
    YOUTUBE("YouTube"),
    FACEBOOK("Facebook")
}

enum class HistoryTab(val label: String) {
    RECENT("Watched"),
    WATCHLIST("Watchlist"),
    LIKED("Liked")
}

data class CinemaUiState(
    val allMovies: List<MovieWithHistory> = emptyList(),
    val filteredMovies: List<MovieWithHistory> = emptyList(),
    val featuredMovie: MovieWithHistory? = null,
    val continueWatching: List<MovieWithHistory> = emptyList(),
    val watchHistory: List<MovieWithHistory> = emptyList(),
    val watchlist: List<MovieWithHistory> = emptyList(),
    val likedMovies: List<MovieWithHistory> = emptyList(),
    val personalizedPicks: List<MovieWithHistory> = emptyList(),
    val genreSections: Map<MovieGenre, List<MovieWithHistory>> = emptyMap(),
    val channels: List<CreatorChannelEntity> = emptyList(),
    val tasteProfile: TasteProfile = TasteProfile(),
    val searchQuery: String = "",
    val selectedGenre: MovieGenre = MovieGenre.ALL,
    val selectedSource: SourceFilter = SourceFilter.ALL,
    val selectedHistoryTab: HistoryTab = HistoryTab.RECENT,
    val isScraping: Boolean = false,
    val isAddingChannel: Boolean = false,
    val syncingChannelId: String? = null,
    val lastScrapeBanner: String? = null,
    val isImportingUrl: Boolean = false,
    val statusMessage: String? = null
)

data class CuratorUiState(
    val promptInput: String = "",
    val isAnalyzing: Boolean = false,
    val result: CuratorAnalysisResult? = null,
    val error: String? = null
)

private data class FilterParams(
    val query: String = "",
    val genre: MovieGenre = MovieGenre.ALL,
    val source: SourceFilter = SourceFilter.ALL,
    val historyTab: HistoryTab = HistoryTab.RECENT
)

private data class OperationStatus(
    val isScraping: Boolean = false,
    val isAddingChannel: Boolean = false,
    val syncingChannelId: String? = null,
    val lastScrapeBanner: String? = null,
    val isImportingUrl: Boolean = false,
    val statusMessage: String? = null
)

class CinemaViewModel(
    private val repository: CinemaRepository,
    private val aiCurationRepository: AiCurationRepository = PlaceholderAiCurationRepository()
) : ViewModel() {

    private val filters = MutableStateFlow(FilterParams())
    private val ops = MutableStateFlow(OperationStatus())

    private val _curatorState = MutableStateFlow(CuratorUiState())
    val curatorState: StateFlow<CuratorUiState> = _curatorState.asStateFlow()

    val uiState: StateFlow<CinemaUiState> = combine(
        repository.moviesWithHistory,
        repository.channels,
        filters,
        ops
    ) { allMovies, channels, filter, op ->
        val query = filter.query.trim().lowercase()

        val filtered = allMovies.filter { item ->
            val m = item.movie
            val matchesSource = when (filter.source) {
                SourceFilter.ALL -> true
                SourceFilter.YOUTUBE -> m.movieSource == MovieSource.YOUTUBE
                SourceFilter.FACEBOOK -> m.movieSource == MovieSource.FACEBOOK
            }
            val matchesGenre = filter.genre == MovieGenre.ALL || m.movieGenre == filter.genre
            val matchesQuery = query.isEmpty() ||
                m.title.lowercase().contains(query) ||
                m.director.lowercase().contains(query) ||
                m.movieGenre.displayName.lowercase().contains(query) ||
                m.aiTools.lowercase().contains(query) ||
                m.synopsis.lowercase().contains(query) ||
                m.movieSource.label.lowercase().contains(query)
            matchesSource && matchesGenre && matchesQuery
        }

        val watchHistory = allMovies
            .filter { it.hasWatched }
            .sortedByDescending { it.history?.lastWatchedAt ?: 0L }

        val continueWatching = watchHistory
            .filter { !it.isCompleted || it.progressPercent < 1f }
            .ifEmpty { watchHistory }
            .take(10)

        val watchlist = allMovies.filter { it.isWatchlisted }
        val liked = allMovies.filter { it.isLiked }

        val tasteProfile = computeTasteProfile(watchHistory, watchlist, liked)
        val personalizedPicks = computePersonalizedPicks(allMovies, tasteProfile)

        val genreSections = linkedMapOf<MovieGenre, List<MovieWithHistory>>()
        MovieGenre.browsableGenres.forEach { genre ->
            val items = filtered.filter { it.movie.movieGenre == genre }
            if (items.isNotEmpty()) {
                genreSections[genre] = items
            }
        }

        val featured = filtered.firstOrNull { it.movie.isFeatured }
            ?: filtered.firstOrNull { it.movie.thumbnailUrl.isNotBlank() }
            ?: filtered.firstOrNull()

        CinemaUiState(
            allMovies = allMovies,
            filteredMovies = filtered,
            featuredMovie = featured,
            continueWatching = continueWatching,
            watchHistory = watchHistory,
            watchlist = watchlist,
            likedMovies = liked,
            personalizedPicks = personalizedPicks,
            genreSections = genreSections,
            channels = channels,
            tasteProfile = tasteProfile,
            searchQuery = filter.query,
            selectedGenre = filter.genre,
            selectedSource = filter.source,
            selectedHistoryTab = filter.historyTab,
            isScraping = op.isScraping,
            isAddingChannel = op.isAddingChannel,
            syncingChannelId = op.syncingChannelId,
            lastScrapeBanner = op.lastScrapeBanner,
            isImportingUrl = op.isImportingUrl,
            statusMessage = op.statusMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = CinemaUiState()
    )

    init {
        viewModelScope.launch {
            repository.ensureSeeded()
            scrapeFreshMovies(silentIfFailed = true)
        }
    }

    fun observeMovie(movieId: String): Flow<MovieWithHistory?> =
        repository.observeMovieWithHistory(movieId)

    fun onSearchQueryChanged(query: String) {
        filters.update { it.copy(query = query) }
    }

    fun onGenreSelected(genre: MovieGenre) {
        filters.update { it.copy(genre = genre) }
    }

    fun onSourceFilterSelected(source: SourceFilter) {
        filters.update { it.copy(source = source) }
    }

    fun onHistoryTabSelected(tab: HistoryTab) {
        filters.update { it.copy(historyTab = tab) }
    }

    fun filterByCreator(creatorName: String, source: MovieSource) {
        filters.update {
            it.copy(
                query = creatorName,
                genre = MovieGenre.ALL,
                source = if (source == MovieSource.YOUTUBE) SourceFilter.YOUTUBE else SourceFilter.FACEBOOK
            )
        }
    }

    fun clearStatusMessage() {
        ops.update { it.copy(statusMessage = null) }
    }

    fun dismissScrapeBanner() {
        ops.update { it.copy(lastScrapeBanner = null) }
    }

    fun scrapeFreshMovies(
        genre: MovieGenre = filters.value.genre,
        silentIfFailed: Boolean = false
    ) {
        if (ops.value.isScraping) return
        viewModelScope.launch {
            ops.update { it.copy(isScraping = true, statusMessage = null) }
            val report = repository.scrapeFreshMovies(genre)
            val banner = when {
                report.totalAdded > 0 ->
                    "Scraped ${report.totalAdded} live AI films (${report.youtubeFound} YouTube, ${report.facebookFound} Facebook)"
                silentIfFailed -> null
                report.errorNote != null -> report.errorNote
                else -> "Vault is up to date — no new videos found right now."
            }
            ops.update {
                it.copy(
                    isScraping = false,
                    lastScrapeBanner = banner,
                    statusMessage = if (!silentIfFailed) banner else null
                )
            }
        }
    }

    fun addChannelOrProfile(
        source: MovieSource,
        handleOrUrl: String,
        preferredGenre: MovieGenre,
        onAdded: () -> Unit = {}
    ) {
        if (ops.value.isAddingChannel) return
        viewModelScope.launch {
            ops.update { it.copy(isAddingChannel = true, statusMessage = null) }
            val result = repository.addAndScrapeChannel(source, handleOrUrl, preferredGenre)
            result.fold(
                onSuccess = { scrapeRes ->
                    val count = scrapeRes.movies.size
                    val msg = scrapeRes.note
                        ?: "Added ${scrapeRes.channel.name} & imported $count movies into your vault"
                    ops.update {
                        it.copy(
                            isAddingChannel = false,
                            statusMessage = msg
                        )
                    }
                    onAdded()
                },
                onFailure = { err ->
                    ops.update {
                        it.copy(
                            isAddingChannel = false,
                            statusMessage = err.message ?: "Could not add that channel or profile."
                        )
                    }
                }
            )
        }
    }

    fun syncChannel(channel: CreatorChannelEntity) {
        if (ops.value.syncingChannelId != null) return
        viewModelScope.launch {
            ops.update { it.copy(syncingChannelId = channel.id) }
            val result = repository.syncChannel(channel)
            result.fold(
                onSuccess = { res ->
                    ops.update {
                        it.copy(
                            syncingChannelId = null,
                            statusMessage = "Synced ${res.channel.name}: ${res.movies.size} movies updated"
                        )
                    }
                },
                onFailure = { err ->
                    ops.update {
                        it.copy(
                            syncingChannelId = null,
                            statusMessage = err.message ?: "Sync failed for ${channel.name}"
                        )
                    }
                }
            )
        }
    }

    fun syncAllChannels() {
        if (ops.value.isScraping || ops.value.syncingChannelId != null) return
        viewModelScope.launch {
            ops.update { it.copy(isScraping = true) }
            val count = repository.syncAllChannels()
            ops.update {
                it.copy(
                    isScraping = false,
                    statusMessage = if (count > 0) {
                        "Synced all channels — $count movies refreshed"
                    } else {
                        "All followed channels are up to date"
                    }
                )
            }
        }
    }

    fun deleteChannel(channel: CreatorChannelEntity) {
        viewModelScope.launch {
            repository.deleteChannel(channel.id)
            ops.update { it.copy(statusMessage = "Removed ${channel.name} from followed sources") }
        }
    }

    fun importMovieUrl(url: String, onSuccess: (String) -> Unit = {}) {
        if (ops.value.isImportingUrl) return
        viewModelScope.launch {
            ops.update { it.copy(isImportingUrl = true) }
            val result = repository.importMovieFromUrl(url)
            result.fold(
                onSuccess = { movie ->
                    ops.update {
                        it.copy(
                            isImportingUrl = false,
                            statusMessage = "Scraped \"${movie.title}\" into ${movie.movieGenre.displayName}"
                        )
                    }
                    onSuccess(movie.id)
                },
                onFailure = { err ->
                    ops.update {
                        it.copy(
                            isImportingUrl = false,
                            statusMessage = err.message ?: "Could not scrape that URL."
                        )
                    }
                }
            )
        }
    }

    fun recordWatchStart(movieId: String) {
        viewModelScope.launch {
            repository.recordWatchStart(movieId)
        }
    }

    fun updateWatchProgress(movieId: String, watchedSeconds: Int, durationSeconds: Int) {
        viewModelScope.launch {
            repository.updateWatchProgress(movieId, watchedSeconds, durationSeconds)
        }
    }

    fun toggleLike(movieId: String) {
        viewModelScope.launch {
            repository.toggleLike(movieId)
        }
    }

    fun toggleWatchlist(movieId: String) {
        viewModelScope.launch {
            repository.toggleWatchlist(movieId)
        }
    }

    fun toggleCompleted(movieId: String) {
        viewModelScope.launch {
            repository.markCompleted(movieId)
        }
    }

    fun removeFromHistory(movieId: String) {
        viewModelScope.launch {
            repository.removeFromHistory(movieId)
            ops.update { it.copy(statusMessage = "Removed from watch history") }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAllHistory()
            ops.update { it.copy(statusMessage = "Watch history cleared") }
        }
    }

    fun onCuratorPromptChanged(text: String) {
        _curatorState.update { it.copy(promptInput = text) }
    }

    fun submitCuratorQuery(overrideQuery: String? = null) {
        val query = (overrideQuery ?: _curatorState.value.promptInput).trim()
        if (query.isEmpty() || _curatorState.value.isAnalyzing) return

        viewModelScope.launch {
            _curatorState.update {
                it.copy(promptInput = query, isAnalyzing = true, error = null)
            }
            try {
                val snapshot = uiState.value
                val result = aiCurationRepository.analyzeComplexQuery(
                    query = query,
                    catalog = snapshot.allMovies,
                    tasteProfile = snapshot.tasteProfile
                )
                _curatorState.update {
                    it.copy(isAnalyzing = false, result = result)
                }
            } catch (e: Exception) {
                _curatorState.update {
                    it.copy(
                        isAnalyzing = false,
                        error = e.localizedMessage ?: "Curator analysis failed."
                    )
                }
            }
        }
    }

    private fun computeTasteProfile(
        history: List<MovieWithHistory>,
        watchlist: List<MovieWithHistory>,
        liked: List<MovieWithHistory>
    ): TasteProfile {
        val totalSeconds = history.sumOf { it.history?.watchedSeconds ?: 0 }
        val genreCounts = history.groupingBy { it.movie.movieGenre }.eachCount()
        val favGenre = genreCounts.maxByOrNull { it.value }?.key
        val ytCount = history.count { it.movie.movieSource == MovieSource.YOUTUBE }
        val fbCount = history.count { it.movie.movieSource == MovieSource.FACEBOOK }
        val completedCount = history.count { it.isCompleted }

        return TasteProfile(
            totalWatchedCount = history.size,
            totalMinutesWatched = (totalSeconds / 60).coerceAtLeast(if (history.isNotEmpty()) 1 else 0),
            favoriteGenre = favGenre,
            youtubeWatchedCount = ytCount,
            facebookWatchedCount = fbCount,
            completedCount = completedCount,
            likedCount = liked.size,
            watchlistCount = watchlist.size
        )
    }

    private fun computePersonalizedPicks(
        allMovies: List<MovieWithHistory>,
        profile: TasteProfile
    ): List<MovieWithHistory> {
        val unwatched = allMovies.filter { !it.hasWatched }
        if (unwatched.isEmpty()) return allMovies.take(6)

        val fav = profile.favoriteGenre
        return unwatched.sortedByDescending { item ->
            var score = item.movie.ratingScore
            if (fav != null && item.movie.movieGenre == fav) score += 2.5f
            if (item.isWatchlisted) score += 1.5f
            if (item.movie.isScrapedLive) score += 0.5f
            score
        }.take(8)
    }
}

class CinemaViewModelFactory(
    private val repository: CinemaRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CinemaViewModel::class.java)) {
            return CinemaViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
