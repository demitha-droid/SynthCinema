package com.example.data.remote

import android.util.Log
import com.example.data.model.AiMovieEntity
import com.example.data.model.ChannelScrapeResult
import com.example.data.model.CreatorChannelEntity
import com.example.data.model.MovieGenre
import com.example.data.model.MovieSource
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

/**
 * Real network scraper that fetches live AI-generated movies & short films
 * from YouTube public search pages, YouTube channels (/videos + Atom XML feeds),
 * and Facebook public Watch/Reel results, pages, and profiles.
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
    ): Pair<List<AiMovieEntity>, String?> = withContext(Dispatchers.IO) {
        val collected = mutableListOf<AiMovieEntity>()
        val errors = mutableListOf<String>()

        coroutineScope {
            val ytJobs = genres.map { genre ->
                async {
                    try {
                        scrapeYouTubeByQuery(
                            query = genre.scrapeQuery,
                            preferredGenre = genre,
                            maxResults = 6
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "YouTube scrape failed for ${genre.displayName}: ${e.message}")
                        synchronized(errors) { errors.add("YT ${genre.displayName}") }
                        emptyList()
                    }
                }
            }

            val fbJob = async {
                try {
                    scrapeFacebookAiVideos(maxResults = 8)
                } catch (e: Exception) {
                    Log.w(TAG, "Facebook scrape failed: ${e.message}")
                    synchronized(errors) { errors.add("Facebook") }
                    emptyList()
                }
            }

            ytJobs.awaitAll().forEach { collected.addAll(it) }
            collected.addAll(fbJob.await())
        }

        val deduplicated = collected
            .distinctBy { it.id }
            .sortedByDescending { it.ratingScore }

        val errorSummary = if (deduplicated.isEmpty() && errors.isNotEmpty()) {
            "Network unavailable or rate-limited while scraping (${errors.take(2).joinToString()})."
        } else {
            null
        }

        deduplicated to errorSummary
    }

    /**
     * Scrapes movies directly from a YouTube channel URL, @handle, channel ID (UC...), or channel name.
     */
    suspend fun scrapeYouTubeChannel(
        rawInput: String,
        preferredGenre: MovieGenre = MovieGenre.ALL
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
        val ogAvatar = extractMetaContent(html, "og:image").orEmpty()

        val channelIdFromHtml = extractYouTubeChannelId(html)
            ?: extractYouTubeChannelIdFromUrl(channelUrl)

        val channelName = ogTitle?.takeIf { it.isNotBlank() && !it.equals("YouTube", ignoreCase = true) }
            ?: cleaned.removePrefix("https://").removePrefix("http://")
                .substringAfter("youtube.com/")
                .substringBefore("/")
                .removePrefix("@")
                .ifBlank { cleaned }

        val collected = mutableListOf<AiMovieEntity>()

        // 1. Parse videos directly from the channel's /videos ytInitialData JSON
        val initialDataJson = extractBalancedJson(html, "var ytInitialData = ")
            ?: extractBalancedJson(html, "window[\"ytInitialData\"] = ")
        if (initialDataJson != null) {
            runCatching {
                val root = JSONObject(initialDataJson)
                val renderers = mutableListOf<JSONObject>()
                collectVideoRenderers(root, renderers, maxCount = 24)
                collected.addAll(
                    parseVideoRenderers(
                        renderers = renderers,
                        fallbackChannel = channelName,
                        preferredGenre = preferredGenre,
                        maxResults = 15,
                        allowShortClips = true
                    )
                )
            }.onFailure {
                Log.w(TAG, "Failed parsing channel ytInitialData: ${it.message}")
            }
        }

        // 2. Also fetch official YouTube public Atom XML RSS feed if we have the UC... channelId
        if (collected.size < 6 && !channelIdFromHtml.isNullOrBlank()) {
            val feedVideos = scrapeYouTubeChannelRss(
                channelId = channelIdFromHtml,
                fallbackChannelName = channelName,
                preferredGenre = preferredGenre
            )
            collected.addAll(feedVideos)
        }

        // 3. Fallback search by channel name / handle if HTML was consent-gated
        if (collected.isEmpty()) {
            val searchFallback = runCatching {
                scrapeYouTubeByQuery(
                    query = "$channelName AI film OR short film",
                    preferredGenre = preferredGenre,
                    maxResults = 10
                )
            }.getOrDefault(emptyList())
            collected.addAll(searchFallback)
        }

        val uniqueMovies = collected.distinctBy { it.id }.take(18)
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

        val note = if (uniqueMovies.isEmpty()) {
            "Channel saved, but no public videos could be fetched right now. Tap Sync later to retry."
        } else {
            null
        }

        Result.success(
            ChannelScrapeResult(
                channel = channelEntity,
                movies = uniqueMovies,
                note = note
            )
        )
    }

    /**
     * Scrapes public movies/videos from a Facebook profile or page URL / handle.
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
        val now = System.currentTimeMillis()

        // 1. Extract any public video/reel links directly present in the profile HTML
        val directVideoRegex = Regex(
            """https://(?:www\.|m\.)?facebook\.com/(?:watch/\?v=\d+|[^"'\\s<>]+/videos/\d+|reel/\d+)""",
            RegexOption.IGNORE_CASE
        )
        directVideoRegex.findAll(html).forEachIndexed { idx, match ->
            if (discovered.size >= 10) return@forEachIndexed
            val url = match.value
            if (!seenUrls.add(url)) return@forEachIndexed
            val fbVid = extractFacebookVideoId(url)
            val genre = if (preferredGenre != MovieGenre.ALL) preferredGenre else classifyGenre(profileName, ogDesc, MovieGenre.SCI_FI)
            discovered.add(
                AiMovieEntity(
                    id = "fb_${abs(url.hashCode())}",
                    title = "$profileName — Video #${idx + 1}",
                    director = profileName,
                    source = MovieSource.FACEBOOK.name,
                    videoId = fbVid,
                    watchUrl = url,
                    thumbnailUrl = ogAvatar,
                    genre = genre.name,
                    durationText = "04:00",
                    durationSeconds = 240,
                    viewCountText = "Facebook Profile",
                    synopsis = ogDesc.ifBlank { "Public video scraped from $profileName on Facebook." },
                    aiTools = detectAiTools("$profileName $ogDesc"),
                    publishedText = "Facebook",
                    ratingScore = 8.8f,
                    isFeatured = false,
                    isScrapedLive = true,
                    addedAt = now - (idx * 60L)
                )
            )
        }

        // 2. Discover public videos/reels published by this Facebook profile via web index search
        val queries = listOfNotNull(
            if (slug.isNotBlank() && !slug.startsWith("profile.php")) "site:facebook.com/$slug/videos" else null,
            if (slug.isNotBlank() && !slug.startsWith("profile.php")) "site:facebook.com/$slug video OR reel" else null,
            "site:facebook.com \"$profileName\" video OR reel OR watch"
        )

        for (query in queries) {
            if (discovered.size >= 12) break
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
                if (discovered.size >= 12) return@forEachIndexed
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
                        durationText = "04:30",
                        durationSeconds = 270,
                        viewCountText = "Facebook Video",
                        synopsis = snippet,
                        aiTools = detectAiTools("$rawTitle $snippet"),
                        publishedText = "Facebook",
                        ratingScore = 8.8f,
                        isFeatured = false,
                        isScrapedLive = true,
                        addedAt = now - (discovered.size * 50L)
                    )
                )
            }
        }

        // 3. Ensure at least a direct playable profile videos hub entry exists if Facebook gated listing behind login
        if (discovered.isEmpty()) {
            val videosTabUrl = if (profileUrl.contains("profile.php")) {
                "$profileUrl&sk=videos"
            } else {
                "${profileUrl.trimEnd('/')}/videos"
            }
            val genre = if (preferredGenre != MovieGenre.ALL) preferredGenre else classifyGenre(profileName, ogDesc, MovieGenre.SCI_FI)
            discovered.add(
                AiMovieEntity(
                    id = "fb_prof_feed_${abs(profileUrl.lowercase().hashCode())}",
                    title = "$profileName — Facebook Video Feed",
                    director = profileName,
                    source = MovieSource.FACEBOOK.name,
                    videoId = "",
                    watchUrl = videosTabUrl,
                    thumbnailUrl = ogAvatar,
                    genre = genre.name,
                    durationText = "Feed",
                    durationSeconds = 300,
                    viewCountText = "Facebook Profile",
                    synopsis = ogDesc.ifBlank {
                        "Direct Facebook videos feed for $profileName. Open inside the Web Player or Facebook app to browse and stream uploads."
                    },
                    aiTools = detectAiTools("$profileName $ogDesc"),
                    publishedText = "Followed Profile",
                    ratingScore = 8.7f,
                    isFeatured = false,
                    isScrapedLive = true,
                    addedAt = now
                )
            )
        }

        val uniqueMovies = discovered.distinctBy { it.id }
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

        Result.success(
            ChannelScrapeResult(
                channel = channelEntity,
                movies = uniqueMovies
            )
        )
    }

    suspend fun scrapeYouTubeByQuery(
        query: String,
        preferredGenre: MovieGenre,
        maxResults: Int = 8
    ): List<AiMovieEntity> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.youtube.com/results?search_query=$encoded"
        val html = fetchHtml(url)
        if (html.isBlank()) return@withContext emptyList()

        val initialDataJson = extractBalancedJson(html, "var ytInitialData = ")
            ?: extractBalancedJson(html, "window[\"ytInitialData\"] = ")
            ?: return@withContext emptyList()

        val root = JSONObject(initialDataJson)
        val renderers = mutableListOf<JSONObject>()
        collectVideoRenderers(root, renderers, maxCount = maxResults * 2)

        parseVideoRenderers(
            renderers = renderers,
            fallbackChannel = "Independent AI Studio",
            preferredGenre = preferredGenre,
            maxResults = maxResults,
            allowShortClips = false
        )
    }

    /**
     * Discovers public Facebook Watch / Video AI short films via public search listings.
     */
    suspend fun scrapeFacebookAiVideos(maxResults: Int = 8): List<AiMovieEntity> =
        withContext(Dispatchers.IO) {
            val queries = listOf(
                "site:facebook.com/watch AI short film movie",
                "site:facebook.com AI generated short film sci-fi horror video"
            )
            val found = mutableListOf<AiMovieEntity>()
            val seenUrls = mutableSetOf<String>()
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
                        ?: "AI-generated film discovered on Facebook Watch."

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
                            durationText = "04:30",
                            durationSeconds = 270,
                            viewCountText = "Facebook Watch",
                            synopsis = snippet,
                            aiTools = tools,
                            publishedText = "Facebook",
                            ratingScore = 8.7f,
                            isFeatured = false,
                            isScrapedLive = true,
                            addedAt = now - (idx * 75L)
                        )
                    )
                }
            }

            found
        }

    /**
     * Scrapes metadata (title, thumbnail, synopsis, genre) from any user-provided YouTube or Facebook video URL.
     */
    suspend fun scrapeSingleUrl(rawUrl: String): AiMovieEntity? = withContext(Dispatchers.IO) {
        val url = rawUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@withContext null

        val isYouTube = url.contains("youtube.com", ignoreCase = true) ||
            url.contains("youtu.be", ignoreCase = true)
        val isFacebook = url.contains("facebook.com", ignoreCase = true) ||
            url.contains("fb.watch", ignoreCase = true)
        if (!isYouTube && !isFacebook) return@withContext null

        val html = fetchHtml(url)

        val ogTitle = extractMetaContent(html, "og:title")
            ?: extractHtmlTitle(html)
            ?: if (isYouTube) "Imported YouTube AI Film" else "Imported Facebook AI Film"
        val ogDesc = extractMetaContent(html, "og:description")
            ?: "Imported AI movie scraped directly from ${if (isYouTube) "YouTube" else "Facebook"}."
        val ogImage = extractMetaContent(html, "og:image").orEmpty()

        val ytId = if (isYouTube) extractYouTubeVideoId(url) else ""
        val fbId = if (isFacebook) extractFacebookVideoId(url) else ""
        val finalThumb = when {
            ytId.length == 11 -> "https://i.ytimg.com/vi/$ytId/hqdefault.jpg"
            ogImage.startsWith("https://") -> ogImage
            else -> ""
        }

        val genre = classifyGenre(ogTitle, ogDesc, MovieGenre.SCI_FI)
        val tools = detectAiTools("$ogTitle $ogDesc")

        AiMovieEntity(
            id = if (ytId.isNotBlank()) "yt_$ytId" else "imp_${abs(url.hashCode())}",
            title = cleanTitle(
                ogTitle
                    .replace(" - YouTube", "", ignoreCase = true)
                    .replace(" | Facebook", "", ignoreCase = true)
            ),
            director = if (isYouTube) "YouTube Creator" else "Facebook Creator",
            source = if (isYouTube) MovieSource.YOUTUBE.name else MovieSource.FACEBOOK.name,
            videoId = if (isYouTube) ytId else fbId,
            watchUrl = if (ytId.length == 11) "https://www.youtube.com/watch?v=$ytId" else url,
            thumbnailUrl = finalThumb,
            genre = genre.name,
            durationText = "05:00",
            durationSeconds = 300,
            viewCountText = "Imported",
            synopsis = ogDesc.take(260),
            aiTools = tools,
            publishedText = "Just Scraped",
            ratingScore = 9.0f,
            isFeatured = false,
            isScrapedLive = true,
            addedAt = System.currentTimeMillis()
        )
    }

    private fun scrapeYouTubeChannelRss(
        channelId: String,
        fallbackChannelName: String,
        preferredGenre: MovieGenre
    ): List<AiMovieEntity> {
        val xml = fetchHtml("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId")
        if (xml.isBlank()) return emptyList()

        val entryRegex = Regex("<entry>(.*?)</entry>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val videoIdRegex = Regex("<yt:videoId>([^<]+)</yt:videoId>")
        val titleRegex = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
        val authorRegex = Regex("<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL)
        val descRegex = Regex("<media:description>(.*?)</media:description>", RegexOption.DOT_MATCHES_ALL)

        val now = System.currentTimeMillis()
        val list = mutableListOf<AiMovieEntity>()

        entryRegex.findAll(xml).forEachIndexed { idx, match ->
            if (list.size >= 15) return@forEachIndexed
            val block = match.groupValues[1]
            val vid = videoIdRegex.find(block)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (vid.length != 11) return@forEachIndexed

            val title = stripHtml(titleRegex.find(block)?.groupValues?.getOrNull(1).orEmpty())
            if (title.isBlank()) return@forEachIndexed

            val author = stripHtml(authorRegex.find(block)?.groupValues?.getOrNull(1).orEmpty())
                .ifBlank { fallbackChannelName }
            val desc = stripHtml(descRegex.find(block)?.groupValues?.getOrNull(1).orEmpty())
                .take(260)
                .ifBlank { "Uploaded by $author on YouTube." }

            val genre = if (preferredGenre != MovieGenre.ALL) {
                preferredGenre
            } else {
                classifyGenre(title, desc, MovieGenre.SCI_FI)
            }
            val tools = detectAiTools("$title $desc $author")

            list.add(
                AiMovieEntity(
                    id = "yt_$vid",
                    title = cleanTitle(title),
                    director = author,
                    source = MovieSource.YOUTUBE.name,
                    videoId = vid,
                    watchUrl = "https://www.youtube.com/watch?v=$vid",
                    thumbnailUrl = "https://i.ytimg.com/vi/$vid/hqdefault.jpg",
                    genre = genre.name,
                    durationText = "04:00",
                    durationSeconds = 240,
                    viewCountText = "Channel Upload",
                    synopsis = desc,
                    aiTools = tools,
                    publishedText = "YouTube Channel",
                    ratingScore = 9.0f,
                    isFeatured = false,
                    isScrapedLive = true,
                    addedAt = now - (idx * 60L)
                )
            )
        }
        return list
    }

    private fun parseVideoRenderers(
        renderers: List<JSONObject>,
        fallbackChannel: String,
        preferredGenre: MovieGenre,
        maxResults: Int,
        allowShortClips: Boolean
    ): List<AiMovieEntity> {
        val now = System.currentTimeMillis()
        val results = mutableListOf<AiMovieEntity>()

        for ((index, renderer) in renderers.withIndex()) {
            if (results.size >= maxResults) break
            val videoId = renderer.optString("videoId").trim()
            if (videoId.length != 11) continue

            val title = readTextField(renderer.optJSONObject("title")).trim()
            if (title.isBlank()) continue

            val channel = readTextField(renderer.optJSONObject("ownerText"))
                .ifBlank { readTextField(renderer.optJSONObject("longBylineText")) }
                .ifBlank { fallbackChannel }

            val durationText = readTextField(renderer.optJSONObject("lengthText"))
                .ifBlank { "04:00" }
            val durationSeconds = parseDurationToSeconds(durationText)
            if (!allowShortClips && durationSeconds < 30) continue

            val viewCountText = readTextField(renderer.optJSONObject("shortViewCountText"))
                .ifBlank { readTextField(renderer.optJSONObject("viewCountText")) }
                .ifBlank { "YouTube AI Film" }

            val publishedText = readTextField(renderer.optJSONObject("publishedTimeText"))
                .ifBlank { "YouTube" }

            val snippet = extractSnippetText(renderer)
                .ifBlank {
                    "AI-generated film from $channel on YouTube."
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
                    isFeatured = index == 0 && preferredGenre == MovieGenre.SCI_FI,
                    isScrapedLive = true,
                    addedAt = now - (index * 50L)
                )
            )
        }
        return results
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
        val parts = duration.trim().split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 240
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
        var score = 8.4f
        val lower = views.lowercase()
        if (lower.contains("m")) score += 0.8f
        else if (lower.contains("k")) score += 0.4f
        if (durationSeconds in 120..1200) score += 0.3f
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
            """<meta[^>]+(?:property|name)=["']$property["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        val direct = pattern.find(html)?.groupValues?.getOrNull(1)
        if (!direct.isNullOrBlank()) return stripHtml(direct)

        val reversed = Regex(
            """<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']$property["']""",
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
