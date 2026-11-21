package com.example.data.remote

import android.util.Log
import com.example.data.model.AiMovieEntity
import com.example.data.model.ChannelScrapeResult
import com.example.data.model.CreatorChannelEntity
import com.example.data.model.MIN_FEATURE_DURATION_SECONDS
import com.example.data.model.MovieGenre
import com.example.data.model.MovieSource
import com.example.data.model.YouTubeChannelSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val TAG = "AiMovieScraper"

// YouTube search parameter that restricts results to long videos (> 20 minutes)
private const val YOUTUBE_LONG_VIDEO_SP = "EgIYAg%3D%3D"
// YouTube search parameter that restricts results to Channels (Type: Channel)
private const val YOUTUBE_CHANNEL_SEARCH_SP = "EgIQAg%3D%3D"

data class PlatformScrapeBatch(
    val movies: List<AiMovieEntity>,
    val skippedUnderMinDuration: Int,
    val errorNote: String? = null
)

private data class ParsedRendererResult(
    val movies: List<AiMovieEntity>,
    val skippedUnderMinDuration: Int
)

data class YouTubeMovieSearchBatch(
    val movies: List<AiMovieEntity>,
    val skippedUnderMinDuration: Int,
    val executedQuery: String
)

/**
 * Real network scraper that fetches live full-length (>45 min) AI-generated movies
 * from YouTube public search pages, YouTube channels (/videos + Atom XML feeds),
 * and Facebook public Watch/Video listings, profiles, and direct URLs.
 */
