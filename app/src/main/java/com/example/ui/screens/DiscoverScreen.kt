package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.MovieGenre
import com.example.ui.CinemaUiState
import com.example.ui.SourceFilter
import com.example.ui.components.GenreChipRow
import com.example.ui.components.HeroSpotlightCard
import com.example.ui.components.ImportUrlDialog
import com.example.ui.components.MovieHorizontalCard
import com.example.ui.components.MovieListCard
import com.example.ui.components.ScraperStatusBanner
import com.example.ui.theme.FacebookAzure
import com.example.ui.theme.ObsidianCanvas
import com.example.ui.theme.ProjectorAmber
import com.example.ui.theme.SynthCyan
import com.example.ui.theme.YouTubeCrimson

@Composable
fun DiscoverScreen(
    uiState: CinemaUiState,
    onSearchQueryChanged: (String) -> Unit,
    onGenreSelected: (MovieGenre) -> Unit,
    onSourceSelected: (SourceFilter) -> Unit,
    onScrapeFresh: () -> Unit,
    onDismissBanner: () -> Unit,
    onImportUrl: (String) -> Unit,
    onMovieClick: (String) -> Unit,
    onToggleWatchlist: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showImportDialog by remember { mutableStateOf(false) }

    val isSearchingOrFiltered = uiState.searchQuery.isNotBlank() ||
        uiState.selectedGenre != MovieGenre.ALL ||
        uiState.selectedSource != SourceFilter.ALL

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianCanvas)
            .drawBehind {
                // Warm projector light glow at the top-left of the screening room
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            ProjectorAmber.copy(alpha = 0.10f),
                            ObsidianCanvas.copy(alpha = 0f)
                        ),
                        center = Offset(size.width * 0.15f, 0f),
                        radius = size.width * 0.85f
                    ),
                    radius = size.width * 0.85f,
                    center = Offset(size.width * 0.15f, 0f)
                )
            }
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 900.dp)
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Editorial Top Header
            item(key = "header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 16.dp, end = 12.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MovieFilter,
                                contentDescription = null,
                                tint = ProjectorAmber,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "${uiState.allMovies.size} AI films • YouTube & Facebook",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = { showImportDialog = true },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("import_url_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AddLink,
                                contentDescription = stringResource(R.string.cd_import),
                                tint = SynthCyan
                            )
                        }

                        Surface(
                            onClick = onScrapeFresh,
                            enabled = !uiState.isScraping,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, ProjectorAmber.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .height(42.dp)
                                .testTag("scrape_fresh_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (uiState.isScraping) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = ProjectorAmber
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = stringResource(R.string.cd_scrape),
                                        tint = ProjectorAmber,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Text(
                                    text = if (uiState.isScraping) "Syncing" else "Scrape",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }

            // Search Bar for Easy Discovery
            item(key = "search_bar") {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = onSearchQueryChanged,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.search_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = stringResource(R.string.cd_search),
                            tint = ProjectorAmber
                        )
                    },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { onSearchQueryChanged("") },
                                modifier = Modifier.testTag("clear_search_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.cd_clear_search),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = ProjectorAmber,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .testTag("search_input")
                )
            }

            // Platform Source Filter Row (All / YouTube / Facebook)
            item(key = "source_filters") {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(SourceFilter.entries, key = { it.name }) { source ->
                        val selected = source == uiState.selectedSource
                        val accent = when (source) {
                            SourceFilter.ALL -> SynthCyan
                            SourceFilter.YOUTUBE -> YouTubeCrimson
                            SourceFilter.FACEBOOK -> FacebookAzure
                        }
                        FilterChip(
                            selected = selected,
                            onClick = { onSourceSelected(source) },
                            label = {
                                Text(
                                    text = source.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accent.copy(alpha = 0.22f),
                                selectedLabelColor = accent,
                                containerColor = MaterialTheme.colorScheme.surface,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                borderColor = MaterialTheme.colorScheme.outline,
                                selectedBorderColor = accent
                            ),
                            modifier = Modifier.testTag("source_filter_${source.name.lowercase()}")
                        )
                    }
                }
            }

            // Genre Categorization Chips
            item(key = "genre_chips") {
                GenreChipRow(
                    selectedGenre = uiState.selectedGenre,
                    onGenreSelected = onGenreSelected
                )
            }

            // Live Scrape Feedback Banner
            item(key = "scrape_banner") {
                AnimatedVisibility(visible = uiState.lastScrapeBanner != null) {
                    uiState.lastScrapeBanner?.let { banner ->
                        ScraperStatusBanner(
                            message = banner,
                            onDismiss = onDismissBanner
                        )
                    }
                }
            }

            // Empty Search State
            if (uiState.filteredMovies.isEmpty()) {
                item(key = "empty_search") {
                    EmptyDiscoveryCard(
                        onResetFilters = {
                            onSearchQueryChanged("")
                            onGenreSelected(MovieGenre.ALL)
                            onSourceSelected(SourceFilter.ALL)
                        },
                        onScrapeFresh = onScrapeFresh
                    )
                }
            } else if (!isSearchingOrFiltered) {
                // Full Editorial Cinema Home: Hero Spotlight + Continue Watching + Genre Rails
                uiState.featuredMovie?.let { hero ->
                    item(key = "hero_spotlight") {
                        HeroSpotlightCard(
                            item = hero,
                            onWatchClick = { onMovieClick(hero.movie.id) },
                            onToggleWatchlist = { onToggleWatchlist(hero.movie.id) }
                        )
                    }
                }

                if (uiState.continueWatching.isNotEmpty()) {
                    item(key = "continue_watching_rail") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionHeader(
                                title = stringResource(R.string.section_continue_watching),
                                subtitle = "Pick up right where you paused"
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(uiState.continueWatching, key = { "cw_${it.movie.id}" }) { item ->
                                    MovieHorizontalCard(
                                        item = item,
                                        onClick = { onMovieClick(item.movie.id) },
                                        showProgress = true
                                    )
                                }
                            }
                        }
                    }
                }

                uiState.genreSections.forEach { (genre, movies) ->
                    item(key = "genre_rail_${genre.name}") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionHeader(
                                title = genre.displayName,
                                subtitle = "${movies.size} curated AI films",
                                actionLabel = "See All",
                                onActionClick = { onGenreSelected(genre) }
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(movies, key = { "${genre.name}_${it.movie.id}" }) { item ->
                                    MovieHorizontalCard(
                                        item = item,
                                        onClick = { onMovieClick(item.movie.id) },
                                        showProgress = item.hasWatched
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Filtered / Search Results List
                item(key = "results_count") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${uiState.filteredMovies.size} results found",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        TextButton(
                            onClick = {
                                onSearchQueryChanged("")
                                onGenreSelected(MovieGenre.ALL)
                                onSourceSelected(SourceFilter.ALL)
                            }
                        ) {
                            Text("Reset Filters", color = ProjectorAmber)
                        }
                    }
                }

                items(uiState.filteredMovies, key = { "res_${it.movie.id}" }) { item ->
                    MovieListCard(
                        item = item,
                        onClick = { onMovieClick(item.movie.id) },
                        onToggleWatchlist = { onToggleWatchlist(item.movie.id) },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }

    if (showImportDialog) {
        ImportUrlDialog(
            isLoading = uiState.isImportingUrl,
            onDismiss = { showImportDialog = false },
            onImport = { url ->
                onImportUrl(url)
                showImportDialog = false
            }
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (actionLabel != null && onActionClick != null) {
            TextButton(onClick = onActionClick) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = ProjectorAmber
                )
            }
        }
    }
}

@Composable
private fun EmptyDiscoveryCard(
    onResetFilters: () -> Unit,
    onScrapeFresh: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_empty_history),
                contentDescription = stringResource(R.string.search_empty_title),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(14.dp))
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.search_empty_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.search_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onResetFilters) {
                    Text("Reset Filters", color = SynthCyan)
                }
                Button(onClick = onScrapeFresh) {
                    Text(stringResource(R.string.action_scrape_fresh))
                }
            }
        }
    }
}
