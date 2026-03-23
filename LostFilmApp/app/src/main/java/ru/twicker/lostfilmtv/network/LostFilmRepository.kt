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
    val episodeInfo: String,  // «4 сезон 3 серия»
    val date: String,         // «22.03.2026»
    val detailsUrl: String,
    val imageUrl: String,
    val episodeCode: String   // «881-2-10» → used for /v_search.php?a=CODE
)

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



class LostFilmRepository {
    private val BASE_URL = "https://lostfilm.tv"

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
            if (nameRu.isBlank() && nameEn.isBlank()) continue
            episodes.add(Episode(
                title       = nameRu.ifBlank { nameEn },
                titleEn     = nameEn,
                episodeInfo = leftPart,
                date        = rightPart,
                detailsUrl  = href,
                imageUrl    = img,
                episodeCode = code
            ))
        }

        Log.d(TAG, "Parsed ${episodes.size} episodes from .row elements")
        episodes.take(3).forEach { Log.d(TAG, "  ep: ${it.title} img=${it.imageUrl} code=${it.episodeCode}") }
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
                
                Log.d(TAG, "Redirect HTML (${redirectHtml.length} chars): ${redirectHtml.take(500)}")
                return@withContext TorrentsResult.Success(parseTorrents(redirectHtml))
            }
            
            // No redirect — might be direct torrent page
            TorrentsResult.Success(parseTorrents(html))
        }

    private fun parseTorrents(html: String): List<TorrentEntry> {
        val doc = Jsoup.parse(html)
        val result = mutableListOf<TorrentEntry>()

        // Try primary structure: .inner-box--item or similar wrappers
        // Each torrent block contains a link + description with size
        for (link in doc.select("a[href*=tracktor], a[href*=td.php]")) {
            val url    = link.attr("href")
            val label  = link.text().trim()
            // Size is usually nearby in a desc element or title attr
            val desc   = link.parent()?.selectFirst(".desc, .inner-box--desc, .details")?.text()
                ?: link.attr("title")
                ?: ""
            val size   = Regex("Размер[:\\s]+([\\d.,]+\\s*(?:ГБ|МБ|GB|MB))", RegexOption.IGNORE_CASE)
                .find(desc)?.groupValues?.get(1) ?: ""
            val quality = when {
                "1080" in label -> "1080p"
                "720"  in label -> "720p"
                "480"  in label -> "480p"
                else            -> "SD"
            }
            if (url.isNotBlank()) result.add(TorrentEntry(quality, size, url))
        }

        // Fallback: look for any tracktor links at all
        if (result.isEmpty()) {
            for (link in doc.select("a[href]")) {
                val href = link.attr("href")
                if ("tracktor" in href || "td.php" in href) {
                    result.add(TorrentEntry(link.text().ifBlank { "Torrent" }, "", href))
                }
            }
        }
        Log.d(TAG, "Parsed ${result.size} torrent entries")
        return result
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
}
