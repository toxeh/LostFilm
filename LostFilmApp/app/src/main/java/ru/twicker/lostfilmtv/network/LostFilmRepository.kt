package ru.twicker.lostfilmtv.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

private const val TAG = "LFRepository"

data class Episode(
    val title: String,
    val detailsUrl: String,
    val imageUrl: String
)

class LostFilmRepository {
    private val BASE_URL = "https://www.lostfilm.tv"

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
        
        // Try various selectors lostfilm.tv may use
        val rows = document.select(".row-simple")
        Log.d(TAG, "Found ${rows.size} .row-simple elements")
        
        if (rows.isNotEmpty()) {
            for (row in rows) {
                val titleEl = row.selectFirst(".name-ru")
                val linkEl = row.selectFirst("a")
                val imgEl = row.selectFirst("img")
                if (titleEl != null && linkEl != null) {
                    val title = titleEl.text()
                    var link = linkEl.attr("href")
                    if (!link.startsWith("http")) link = "$BASE_URL$link"
                    var img = imgEl?.attr("src") ?: imgEl?.attr("data-src") ?: ""
                    img = normalizeImageUrl(img)
                    episodes.add(Episode(title, link, img))
                }
            }
        } else {
            // Fallback: parse any links to series
            val allLinks = document.select("a[href*=/series/]")
            Log.d(TAG, "Fallback: ${allLinks.size} links to /series/")
            
            for (link in allLinks) {
                val title = link.selectFirst(".name-ru")?.text()
                    ?: link.selectFirst(".title-ru")?.text()
                    ?: link.text()
                if (title.isBlank()) continue
                
                var href = link.attr("href")
                if (!href.startsWith("http")) href = "$BASE_URL$href"
                
                val imgEl = link.selectFirst("img") ?: link.parent()?.selectFirst("img")
                var img = imgEl?.attr("src") ?: imgEl?.attr("data-src") ?: ""
                img = normalizeImageUrl(img)
                
                episodes.add(Episode(title, href, img))
            }
        }
        
        Log.d(TAG, "Total episodes: ${episodes.size}")
        episodes
    }

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
            // Protocol-relative with wrong host like "//static/Images"
            raw.startsWith("//static/") -> "https://static.lostfilm.tv/" + raw.removePrefix("//static/")
            // Protocol-relative with full host like "//static.lostfilm.tv/..."
            raw.startsWith("//") -> "https:$raw"
            // Root-relative
            raw.startsWith("/") -> "https://static.lostfilm.tv$raw"
            // Already absolute
            raw.startsWith("http") -> raw
            else -> raw
        }
    }
}
