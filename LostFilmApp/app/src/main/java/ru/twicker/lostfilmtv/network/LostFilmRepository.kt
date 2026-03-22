package ru.twicker.lostfilmtv.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

private const val TAG = "LFRepository"

data class Episode(
    val title: String,        // «Неуязвимый»
    val titleEn: String,      // «Invincible»
    val episodeInfo: String,  // «4 сезон 3 серия»
    val date: String,         // «22.03.2026»
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
            val nameRu   = row.selectFirst(".name-ru")?.text().orEmpty()
            val nameEn   = row.selectFirst(".name-en")?.text().orEmpty()
            val leftPart = row.selectFirst(".overlay .left-part")?.text().orEmpty()   // «4 сезон 3 серия»
            val rightPart = row.selectFirst(".overlay .right-part")?.text().orEmpty() // «22.03.2026»
            val img = normalizeImageUrl(
                row.selectFirst("img.thumb")?.attr("src")
                    ?: row.selectFirst("img")?.attr("src").orEmpty()
            )
            if (nameRu.isBlank() && nameEn.isBlank()) continue
            episodes.add(Episode(
                title       = nameRu.ifBlank { nameEn },
                titleEn     = nameEn,
                episodeInfo = leftPart,
                date        = rightPart,
                detailsUrl  = href,
                imageUrl    = img
            ))
        }

        Log.d(TAG, "Parsed ${episodes.size} episodes from .row elements")
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
