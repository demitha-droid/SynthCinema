package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.model.CreatorChannelEntity
import com.example.data.model.MovieGenre
import com.example.data.model.MovieSource
import com.example.data.model.YouTubeChannelSearchResult
import com.example.ui.CinemaUiState
import com.example.ui.YouTubeChannelSearchUiState
import com.example.ui.components.SourceBadge
import com.example.ui.components.YouTubeChannelSearchCard
import com.example.ui.theme.FacebookAzure
import com.example.ui.theme.ObsidianCanvas
import com.example.ui.theme.ProjectorAmber
import com.example.ui.theme.SynthCyan
import com.example.ui.theme.YouTubeCrimson

private enum class ChannelAddMode {
    SEARCH_YOUTUBE,
    DIRECT_URL
}

private data class SuggestedCreator(
    val label: String,
    val handle: String,
    val source: MovieSource,
    val genre: MovieGenre
)

private val suggestedCreators = listOf(
    SuggestedCreator("@CuriousRefuge", "@CuriousRefuge", MovieSource.YOUTUBE, MovieGenre.SCI_FI),
    SuggestedCreator("@NeuralViz", "@NeuralViz", MovieSource.YOUTUBE, MovieGenre.COMEDY),
    SuggestedCreator("@Waymark", "@Waymark", MovieSource.YOUTUBE, MovieGenre.EXPERIMENTAL),
    SuggestedCreator("@RunwayML", "@RunwayML", MovieSource.YOUTUBE, MovieGenre.SCI_FI),
    SuggestedCreator("AI Short Films (FB)", "https://www.facebook.com/AIShortFilms", MovieSource.FACEBOOK, MovieGenre.SCI_FI)
)

