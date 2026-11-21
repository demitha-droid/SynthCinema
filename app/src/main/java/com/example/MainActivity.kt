package com.example

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.data.local.CinemaDatabase
import com.example.data.repository.CinemaRepository
import com.example.ui.CinemaViewModel
import com.example.ui.CinemaViewModelFactory
import com.example.ui.screens.ChannelsScreen
import com.example.ui.screens.CuratorScreen
import com.example.ui.screens.DiscoverScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.PlayerScreen
import com.example.ui.theme.ObsidianCanvas
import com.example.ui.theme.ProjectorAmber
import com.example.ui.theme.SynthCinemaTheme
import java.net.URLDecoder
import java.net.URLEncoder

private const val ROUTE_DISCOVER = "discover"
private const val ROUTE_CHANNELS = "channels"
private const val ROUTE_CURATOR = "curator"
private const val ROUTE_HISTORY = "history"
private const val ROUTE_PLAYER = "player/{movieId}"

private data class CinemaNavDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val topLevelDestinations = listOf(
    CinemaNavDestination(
        route = ROUTE_DISCOVER,
        labelRes = R.string.nav_discover,
        selectedIcon = Icons.Filled.Explore,
        unselectedIcon = Icons.Outlined.Explore,
        testTag = "nav_discover"
    ),
    CinemaNavDestination(
        route = ROUTE_CHANNELS,
        labelRes = R.string.nav_channels,
        selectedIcon = Icons.Filled.Subscriptions,
        unselectedIcon = Icons.Outlined.Subscriptions,
        testTag = "nav_channels"
    ),
    CinemaNavDestination(
        route = ROUTE_CURATOR,
        labelRes = R.string.nav_curator,
        selectedIcon = Icons.Filled.AutoAwesome,
        unselectedIcon = Icons.Outlined.AutoAwesome,
        testTag = "nav_curator"
    ),
    CinemaNavDestination(
        route = ROUTE_HISTORY,
        labelRes = R.string.nav_history,
        selectedIcon = Icons.Filled.History,
        unselectedIcon = Icons.Outlined.History,
        testTag = "nav_history"
    )
)

class MainActivity : ComponentActivity() {

