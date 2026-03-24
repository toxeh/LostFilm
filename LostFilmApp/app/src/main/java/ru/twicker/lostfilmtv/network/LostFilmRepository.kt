package ru.twicker.lostfilmtv.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.Jsoup

private const val TAG = "LFRepository"

data class Episode(
    val title: String,        // «Неуязвимый»
    val titleEn: String,      // «Invincible»
    var episodeInfo: String,  // «4 сезон 3 серия»
    var date: String,         // «22.03.2026»
    val detailsUrl: String,
    val imageUrl: String,
    val episodeCode: String,  // «881-2-10» → used for /v_search.php?a=CODE
    var isWatched: Boolean = false
) : java.io.Serializable

data class Series(
    val title: String,
    val titleRu: String,
    val link: String,
    val poster: String,
    val genres: String,
    val year: String
) : java.io.Serializable

data class TorrentEntry(
    val quality: String,   // «WEB-DLRip», «1080p WEB-DLRip», «720p WEB-DLRip»
    val sizeMb: String,    // «1.05 ГБ»
    val url: String        // «https://n.tracktor.site/td.php?s=...»
)

sealed class TorrentsResult {
    data class Success(val entries: List<TorrentEntry>) : TorrentsResult()
    object NeedAuth : TorrentsResult()
    data class Error(val message: String) : TorrentsResult()
}



data class SeriesDetails(
    val title: String,
    val titleRu: String,
    val description: String,
    val posterUrl: String,
    val seasons: Map<String, List<Episode>>,
    val isFavorite: Boolean = false,
    val seriesId: String = "",
    val sessionToken: String = ""
)

class LostFilmRepository {
    private val BASE_URL = "https://${NetworkClient.getHost()}"  // www.lostfilm.tv for API

    /**
     * Fetch user's favorite series from /series/?type=search&s=3&t=99
     * s=3 = "мои сериалы", t=99 = все типы
     */
    suspend fun getFavoriteSeries(client: OkHttpClient): List<Series> = withContext(Dispatchers.IO) {
        try {
            val allResults = mutableListOf<Series>()
            
            // Fetch both favorite series and favorite movies
            for ((urlPath, linkPrefix) in listOf("/series/" to "/series/", "/movies/" to "/movies/")) {
                val url = "https://${NetworkClient.getHost()}${urlPath}?type=search&s=3&t=99"
                Log.d(TAG, "getFavorites: fetching $url")
                
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", NetworkClient.UA)
                    .build()
                
                val response = try { client.newCall(request).execute() } catch (e: Exception) {
                    Log.e(TAG, "getFavorites failed for $urlPath", e)
                    continue
                }
                if (!response.isSuccessful) {
                    Log.e(TAG, "getFavorites HTTP ${response.code} for $urlPath")
                    continue
                }
                
                val html = response.body?.string() ?: continue
                Log.d(TAG, "getFavorites($urlPath): got ${html.length} chars")
                
                val doc = Jsoup.parse(html)
                
                val links = doc.select("a.no-decoration[href^=$linkPrefix]")
                Log.d(TAG, "getFavorites($urlPath): found ${links.size} links")
                
                val seen = mutableSetOf<String>()
                for (link in links) {
                    val href = link.attr("href")
                    val match = Regex("(?:/series/|/movies/)([^/]+)").find(href) ?: continue
                    val slug = match.groupValues[1]
                    if (slug in seen) continue
                    seen.add(slug)
                    
                    val img = link.selectFirst("img.thumb")
                    val posterSrc = img?.attr("src") ?: ""
                    val posterUrl = if (posterSrc.isNotBlank()) {
                        if (posterSrc.startsWith("//")) "https:$posterSrc"
                        else if (posterSrc.startsWith("/")) "https://${NetworkClient.getHost()}$posterSrc"
                        else posterSrc
                    } else ""
                    
                    val parent = link.parent()
                    val titleRu = parent?.selectFirst(".name-ru")?.text()?.trim() ?: ""
                    val titleEn = parent?.selectFirst(".name-en")?.text()?.trim() ?: ""
                    
                    if (titleRu.isBlank() && titleEn.isBlank()) continue
                    
                    allResults.add(Series(
                        title = if (titleEn.isNotBlank()) titleEn else slug.replace("_", " "),
                        titleRu = if (titleRu.isNotBlank()) titleRu else slug.replace("_", " "),
                        link = "$linkPrefix$slug/",
                        poster = posterUrl,
                        genres = "",
                        year = ""
                    ))
                }
            }
            
            Log.d(TAG, "getFavorites: total ${allResults.size} items")
            allResults
        } catch (e: Exception) {
            Log.e(TAG, "getFavorites failed", e)
            emptyList()
        }
    }