@Composable
fun ChannelsScreen(
    uiState: CinemaUiState,
    channelSearchState: YouTubeChannelSearchUiState,
    onChannelSearchKeywordChanged: (String) -> Unit,
    onChannelSearchGenreChanged: (MovieGenre) -> Unit,
    onSearchYouTubeChannels: (String?) -> Unit,
    onToggleSelectYouTubeChannel: (String) -> Unit,
    onClearSelectedYouTubeChannels: () -> Unit,
    onAddSelectedYouTubeChannels: () -> Unit,
    onAddSingleSearchedYouTubeChannel: (YouTubeChannelSearchResult) -> Unit,
    onAddChannel: (MovieSource, String, MovieGenre, () -> Unit) -> Unit,
    onSyncChannel: (CreatorChannelEntity) -> Unit,
    onSyncAllChannels: () -> Unit,
    onDeleteChannel: (CreatorChannelEntity) -> Unit,
    onBrowseChannelMovies: (CreatorChannelEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    var addMode by remember { mutableStateOf(ChannelAddMode.SEARCH_YOUTUBE) }
    var selectedSource by remember { mutableStateOf(MovieSource.YOUTUBE) }
    var inputHandleOrUrl by remember { mutableStateOf("") }
    var selectedGenre by remember { mutableStateOf(MovieGenre.ALL) }

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
            // Header
            item(key = "channels_header") {
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
                                imageVector = Icons.Filled.Subscriptions,
                                contentDescription = null,
                                tint = ProjectorAmber,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = stringResource(R.string.channels_title),
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.channels_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (uiState.channels.isNotEmpty()) {
                        Surface(
                            onClick = onSyncAllChannels,
                            enabled = !uiState.isScraping && uiState.syncingChannelId == null,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, ProjectorAmber.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .height(42.dp)
                                .testTag("channels_sync_all_button")
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
                                        contentDescription = stringResource(R.string.action_sync_all),
                                        tint = ProjectorAmber,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.action_sync_all),
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

            // Mode Switcher: Search YouTube by Keywords vs Direct URL / Handle
            item(key = "channel_add_mode_tabs") {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "mode_search_yt") {
                        val selected = addMode == ChannelAddMode.SEARCH_YOUTUBE
                        FilterChip(
                            selected = selected,
                            onClick = { addMode = ChannelAddMode.SEARCH_YOUTUBE },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(R.string.channel_mode_search_yt),
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = YouTubeCrimson.copy(alpha = 0.22f),
                                selectedLabelColor = YouTubeCrimson,
                                selectedLeadingIconColor = YouTubeCrimson,
                                containerColor = MaterialTheme.colorScheme.surface,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                borderColor = MaterialTheme.colorScheme.outline,
                                selectedBorderColor = YouTubeCrimson
                            ),
                            modifier = Modifier.testTag("mode_tab_search_youtube")
                        )
                    }
                    item(key = "mode_direct_url") {
                        val selected = addMode == ChannelAddMode.DIRECT_URL
                        FilterChip(
                            selected = selected,
                            onClick = { addMode = ChannelAddMode.DIRECT_URL },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Link,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(R.string.channel_mode_direct_url),
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SynthCyan.copy(alpha = 0.22f),
                                selectedLabelColor = SynthCyan,
                                selectedLeadingIconColor = SynthCyan,
                                containerColor = MaterialTheme.colorScheme.surface,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                borderColor = MaterialTheme.colorScheme.outline,
                                selectedBorderColor = SynthCyan
                            ),
                            modifier = Modifier.testTag("mode_tab_direct_url")
                        )
                    }
                }
            }

            if (addMode == ChannelAddMode.SEARCH_YOUTUBE) {
                item(key = "youtube_channel_search_card") {
                    YouTubeChannelSearchCard(
                        searchState = channelSearchState,
                        followedChannels = uiState.channels,
                        onKeywordChanged = onChannelSearchKeywordChanged,
                        onGenreChanged = onChannelSearchGenreChanged,
                        onSearch = onSearchYouTubeChannels,
                        onToggleSelect = onToggleSelectYouTubeChannel,
                        onClearSelection = onClearSelectedYouTubeChannels,
                        onAddSelected = onAddSelectedYouTubeChannels,
                        onAddSingle = onAddSingleSearchedYouTubeChannel
                    )
                }
            } else {
                // Add YouTube Channel / Facebook Profile Card (Direct URL / Handle)
                item(key = "add_creator_card") {
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
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PersonAdd,
                                contentDescription = null,
                                tint = SynthCyan,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Add Channel or Profile",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = ProjectorAmber.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, ProjectorAmber.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("channels_duration_rule_notice")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Timer,
                                    contentDescription = null,
                                    tint = ProjectorAmber,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Filter rule active: Only uploads longer than 45 minutes are added to your database.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }

                        // Platform selector
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            MovieSource.entries.forEach { src ->
                                val selected = src == selectedSource
                                val accent = if (src == MovieSource.YOUTUBE) YouTubeCrimson else FacebookAzure
                                FilterChip(
                                    selected = selected,
                                    onClick = { selectedSource = src },
                                    label = {
                                        Text(
                                            text = if (src == MovieSource.YOUTUBE) "YouTube Channel" else "Facebook Profile",
                                            style = MaterialTheme.typography.labelLarge,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = accent.copy(alpha = 0.22f),
                                        selectedLabelColor = accent,
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = selected,
                                        borderColor = MaterialTheme.colorScheme.outline,
                                        selectedBorderColor = accent
                                    ),
                                    modifier = Modifier.testTag("add_source_${src.name.lowercase()}")
                                )
                            }
                        }

                        OutlinedTextField(
                            value = inputHandleOrUrl,
                            onValueChange = { inputHandleOrUrl = it },
                            placeholder = {
                                Text(
                                    text = if (selectedSource == MovieSource.YOUTUBE) {
                                        "e.g. @CuriousRefuge or youtube.com/@handle"
                                    } else {
                                        "e.g. facebook.com/pagename or profile URL"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            singleLine = true,
                            enabled = !uiState.isAddingChannel,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ProjectorAmber,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("channel_url_input")
                        )

                        // Default genre categorization selector
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "DEFAULT GENRE TAG",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(MovieGenre.entries, key = { "add_genre_${it.name}" }) { genre ->
                                    val isSelected = genre == selectedGenre
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedGenre = genre },
                                        label = {
                                            Text(
                                                text = if (genre == MovieGenre.ALL) "Auto-Detect" else genre.displayName,
                                                style = MaterialTheme.typography.labelMedium,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedLabelColor = ProjectorAmber,
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = {
                                onAddChannel(selectedSource, inputHandleOrUrl, selectedGenre) {
                                    inputHandleOrUrl = ""
                                }
                            },
                            enabled = inputHandleOrUrl.isNotBlank() && !uiState.isAddingChannel,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("add_channel_submit_button")
                        ) {
                            if (uiState.isAddingChannel) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Scraping Channel Movies…")
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.action_add_channel))
                            }
                            }
                        }
                    }
                }
            }

            // Quick-Add Popular AI Cinema Channels
            item(key = "suggested_creators") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Quick-Add AI Cinema Creators",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(suggestedCreators, key = { it.label }) { creator ->
                            val accent = if (creator.source == MovieSource.YOUTUBE) YouTubeCrimson else FacebookAzure
                            AssistChip(
                                onClick = {
                                    if (!uiState.isAddingChannel) {
                                        onAddChannel(creator.source, creator.handle, creator.genre) {}
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Add,
                                        contentDescription = null,
                                        tint = accent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                label = {
                                    Text(
                                        text = creator.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    labelColor = MaterialTheme.colorScheme.onBackground
                                ),
                                border = BorderStroke(1.dp, accent.copy(alpha = 0.5f))
                            )
                        }
                    }
                }
            }

            // Followed Channels & Profiles List
            item(key = "followed_heading") {
                Text(
                    text = "Followed Channels & Profiles (${uiState.channels.size})",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            if (uiState.channels.isEmpty()) {
                item(key = "channels_empty") {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Movie,
                                contentDescription = null,
                                tint = ProjectorAmber,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = "No Custom Channels Added Yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "Add any YouTube channel handle/URL or Facebook profile above, or tap a suggested creator to automatically scrape and save their AI movies longer than 45 minutes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(uiState.channels, key = { it.id }) { channel ->
                    FollowedChannelCard(
                        channel = channel,
                        isSyncing = uiState.syncingChannelId == channel.id,
                        onSync = { onSyncChannel(channel) },
                        onBrowse = { onBrowseChannelMovies(channel) },
                        onDelete = { onDeleteChannel(channel) },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FollowedChannelCard(
    channel: CreatorChannelEntity,
    isSyncing: Boolean,
    onSync: () -> Unit,
    onBrowse: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = if (channel.movieSource == MovieSource.YOUTUBE) YouTubeCrimson else FacebookAzure

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onBrowse)
            .testTag("followed_channel_${channel.id}"),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.8f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (channel.avatarUrl.startsWith("https://")) {
                        SubcomposeAsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(channel.avatarUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = channel.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            error = {
                                Text(
                                    text = channel.name.take(1).uppercase(),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = accent
                                )
                            }
                        )
                    } else {
                        Text(
                            text = channel.name.take(1).uppercase(),
                            style = MaterialTheme.typography.titleLarge,
                            color = accent
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = channel.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        SourceBadge(source = channel.movieSource)
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = channel.handleOrUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${channel.moviesImportedCount} movies imported • ${
                            if (channel.preferredGenre == MovieGenre.ALL) "Auto Genre" else channel.preferredGenre.displayName
                        }",
                        style = MaterialTheme.typography.labelSmall,
                        color = ProjectorAmber,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = "Remove ${channel.name}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onSync,
                    enabled = !isSyncing,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = ProjectorAmber
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            tint = ProjectorAmber,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isSyncing) "Syncing…" else "Sync Movies",
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Button(
                    onClick = onBrowse,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                ) {
                    Text(
                        text = "View Films",
                        color = SynthCyan,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}
