package com.example.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.AiMovieEntity
import com.example.ui.YouTubeMovieSearchUiState
import com.example.ui.theme.ProjectorAmber
import com.example.ui.theme.SynthCyan
import com.example.ui.theme.YouTubeCrimson

@Composable
fun YouTubeMovieSearchBar(
    state: YouTubeMovieSearchUiState,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChanged,
            placeholder = {
                Text(
                    text = stringResource(R.string.yt_movie_search_placeholder),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.SmartDisplay,
                    contentDescription = stringResource(R.string.cd_search_yt_movies),
                    tint = YouTubeCrimson
                )
            },
            trailingIcon = {
                if (state.query.isNotEmpty() || state.hasSearched) {
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier.testTag("clear_yt_movie_search_button")
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
            enabled = !state.isSearching,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = YouTubeCrimson,
                unfocusedBorderColor = YouTubeCrimson.copy(alpha = 0.45f)
            ),
            modifier = Modifier
                .weight(1f)
                .testTag("yt_movie_search_input")
        )

        Button(
            onClick = onSearch,
            enabled = state.query.isNotBlank() && !state.isSearching,
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(horizontal = 14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = YouTubeCrimson,
                contentColor = MaterialTheme.colorScheme.onBackground
            ),
            modifier = Modifier
                .height(54.dp)
                .testTag("yt_movie_search_button")
        ) {
            if (state.isSearching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onBackground
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = stringResource(R.string.cd_search_yt_movies),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun YouTubeMovieResultsPanel(
    state: YouTubeMovieSearchUiState,
    savedMovieIds: Set<String>,
    onWatchMovie: (AiMovieEntity) -> Unit,
    onSaveMovie: (AiMovieEntity) -> Unit,
    onSaveAll: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("yt_movie_results_panel"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, YouTubeCrimson.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SmartDisplay,
                            contentDescription = null,
                            tint = YouTubeCrimson,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (state.isSearching) {
                                "Searching YouTube…"
                            } else {
                                "YouTube AI Movies (${state.results.size})"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!state.isSearching && state.executedQuery.isNotBlank()) {
                        val skippedNote = if (state.skippedUnderMinDuration > 0) {
                            " • ${state.skippedUnderMinDuration} <45m filtered"
                        } else {
                            ""
                        }
                        Text(
                            text = "Query: \"${state.executedQuery}\"$skippedNote",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!state.isSearching && state.results.isNotEmpty()) {
                        val unsavedCount = state.results.count { it.id !in savedMovieIds }
                        if (unsavedCount > 0) {
                            TextButton(
                                onClick = onSaveAll,
                                modifier = Modifier.testTag("yt_save_all_results_button")
                            ) {
                                Text(
                                    text = "Save All ($unsavedCount)",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = ProjectorAmber,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("close_yt_results_panel")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.cd_clear_search),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (state.isSearching) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = YouTubeCrimson
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Finding feature-length (>45m) AI movies on YouTube…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (state.error != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.results.forEach { movie ->
                        val isSaved = movie.id in savedMovieIds
                        YouTubeMovieSearchResultRow(
                            movie = movie,
                            isSavedInVault = isSaved,
                            onWatch = { onWatchMovie(movie) },
                            onSave = { onSaveMovie(movie) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun YouTubeMovieSearchResultRow(
    movie: AiMovieEntity,
    isSavedInVault: Boolean,
    onWatch: () -> Unit,
    onSave: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onWatch)
            .testTag("yt_movie_result_${movie.id}"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(
            1.dp,
            if (isSavedInVault) SynthCyan.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(114.dp)
                    .aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(10.dp))
            ) {
                MoviePosterImage(
                    thumbnailUrl = movie.thumbnailUrl,
                    genre = movie.movieGenre,
                    contentDescription = movie.title,
                    modifier = Modifier.fillMaxSize()
                )
                DurationBadge(
                    durationText = movie.durationText,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = movie.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "${movie.director} • ${movie.viewCountText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(5.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = movie.movieGenre.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = ProjectorAmber,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = SynthCyan,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "Tap to watch",
                        style = MaterialTheme.typography.labelSmall,
                        color = SynthCyan,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            if (isSavedInVault) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SynthCyan.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, SynthCyan.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
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
                            text = stringResource(R.string.action_in_vault),
                            style = MaterialTheme.typography.labelSmall,
                            color = SynthCyan,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            } else {
                IconButton(
                    onClick = onSave,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("save_yt_movie_${movie.id}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.action_save_to_vault),
                        tint = ProjectorAmber
                    )
                }
            }
        }
    }
}