    suspend fun getSeriesDetails(client: OkHttpClient, seriesUrl: String): SeriesDetails? {
        // Normalize: strip domain to get just the path
        val path = seriesUrl
            .replace("https://www.lostfilm.tv", "")
            .replace("https://lostfilm.tv", "")
            .replace("http://lostfilm.tv", "")
            .replace("//lostfilm.tv", "")
        
        // Base series url without season_ or seasons/ or episode_
        val parts = path.split("/").filter { it.isNotBlank() }
        // parts: ["series", "The_Pitt", "season_2", "episode_11"] or ["series", "The_Pitt"]
        val baseSeriesPath = if (parts.size >= 2) "/${parts[0]}/${parts[1]}/" else path
        
        // Single request: fetch /seasons/ page which has both titles and episode list
        val seasonsUrl = "https://${NetworkClient.getHost()}${baseSeriesPath}seasons/"
        Log.d(TAG, "getSeriesDetails: input=$seriesUrl → seasonsUrl=$seasonsUrl")
        
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(seasonsUrl)
                .header("User-Agent", NetworkClient.UA)
                .build()
                
            val response = try {
                client.newCall(request).execute()
            } catch (e: Exception) {
                Log.e(TAG, "getSeriesDetails failed: $seasonsUrl", e)
                return@withContext null
            }
            
            if (!response.isSuccessful) {
                Log.e(TAG, "getSeriesDetails HTTP ${response.code}")
                return@withContext null
            }
            val html = response.body?.string() ?: return@withContext null
            Log.d(TAG, "getSeriesDetails: got ${html.length} chars")
            
            val doc = Jsoup.parse(html)
            
            // Extract title and description from the same page
            val titleRu = doc.selectFirst("h1.title-ru")?.text() ?: ""
            val titleEn = doc.selectFirst("h2.title-en")?.text() ?: ""
            // Try itemprop first, then meta description
            var description = doc.selectFirst("div[itemprop=description]")?.text()?.trim() ?: ""
            if (description.isBlank()) {
                description = doc.selectFirst("meta[name=description]")?.attr("content")?.trim() ?: ""
            }
            Log.d(TAG, "Parsed: titleRu=$titleRu, titleEn=$titleEn, desc=${description.take(50)}")
            
            val rows = doc.select("tr")
            val seasonMap = mutableMapOf<String, MutableList<Episode>>()
            var seriesId = ""
            // Map data-episode → Episode for watched matching
            val dataEpMap = mutableMapOf<String, Episode>()
            
            for (row in rows) {
                val btn = row.selectFirst(".haveseen-btn") ?: continue
                val gamma = row.selectFirst(".gamma") ?: continue
                val beta = row.selectFirst(".beta") ?: continue
                val delta = row.selectFirst(".delta") ?: continue
                
                val code = btn.attr("data-code") // e.g. "868-1-1"
                if (code.isBlank()) continue
                val dataEpisode = btn.attr("data-episode") // e.g. "1097001004"
                
                var detailsUrl = beta.attr("onClick")
                if (detailsUrl != null) {
                    detailsUrl = detailsUrl.substringAfter("goTo('").substringBefore("',")
                } else {
                    detailsUrl = ""
                }
                
                val episodeInfo = beta.text()
                
                val titleBlock = gamma.selectFirst("div")
                val title = titleBlock?.ownText() ?: gamma.ownText()
                val epTitleEn = gamma.selectFirst("span")?.text() ?: ""
                
                val date = delta.selectFirst("span")?.text() ?: ""
                
                val codeParts = code.split("-")
                if (codeParts.isNotEmpty()) seriesId = codeParts[0]
                // Individual episode image: e_{season}_{episode}.jpg
                val imageUrl = if (codeParts.size >= 3) {
                    "https://${NetworkClient.getImageHost()}/Static/Images/${codeParts[0]}/Posters/e_${codeParts[1]}_${codeParts[2]}.jpg"
                } else if (codeParts.size >= 2) {
                    "https://${NetworkClient.getImageHost()}/Static/Images/${codeParts[0]}/Posters/image_s${codeParts[1]}.jpg"
                } else ""
                
                val ep = Episode(
                    title = title,
                    titleEn = epTitleEn,
                    episodeInfo = episodeInfo,
                    date = date,
                    detailsUrl = detailsUrl,
                    imageUrl = imageUrl,
                    episodeCode = code,
                    isWatched = false // will be set from getmarks
                )
                
                if (dataEpisode.isNotBlank()) dataEpMap[dataEpisode] = ep
                val seasonName = if (codeParts.size >= 2) "${codeParts[1]} сезон" else "Сезон"
                seasonMap.getOrPut(seasonName) { mutableListOf() }.add(ep)
            }
            
            // Fetch watched marks via AJAX and update episodes
            if (seriesId.isNotBlank() && dataEpMap.isNotEmpty()) {
                val watchedIds = getWatchedMarks(client, seriesId)
                Log.d(TAG, "getSeriesDetails: getmarks returned ${watchedIds.size} watched IDs for series $seriesId")
                for (id in watchedIds) {
                    dataEpMap[id]?.isWatched = true
                }
            }
            Log.d(TAG, "getSeriesDetails: parsed ${seasonMap.values.sumOf { it.size }} episodes, ${dataEpMap.values.count { it.isWatched }} watched")
            
            // Extract seriesId from FollowSerial(id, ...) if not parsed from episodes (e.g. movies)
            val favBtn = doc.selectFirst(".favorites-btn, .favorites-btn2")
            val favOnClick = favBtn?.attr("onClick") ?: ""
            if (seriesId.isBlank() && favOnClick.contains("FollowSerial")) {
                seriesId = Regex("FollowSerial\\((\\d+)").find(favOnClick)?.groupValues?.get(1) ?: ""
            }
            
            // For movies: no episodes from <tr>, create a single entry from .isawthat-btn
            if (seasonMap.isEmpty()) {
                val isawthat = doc.selectFirst(".isawthat-btn")
                val dataEp = isawthat?.attr("data-episode") ?: ""
                // Construct detailsUrl from base path (e.g. /movies/Dune/)
                val movieDetailsUrl = baseSeriesPath
                
                // Use movie poster for the card image
                val moviePosterUrl = if (seriesId.isNotBlank()) {
                    "https://${NetworkClient.getImageHost()}/Static/Images/$seriesId/Posters/poster.jpg"
                } else ""
                
                val movieEp = Episode(
                    title = titleRu.ifBlank { titleEn },
                    titleEn = titleEn,
                    episodeInfo = "Фильм",
                    date = "",
                    detailsUrl = movieDetailsUrl,
                    imageUrl = moviePosterUrl,
                    episodeCode = if (seriesId.isNotBlank()) "$seriesId-1-1" else "",
                    isWatched = false
                )
                // Check watched status for the movie
                if (dataEp.isNotBlank() && seriesId.isNotBlank()) {
                    val watchedIds = getWatchedMarks(client, seriesId)
                    if (dataEp in watchedIds) movieEp.isWatched = true
                    dataEpMap[dataEp] = movieEp
                }
                seasonMap["Фильм"] = mutableListOf(movieEp)
                Log.d(TAG, "Created movie episode: ${movieEp.title} code=${movieEp.episodeCode} watched=${movieEp.isWatched}")
            }
            
            // Parse poster from itemprop=image (e.g. /Static/Images/1097/Posters/poster.jpg)
            var posterUrl = doc.selectFirst("img[itemprop=image]")?.attr("src")?.let { src ->
                if (src.startsWith("http")) src else "https://${NetworkClient.getHost()}$src"
            } ?: ""
            // Fallback to constructed URL from seriesId
            if (posterUrl.isBlank() && seriesId.isNotBlank()) {
                posterUrl = "https://${NetworkClient.getImageHost()}/Static/Images/$seriesId/Posters/poster.jpg"
            }
            
            // FollowSerial(id, true) means it IS favorited; FollowSerial(id, false) means NOT
            // CSS class approach doesn't work because it's set by JS on the client
            val isFavorite = favOnClick.contains(Regex("FollowSerial\\(\\d+,\\s*true"))
            
            // Parse session token from inline JS: UserData.session = '...'
            val sessionToken = Regex("UserData\\.session\\s*=\\s*['\"]([^'\"]+)").find(html)?.groupValues?.get(1) ?: ""
            
            Log.d(TAG, "posterUrl=$posterUrl seriesId=$seriesId isFavorite=$isFavorite session=${sessionToken.take(8)}")
            
            SeriesDetails(
                title = titleEn,
                titleRu = titleRu,
                description = description,
                posterUrl = posterUrl,
                seasons = seasonMap,
                isFavorite = isFavorite,
                seriesId = seriesId,
                sessionToken = sessionToken
            )
        }
    }

    suspend fun getRecentEpisodes(client: OkHttpClient = NetworkClient.createProxiedClient()): List<Episode> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Loading recent episodes from $BASE_URL/new")
        
        val request = Request.Builder()
            .url("$BASE_URL/new")
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            Log.e(TAG, "Failed: ${e.javaClass.simpleName}: ${e.message}", e)
            throw e
        }
        
        Log.d(TAG, "Response: ${response.code}, length=${response.header("Content-Length")}")
        
        if (!response.isSuccessful) {
            Log.e(TAG, "HTTP error ${response.code}")
            throw Exception("HTTP ${response.code}")
        }

        val html = response.body?.string()
        Log.d(TAG, "HTML body: ${html?.length ?: 0} chars")
        
        if (html.isNullOrEmpty()) return@withContext emptyList()
        
        val document = Jsoup.parse(html)
        val episodes = mutableListOf<Episode>()

        // lostfilm.tv /new page structure: div.row > a[href] > div.picture-box > img.thumb
        //                                               > div.body > .name-ru, .name-en
        //                                                          > .details-pane .alpha (episode name)
        //              div.overlay .left-part = "4 сезон 3 серия"
        //              div.overlay .right-part = «22.03.2026»
        val rows = document.select("div.row")
        Log.d(TAG, "Found ${rows.size} .row elements")

        for (row in rows) {
            val link = row.selectFirst("a[href]") ?: continue
            val href = link.attr("href").let {
                if (it.startsWith("http")) it else "$BASE_URL$it"
            }
            val nameRu    = row.selectFirst(".name-ru")?.text().orEmpty()
            val nameEn    = row.selectFirst(".name-en")?.text().orEmpty()
            val leftPart  = row.selectFirst(".overlay .left-part")?.text().orEmpty()
            val rightPart = row.selectFirst(".overlay .right-part")?.text().orEmpty()
            val img = normalizeImageUrl(
                row.selectFirst("img.thumb")?.attr("src")
                    ?: row.selectFirst("img")?.attr("src").orEmpty()
            )
            // data-code="881-2-10" from markEpisodeAsWatched element, or derive from href
            val code = row.selectFirst("[data-code]")?.attr("data-code")
                ?: extractCodeFromUrl(href)
            val isWatched = row.selectFirst(".haveseen-btn")?.hasClass("checked") ?: false
            if (nameRu.isBlank() && nameEn.isBlank()) continue
            episodes.add(Episode(
                title       = nameRu.ifBlank { nameEn },
                titleEn     = nameEn,
                episodeInfo = leftPart,
                date        = rightPart,
                detailsUrl  = href,
                imageUrl    = img,
                episodeCode = code,
                isWatched   = isWatched
            ))
        }

        Log.d(TAG, "Parsed ${episodes.size} episodes from .row elements")
        episodes.take(3).forEach { Log.d(TAG, "  ep: ${it.title} img=${it.imageUrl} code=${it.episodeCode} watched=${it.isWatched}") }
        episodes
    }

    /**
     * Fetch torrent options. Returns:
     *   TorrentsResult.Success — list (may be empty if no rows parsed)
     *   TorrentsResult.NeedAuth — server returned a login page
     *   TorrentsResult.Error — network/other failure
     */
    suspend fun getTorrents(client: OkHttpClient, episodeCode: String): TorrentsResult =
        withContext(Dispatchers.IO) {
            if (episodeCode.isBlank()) return@withContext TorrentsResult.Error("Код серии не найден")

            // Convert code: "972-2-11" → "972002011" (showId + 3-digit season + 3-digit episode)
            val parts = episodeCode.split("-")
            val vsearchCode = if (parts.size == 3) {
                "${parts[0]}${parts[1].padStart(3, '0')}${parts[2].padStart(3, '0')}"
            } else episodeCode
            
            val url = "$BASE_URL/v_search.php?a=$vsearchCode"
            Log.d(TAG, "Fetching torrents: $url (from $episodeCode)")
            
            val request = Request.Builder()
                .url(url)
                .header("Referer", "$BASE_URL/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "text/html,application/xhtml+xml")
                .build()
            val html = try {
                val resp = client.newCall(request).execute()
                Log.d(TAG, "v_search response: ${resp.code}, url=${resp.request.url}")
                resp.body?.string()
                    ?: return@withContext TorrentsResult.Error("Пустой ответ")
            } catch (e: Exception) {
                Log.e(TAG, "getTorrents failed: ${e.message}")
                return@withContext TorrentsResult.Error(e.message ?: "Ошибка сети")
            }
            
            Log.d(TAG, "v_search HTML (${html.length} chars): ${html.take(500)}")
            
            // Check for actual login page (not JS redirect)
            if (html.contains("Войдите или зарегистрируйтесь")) {
                Log.w(TAG, "getTorrents: login page returned")
                return@withContext TorrentsResult.NeedAuth
            }
            
            // v_search.php normally returns JS redirect: location.replace('/V/...')
            // This is the NORMAL flow — extract URL and follow it
            val redirectUrl = Regex("""location\.replace\(['"](.+?)['"]\)""")
                .find(html)?.groupValues?.get(1)
            
            if (redirectUrl != null) {
                val fullUrl = if (redirectUrl.startsWith("http")) redirectUrl
                              else "$BASE_URL$redirectUrl"
                Log.d(TAG, "Following JS redirect to: $fullUrl")
                
                val redirectReq = Request.Builder()
                    .url(fullUrl)
                    .header("Referer", url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .build()
                val redirectHtml = try {
                    val resp2 = client.newCall(redirectReq).execute()
                    Log.d(TAG, "Redirect response: ${resp2.code}, url=${resp2.request.url}")
                    resp2.body?.string()
                        ?: return@withContext TorrentsResult.Error("Пустой ответ после редиректа")
                } catch (e: Exception) {
                    Log.e(TAG, "Redirect failed: ${e.message}")
                    return@withContext TorrentsResult.Error(e.message ?: "Ошибка сети")
                }
                
                Log.d(TAG, "Redirect HTML (${redirectHtml.length} chars): ${redirectHtml.take(3000)}")
                return@withContext TorrentsResult.Success(parseTorrents(redirectHtml))
            }
            
            // No redirect — might be direct torrent page
            TorrentsResult.Success(parseTorrents(html))
        }

    private fun parseTorrents(html: String): List<TorrentEntry> {
        val doc = Jsoup.parse(html)
        val result = mutableListOf<TorrentEntry>()

        // Primary: parse .inner-box--item blocks (LostFilm /V/ page structure)
        for (item in doc.select(".inner-box--item")) {
            val link = item.selectFirst(".inner-box--link a[href]") ?: continue
            val url = link.attr("href")
            if (url.isBlank()) continue
            
            val subtitle = item.selectFirst(".inner-box--subtitle")?.text()?.trim() ?: ""
            val desc = item.selectFirst(".inner-box--desc")?.text()?.trim() ?: ""
            
            val quality = when {
                "1080" in subtitle -> "1080p"
                "720" in subtitle  -> "720p"
                "480" in subtitle  -> "480p"
                "MP4" in subtitle  -> "SD"
                "1080" in desc     -> "1080p"
                "720" in desc      -> "720p"
                else               -> subtitle.ifBlank { "SD" }
            }
            
            val size = Regex("""(\d[\d.,]+\s*(?:ГБ|МБ|GB|MB))""", RegexOption.IGNORE_CASE)
                .find(desc)?.groupValues?.get(1) ?: ""
            
            result.add(TorrentEntry(quality, size, url))
        }

        // Fallback: unique td.php/tracktor links
        if (result.isEmpty()) {
            val seen = mutableSetOf<String>()
            for (link in doc.select("a[href*=tracktor], a[href*=td.php]")) {
                val url = link.attr("href")
                if (url.isBlank() || url in seen) continue
                seen.add(url)
                val ctx = link.parent()?.text() ?: link.text()
                val quality = when {
                    "1080" in ctx -> "1080p"
                    "720" in ctx  -> "720p"
                    "480" in ctx  -> "480p"
                    else          -> link.text().ifBlank { "Torrent" }
                }
                result.add(TorrentEntry(quality, "", url))
            }
        }
        
        Log.d(TAG, "Parsed ${result.size} torrent entries: ${result.map { "${it.quality} ${it.sizeMb}" }}")
        return result
    }

    /**
     * Loads the episode information from its details page.
     * Uses the episode's detailsUrl (e.g. /series/The_Pitt/season_2/episode_11/).
     * Falls back to v_search.php if detailsUrl is empty.
     */
    suspend fun getEpisodeInfo(client: okhttp3.OkHttpClient, episodeCode: String, detailsUrl: String = ""): EpisodeInfo? = withContext(Dispatchers.IO) {
        try {
            // Try loading from episode page first (detailsUrl)
            if (detailsUrl.isNotBlank()) {
                val pageUrl = if (detailsUrl.startsWith("http")) detailsUrl
                              else "https://${NetworkClient.getHost()}$detailsUrl"
                val request = okhttp3.Request.Builder()
                    .url(pageUrl)
                    .header("User-Agent", NetworkClient.UA)
                    .build()
                
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    val document = org.jsoup.Jsoup.parse(html)
                    
                    // Try text-block description first (some pages have it)
                    var desc = document.selectFirst("div.text-block.description > div.body > div.body")?.text()?.trim()
                    
                    // Fall back to meta description
                    if (desc.isNullOrBlank()) {
                        desc = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
                    }
                    
                    // Also try itemprop=description
                    if (desc.isNullOrBlank()) {
                        desc = document.selectFirst("div[itemprop=description]")?.text()?.trim()
                    }
                    
                    val runtime = document.selectFirst("div.details-pane > div:nth-child(2)")?.text()?.trim()
                    
                    if (!desc.isNullOrBlank()) {
                        // Parse data-episode value (e.g. "972002011") for watched API
                        val dataEpisode = document.selectFirst(".isawthat-btn[data-episode]")?.attr("data-episode") ?: ""
                        
                        // Parse UserData.session from inline JS
                        val sessionToken = Regex("UserData\\.session\\s*=\\s*['\"]([^'\"]+)").find(html)?.groupValues?.get(1) ?: ""
                        
                        return@withContext EpisodeInfo(description = desc, runtime = runtime, dataEpisode = dataEpisode, sessionToken = sessionToken)
                    }
                }
            }
            
            // Fallback: try v_search.php
            val url = "https://${NetworkClient.getHost()}/v_search.php?a=$episodeCode"
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", NetworkClient.UA)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val html = response.body?.string() ?: return@withContext null
            val document = org.jsoup.Jsoup.parse(html)
            
            var desc = document.selectFirst("div.text-block.description > div.body > div.body")?.text()?.trim()
            if (desc.isNullOrBlank()) {
                desc = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            }
            val runtime = document.selectFirst("div.details-pane > div:nth-child(2)")?.text()?.trim()
            
            return@withContext EpisodeInfo(description = desc, runtime = runtime)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load episode info", e)
            return@withContext null
        }
    }
    
    data class EpisodeInfo(
        val description: String?,
        val runtime: String?,
        val dataEpisode: String = "",
        val sessionToken: String = ""
    )

    /** Fetch list of watched episode data-episode IDs via ajaxik.php.
     *  Response: {"data":["1097001004","1097001001"]} */
    private suspend fun getWatchedMarks(client: okhttp3.OkHttpClient, seriesId: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val url = "https://${NetworkClient.getHost()}/ajaxik.php"
            val body = okhttp3.FormBody.Builder()
                .add("act", "serial")
                .add("type", "getmarks")
                .add("id", seriesId)
                .build()
            val request = okhttp3.Request.Builder()
                .url(url)
                .post(body)
                .header("User-Agent", NetworkClient.UA)
                .header("Referer", "https://${NetworkClient.getHost()}/")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d(TAG, "getWatchedMarks(series=$seriesId) → HTTP ${response.code} body=${responseBody.take(200)}")
            if (!response.isSuccessful) return@withContext emptyList()
            // Parse {"data":["1097001004","1097001001"]}
            val jsonObj = org.json.JSONObject(responseBody)
            val dataArr = jsonObj.optJSONArray("data") ?: return@withContext emptyList()
            (0 until dataArr.length()).map { dataArr.getString(it) }
        } catch (e: Exception) {
            Log.e(TAG, "getWatchedMarks failed", e)
            emptyList()
        }
    }

    /** Set watched status via ajaxik.php */
    suspend fun setWatched(client: okhttp3.OkHttpClient, dataEpisode: String, sessionToken: String, watched: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "https://${NetworkClient.getHost()}/ajaxik.php"
            
            val bodyBuilder = okhttp3.FormBody.Builder()
                .add("act", "serial")
                .add("type", "markepisode")
                .add("val", dataEpisode)
                .add("auto", "0")
                .add("mode", if (watched) "on" else "off")
            
            if (sessionToken.isNotBlank()) {
                bodyBuilder.add("session", sessionToken)
            }
            
            val body = bodyBuilder.build()

            val request = okhttp3.Request.Builder()
                .url(url)
                .post(body)
                .header("User-Agent", NetworkClient.UA)
                .header("Referer", "https://${NetworkClient.getHost()}/")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            val ok = response.isSuccessful
            Log.d(TAG, "setWatched(dataEp=$dataEpisode, watched=$watched) → HTTP ${response.code} body=$responseBody")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set watched status", e)
            false
        }
    }

    /**
     * Toggle favorite status via ajaxik.php.
     * LostFilm API uses act=serial&type=follow&id=X as a TOGGLE.
     * Response: {"result":"on"} or {"result":"off"}
     * Returns the new state: true=now favorite, false=now not favorite, null=error
     */
    suspend fun toggleFavorite(client: okhttp3.OkHttpClient, seriesId: String, sessionToken: String): Boolean? = withContext(Dispatchers.IO) {
        try {
            val url = "https://${NetworkClient.getHost()}/ajaxik.php"
            
            val bodyBuilder = okhttp3.FormBody.Builder()
                .add("act", "serial")
                .add("type", "follow")
                .add("id", seriesId)
            
            if (sessionToken.isNotBlank()) {
                bodyBuilder.add("session", sessionToken)
            }
            
            val body = bodyBuilder.build()

            val request = okhttp3.Request.Builder()
                .url(url)
                .post(body)
                .header("User-Agent", NetworkClient.UA)
                .header("Referer", "https://${NetworkClient.getHost()}/")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d(TAG, "toggleFavorite(id=$seriesId) → HTTP ${response.code} body=$responseBody")
            
            if (!response.isSuccessful) return@withContext null
            
            // Parse result: {"result":"on"} or {"result":"off"}
            val json = org.json.JSONObject(responseBody)
            val result = json.optString("result", "")
            when (result) {
                "on" -> true
                "off" -> false
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle favorite", e)
            null
        }
    }

    /** Extract series code from URL like /series/Landman/season_2/episode_10/ → empty (code unknown from URL alone) */
    private fun extractCodeFromUrl(url: String): String = ""

    /**
     * Normalize image URLs from lostfilm.tv.
     * Raw HTML may contain:
     *   "//static/Images/..."          → needs proto + proper host
     *   "/Images/..."                  → relative to base
     *   "https://static.lostfilm.tv/.." → already good
     */
    private fun normalizeImageUrl(raw: String): String {
        if (raw.isBlank()) return ""
        return when {
            raw.startsWith("//")         -> "https:$raw"
            raw.startsWith("/")          -> "$BASE_URL$raw"
            raw.startsWith("http")       -> raw
            else                         -> raw
        }
    }

    /**
     * Search series by name via ajaxik.php API.
     * Returns list of Series matching the query.
     */
    suspend fun searchSeries(client: OkHttpClient, query: String): List<Series> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        Log.d(TAG, "searchSeries: query=$query")

        val body = okhttp3.FormBody.Builder()
            .add("act", "common")
            .add("type", "search")
            .add("val", query)
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/ajaxik.php")
            .post(body)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "$BASE_URL/")
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            Log.e(TAG, "searchSeries failed", e)
            return@withContext emptyList()
        }

        if (!response.isSuccessful) {
            Log.e(TAG, "searchSeries HTTP ${response.code}")
            return@withContext emptyList()
        }

        val json = response.body?.string() ?: return@withContext emptyList()
        Log.d(TAG, "searchSeries response: ${json.take(200)}")

        try {
            val root = org.json.JSONObject(json)
            val data = root.optJSONObject("data") ?: return@withContext emptyList()
            val seriesArr = data.optJSONArray("series") ?: return@withContext emptyList()

            val results = mutableListOf<Series>()
            for (i in 0 until seriesArr.length()) {
                val s = seriesArr.getJSONObject(i)
                val icon = s.optString("icon", "")
                val posterUrl = if (icon.isNotBlank()) "https://${NetworkClient.getHost()}$icon" else ""
                results.add(Series(
                    title = s.optString("title_orig", ""),
                    titleRu = s.optString("title", ""),
                    link = s.optString("link", ""),
                    poster = posterUrl,
                    genres = "",
                    year = ""
                ))
            }
            Log.d(TAG, "searchSeries: found ${results.size} series")
            results
        } catch (e: Exception) {
            Log.e(TAG, "searchSeries parse error", e)
            emptyList()
        }
    }
}
