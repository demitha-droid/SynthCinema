package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.MovieWithHistory
import com.example.data.model.TasteProfile
import com.example.ui.CinemaUiState
import com.example.ui.HistoryTab
import com.example.ui.components.DurationBadge
import com.example.ui.components.MovieHorizontalCard
import com.example.ui.components.MovieListCard
import com.example.ui.components.MoviePosterImage
import com.example.ui.components.SourceBadge
import com.example.ui.theme.CinemaEmerald
import com.example.ui.theme.FacebookAzure
import com.example.ui.theme.ObsidianCanvas
import com.example.ui.theme.ProjectorAmber
import com.example.ui.theme.SynthCyan
import com.example.ui.theme.YouTubeCrimson
import kotlin.math.roundToInt

@Composable
fun HistoryScreen(
    uiState: CinemaUiState,
    onTabSelected: (HistoryTab) -> Unit,
    onMovieClick: (String) -> Unit,
    onToggleWatchlist: (String) -> Unit,
    onRemoveHistoryItem: (String) -> Unit,
    onClearAllHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showClearConfirm by remember { mutableStateOf(false) }

    val displayedItems = when (uiState.selectedHistoryTab) {
        HistoryTab.RECENT -> uiState.watchHistory
        HistoryTab.WATCHLIST -> uiState.watchlist
        HistoryTab.LIKED -> uiState.likedMovies
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianCanvas)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 900.dp)
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "history_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 16.dp, end = 12.dp, top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
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
                                imageVector = Icons.Filled.History,
                                contentDescription = null,
                                tint = ProjectorAmber,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = "Watch History",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "Personalized viewing log & taste analytics",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (uiState.watchHistory.isNotEmpty()) {
                        IconButton(
                            onClick = { showClearConfirm = true },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("clear_history_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = stringResource(R.string.action_clear_history),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Personalized Taste Profile Summary Card
            item(key = "taste_profile") {
                TasteProfileCard(
                    profile = uiState.tasteProfile,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // Personalized "Picked For Your Taste" Rail
            if (uiState.personalizedPicks.isNotEmpty()) {
                item(key = "picked_for_you") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            Text(
                                text = stringResource(R.string.section_picked_for_you),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            val reason = uiState.tasteProfile.favoriteGenre?.let {
                                "Tailored around your ${it.displayName} viewing sessions"
                            } ?: "Top-rated unseen AI movies across YouTube & Facebook"
                            Text(
                                text = reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(uiState.personalizedPicks, key = { "pick_${it.movie.id}" }) { item ->
                                MovieHorizontalCard(
                                    item = item,
                                    onClick = { onMovieClick(item.movie.id) }
                                )
                            }
                        }
                    }
                }
            }

            // History / Watchlist / Liked Filter Tabs
            item(key = "history_tabs") {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(HistoryTab.entries, key = { it.name }) { tab ->
                        val count = when (tab) {
                            HistoryTab.RECENT -> uiState.watchHistory.size
                            HistoryTab.WATCHLIST -> uiState.watchlist.size
                            HistoryTab.LIKED -> uiState.likedMovies.size
                        }
                        val selected = tab == uiState.selectedHistoryTab
                        FilterChip(
                            selected = selected,
                            onClick = { onTabSelected(tab) },
                            label = {
                                Text(
                                    text = "${tab.label} ($count)",
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                containerColor = MaterialTheme.colorScheme.surface,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                borderColor = MaterialTheme.colorScheme.outline,
                                selectedBorderColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.testTag("history_tab_${tab.name.lowercase()}")
                        )
                    }
                }
            }

            if (displayedItems.isEmpty()) {
                item(key = "empty_history") {
                    EmptyHistoryCard(
                        tab = uiState.selectedHistoryTab,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            } else {
                items(displayedItems, key = { "hist_${uiState.selectedHistoryTab}_${it.movie.id}" }) { item ->
                    if (uiState.selectedHistoryTab == HistoryTab.RECENT) {
                        WatchHistoryItemCard(
                            item = item,
                            onClick = { onMovieClick(item.movie.id) },
                            onRemove = { onRemoveHistoryItem(item.movie.id) },
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    } else {
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
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.clear_history_confirm_title)) },
            text = {
                Text(
                    text = stringResource(R.string.clear_history_confirm_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearAllHistory()
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun TasteProfileCard(
    profile: TasteProfile,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TASTE PROFILE",
                    style = MaterialTheme.typography.labelMedium,
                    color = ProjectorAmber,
                    maxLines = 1,
                    softWrap = false
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = profile.favoriteGenre?.let { "Top: ${it.displayName}" }
                        ?: "Start watching to unlock",
                    style = MaterialTheme.typography.labelMedium,
                    color = SynthCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatPill(label = "Watched", value = profile.totalWatchedCount.toString())
                StatPill(label = "Minutes", value = "${profile.totalMinutesWatched}m")
                StatPill(label = "Completed", value = profile.completedCount.toString())
                StatPill(label = "Saved", value = profile.watchlistCount.toString())
            }

            val totalPlatforms = (profile.youtubeWatchedCount + profile.facebookWatchedCount).coerceAtLeast(1)
            val ytRatio = profile.youtubeWatchedCount.toFloat() / totalPlatforms.toFloat()

            if (profile.totalWatchedCount > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "YouTube (${profile.youtubeWatchedCount})",
                            style = MaterialTheme.typography.labelSmall,
                            color = YouTubeCrimson
                        )
                        Text(
                            text = "Facebook (${profile.facebookWatchedCount})",
                            style = MaterialTheme.typography.labelSmall,
                            color = FacebookAzure
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(FacebookAzure)
                    ) {
                        if (ytRatio > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(ytRatio.coerceIn(0f, 1f))
                                    .height(6.dp)
                                    .background(YouTubeCrimson)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatPill(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WatchHistoryItemCard(
    item: MovieWithHistory,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val movie = item.movie
    val history = item.history
    val progressPct = (item.progressPercent * 100f).roundToInt().coerceIn(1, 100)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("history_card_${movie.id}"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.75f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(128.dp)
                    .aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                MoviePosterImage(
                    thumbnailUrl = movie.thumbnailUrl,
                    genre = movie.movieGenre,
                    contentDescription = movie.title,
                    modifier = Modifier.fillMaxSize()
                )
                SourceBadge(
                    source = movie.movieSource,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                )
                DurationBadge(
                    durationText = movie.durationText,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                )
                LinearProgressIndicator(
                    progress = { item.progressPercent.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp),
                    color = if (item.isCompleted) CinemaEmerald else ProjectorAmber,
                    trackColor = ObsidianCanvas.copy(alpha = 0.65f)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = movie.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (item.isCompleted) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = CinemaEmerald,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Completed",
                            style = MaterialTheme.typography.labelSmall,
                            color = CinemaEmerald
                        )
                    } else {
                        Text(
                            text = "$progressPct% watched",
                            style = MaterialTheme.typography.labelSmall,
                            color = ProjectorAmber
                        )
                    }
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatRelativeTime(history?.lastWatchedAt ?: 0L),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${movie.movieGenre.displayName} • Watched ${history?.watchCount ?: 1}x",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.cd_remove_history),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyHistoryCard(
    tab: HistoryTab,
    modifier: Modifier = Modifier
) {
    val title = when (tab) {
        HistoryTab.RECENT -> stringResource(R.string.history_empty_title)
        HistoryTab.WATCHLIST -> "Your Watchlist Is Empty"
        HistoryTab.LIKED -> "No Liked AI Films Yet"
    }
    val subtitle = when (tab) {
        HistoryTab.RECENT -> stringResource(R.string.history_empty_subtitle)
        HistoryTab.WATCHLIST -> "Tap the bookmark icon on any YouTube or Facebook AI movie to save it for later."
        HistoryTab.LIKED -> "Tap the heart button while watching a film to mark your favorites."
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
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
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(14.dp))
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun formatRelativeTime(timestampMs: Long): String {
    if (timestampMs <= 0L) return "Recently"
    val diffSec = ((System.currentTimeMillis() - timestampMs) / 1000L).coerceAtLeast(0L)
    return when {
        diffSec < 60 -> "Just now"
        diffSec < 3600 -> "${diffSec / 60}m ago"
        diffSec < 86400 -> "${diffSec / 3600}h ago"
        else -> "${diffSec / 86400}d ago"
    }
}