    private val cinemaRepository by lazy {
        val db = CinemaDatabase.getInstance(applicationContext)
        CinemaRepository(db.cinemaDao())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        setContent {
            SynthCinemaTheme {
                val viewModel: CinemaViewModel = viewModel(
                    factory = CinemaViewModelFactory(cinemaRepository)
                )
                SynthCinemaApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun SynthCinemaApp(viewModel: CinemaViewModel) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val curatorState by viewModel.curatorState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showChrome = currentRoute in topLevelDestinations.map { it.route }

    LaunchedEffect(uiState.statusMessage) {
        val msg = uiState.statusMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    val navigateToPlayer: (String) -> Unit = { movieId ->
        val encoded = URLEncoder.encode(movieId, "UTF-8")
        navController.navigate("player/$encoded")
    }

    val navigateTopLevel: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianCanvas)
    ) {
        val useNavigationRail = maxWidth >= 600.dp

        Row(modifier = Modifier.fillMaxSize()) {
            if (useNavigationRail && showChrome) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxHeight()
                ) {
                    topLevelDestinations.forEach { dest ->
                        val selected = currentRoute == dest.route
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navigateTopLevel(dest.route) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                    contentDescription = stringResource(dest.labelRes)
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(dest.labelRes),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                selectedTextColor = ProjectorAmber,
                                indicatorColor = ProjectorAmber,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.testTag(dest.testTag)
                        )
                    }
                }
            }

            Scaffold(
                modifier = Modifier.weight(1f),
                containerColor = ObsidianCanvas,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                bottomBar = {
                    if (!useNavigationRail && showChrome) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 4.dp
                        ) {
                            topLevelDestinations.forEach { dest ->
                                val selected = currentRoute == dest.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateTopLevel(dest.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = stringResource(dest.labelRes)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = stringResource(dest.labelRes),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                        selectedTextColor = ProjectorAmber,
                                        indicatorColor = ProjectorAmber,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    modifier = Modifier.testTag(dest.testTag)
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = ROUTE_DISCOVER,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    composable(ROUTE_DISCOVER) {
                        DiscoverScreen(
                            uiState = uiState,
                            onSearchQueryChanged = viewModel::onSearchQueryChanged,
                            onGenreSelected = viewModel::onGenreSelected,
                            onSourceSelected = viewModel::onSourceFilterSelected,
                            onScrapeFresh = { viewModel.scrapeFreshMovies() },
                            onDismissBanner = viewModel::dismissScrapeBanner,
                            onImportUrl = { url ->
                                viewModel.importMovieUrl(url) { importedId ->
                                    navigateToPlayer(importedId)
                                }
                            },
                            onMovieClick = navigateToPlayer,
                            onToggleWatchlist = viewModel::toggleWatchlist
                        )
                    }

                    composable(ROUTE_CHANNELS) {
                        ChannelsScreen(
                            uiState = uiState,
                            onAddChannel = viewModel::addChannelOrProfile,
                            onSyncChannel = viewModel::syncChannel,
                            onSyncAllChannels = viewModel::syncAllChannels,
                            onDeleteChannel = viewModel::deleteChannel,
                            onBrowseChannelMovies = { channel ->
                                viewModel.filterByCreator(channel.name, channel.movieSource)
                                navigateTopLevel(ROUTE_DISCOVER)
                            }
                        )
                    }

                    composable(ROUTE_CURATOR) {
                        CuratorScreen(
                            curatorState = curatorState,
                            allMovies = uiState.allMovies,
                            onPromptChanged = viewModel::onCuratorPromptChanged,
                            onSubmitQuery = viewModel::submitCuratorQuery,
                            onMovieClick = navigateToPlayer,
                            onToggleWatchlist = viewModel::toggleWatchlist
                        )
                    }

                    composable(ROUTE_HISTORY) {
                        HistoryScreen(
                            uiState = uiState,
                            onTabSelected = viewModel::onHistoryTabSelected,
                            onMovieClick = navigateToPlayer,
                            onToggleWatchlist = viewModel::toggleWatchlist,
                            onRemoveHistoryItem = viewModel::removeFromHistory,
                            onClearAllHistory = viewModel::clearAllHistory
                        )
                    }

                    composable(
                        route = ROUTE_PLAYER,
                        arguments = listOf(navArgument("movieId") { type = NavType.StringType })
                    ) { entry ->
                        val encodedId = entry.arguments?.getString("movieId").orEmpty()
                        val movieId = runCatching { URLDecoder.decode(encodedId, "UTF-8") }
                            .getOrDefault(encodedId)
                        val movieWithHistory by remember(movieId) {
                            viewModel.observeMovie(movieId)
                        }.collectAsStateWithLifecycle(initialValue = uiState.allMovies.firstOrNull { it.movie.id == movieId })

                        val related = remember(uiState.allMovies, movieWithHistory) {
                            val currentGenre = movieWithHistory?.movie?.movieGenre
                            uiState.allMovies.filter {
                                it.movie.id != movieId && (currentGenre == null || it.movie.movieGenre == currentGenre)
                            }.take(8)
                        }

                        PlayerScreen(
                            movieWithHistory = movieWithHistory,
                            relatedMovies = related,
                            onBack = { navController.popBackStack() },
                            onRecordStart = viewModel::recordWatchStart,
                            onUpdateProgress = viewModel::updateWatchProgress,
                            onToggleLike = viewModel::toggleLike,
                            onToggleWatchlist = viewModel::toggleWatchlist,
                            onToggleCompleted = viewModel::toggleCompleted,
                            onSelectRelated = { nextId ->
                                val nextEncoded = URLEncoder.encode(nextId, "UTF-8")
                                navController.navigate("player/$nextEncoded") {
                                    popUpTo(ROUTE_PLAYER) { inclusive = true }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
