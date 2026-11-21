package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ai.AiCurationRepository
import com.example.data.ai.CuratorAnalysisResult
import com.example.data.ai.PlaceholderAiCurationRepository
import com.example.data.model.AiMovieEntity
import com.example.data.model.CreatorChannelEntity
import com.example.data.model.MovieGenre
import com.example.data.model.MovieSource
import com.example.data.model.MovieWithHistory
import com.example.data.model.TasteProfile
import com.example.data.model.YouTubeChannelSearchResult
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

data class YouTubeChannelSearchUiState(
    val keyword: String = "",
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val results: List<YouTubeChannelSearchResult> = emptyList(),
    val selectedChannelIds: Set<String> = emptySet(),
    val preferredGenre: MovieGenre = MovieGenre.ALL,
    val isAddingSelected: Boolean = false,
    val addingSingleChannelId: String? = null,
    val error: String? = null
)

data class YouTubeMovieSearchUiState(
    val query: String = "",
    val executedQuery: String = "",
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val results: List<AiMovieEntity> = emptyList(),
    val skippedUnderMinDuration: Int = 0,
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

    private val _channelSearchState = MutableStateFlow(YouTubeChannelSearchUiState())
    val channelSearchState: StateFlow<YouTubeChannelSearchUiState> = _channelSearchState.asStateFlow()

    private val _ytMovieSearchState = MutableStateFlow(YouTubeMovieSearchUiState())
    val ytMovieSearchState: StateFlow<YouTubeMovieSearchUiState> = _ytMovieSearchState.asStateFlow()

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

    fun onYouTubeMovieQueryChanged(query: String) {
        _ytMovieSearchState.update { it.copy(query = query, error = null) }
    }

    fun clearYouTubeMovieSearch() {
        _ytMovieSearchState.value = YouTubeMovieSearchUiState()
    }

    fun searchYouTubeForAiMovies(overrideQuery: String? = null) {
        val raw = (overrideQuery ?: _ytMovieSearchState.value.query).trim()
        if (raw.isEmpty() || _ytMovieSearchState.value.isSearching) return

        viewModelScope.launch {
            _ytMovieSearchState.update {
                it.copy(
                    query = raw,
                    isSearching = true,
                    error = null
                )
            }
            val result = repository.searchYouTubeAiMovies(raw)
            result.fold(
                onSuccess = { batch ->
                    val emptyNote = if (batch.movies.isEmpty()) {
                        if (batch.skippedUnderMinDuration > 0) {
                            "No AI movies >45m found for \"$raw\" (${batch.skippedUnderMinDuration} shorter clips filtered out)."
                        } else {
                            "No YouTube AI movies >45m found for \"$raw\". Try another keyword like \"sci-fi\", \"cyberpunk\", or \"fantasy\"."
                        }
                    } else {
                        null
                    }
                    _ytMovieSearchState.update {
                        it.copy(
                            isSearching = false,
                            hasSearched = true,
                            executedQuery = batch.executedQuery,
                            results = batch.movies,
                            skippedUnderMinDuration = batch.skippedUnderMinDuration,
                            error = emptyNote
                        )
                    }
                },
                onFailure = { err ->
                    _ytMovieSearchState.update {
                        it.copy(
                            isSearching = false,
                            hasSearched = true,
                            results = emptyList(),
                            error = err.message ?: "YouTube search failed."
                        )
                    }
                }
            )
        }
    }

    fun saveYouTubeMovieToVault(movie: AiMovieEntity, onSaved: (String) -> Unit = {}) {
        viewModelScope.launch {
            val saved = repository.saveMoviesToVault(listOf(movie))
            if (saved > 0) {
                ops.update {
                    it.copy(statusMessage = "Saved \"${movie.title}\" (${movie.durationText}) to your vault")
                }
                onSaved(movie.id)
            } else {
                ops.update {
                    it.copy(statusMessage = "Could not save — only movies longer than 45 minutes are allowed.")
                }
            }
        }
    }

    fun saveAllSearchedYouTubeMovies() {
        val movies = _ytMovieSearchState.value.results
        if (movies.isEmpty()) return
        viewModelScope.launch {
            val savedCount = repository.saveMoviesToVault(movies)
            ops.update {
                it.copy(statusMessage = "Saved $savedCount YouTube AI movies (>45m) to your vault")
            }
        }
    }

    fun scrapeFreshMovies(
        genre: MovieGenre = filters.value.genre,
        silentIfFailed: Boolean = false
    ) {
        if (ops.value.isScraping) return
        viewModelScope.launch {
            ops.update { it.copy(isScraping = true, statusMessage = null) }
            val report = repository.scrapeFreshMovies(genre)
            val skippedSuffix = if (report.skippedUnderMinDuration > 0) {
                " • ${report.skippedUnderMinDuration} under 45m filtered"
            } else {
                ""
            }
            val banner = when {
                report.totalAdded > 0 ->
                    "Scraped ${report.totalAdded} feature AI movies (>45m) (${report.youtubeFound} YT, ${report.facebookFound} FB)$skippedSuffix"
                silentIfFailed -> null
                report.errorNote != null -> report.errorNote
                report.skippedUnderMinDuration > 0 ->
                    "No new movies >45m found (${report.skippedUnderMinDuration} shorter videos filtered out)."
                else -> "Vault is up to date — no new feature movies (>45m) found right now."
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
                    val defaultMsg = if (res.skippedUnderMinDuration > 0) {
                        "Synced ${res.channel.name}: ${res.movies.size} movies (>45m) • ${res.skippedUnderMinDuration} shorter skipped"
                    } else {
                        "Synced ${res.channel.name}: ${res.movies.size} movies (>45m) updated"
                    }
                    ops.update {
                        it.copy(
                            syncingChannelId = null,
                            statusMessage = res.note ?: defaultMsg
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
                        "Synced all channels — $count feature movies (>45m) refreshed"
                    } else {
                        "All followed channels are up to date (only >45m movies saved)"
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

    fun onYouTubeChannelKeywordChanged(keyword: String) {
        _channelSearchState.update { it.copy(keyword = keyword, error = null) }
    }

    fun onYouTubeChannelSearchGenreChanged(genre: MovieGenre) {
        _channelSearchState.update { it.copy(preferredGenre = genre) }
    }

    fun searchYouTubeChannels(overrideKeyword: String? = null) {
        val query = (overrideKeyword ?: _channelSearchState.value.keyword).trim()
        if (query.isEmpty() || _channelSearchState.value.isSearching) return

        viewModelScope.launch {
            _channelSearchState.update {
                it.copy(
                    keyword = query,
                    isSearching = true,
                    error = null,
                    selectedChannelIds = emptySet()
                )
            }
            val result = repository.searchYouTubeChannels(query)
            result.fold(
                onSuccess = { found ->
                    _channelSearchState.update {
                        it.copy(
                            isSearching = false,
                            hasSearched = true,
                            results = found,
                            error = if (found.isEmpty()) {
                                "No YouTube channels found for \"$query\". Try broader keywords like \"AI movies\" or \"sci-fi full film\"."
                            } else {
                                null
                            }
                        )
                    }
                },
                onFailure = { err ->
                    _channelSearchState.update {
                        it.copy(
                            isSearching = false,
                            hasSearched = true,
                            error = err.message ?: "Failed to search YouTube channels."
                        )
                    }
                }
            )
        }
    }

    fun toggleYouTubeChannelSelection(channelId: String) {
        _channelSearchState.update { state ->
            val updated = if (channelId in state.selectedChannelIds) {
                state.selectedChannelIds - channelId
            } else {
                state.selectedChannelIds + channelId
            }
            state.copy(selectedChannelIds = updated)
        }
    }

    fun clearYouTubeChannelSelection() {
        _channelSearchState.update { it.copy(selectedChannelIds = emptySet()) }
    }

    fun addSingleSearchedYouTubeChannel(candidate: YouTubeChannelSearchResult) {
        val state = _channelSearchState.value
        if (state.isAddingSelected || state.addingSingleChannelId != null) return

        viewModelScope.launch {
            _channelSearchState.update { it.copy(addingSingleChannelId = candidate.channelId) }
            val result = repository.addPickedYouTubeChannel(candidate, state.preferredGenre)
            result.fold(
                onSuccess = { scrapeRes ->
                    val defaultMsg = if (scrapeRes.skippedUnderMinDuration > 0) {
                        "Added ${scrapeRes.channel.name} to feed • ${scrapeRes.movies.size} movies (>45m), ${scrapeRes.skippedUnderMinDuration} shorter skipped"
                    } else {
                        "Added ${scrapeRes.channel.name} to feed • ${scrapeRes.movies.size} feature movies (>45m) imported"
                    }
                    _channelSearchState.update {
                        it.copy(
                            addingSingleChannelId = null,
                            selectedChannelIds = it.selectedChannelIds - candidate.channelId
                        )
                    }
                    ops.update { it.copy(statusMessage = scrapeRes.note ?: defaultMsg) }
                },
                onFailure = { err ->
                    _channelSearchState.update { it.copy(addingSingleChannelId = null) }
                    ops.update {
                        it.copy(statusMessage = err.message ?: "Could not add ${candidate.name}.")
                    }
                }
            )
        }
    }

    fun addPickedYouTubeChannels() {
        val state = _channelSearchState.value
        if (state.isAddingSelected || state.selectedChannelIds.isEmpty()) return

        val picked = state.results.filter { it.channelId in state.selectedChannelIds }
        if (picked.isEmpty()) return

        viewModelScope.launch {
            _channelSearchState.update { it.copy(isAddingSelected = true) }
            var addedChannels = 0
            var totalMovies = 0
            var totalSkippedShort = 0

            for (candidate in picked) {
                repository.addPickedYouTubeChannel(candidate, state.preferredGenre).onSuccess { res ->
                    addedChannels++
                    totalMovies += res.movies.size
                    totalSkippedShort += res.skippedUnderMinDuration
                }
            }

            _channelSearchState.update {
                it.copy(
                    isAddingSelected = false,
                    selectedChannelIds = emptySet()
                )
            }

            val skippedPart = if (totalSkippedShort > 0) {
                " • $totalSkippedShort under 45m filtered"
            } else {
                ""
            }
            val msg = if (addedChannels > 0) {
                "Added $addedChannels YouTube channel${if (addedChannels == 1) "" else "s"} to feed ($totalMovies feature movies >45m$skippedPart)"
            } else {
                "Could not import the selected YouTube channels right now."
            }
            ops.update { it.copy(statusMessage = msg) }
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
                            statusMessage = "Scraped \"${movie.title}\" (${movie.durationText}) into ${movie.movieGenre.displayName}"
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
