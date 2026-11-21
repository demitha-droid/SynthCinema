package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.data.model.AiMovieEntity
import com.example.data.model.MovieSource
import com.example.data.model.MovieWithHistory
import com.example.ui.components.MovieHorizontalCard
import com.example.ui.components.MoviePosterImage
import com.example.ui.components.SourceBadge
import com.example.ui.theme.CinemaEmerald
import com.example.ui.theme.FacebookAzure
import com.example.ui.theme.ObsidianCanvas
import com.example.ui.theme.ProjectorAmber
import com.example.ui.theme.SynthCyan
import com.example.ui.theme.YouTubeCrimson
import kotlinx.coroutines.delay
import java.net.URLEncoder
import kotlin.math.roundToInt

private const val PLAYER_TAG = "CinemaPlayer"

// Standard Chrome Mobile User-Agent (without Android "; wv)" token that YouTube/Facebook block)
private const val CHROME_MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

private enum class PlaybackMode {
    CINEMA_EMBED,
    MOBILE_WEB
}

private enum class EmbedPlayerStage {
    PLAYING_EMBED,
    RENDERER_CRASHED
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(
    movieWithHistory: MovieWithHistory?,
    relatedMovies: List<MovieWithHistory>,
    onBack: () -> Unit,
    onRecordStart: (String) -> Unit,
    onUpdateProgress: (String, Int, Int) -> Unit,
    onToggleLike: (String) -> Unit,
    onToggleWatchlist: (String) -> Unit,
    onToggleCompleted: (String) -> Unit,
    onSelectRelated: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val movie = movieWithHistory?.movie

    if (movie == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(ObsidianCanvas),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Film not found in vault", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onBack) { Text("Back to Discover") }
            }
        }
        return
    }

    // Default to Cinema Embed for YouTube with valid 11-char ID, and Mobile Web for Facebook/search links
    val defaultMode = remember(movie.id) {
        if (movie.movieSource == MovieSource.YOUTUBE && movie.videoId.length == 11) {
            PlaybackMode.CINEMA_EMBED
        } else {
            PlaybackMode.MOBILE_WEB
        }
    }

    var playbackMode by remember(movie.id) { mutableStateOf(defaultMode) }
    var playerStage by remember(movie.id) { mutableStateOf(EmbedPlayerStage.PLAYING_EMBED) }
    var reloadNonce by remember(movie.id) { mutableIntStateOf(0) }
    var isWebLoading by remember(movie.id) { mutableStateOf(true) }
    var fallbackNotice by remember(movie.id) { mutableStateOf<String?>(null) }

    // Fullscreen HTML5 custom view support
    var fullscreenView by remember(movie.id) { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember(movie.id) {
        mutableStateOf<WebChromeClient.CustomViewCallback?>(null)
    }

    BackHandler(enabled = fullscreenView != null) {
        fullscreenCallback?.onCustomViewHidden()
        fullscreenView = null
        fullscreenCallback = null
    }

    // Record watch start immediately and track live playback watch time into Room
    var sessionElapsedSec by remember(movie.id) {
        mutableIntStateOf(movieWithHistory.history?.watchedSeconds ?: 0)
    }

    LaunchedEffect(movie.id) {
        onRecordStart(movie.id)
        while (true) {
            delay(5_000L)
            sessionElapsedSec += 5
            onUpdateProgress(movie.id, sessionElapsedSec, movie.durationSeconds)
        }
    }

    val openExternal: () -> Unit = {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(movie.watchUrl))
            context.startActivity(intent)
        }
    }

    val shareMovie: () -> Unit = {
        runCatching {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, movie.title)
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Watch \"${movie.title}\" (${movie.movieGenre.displayName} AI Film on ${movie.movieSource.label}): ${movie.watchUrl}"
                )
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share AI Movie"))
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianCanvas)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Pinned Top Bar + Pinned Video Stage (kept outside LazyColumn so scrolling never tears down WebView)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 900.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("player_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        Text(
                            text = "Screening Room",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    SourceBadge(
                        source = movie.movieSource,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }

                // Video Stage: 16:9 for Cinema Embed, slightly taller 4:3 for Mobile Web so controls are easy to tap
                val stageAspect = if (playbackMode == PlaybackMode.MOBILE_WEB) 4f / 3f else 16f / 9f
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(stageAspect)
                        .background(Color.Black)
                ) {
                    when (playerStage) {
                        EmbedPlayerStage.PLAYING_EMBED -> {
                            key(movie.id, playbackMode, reloadNonce) {
                                EmbeddedVideoPlayer(
                                    movie = movie,
                                    mode = playbackMode,
                                    onPlayerReady = { isWebLoading = false },
                                    onEmbedFallbackNeeded = { reason ->
                                        if (playbackMode == PlaybackMode.CINEMA_EMBED) {
                                            Log.w(PLAYER_TAG, "Embed fallback triggered: $reason")
                                            fallbackNotice = "Switched to Mobile Web Player ($reason)"
                                            isWebLoading = true
                                            playbackMode = PlaybackMode.MOBILE_WEB
                                        }
                                    },
                                    onShowFullscreen = { view, cb ->
                                        fullscreenView = view
                                        fullscreenCallback = cb
                                    },
                                    onHideFullscreen = {
                                        fullscreenView = null
                                        fullscreenCallback = null
                                    },
                                    onRendererCrash = {
                                        isWebLoading = false
                                        playerStage = EmbedPlayerStage.RENDERER_CRASHED
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            if (isWebLoading) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(ObsidianCanvas.copy(alpha = 0.65f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        CircularProgressIndicator(color = ProjectorAmber)
                                        Text(
                                            text = if (playbackMode == PlaybackMode.CINEMA_EMBED) {
                                                "Loading Cinema Player…"
                                            } else {
                                                "Loading ${movie.movieSource.label} Web Player…"
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        EmbedPlayerStage.RENDERER_CRASHED -> {
                            RendererRecoveryStage(
                                movie = movie,
                                onRetry = {
                                    reloadNonce += 1
                                    isWebLoading = true
                                    playerStage = EmbedPlayerStage.PLAYING_EMBED
                                },
                                onOpenExternal = openExternal
                            )
                        }
                    }
                }

                val progress = movieWithHistory.progressPercent.coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = if (movieWithHistory.isCompleted) CinemaEmerald else ProjectorAmber,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                // Compact Player Mode Switcher (Cinema Embed vs Web Player + Reload)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (movie.movieSource == MovieSource.YOUTUBE && movie.videoId.length == 11) {
                            FilterChip(
                                selected = playbackMode == PlaybackMode.CINEMA_EMBED,
                                onClick = {
                                    if (playbackMode != PlaybackMode.CINEMA_EMBED || playerStage != EmbedPlayerStage.PLAYING_EMBED) {
                                        fallbackNotice = null
                                        isWebLoading = true
                                        playbackMode = PlaybackMode.CINEMA_EMBED
                                        playerStage = EmbedPlayerStage.PLAYING_EMBED
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.SmartDisplay,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                label = { Text("Embed", style = MaterialTheme.typography.labelMedium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = ProjectorAmber,
                                    selectedLeadingIconColor = ProjectorAmber
                                ),
                                modifier = Modifier.testTag("player_mode_embed")
                            )
                        }

                        FilterChip(
                            selected = playbackMode == PlaybackMode.MOBILE_WEB,
                            onClick = {
                                if (playbackMode != PlaybackMode.MOBILE_WEB || playerStage != EmbedPlayerStage.PLAYING_EMBED) {
                                    isWebLoading = true
                                    playbackMode = PlaybackMode.MOBILE_WEB
                                    playerStage = EmbedPlayerStage.PLAYING_EMBED
                                }
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Language,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = { Text("Web Player", style = MaterialTheme.typography.labelMedium) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedLabelColor = SynthCyan,
                                selectedLeadingIconColor = SynthCyan
                            ),
                            modifier = Modifier.testTag("player_mode_web")
                        )
                    }

                    IconButton(
                        onClick = {
                            isWebLoading = true
                            reloadNonce += 1
                            playerStage = EmbedPlayerStage.PLAYING_EMBED
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("player_reload_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Reload player",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Scrollable Movie Metadata & Related Rail
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .widthIn(max = 900.dp),
                contentPadding = PaddingValues(top = 14.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                fallbackNotice?.let { notice ->
                    item(key = "fallback_notice") {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, SynthCyan.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            Text(
                                text = notice,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                item(key = "movie_details") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = movie.movieGenre.displayName.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ProjectorAmber,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Text(
                                text = "${movie.durationText} • ${movie.viewCountText}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val pct = (movieWithHistory.progressPercent * 100f).roundToInt()
                            if (pct > 0) {
                                Text(
                                    text = "• ${if (movieWithHistory.isCompleted) "Completed" else "$pct%"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (movieWithHistory.isCompleted) CinemaEmerald else ProjectorAmber
                                )
                            }
                        }

                        Text(
                            text = movie.title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )

                        Text(
                            text = "Directed / Posted by ${movie.director} • ${movie.publishedText}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val sourceAccent = if (movie.movieSource == MovieSource.YOUTUBE) {
                            YouTubeCrimson
                        } else {
                            FacebookAzure
                        }
                        Button(
                            onClick = openExternal,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = sourceAccent,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("player_external_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.action_open_external, movie.movieSource.label),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ActionChipButton(
                                label = if (movieWithHistory.isLiked) "Liked" else "Like",
                                icon = if (movieWithHistory.isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                active = movieWithHistory.isLiked,
                                activeColor = YouTubeCrimson,
                                onClick = { onToggleLike(movie.id) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("player_like_button")
                            )
                            ActionChipButton(
                                label = if (movieWithHistory.isWatchlisted) "Saved" else "Save",
                                icon = if (movieWithHistory.isWatchlisted) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                active = movieWithHistory.isWatchlisted,
                                activeColor = ProjectorAmber,
                                onClick = { onToggleWatchlist(movie.id) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("player_watchlist_button")
                            )
                            ActionChipButton(
                                label = if (movieWithHistory.isCompleted) "Done" else "Finish",
                                icon = if (movieWithHistory.isCompleted) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                                active = movieWithHistory.isCompleted,
                                activeColor = CinemaEmerald,
                                onClick = { onToggleCompleted(movie.id) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("player_complete_button")
                            )
                            IconButton(
                                onClick = shareMovie,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("player_share_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Share,
                                    contentDescription = stringResource(R.string.action_share),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (movie.aiToolList.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "AI PRODUCTION PIPELINE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SynthCyan
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    movie.aiToolList.forEach { tool ->
                                        Surface(
                                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, SynthCyan.copy(alpha = 0.35f))
                                        ) {
                                            Text(
                                                text = tool,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = SynthCyan,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "SYNOPSIS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ProjectorAmber
                                )
                                Text(
                                    text = movie.synopsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                    }
                }

                if (relatedMovies.isNotEmpty()) {
                    item(key = "related_rail") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = stringResource(R.string.section_more_in_genre, movie.movieGenre.displayName),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(relatedMovies, key = { "rel_${it.movie.id}" }) { related ->
                                    MovieHorizontalCard(
                                        item = related,
                                        onClick = { onSelectRelated(related.movie.id) },
                                        showProgress = related.hasWatched
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Fullscreen HTML5 video container when WebChromeClient enters fullscreen
        fullscreenView?.let { customView ->
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        (customView.parent as? ViewGroup)?.removeView(customView)
                        addView(
                            customView,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun RendererRecoveryStage(
    movie: AiMovieEntity,
    onRetry: () -> Unit,
    onOpenExternal: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        MoviePosterImage(
            thumbnailUrl = movie.thumbnailUrl,
            genre = movie.movieGenre,
            contentDescription = movie.title,
            isHero = true,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ObsidianCanvas.copy(alpha = 0.84f))
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "In-app video renderer restarted",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Tap Retry to reload the player or open directly in ${movie.movieSource.label}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onRetry,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = ProjectorAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Retry", color = ProjectorAmber)
                }
                Button(
                    onClick = onOpenExternal,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open ${movie.movieSource.label}")
                }
            }
        }
    }
}

@Composable
private fun ActionChipButton(
    label: String,
    icon: ImageVector,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (active) activeColor else MaterialTheme.colorScheme.outline
        ),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
        modifier = modifier.height(46.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) activeColor else MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

private class YouTubeJsBridge(
    private val mainHandler: Handler,
    private val onReady: () -> Unit,
    private val onEmbedError: (Int) -> Unit
) {
    @JavascriptInterface
    fun notifyReady() {
        mainHandler.post { onReady() }
    }

    @JavascriptInterface
    fun notifyError(errorCode: Int) {
        mainHandler.post { onEmbedError(errorCode) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EmbeddedVideoPlayer(
    movie: AiMovieEntity,
    mode: PlaybackMode,
    onPlayerReady: () -> Unit,
    onEmbedFallbackNeeded: (String) -> Unit,
    onShowFullscreen: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onHideFullscreen: () -> Unit,
    onRendererCrash: () -> Unit,
    modifier: Modifier = Modifier
) {
    val webViewHolder = remember(movie.id, mode) { mutableListOf<WebView>() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val defaultPoster = remember {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLACK)
        }
    }

    DisposableEffect(movie.id, mode) {
        onDispose {
            webViewHolder.firstOrNull()?.let { safeDestroyWebView(it) }
            webViewHolder.clear()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                // Keep hardware acceleration for HTML5 <video> surface decoding and high renderer priority while visible
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
                }

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    javaScriptCanOpenWindowsAutomatically = false
                    allowContentAccess = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    userAgentString = CHROME_MOBILE_USER_AGENT
                }

                addJavascriptInterface(
                    YouTubeJsBridge(
                        mainHandler = mainHandler,
                        onReady = onPlayerReady,
                        onEmbedError = { code ->
                            val label = when (code) {
                                101, 150 -> "creator restricted embed"
                                153 -> "embed referrer restricted"
                                else -> "embed error $code"
                            }
                            onEmbedFallbackNeeded(label)
                        }
                    ),
                    "AndroidPlayer"
                )

                webChromeClient = object : WebChromeClient() {
                    override fun getDefaultVideoPoster(): Bitmap = defaultPoster

                    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                        if (view != null && callback != null) {
                            onShowFullscreen(view, callback)
                        }
                    }

                    override fun onHideCustomView() {
                        onHideFullscreen()
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        onPlayerReady()
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true) {
                            onPlayerReady()
                        }
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val uri = request?.url ?: return false
                        val scheme = uri.scheme?.lowercase().orEmpty()
                        // Block automatic intent:// / vnd.youtube:// hijacks so video stays inside the in-app player
                        if (scheme != "http" && scheme != "https") {
                            return true
                        }
                        return false
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?
                    ): Boolean {
                        val crashed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            detail?.didCrash() == true
                        } else {
                            true
                        }
                        Log.w(PLAYER_TAG, "WebView renderer gone (didCrash=$crashed); recovering gracefully")
                        view?.let { safeDestroyWebView(it) }
                        webViewHolder.clear()
                        onRendererCrash()
                        return true
                    }
                }

                loadMovieIntoWebView(this, movie, mode)
                webViewHolder.clear()
                webViewHolder.add(this)
            }
        }
    )
}

private fun safeDestroyWebView(webView: WebView) {
    runCatching {
        webView.removeJavascriptInterface("AndroidPlayer")
        webView.stopLoading()
        webView.onPause()
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.removeAllViews()
        webView.destroy()
    }
}

private fun loadMovieIntoWebView(
    webView: WebView,
    movie: AiMovieEntity,
    mode: PlaybackMode
) {
    webView.tag = "${movie.id}_${mode.name}"
    when {
        movie.movieSource == MovieSource.YOUTUBE &&
            movie.videoId.length == 11 &&
            mode == PlaybackMode.CINEMA_EMBED -> {
            val safeVideoId = movie.videoId
            val embedHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                    <meta name="referrer" content="strict-origin-when-cross-origin">
                    <style>
                        html, body {
                            margin: 0;
                            padding: 0;
                            width: 100%;
                            height: 100%;
                            background: #000000;
                            overflow: hidden;
                        }
                        #player {
                            position: fixed;
                            top: 0;
                            left: 0;
                            width: 100%;
                            height: 100%;
                        }
                    </style>
                </head>
                <body>
                    <div id="player"></div>
                    <script>
                        var tag = document.createElement('script');
                        tag.src = "https://www.youtube.com/iframe_api";
                        var firstScriptTag = document.getElementsByTagName('script')[0];
                        firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);

                        var ytPlayer;
                        var readyNotified = false;
                        function notifyReadyOnce() {
                            if (!readyNotified && window.AndroidPlayer) {
                                readyNotified = true;
                                AndroidPlayer.notifyReady();
                            }
                        }

                        function onYouTubeIframeAPIReady() {
                            ytPlayer = new YT.Player('player', {
                                width: '100%',
                                height: '100%',
                                videoId: '$safeVideoId',
                                host: 'https://www.youtube.com',
                                playerVars: {
                                    autoplay: 1,
                                    playsinline: 1,
                                    rel: 0,
                                    modestbranding: 1,
                                    fs: 1,
                                    enablejsapi: 1,
                                    origin: 'https://www.youtube.com'
                                },
                                events: {
                                    onReady: function(event) {
                                        notifyReadyOnce();
                                        try { event.target.playVideo(); } catch (e) {}
                                    },
                                    onError: function(event) {
                                        if (window.AndroidPlayer) {
                                            AndroidPlayer.notifyError(event.data || 150);
                                        }
                                    }
                                }
                            });
                        }

                        // Fallback if IFrame API fails to load within 6 seconds
                        setTimeout(function() {
                            if (!readyNotified && window.AndroidPlayer) {
                                AndroidPlayer.notifyError(153);
                            }
                        }, 6000);
                    </script>
                </body>
                </html>
            """.trimIndent()
            webView.loadDataWithBaseURL(
                "https://www.youtube.com",
                embedHtml,
                "text/html",
                "utf-8",
                null
            )
        }

        movie.movieSource == MovieSource.YOUTUBE && movie.videoId.length == 11 -> {
            webView.loadUrl("https://m.youtube.com/watch?v=${movie.videoId}")
        }

        movie.movieSource == MovieSource.FACEBOOK && movie.videoId.isNotBlank() && mode == PlaybackMode.CINEMA_EMBED -> {
            val encoded = URLEncoder.encode(movie.watchUrl, "UTF-8")
            val pluginUrl = "https://www.facebook.com/plugins/video.php?href=$encoded&show_text=false&t=0"
            webView.loadUrl(pluginUrl)
        }

        movie.movieSource == MovieSource.FACEBOOK -> {
            val mobileUrl = movie.watchUrl.replace("https://www.facebook.com", "https://m.facebook.com")
            webView.loadUrl(mobileUrl)
        }

        else -> {
            webView.loadUrl(movie.watchUrl)
        }
    }
}