class AiMovieScraperEngine(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {

    private val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    suspend fun scrapeAllPlatforms(
        genres: List<MovieGenre> = MovieGenre.browsableGenres
    ): PlatformScrapeBatch = withContext(Dispatchers.IO) {
        val collected = mutableListOf<AiMovieEntity>()
        var totalSkippedShort = 0
        val errors = mutableListOf<String>()

        coroutineScope {
            val ytJobs = genres.map { genre ->
                async {
                    try {
                        scrapeYouTubeByQueryInternal(
                            query = genre.scrapeQuery,
                            preferredGenre = genre,
                            maxResults = 8
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "YouTube scrape failed for ${genre.displayName}: ${e.message}")
                        synchronized(errors) { errors.add("YT ${genre.displayName}") }
                        ParsedRendererResult(emptyList(), 0)
                    }
                }
            }

            val fbJob = async {
                try {
                    scrapeFacebookAiVideosInternal(maxResults = 8)
                } catch (e: Exception) {
                    Log.w(TAG, "Facebook scrape failed: ${e.message}")
                    synchronized(errors) { errors.add("Facebook") }
                    ParsedRendererResult(emptyList(), 0)
                }
            }

            ytJobs.awaitAll().forEach { batch ->
                collected.addAll(batch.movies)
                totalSkippedShort += batch.skippedUnderMinDuration
            }
            val fbBatch = fbJob.await()
            collected.addAll(fbBatch.movies)
            totalSkippedShort += fbBatch.skippedUnderMinDuration
        }

        val deduplicated = collected
            .filter { it.durationSeconds > MIN_FEATURE_DURATION_SECONDS }
            .distinctBy { it.id }
            .sortedByDescending { it.ratingScore }

        val errorSummary = if (deduplicated.isEmpty() && errors.isNotEmpty()) {
            "Network unavailable or rate-limited while scraping (${errors.take(2).joinToString()})."
        } else {
            null
        }

        PlatformScrapeBatch(
            movies = deduplicated,
            skippedUnderMinDuration = totalSkippedShort,
            errorNote = errorSummary
        )
    }

    /**
     * Searches YouTube for channels matching the given keywords using YouTube's native
     * Type: Channel filter (sp=EgIQAg==), supplemented by channels publishing long (>20m)
     * videos for that keyword when fewer than 4 direct channel matches are returned.
     */
    suspend fun searchYouTubeChannels(
        keyword: String,
        maxResults: Int = 15
    ): Result<List<YouTubeChannelSearchResult>> = withContext(Dispatchers.IO) {
        val trimmed = keyword.trim()
        if (trimmed.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Enter keywords to search YouTube channels."))
        }

        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val collected = mutableListOf<YouTubeChannelSearchResult>()
        val seenKeys = mutableSetOf<String>()

        // 1. Primary pass: YouTube Channel search (sp=EgIQAg==)
        val channelSearchUrl = "https://www.youtube.com/results?search_query=$encoded&sp=$YOUTUBE_CHANNEL_SEARCH_SP"
        val channelHtml = fetchHtml(channelSearchUrl)
        if (channelHtml.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Could not reach YouTube right now. Check your connection and try again.")
            )
        }

        val initialDataJson = extractBalancedJson(channelHtml, "var ytInitialData = ")
            ?: extractBalancedJson(channelHtml, "window[\"ytInitialData\"] = ")

        if (initialDataJson != null) {
            runCatching {
                val root = JSONObject(initialDataJson)
                val renderers = mutableListOf<JSONObject>()
                collectChannelRenderers(root, renderers, maxCount = maxResults + 5)
                parseChannelRenderers(renderers, maxResults).forEach { candidate ->
                    val dedupeKey = candidate.channelId.ifBlank { candidate.channelUrl.lowercase() }
                    if (seenKeys.add(dedupeKey)) {
                        collected.add(candidate)
                    }
                }
            }.onFailure {
                Log.w(TAG, "Failed parsing YouTube channel search JSON: ${it.message}")
            }
        }

        // 2. Secondary pass: if keyword is movie/topic-oriented and yielded < 4 channels,
        // extract unique creator channels from YouTube's long-video (>20m) search results
        if (collected.size < 4) {
            val longVideoUrl = "https://www.youtube.com/results?search_query=$encoded&sp=$YOUTUBE_LONG_VIDEO_SP"
            val videoHtml = fetchHtml(longVideoUrl)
            val videoDataJson = extractBalancedJson(videoHtml, "var ytInitialData = ")
                ?: extractBalancedJson(videoHtml, "window[\"ytInitialData\"] = ")
            if (videoDataJson != null) {
                runCatching {
                    val root = JSONObject(videoDataJson)
                    val videoRenderers = mutableListOf<JSONObject>()
                    collectVideoRenderers(root, videoRenderers, maxCount = 35)
                    extractChannelsFromVideoRenderers(videoRenderers, maxResults - collected.size).forEach { candidate ->
                        val dedupeKey = candidate.channelId.ifBlank { candidate.channelUrl.lowercase() }
                        if (seenKeys.add(dedupeKey)) {
                            collected.add(candidate)
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "Failed extracting fallback channels from video search: ${it.message}")
                }
            }
        }

        Result.success(collected.take(maxResults))
    }

    /**
     * Searches YouTube directly for full-length (>45m) AI movies matching user keywords.
     * Automatically appends "AI movie" context when the query doesn't already mention AI,
     * uses YouTube's >20m long-video filter, and strictly enforces >45m (2700s).
     */
    suspend fun searchYouTubeAiMovies(
        keyword: String,
        maxResults: Int = 18
    ): Result<YouTubeMovieSearchBatch> = withContext(Dispatchers.IO) {
        val trimmed = keyword.trim()
        if (trimmed.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Enter keywords to search YouTube for AI movies."))
        }

        val lower = trimmed.lowercase()
        val hasAiTerm = Regex("""\b(ai|a\.i\.|sora|runway|kling|veo|midjourney|luma|pika|generative)\b""")
            .containsMatchIn(lower)
        val hasMovieTerm = Regex("""\b(movie|film|cinema|documentary|feature)\b""")
            .containsMatchIn(lower)

        val effectiveQuery = buildString {
            append(trimmed)
            if (!hasAiTerm) append(" AI")
            if (!hasMovieTerm) append(" full movie")
        }

        val encoded = URLEncoder.encode(effectiveQuery, "UTF-8")
        val url = "https://www.youtube.com/results?search_query=$encoded&sp=$YOUTUBE_LONG_VIDEO_SP"
        val html = fetchHtml(url)
        if (html.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Could not reach YouTube right now. Check your connection and try again.")
            )
        }

        val initialDataJson = extractBalancedJson(html, "var ytInitialData = ")
            ?: extractBalancedJson(html, "window[\"ytInitialData\"] = ")
            ?: return@withContext Result.success(
                YouTubeMovieSearchBatch(
                    movies = emptyList(),
                    skippedUnderMinDuration = 0,
                    executedQuery = effectiveQuery
                )
            )

        runCatching {
            val root = JSONObject(initialDataJson)
            val renderers = mutableListOf<JSONObject>()
            collectVideoRenderers(root, renderers, maxCount = 65)
            val parsed = parseVideoRenderers(
                renderers = renderers,
                fallbackChannel = "YouTube AI Creator",
                preferredGenre = MovieGenre.ALL,
                maxResults = maxResults
            )
            val valid = parsed.movies
                .filter { it.durationSeconds > MIN_FEATURE_DURATION_SECONDS }
                .distinctBy { it.id }
            YouTubeMovieSearchBatch(
                movies = valid,
                skippedUnderMinDuration = parsed.skippedUnderMinDuration,
                executedQuery = effectiveQuery
            )
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { err ->
                Log.w(TAG, "YouTube AI movie search parse error: ${err.message}")
                Result.failure(IllegalStateException("Unable to parse YouTube results right now."))
            }
        )
    }

    /**
     * Scrapes movies strictly longer than 45 minutes from a YouTube channel URL, @handle, or ID.
     */
    suspend fun scrapeYouTubeChannel(
        rawInput: String,
        preferredGenre: MovieGenre = MovieGenre.ALL,
        fallbackName: String? = null,
        fallbackAvatarUrl: String? = null
    ): Result<ChannelScrapeResult> = withContext(Dispatchers.IO) {
        val cleaned = rawInput.trim()
        if (cleaned.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Enter a YouTube channel URL or @handle."))
        }

        val channelUrl = normalizeYouTubeChannelUrl(cleaned)
        val videosUrl = if (channelUrl.endsWith("/videos")) channelUrl else "$channelUrl/videos"

        val html = fetchHtml(videosUrl).ifBlank { fetchHtml(channelUrl) }
        val ogTitle = extractMetaContent(html, "og:title")
            ?.replace(" - YouTube", "", ignoreCase = true)
            ?.trim()
        val ogAvatar = extractMetaContent(html, "og:image")
            ?.takeIf { it.startsWith("https://") }
            ?: fallbackAvatarUrl.orEmpty()

        val channelIdFromHtml = extractYouTubeChannelId(html)
            ?: extractYouTubeChannelIdFromUrl(channelUrl)

        val channelName = ogTitle?.takeIf { it.isNotBlank() && !it.equals("YouTube", ignoreCase = true) }
            ?: fallbackName?.takeIf { it.isNotBlank() }
            ?: cleaned.removePrefix("https://").removePrefix("http://")
                .substringAfter("youtube.com/")
                .substringBefore("/")
                .removePrefix("@")
                .ifBlank { cleaned }

        val collected = mutableListOf<AiMovieEntity>()
        var skippedShort = 0

        // 1. Parse videos directly from the channel's /videos ytInitialData JSON (includes exact duration)
        val initialDataJson = extractBalancedJson(html, "var ytInitialData = ")
            ?: extractBalancedJson(html, "window[\"ytInitialData\"] = ")
        if (initialDataJson != null) {
            runCatching {
                val root = JSONObject(initialDataJson)
                val renderers = mutableListOf<JSONObject>()
                collectVideoRenderers(root, renderers, maxCount = 60)
                val parsed = parseVideoRenderers(
                    renderers = renderers,
                    fallbackChannel = channelName,
                    preferredGenre = preferredGenre,
                    maxResults = 18
                )
                collected.addAll(parsed.movies)
                skippedShort += parsed.skippedUnderMinDuration
            }.onFailure {
                Log.w(TAG, "Failed parsing channel ytInitialData: ${it.message}")
            }
        }

        // 2. Also query YouTube long-video search scoped to this channel name for >45m full movies
        if (collected.size < 6) {
            val searchBatch = runCatching {
                scrapeYouTubeByQueryInternal(
                    query = "\"$channelName\" full movie",
                    preferredGenre = preferredGenre,
                    maxResults = 12
                )
            }.getOrDefault(ParsedRendererResult(emptyList(), 0))
            collected.addAll(searchBatch.movies)
            skippedShort += searchBatch.skippedUnderMinDuration
        }

        val uniqueMovies = collected
            .filter { it.durationSeconds > MIN_FEATURE_DURATION_SECONDS }
            .distinctBy { it.id }
            .take(18)

        val externalId = channelIdFromHtml ?: cleaned.lowercase().replace(Regex("[^a-z0-9@_-]"), "")
        val stableId = "yt_ch_${abs((channelIdFromHtml ?: channelUrl.lowercase()).hashCode())}"

        val now = System.currentTimeMillis()
        val channelEntity = CreatorChannelEntity(
            id = stableId,
            name = cleanTitle(channelName),
            handleOrUrl = channelUrl,
            source = MovieSource.YOUTUBE.name,
            externalId = externalId,
            avatarUrl = ogAvatar,
            defaultGenre = preferredGenre.name,
            moviesImportedCount = uniqueMovies.size,
            lastSyncedAt = now,
            addedAt = now
        )

        val note = when {
            uniqueMovies.isEmpty() && skippedShort > 0 ->
                "Saved ${channelEntity.name}, but skipped $skippedShort videos under 45 minutes (0 feature films >45m found)."
            uniqueMovies.isEmpty() ->
                "Saved ${channelEntity.name}, but no public movies longer than 45 minutes were found right now."
            skippedShort > 0 ->
                "Added ${channelEntity.name}: ${uniqueMovies.size} feature movies (>45m) imported, $skippedShort shorter videos filtered out."
            else -> null
        }

        Result.success(
            ChannelScrapeResult(
                channel = channelEntity,
                movies = uniqueMovies,
                skippedUnderMinDuration = skippedShort,
                note = note
            )
        )
    }

    /**
     * Scrapes public movies strictly longer than 45 minutes from a Facebook profile or page URL / handle.
     */
    suspend fun scrapeFacebookProfile(
        rawInput: String,
        preferredGenre: MovieGenre = MovieGenre.ALL
    ): Result<ChannelScrapeResult> = withContext(Dispatchers.IO) {
        val cleaned = rawInput.trim()
        if (cleaned.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Enter a Facebook profile or page URL / username."))
        }

        val profileUrl = normalizeFacebookProfileUrl(cleaned)
        val slug = extractFacebookSlug(profileUrl)
        val html = fetchHtml(profileUrl)

        val ogTitle = extractMetaContent(html, "og:title")
            ?.replace("| Facebook", "", ignoreCase = true)
            ?.replace("- Facebook", "", ignoreCase = true)
            ?.trim()
        val ogAvatar = extractMetaContent(html, "og:image").orEmpty()
        val ogDesc = extractMetaContent(html, "og:description").orEmpty()

        val profileName = ogTitle
            ?.takeIf {
                it.isNotBlank() &&
                    !it.equals("Facebook", ignoreCase = true) &&
                    !it.contains("Log in", ignoreCase = true) &&
                    !it.contains("Log into", ignoreCase = true)
            }
            ?: slug.replace('.', ' ').replace('-', ' ').replace('_', ' ')
                .split(" ")
                .filter { it.isNotBlank() }
                .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
                .ifBlank { "Facebook AI Creator" }

        val discovered = mutableListOf<AiMovieEntity>()
        val seenUrls = mutableSetOf<String>()
        var skippedShort = 0
        val now = System.currentTimeMillis()

        // 1. Discover public full-length videos published by this Facebook profile via web index search
        val queries = listOfNotNull(
            if (slug.isNotBlank() && !slug.startsWith("profile.php")) "site:facebook.com/$slug/videos full movie" else null,
            if (slug.isNotBlank() && !slug.startsWith("profile.php")) "site:facebook.com/$slug/videos" else null,
            "site:facebook.com \"$profileName\" \"full movie\""
        )

        for (query in queries) {
            if (discovered.size >= 10) break
            val encoded = URLEncoder.encode(query, "UTF-8")
            val ddgHtml = fetchHtml("https://html.duckduckgo.com/html/?q=$encoded")
            if (ddgHtml.isBlank()) continue

            val linkRegex = Regex(
                """<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            val snippetRegex = Regex(
                """<a[^>]+class="result__snippet"[^>]*>(.*?)</a>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            val snippets = snippetRegex.findAll(ddgHtml).map { stripHtml(it.groupValues[1]) }.toList()

            linkRegex.findAll(ddgHtml).forEachIndexed { idx, match ->
                if (discovered.size >= 10) return@forEachIndexed
                val resolvedUrl = resolveDuckDuckGoUrl(match.groupValues[1])
                if (!resolvedUrl.contains("facebook.com", ignoreCase = true)) return@forEachIndexed
                if (!seenUrls.add(resolvedUrl)) return@forEachIndexed

                val rawTitle = stripHtml(match.groupValues[2])
                    .replace("| Facebook", "", ignoreCase = true)
                    .replace("- Facebook", "", ignoreCase = true)
                    .trim()
                if (rawTitle.length < 3) return@forEachIndexed

                val snippet = snippets.getOrNull(idx)
                    ?.takeIf { it.isNotBlank() }
                    ?: "Video from $profileName on Facebook."

                // Verify duration from snippet/title or video page metadata; strictly require > 45 min
                var durationSeconds = extractDurationSecondsFromText("$rawTitle $snippet")
                if (durationSeconds == 0) {
                    val videoPageHtml = fetchHtml(resolvedUrl)
                    durationSeconds = extractDurationSecondsFromHtml(videoPageHtml)
                }

                if (durationSeconds <= MIN_FEATURE_DURATION_SECONDS) {
                    skippedShort++
                    return@forEachIndexed
                }

                val genre = if (preferredGenre != MovieGenre.ALL) {
                    preferredGenre
                } else {
                    classifyGenre(rawTitle, snippet, MovieGenre.SCI_FI)
                }
                val fbVid = extractFacebookVideoId(resolvedUrl)

                discovered.add(
                    AiMovieEntity(
                        id = "fb_${abs(resolvedUrl.hashCode())}",
                        title = cleanTitle(rawTitle),
                        director = profileName,
                        source = MovieSource.FACEBOOK.name,
                        videoId = fbVid,
                        watchUrl = resolvedUrl,
                        thumbnailUrl = ogAvatar,
                        genre = genre.name,
                        durationText = formatSecondsToDuration(durationSeconds),
                        durationSeconds = durationSeconds,
                        viewCountText = "Facebook Feature",
                        synopsis = snippet,
                        aiTools = detectAiTools("$rawTitle $snippet"),
                        publishedText = "Facebook",
                        ratingScore = 8.9f,
                        isFeatured = false,
                        isScrapedLive = true,
                        addedAt = now - (discovered.size * 50L)
                    )
                )
            }
        }

        val uniqueMovies = discovered
            .filter { it.durationSeconds > MIN_FEATURE_DURATION_SECONDS }
            .distinctBy { it.id }

        val stableId = "fb_prof_${abs(profileUrl.lowercase().hashCode())}"
        val channelEntity = CreatorChannelEntity(
            id = stableId,
            name = cleanTitle(profileName),
            handleOrUrl = profileUrl,
            source = MovieSource.FACEBOOK.name,
            externalId = slug,
            avatarUrl = ogAvatar,
            defaultGenre = preferredGenre.name,
            moviesImportedCount = uniqueMovies.size,
            lastSyncedAt = now,
            addedAt = now
        )

        val note = when {
            uniqueMovies.isEmpty() && skippedShort > 0 ->
                "Saved ${channelEntity.name}, but skipped $skippedShort videos under 45 minutes or without verified >45m duration."
            uniqueMovies.isEmpty() ->
                "Saved ${channelEntity.name}, but no public Facebook movies longer than 45 minutes were detected."
            skippedShort > 0 ->
                "Added ${channelEntity.name}: ${uniqueMovies.size} feature movies (>45m) imported, $skippedShort shorter videos filtered out."
            else -> null
        }

        Result.success(
            ChannelScrapeResult(
                channel = channelEntity,
                movies = uniqueMovies,
                skippedUnderMinDuration = skippedShort,
                note = note
            )
        )
    }

    /**
     * Scrapes metadata from a user-provided YouTube or Facebook video URL and enforces the >45 minute rule.
     */
    suspend fun scrapeSingleUrl(rawUrl: String): Result<AiMovieEntity> = withContext(Dispatchers.IO) {
        val url = rawUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext Result.failure(IllegalArgumentException("Enter a valid https:// YouTube or Facebook URL."))
        }

        val isYouTube = url.contains("youtube.com", ignoreCase = true) ||
            url.contains("youtu.be", ignoreCase = true)
        val isFacebook = url.contains("facebook.com", ignoreCase = true) ||
            url.contains("fb.watch", ignoreCase = true)
        if (!isYouTube && !isFacebook) {
            return@withContext Result.failure(IllegalArgumentException("Only YouTube and Facebook video URLs are supported."))
        }

        val html = fetchHtml(url)

        val ogTitle = extractMetaContent(html, "og:title")
            ?: extractHtmlTitle(html)
            ?: if (isYouTube) "Imported YouTube AI Movie" else "Imported Facebook AI Movie"
        val ogDesc = extractMetaContent(html, "og:description")
            ?: "Imported AI feature film scraped directly from ${if (isYouTube) "YouTube" else "Facebook"}."
        val ogImage = extractMetaContent(html, "og:image").orEmpty()

        val durationSeconds = extractDurationSecondsFromHtml(html)
            .takeIf { it > 0 }
            ?: extractDurationSecondsFromText("$ogTitle $ogDesc")

        val cleanDisplayTitle = cleanTitle(
            ogTitle
                .replace(" - YouTube", "", ignoreCase = true)
                .replace(" | Facebook", "", ignoreCase = true)
        )

        if (durationSeconds <= MIN_FEATURE_DURATION_SECONDS) {
            val foundText = if (durationSeconds > 0) formatSecondsToDuration(durationSeconds) else "unknown length"
            return@withContext Result.failure(
                IllegalArgumentException(
                    "Filtered out: \"$cleanDisplayTitle\" ($foundText). Only movies longer than 45 minutes can be added."
                )
            )
        }

        val ytId = if (isYouTube) extractYouTubeVideoId(url) else ""
        val fbId = if (isFacebook) extractFacebookVideoId(url) else ""
        val finalThumb = when {
            ytId.length == 11 -> "https://i.ytimg.com/vi/$ytId/hqdefault.jpg"
            ogImage.startsWith("https://") -> ogImage
            else -> ""
        }

        val genre = classifyGenre(cleanDisplayTitle, ogDesc, MovieGenre.SCI_FI)
        val tools = detectAiTools("$cleanDisplayTitle $ogDesc")

        Result.success(
            AiMovieEntity(
                id = if (ytId.isNotBlank()) "yt_$ytId" else "imp_${abs(url.hashCode())}",
                title = cleanDisplayTitle,
                director = if (isYouTube) "YouTube Creator" else "Facebook Creator",
                source = if (isYouTube) MovieSource.YOUTUBE.name else MovieSource.FACEBOOK.name,
                videoId = if (isYouTube) ytId else fbId,
                watchUrl = if (ytId.length == 11) "https://www.youtube.com/watch?v=$ytId" else url,
                thumbnailUrl = finalThumb,
                genre = genre.name,
                durationText = formatSecondsToDuration(durationSeconds),
                durationSeconds = durationSeconds,
                viewCountText = "Imported Feature",
                synopsis = ogDesc.take(260),
                aiTools = tools,
                publishedText = "Just Scraped",
                ratingScore = 9.1f,
                isFeatured = false,
                isScrapedLive = true,
                addedAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun scrapeYouTubeByQueryInternal(
        query: String,
        preferredGenre: MovieGenre,
        maxResults: Int
    ): ParsedRendererResult = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        // Apply YouTube's native >20m long-video filter (sp=EgIYAg%3D%3D) to surface full-length feature movies
        val url = "https://www.youtube.com/results?search_query=$encoded&sp=$YOUTUBE_LONG_VIDEO_SP"
        val html = fetchHtml(url)
        if (html.isBlank()) return@withContext ParsedRendererResult(emptyList(), 0)

        val initialDataJson = extractBalancedJson(html, "var ytInitialData = ")
            ?: extractBalancedJson(html, "window[\"ytInitialData\"] = ")
            ?: return@withContext ParsedRendererResult(emptyList(), 0)

        val root = JSONObject(initialDataJson)
        val renderers = mutableListOf<JSONObject>()
        collectVideoRenderers(root, renderers, maxCount = 45)

        parseVideoRenderers(
            renderers = renderers,
            fallbackChannel = "Independent AI Studio",
            preferredGenre = preferredGenre,
            maxResults = maxResults
        )
    }

    private suspend fun scrapeFacebookAiVideosInternal(maxResults: Int): ParsedRendererResult =
        withContext(Dispatchers.IO) {
            val queries = listOf(
                "site:facebook.com/watch AI full movie",
                "site:facebook.com AI generated full movie sci-fi OR fantasy OR horror"
            )
            val found = mutableListOf<AiMovieEntity>()
            val seenUrls = mutableSetOf<String>()
            var skippedShort = 0
            val now = System.currentTimeMillis()

            for (query in queries) {
                if (found.size >= maxResults) break
                val encoded = URLEncoder.encode(query, "UTF-8")
                val html = fetchHtml("https://html.duckduckgo.com/html/?q=$encoded")
                if (html.isBlank()) continue

                val linkRegex = Regex(
                    """<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                )
                val snippetRegex = Regex(
                    """<a[^>]+class="result__snippet"[^>]*>(.*?)</a>""",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                )
                val snippets = snippetRegex.findAll(html).map { stripHtml(it.groupValues[1]) }.toList()

                linkRegex.findAll(html).forEachIndexed { idx, match ->
                    if (found.size >= maxResults) return@forEachIndexed
                    val resolvedUrl = resolveDuckDuckGoUrl(match.groupValues[1])
                    if (!resolvedUrl.contains("facebook.com", ignoreCase = true)) return@forEachIndexed
                    if (!seenUrls.add(resolvedUrl)) return@forEachIndexed

                    val rawTitle = stripHtml(match.groupValues[2])
                        .replace("| Facebook", "", ignoreCase = true)
                        .replace("- Facebook", "", ignoreCase = true)
                        .trim()
                    if (rawTitle.length < 4) return@forEachIndexed

                    val snippet = snippets.getOrNull(idx)
                        ?.takeIf { it.isNotBlank() }
                        ?: "AI-generated feature film discovered on Facebook Watch."

                    var durationSeconds = extractDurationSecondsFromText("$rawTitle $snippet")
                    if (durationSeconds == 0) {
                        val videoHtml = fetchHtml(resolvedUrl)
                        durationSeconds = extractDurationSecondsFromHtml(videoHtml)
                    }

                    if (durationSeconds <= MIN_FEATURE_DURATION_SECONDS) {
                        skippedShort++
                        return@forEachIndexed
                    }

                    val genre = classifyGenre(rawTitle, snippet, MovieGenre.SCI_FI)
                    val tools = detectAiTools("$rawTitle $snippet")
                    val fbVideoId = extractFacebookVideoId(resolvedUrl)

                    found.add(
                        AiMovieEntity(
                            id = "fb_${abs(resolvedUrl.hashCode())}",
                            title = cleanTitle(rawTitle),
                            director = "Facebook AI Creator",
                            source = MovieSource.FACEBOOK.name,
                            videoId = fbVideoId,
                            watchUrl = resolvedUrl,
                            thumbnailUrl = "",
                            genre = genre.name,
                            durationText = formatSecondsToDuration(durationSeconds),
                            durationSeconds = durationSeconds,
                            viewCountText = "Facebook Feature",
                            synopsis = snippet,
                            aiTools = tools,
                            publishedText = "Facebook",
                            ratingScore = 8.9f,
                            isFeatured = false,
                            isScrapedLive = true,
                            addedAt = now - (idx * 75L)
                        )
                    )
                }
            }

            ParsedRendererResult(found, skippedShort)
        }

    private fun parseVideoRenderers(
        renderers: List<JSONObject>,
        fallbackChannel: String,
        preferredGenre: MovieGenre,
        maxResults: Int
    ): ParsedRendererResult {
        val now = System.currentTimeMillis()
        val results = mutableListOf<AiMovieEntity>()
        var skippedShort = 0

        for ((index, renderer) in renderers.withIndex()) {
            if (results.size >= maxResults) break
            val videoId = renderer.optString("videoId").trim()
            if (videoId.length != 11) continue

            val title = readTextField(renderer.optJSONObject("title")).trim()
            if (title.isBlank()) continue

            val durationText = readTextField(renderer.optJSONObject("lengthText")).trim()
            val durationSeconds = parseDurationToSeconds(durationText)
            // Strict filter rule: only add movies strictly longer than 45 minutes (2700s)
            if (durationSeconds <= MIN_FEATURE_DURATION_SECONDS) {
                skippedShort++
                continue
            }

            val channel = readTextField(renderer.optJSONObject("ownerText"))
                .ifBlank { readTextField(renderer.optJSONObject("longBylineText")) }
                .ifBlank { fallbackChannel }

            val viewCountText = readTextField(renderer.optJSONObject("shortViewCountText"))
                .ifBlank { readTextField(renderer.optJSONObject("viewCountText")) }
                .ifBlank { "YouTube Feature" }

            val publishedText = readTextField(renderer.optJSONObject("publishedTimeText"))
                .ifBlank { "YouTube" }

            val snippet = extractSnippetText(renderer)
                .ifBlank {
                    "Full-length AI-generated feature film ($durationText) from $channel on YouTube."
                }

            val detectedGenre = if (preferredGenre != MovieGenre.ALL) {
                classifyGenre(title, snippet, preferredGenre)
            } else {
                classifyGenre(title, snippet, MovieGenre.SCI_FI)
            }
            val detectedTools = detectAiTools("$title $snippet $channel")
            val score = estimateQualityScore(viewCountText, durationSeconds, detectedTools)

            results.add(
                AiMovieEntity(
                    id = "yt_$videoId",
                    title = cleanTitle(title),
                    director = channel,
                    source = MovieSource.YOUTUBE.name,
                    videoId = videoId,
                    watchUrl = "https://www.youtube.com/watch?v=$videoId",
                    thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                    genre = detectedGenre.name,
                    durationText = durationText,
                    durationSeconds = durationSeconds,
                    viewCountText = viewCountText,
                    synopsis = snippet,
                    aiTools = detectedTools,
                    publishedText = publishedText,
                    ratingScore = score,
                    isFeatured = results.isEmpty() && preferredGenre == MovieGenre.SCI_FI,
                    isScrapedLive = true,
                    addedAt = now - (index * 50L)
                )
            )
        }
        return ParsedRendererResult(results, skippedShort)
    }

    private fun extractDurationSecondsFromHtml(html: String): Int {
        if (html.isBlank()) return 0

        // YouTube JSON lengthSeconds
        Regex(""""lengthSeconds"\s*:\s*"(\d+)"""").find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            if (it > 0) return it
        }
        // YouTube approxDurationMs
        Regex(""""approxDurationMs"\s*:\s*"(\d+)"""").find(html)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let {
            val sec = (it / 1000L).toInt()
            if (sec > 0) return sec
        }
        // Facebook playable_duration_in_ms
        Regex(""""playable_duration_in_ms"\s*:\s*(\d+)""").find(html)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let {
            val sec = (it / 1000L).toInt()
            if (sec > 0) return sec
        }
        // OpenGraph / Schema.org duration meta tags
        val metaDuration = extractMetaContent(html, "video:duration")
            ?: extractMetaContent(html, "duration")
        if (!metaDuration.isNullOrBlank()) {
            metaDuration.toIntOrNull()?.let { if (it > 0) return it }
            val iso = parseIso8601Duration(metaDuration)
            if (iso > 0) return iso
        }
        return 0
    }

    private fun parseIso8601Duration(iso: String): Int {
        val match = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""", RegexOption.IGNORE_CASE).find(iso)
            ?: return 0
        val h = match.groupValues[1].toIntOrNull() ?: 0
        val m = match.groupValues[2].toIntOrNull() ?: 0
        val s = match.groupValues[3].toIntOrNull() ?: 0
        return h * 3600 + m * 60 + s
    }

    private fun extractDurationSecondsFromText(text: String): Int {
        // Match HH:MM:SS timestamps like 1:14:22
        Regex("""\b(\d{1,2}):([0-5]\d):([0-5]\d)\b""").find(text)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: 0
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val s = m.groupValues[3].toIntOrNull() ?: 0
            return h * 3600 + min * 60 + s
        }
        // Match "1h 20m" or "1 hr 15 min"
        Regex("""\b(\d+)\s*(?:h|hr|hrs|hour|hours)\s*(?:(\d+)\s*(?:m|min|mins|minute|minutes))?""", RegexOption.IGNORE_CASE)
            .find(text)?.let { m ->
                val h = m.groupValues[1].toIntOrNull() ?: 0
                val min = m.groupValues[2].toIntOrNull() ?: 0
                if (h > 0) return h * 3600 + min * 60
            }
        // Match "74 min" / "60 minutes"
        Regex("""\b(\d{2,3})\s*(?:min|mins|minute|minutes)\b""", RegexOption.IGNORE_CASE)
            .find(text)?.let { m ->
                val min = m.groupValues[1].toIntOrNull() ?: 0
                if (min > 0) return min * 60
            }
        return 0
    }

    private fun formatSecondsToDuration(totalSeconds: Int): String {
        val safe = totalSeconds.coerceAtLeast(0)
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        val seconds = safe % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    private fun fetchHtml(url: String): String = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", desktopUserAgent)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        client.newCall(request).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
        }
    }.getOrDefault("")

    private fun normalizeYouTubeChannelUrl(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("youtube.com/") || trimmed.startsWith("www.youtube.com/") -> "https://$trimmed"
            trimmed.startsWith("UC") && trimmed.length >= 20 -> "https://www.youtube.com/channel/$trimmed"
            trimmed.startsWith("@") -> "https://www.youtube.com/$trimmed"
            else -> "https://www.youtube.com/@${trimmed.replace(" ", "")}"
        }
    }

    private fun normalizeFacebookProfileUrl(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("facebook.com/") || trimmed.startsWith("www.facebook.com/") || trimmed.startsWith("m.facebook.com/") ->
                "https://$trimmed"
            else -> "https://www.facebook.com/${trimmed.removePrefix("@").replace(" ", "")}"
        }
    }

    private fun extractFacebookSlug(url: String): String {
        val afterDomain = url.substringAfter("facebook.com/", "").trim('/')
        return if (afterDomain.startsWith("profile.php")) {
            afterDomain
        } else {
            afterDomain.substringBefore("/").substringBefore("?")
        }
    }

    private fun extractYouTubeChannelId(html: String): String? {
        val patterns = listOf(
            Regex(""""externalId":"(UC[a-zA-Z0-9_-]{20,})""""),
            Regex(""""channelId":"(UC[a-zA-Z0-9_-]{20,})""""),
            Regex("""/channel/(UC[a-zA-Z0-9_-]{20,})""")
        )
        return patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }
    }

    private fun extractYouTubeChannelIdFromUrl(url: String): String? =
        Regex("""/channel/(UC[a-zA-Z0-9_-]{20,})""").find(url)?.groupValues?.getOrNull(1)

    private fun extractBalancedJson(source: String, marker: String): String? {
        val markerIdx = source.indexOf(marker)
        if (markerIdx == -1) return null
        val start = source.indexOf('{', markerIdx + marker.length)
        if (start == -1) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until source.length) {
            val c = source[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\' && inString) {
                escaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        return source.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun collectVideoRenderers(node: Any?, out: MutableList<JSONObject>, maxCount: Int) {
        if (node == null || out.size >= maxCount) return
        when (node) {
            is JSONObject -> {
                val renderer = node.optJSONObject("videoRenderer")
                    ?: node.optJSONObject("gridVideoRenderer")
                if (renderer != null) {
                    out.add(renderer)
                    return
                }
                val keys = node.keys()
                while (keys.hasNext() && out.size < maxCount) {
                    val key = keys.next()
                    collectVideoRenderers(node.opt(key), out, maxCount)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    if (out.size >= maxCount) break
                    collectVideoRenderers(node.opt(i), out, maxCount)
                }
            }
        }
    }

    private fun collectChannelRenderers(node: Any?, out: MutableList<JSONObject>, maxCount: Int) {
        if (node == null || out.size >= maxCount) return
        when (node) {
            is JSONObject -> {
                val renderer = node.optJSONObject("channelRenderer")
                if (renderer != null) {
                    out.add(renderer)
                    return
                }
                val keys = node.keys()
                while (keys.hasNext() && out.size < maxCount) {
                    val key = keys.next()
                    collectChannelRenderers(node.opt(key), out, maxCount)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    if (out.size >= maxCount) break
                    collectChannelRenderers(node.opt(i), out, maxCount)
                }
            }
        }
    }

    private fun parseChannelRenderers(
        renderers: List<JSONObject>,
        maxResults: Int
    ): List<YouTubeChannelSearchResult> {
        val results = mutableListOf<YouTubeChannelSearchResult>()
        for (renderer in renderers) {
            if (results.size >= maxResults) break
            val channelId = renderer.optString("channelId").trim()
            val name = readTextField(renderer.optJSONObject("title")).trim()
            if (name.isBlank()) continue

            val browseEndpoint = renderer.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")
            val canonicalBase = browseEndpoint?.optString("canonicalBaseUrl").orEmpty().trim()
            val resolvedId = channelId.ifBlank { browseEndpoint?.optString("browseId").orEmpty().trim() }

            val channelUrl = when {
                canonicalBase.startsWith("/") -> "https://www.youtube.com$canonicalBase"
                resolvedId.startsWith("UC") -> "https://www.youtube.com/channel/$resolvedId"
                else -> continue
            }

            val subText = readTextField(renderer.optJSONObject("subscriberCountText")).trim()
            val videoCountText = readTextField(renderer.optJSONObject("videoCountText")).trim()

            // YouTube's newer channelRenderer puts @handle in subscriberCountText and subscriber count in videoCountText
            val handle = when {
                canonicalBase.startsWith("/@") -> canonicalBase.removePrefix("/")
                subText.startsWith("@") -> subText
                else -> ""
            }

            val statsParts = listOf(subText, videoCountText)
                .filter { it.isNotBlank() && !it.startsWith("@") }
                .distinct()
            val statsText = statsParts.joinToString(" • ").ifBlank { "YouTube Channel" }

            val description = readTextField(renderer.optJSONObject("descriptionSnippet"))
                .trim()
                .ifBlank { "YouTube creator channel discovered via keyword search." }

            val avatarUrl = extractBestThumbnailUrl(renderer.optJSONObject("thumbnail"))

            results.add(
                YouTubeChannelSearchResult(
                    channelId = resolvedId.ifBlank { "yt_srch_${abs(channelUrl.hashCode())}" },
                    name = cleanTitle(name),
                    handle = handle,
                    channelUrl = channelUrl,
                    avatarUrl = avatarUrl,
                    statsText = statsText,
                    description = description
                )
            )
        }
        return results
    }

    private fun extractChannelsFromVideoRenderers(
        videoRenderers: List<JSONObject>,
        maxResults: Int
    ): List<YouTubeChannelSearchResult> {
        if (maxResults <= 0) return emptyList()
        val results = mutableListOf<YouTubeChannelSearchResult>()
        val seenIds = mutableSetOf<String>()

        for (renderer in videoRenderers) {
            if (results.size >= maxResults) break
            val ownerObj = renderer.optJSONObject("ownerText")
                ?: renderer.optJSONObject("longBylineText")
                ?: continue
            val ownerName = readTextField(ownerObj).trim()
            if (ownerName.isBlank()) continue

            val firstRun = ownerObj.optJSONArray("runs")?.optJSONObject(0)
            val browse = firstRun?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            val browseId = browse?.optString("browseId").orEmpty().trim()
            val canonicalBase = browse?.optString("canonicalBaseUrl").orEmpty().trim()

            val channelUrl = when {
                canonicalBase.startsWith("/") -> "https://www.youtube.com$canonicalBase"
                browseId.startsWith("UC") -> "https://www.youtube.com/channel/$browseId"
                else -> continue
            }

            val dedupeKey = browseId.ifBlank { channelUrl.lowercase() }
            if (!seenIds.add(dedupeKey)) continue

            val handle = if (canonicalBase.startsWith("/@")) canonicalBase.removePrefix("/") else ""
            val avatarThumbObj = renderer
                .optJSONObject("channelThumbnailSupportedRenderers")
                ?.optJSONObject("channelThumbnailWithLinkRenderer")
                ?.optJSONObject("thumbnail")
            val avatarUrl = extractBestThumbnailUrl(avatarThumbObj)

            val sampleTitle = readTextField(renderer.optJSONObject("title")).trim()
            val sampleDuration = readTextField(renderer.optJSONObject("lengthText")).trim()
            val desc = if (sampleTitle.isNotBlank()) {
                val durPart = if (sampleDuration.isNotBlank()) " ($sampleDuration)" else ""
                "Publishes: $sampleTitle$durPart"
            } else {
                "Channel publishing long-form videos matching your keywords."
            }

            results.add(
                YouTubeChannelSearchResult(
                    channelId = browseId.ifBlank { "yt_vidch_${abs(channelUrl.hashCode())}" },
                    name = cleanTitle(ownerName),
                    handle = handle,
                    channelUrl = channelUrl,
                    avatarUrl = avatarUrl,
                    statsText = "Matched via long videos",
                    description = desc
                )
            )
        }
        return results
    }

    private fun extractBestThumbnailUrl(thumbnailObj: JSONObject?): String {
        val thumbs = thumbnailObj?.optJSONArray("thumbnails") ?: return ""
        if (thumbs.length() == 0) return ""
        val raw = thumbs.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty().trim()
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") -> raw.replaceFirst("http://", "https://")
            raw.startsWith("https://") -> raw
            else -> ""
        }
    }

    private fun readTextField(obj: JSONObject?): String {
        if (obj == null) return ""
        val simple = obj.optString("simpleText")
        if (simple.isNotBlank()) return simple
        val runs = obj.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
        return sb.toString()
    }

    private fun extractSnippetText(renderer: JSONObject): String {
        val detailed = renderer.optJSONArray("detailedMetadataSnippets")
        if (detailed != null && detailed.length() > 0) {
            val textObj = detailed.optJSONObject(0)?.optJSONObject("snippetText")
            val text = readTextField(textObj)
            if (text.isNotBlank()) return text
        }
        return readTextField(renderer.optJSONObject("descriptionSnippet"))
    }

    private fun parseDurationToSeconds(duration: String): Int {
        if (duration.isBlank()) return 0
        val parts = duration.trim().split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0
        }
    }

    private fun classifyGenre(title: String, description: String, fallback: MovieGenre): MovieGenre {
        val text = "$title $description".lowercase()
        return when {
            listOf("cyberpunk", "neon", "blade runner", "dystopian", "synthwave", "android").any { it in text } ->
                MovieGenre.CYBERPUNK
            listOf("horror", "scary", "nightmare", "creepy", "witch", "monster", "thriller", "haunted", "analog").any { it in text } ->
                MovieGenre.HORROR
            listOf("fantasy", "dragon", "magic", "medieval", "kingdom", "wizard", "myth", "dungeon", "elf").any { it in text } ->
                MovieGenre.FANTASY
            listOf("animation", "animated", "pixar", "anime", "cartoon", "3d", "ghibli").any { it in text } ->
                MovieGenre.ANIMATION
            listOf("documentary", "history", "nature", "archive", "explained", "evolution", "cosmos").any { it in text } ->
                MovieGenre.DOCUMENTARY
            listOf("comedy", "funny", "parody", "satire", "sitcom", "neural viz", "hilarious").any { it in text } ->
                MovieGenre.COMEDY
            listOf("experimental", "surreal", "abstract", "dreamscape", "avant", "music video").any { it in text } ->
                MovieGenre.EXPERIMENTAL
            listOf("sci-fi", "scifi", "space", "mars", "planet", "alien", "robot", "future", "2099", "2166").any { it in text } ->
                MovieGenre.SCI_FI
            else -> if (fallback == MovieGenre.ALL) MovieGenre.SCI_FI else fallback
        }
    }

    private fun detectAiTools(text: String): String {
        val lower = text.lowercase()
        val known = listOf(
            "Sora" to listOf("sora"),
            "Runway" to listOf("runway", "gen-3", "gen-4", "gen3"),
            "Kling" to listOf("kling"),
            "Veo" to listOf("veo", "veo 2", "veo 3"),
            "Midjourney" to listOf("midjourney", "mj "),
            "Luma" to listOf("luma", "dream machine"),
            "Pika" to listOf("pika"),
            "ElevenLabs" to listOf("elevenlabs"),
            "Suno" to listOf("suno"),
            "Hailuo" to listOf("hailuo", "minimax")
        )
        val matched = known.filter { (_, keywords) -> keywords.any { it in lower } }.map { it.first }
        return if (matched.isNotEmpty()) {
            matched.take(3).joinToString(", ")
        } else {
            "Generative AI Cinema"
        }
    }

    private fun estimateQualityScore(views: String, durationSeconds: Int, tools: String): Float {
        var score = 8.5f
        val lower = views.lowercase()
        if (lower.contains("m")) score += 0.8f
        else if (lower.contains("k")) score += 0.4f
        if (durationSeconds >= 3600) score += 0.3f
        if (tools != "Generative AI Cinema") score += 0.2f
        return score.coerceAtMost(9.8f)
    }

    private fun cleanTitle(raw: String): String =
        raw.replace(Regex("\\s+"), " ").trim().take(95)

    private fun stripHtml(raw: String): String =
        raw.replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun resolveDuckDuckGoUrl(href: String): String {
        val uddgIndex = href.indexOf("uddg=")
        return if (uddgIndex != -1) {
            val encoded = href.substring(uddgIndex + 5).substringBefore("&")
            runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(href)
        } else if (href.startsWith("//")) {
            "https:$href"
        } else {
            href
        }
    }

    private fun extractMetaContent(html: String, property: String): String? {
        val pattern = Regex(
            """<meta[^>]+(?:property|name|itemprop)=["']$property["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        val direct = pattern.find(html)?.groupValues?.getOrNull(1)
        if (!direct.isNullOrBlank()) return stripHtml(direct)

        val reversed = Regex(
            """<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name|itemprop)=["']$property["']""",
            RegexOption.IGNORE_CASE
        )
        return reversed.find(html)?.groupValues?.getOrNull(1)?.let { stripHtml(it) }
    }

    private fun extractHtmlTitle(html: String): String? {
        val match = Regex("<title>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
        return match?.groupValues?.getOrNull(1)?.let { stripHtml(it) }?.takeIf { it.isNotBlank() }
    }

    private fun extractYouTubeVideoId(url: String): String {
        val patterns = listOf(
            Regex("""[?&]v=([a-zA-Z0-9_-]{11})"""),
            Regex("""youtu\.be/([a-zA-Z0-9_-]{11})"""),
            Regex("""youtube\.com/embed/([a-zA-Z0-9_-]{11})"""),
            Regex("""youtube\.com/shorts/([a-zA-Z0-9_-]{11})""")
        )
        return patterns.firstNotNullOfOrNull { it.find(url)?.groupValues?.getOrNull(1) }.orEmpty()
    }

    private fun extractFacebookVideoId(url: String): String {
        val patterns = listOf(
            Regex("""[?&]v=(\d+)"""),
            Regex("""/videos/(\d+)"""),
            Regex("""/reel/(\d+)""")
        )
        return patterns.firstNotNullOfOrNull { it.find(url)?.groupValues?.getOrNull(1) }.orEmpty()
    }
}
