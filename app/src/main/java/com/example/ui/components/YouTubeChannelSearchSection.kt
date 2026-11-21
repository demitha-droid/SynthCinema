package com.example.ui.components

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timer
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.model.CreatorChannelEntity
import com.example.data.model.MovieGenre
import com.example.data.model.YouTubeChannelSearchResult
import com.example.ui.YouTubeChannelSearchUiState
import com.example.ui.theme.ProjectorAmber
import com.example.ui.theme.SynthCyan
import com.example.ui.theme.YouTubeCrimson

private val quickKeywordPresets = listOf(
    "AI Full Movies",
    "AI Sci-Fi Cinema",
    "AI Cyberpunk Film",
    "AI Documentary",
    "AI Horror Movie",
    "Sora Film Studio"
)

@Composable
fun YouTubeChannelSearchCard(
    searchState: YouTubeChannelSearchUiState,
    followedChannels: List<CreatorChannelEntity>,
    onKeywordChanged: (String) -> Unit,
    onGenreChanged: (MovieGenre) -> Unit,
    onSearch: (String?) -> Unit,
    onToggleSelect: (String) -> Unit,
    onClearSelection: () -> Unit,
    onAddSelected: () -> Unit,
    onAddSingle: (YouTubeChannelSearchResult) -> Unit,
    modifier: Modifier = Modifier
) {
    val followedUrls = followedChannels.map { it.handleOrUrl.trimEnd('/').lowercase() }.toSet()
    val followedExternalIds = followedChannels.map { it.externalId.lowercase() }.toSet()

    Surface(
        modifier = modifier
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
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = YouTubeCrimson,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Find YouTube Channels by Keyword",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = ProjectorAmber.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, ProjectorAmber.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
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
                        text = "Pick one or more channels to add to your feed. Only uploads > 45 min are saved.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            // Keyword input + Search button row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchState.keyword,
                    onValueChange = onKeywordChanged,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.yt_channel_search_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    trailingIcon = {
                        if (searchState.keyword.isNotEmpty()) {
                            IconButton(
                                onClick = { onKeywordChanged("") },
                                modifier = Modifier.testTag("clear_yt_channel_keyword")
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
                    enabled = !searchState.isSearching,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(null) }),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ProjectorAmber,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("yt_channel_keyword_input")
                )

                Button(
                    onClick = { onSearch(null) },
                    enabled = searchState.keyword.isNotBlank() && !searchState.isSearching,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = YouTubeCrimson,
                        contentColor = MaterialTheme.colorScheme.onBackground
                    ),
                    modifier = Modifier
                        .height(54.dp)
                        .testTag("yt_channel_search_button")
                ) {
                    if (searchState.isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = stringResource(R.string.cd_search_yt_channels),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Quick keyword chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(quickKeywordPresets, key = { it }) { preset ->
                    AssistChip(
                        onClick = {
                            if (!searchState.isSearching) {
                                onSearch(preset)
                            }
                        },
                        label = {
                            Text(
                                text = preset,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onBackground
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.testTag("yt_keyword_preset_${preset.lowercase().replace(" ", "_")}")
                    )
                }
            }

            // Default genre tag for picked channels
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "TAG IMPORTED MOVIES AS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(MovieGenre.entries, key = { "search_genre_${it.name}" }) { genre ->
                        val isSelected = genre == searchState.preferredGenre
                        FilterChip(
                            selected = isSelected,
                            onClick = { onGenreChanged(genre) },
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

            // Loading indicator
            if (searchState.isSearching) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = ProjectorAmber
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Searching YouTube for \"${searchState.keyword}\"…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Error / Empty message
            if (!searchState.isSearching && searchState.error != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = searchState.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Search Results Picker List
            if (!searchState.isSearching && searchState.results.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${searchState.results.size} YouTube channels found • Tap to pick",
                        style = MaterialTheme.typography.labelMedium,
                        color = SynthCyan,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (searchState.selectedChannelIds.isNotEmpty()) {
                        TextButton(onClick = onClearSelection) {
                            Text(
                                text = "Clear (${searchState.selectedChannelIds.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = ProjectorAmber
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    searchState.results.forEach { candidate ->
                        val isFollowed = candidate.channelUrl.trimEnd('/').lowercase() in followedUrls ||
                            candidate.channelId.lowercase() in followedExternalIds
                        val isSelected = candidate.channelId in searchState.selectedChannelIds
                        val isAddingThis = searchState.addingSingleChannelId == candidate.channelId

                        YouTubeChannelSearchResultRow(
                            candidate = candidate,
                            isSelected = isSelected,
                            isAlreadyFollowed = isFollowed,
                            isAdding = isAddingThis,
                            disableActions = searchState.isAddingSelected || searchState.addingSingleChannelId != null,
                            onToggleSelect = { onToggleSelect(candidate.channelId) },
                            onQuickAdd = { onAddSingle(candidate) }
                        )
                    }
                }

                // Batch "Add Selected Channels to Feed" button
                if (searchState.selectedChannelIds.isNotEmpty()) {
                    val count = searchState.selectedChannelIds.size
                    Button(
                        onClick = onAddSelected,
                        enabled = !searchState.isAddingSelected,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("add_selected_yt_channels_button")
                    ) {
                        if (searchState.isAddingSelected) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Adding & Scraping $count Channel${if (count == 1) "" else "s"}…")
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add $count Selected Channel${if (count == 1) "" else "s"} to Feed")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun YouTubeChannelSearchResultRow(
    candidate: YouTubeChannelSearchResult,
    isSelected: Boolean,
    isAlreadyFollowed: Boolean,
    isAdding: Boolean,
    disableActions: Boolean,
    onToggleSelect: () -> Unit,
    onQuickAdd: () -> Unit
) {
    val borderColor = when {
        isSelected -> ProjectorAmber
        isAlreadyFollowed -> SynthCyan.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)
    }
    val bgColor = when {
        isSelected -> ProjectorAmber.copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = !isAlreadyFollowed && !disableActions) { onToggleSelect() }
            .testTag("yt_search_result_${candidate.channelId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selection indicator
            if (!isAlreadyFollowed) {
                Icon(
                    imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = if (isSelected) "Selected" else "Select channel",
                    tint = if (isSelected) ProjectorAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
            }

            // Channel avatar
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(YouTubeCrimson.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                if (candidate.avatarUrl.startsWith("https://")) {
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(candidate.avatarUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = candidate.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        error = {
                            Text(
                                text = candidate.name.take(1).uppercase(),
                                style = MaterialTheme.typography.titleMedium,
                                color = YouTubeCrimson
                            )
                        }
                    )
                } else {
                    Text(
                        text = candidate.name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = YouTubeCrimson
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Channel metadata
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = candidate.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = buildString {
                    if (candidate.handle.isNotBlank()) {
                        append(candidate.handle)
                        if (candidate.statsText.isNotBlank()) append(" • ")
                    }
                    append(candidate.statsText)
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = ProjectorAmber,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (candidate.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = candidate.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Trailing status / quick-add button
            if (isAlreadyFollowed) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SynthCyan.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, SynthCyan.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = SynthCyan,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "In Feed",
                            style = MaterialTheme.typography.labelSmall,
                            color = SynthCyan,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            } else {
                IconButton(
                    onClick = onQuickAdd,
                    enabled = !disableActions,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("quick_add_channel_${candidate.channelId}")
                ) {
                    if (isAdding) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = ProjectorAmber
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.cd_add_single_channel),
                            tint = SynthCyan
                        )
                    }
                }
            }
        }
    }
}
