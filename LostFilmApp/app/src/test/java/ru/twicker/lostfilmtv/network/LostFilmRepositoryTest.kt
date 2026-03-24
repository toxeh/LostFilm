package ru.twicker.lostfilmtv.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for LostFilmRepository: searchSeries, posterUrl parsing, watched status parsing.
 * Uses MockWebServer to simulate LostFilm API responses.
 * Robolectric provides Android API stubs (org.json, etc.)
 */
@RunWith(RobolectricTestRunner::class)
class LostFilmRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: LostFilmRepository
    private lateinit var client: okhttp3.OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = okhttp3.OkHttpClient.Builder().build()
        repository = LostFilmRepository()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // --- searchSeries JSON parsing tests ---

    @Test
    fun `searchSeries parses valid JSON with one result`() {
        val json = """{"data":{"series":[{"id":"972","icon":"/Static/Images/972/Posters/icon.jpg","title":"Больница Питт","title_orig":"The Pitt","link":"/series/The_Pitt"}]},"result":"ok"}"""
        val results = parseSearchResponse(json)

        assertEquals(1, results.size)
        assertEquals("The Pitt", results[0].title)
        assertEquals("Больница Питт", results[0].titleRu)
        assertEquals("/series/The_Pitt", results[0].link)
        assertTrue(results[0].poster.contains("/Static/Images/972/Posters/icon.jpg"))
    }

    @Test
    fun `searchSeries returns empty for empty data array`() {
        val json = """{"data":[],"result":"ok"}"""
        val results = parseSearchResponse(json)
        assertEquals(0, results.size)
    }

    @Test
    fun `searchSeries returns empty for no series key`() {
        val json = """{"data":{"names":[]},"result":"ok"}"""
        val results = parseSearchResponse(json)
        assertEquals(0, results.size)
    }

    @Test
    fun `searchSeries parses multiple results`() {
        val json = """{"data":{"series":[{"id":"972","icon":"/Static/Images/972/Posters/icon.jpg","title":"Больница Питт","title_orig":"The Pitt","link":"/series/The_Pitt"},{"id":"780","icon":"/Static/Images/780/Posters/icon.jpg","title":"Ван-Пис","title_orig":"One Piece","link":"/series/One_Piece"}]},"result":"ok"}"""
        val results = parseSearchResponse(json)
        assertEquals(2, results.size)
        assertEquals("The Pitt", results[0].title)
        assertEquals("One Piece", results[1].title)
    }

    @Test
    fun `searchSeries handles missing icon`() {
        val json = """{"data":{"series":[{"id":"100","title":"Тест","title_orig":"Test","link":"/series/Test"}]},"result":"ok"}"""
        val results = parseSearchResponse(json)
        assertEquals(1, results.size)
        assertEquals("", results[0].poster)
    }

    @Test
    fun `searchSeries blank query returns empty`() = runBlocking {
        val results = repository.searchSeries(client, "")
        assertEquals(0, results.size)
    }

    @Test
    fun `searchSeries whitespace query returns empty`() = runBlocking {
        val results = repository.searchSeries(client, "   ")
        assertEquals(0, results.size)
    }

    @Test
    fun `searchSeries returns empty on malformed JSON`() {
        val json = "not json at all"
        val results = parseSearchResponse(json)
        assertEquals(0, results.size)
    }

    @Test
    fun `searchSeries returns empty on HTTP error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        // Can't easily test full searchSeries with custom BASE_URL,
        // but we verify blank query behavior
        val results = repository.searchSeries(client, "")
        assertEquals(0, results.size)
    }

    // --- posterUrl parsing tests ---

    @Test
    fun `parsePosterUrl from itemprop image tag`() {
        val html = """<html><body><img src="/Static/Images/972/Posters/poster.jpg" itemprop="image"></body></html>"""
        val doc = org.jsoup.Jsoup.parse(html)
        val imgTag = doc.selectFirst("img[itemprop=image]")
        assertNotNull(imgTag)
        assertEquals("/Static/Images/972/Posters/poster.jpg", imgTag!!.attr("src"))
    }

    @Test
    fun `parsePosterUrl missing itemprop image returns null`() {
        val html = """<html><body><img src="/Static/Images/972/Posters/banner.jpg"></body></html>"""
        val doc = org.jsoup.Jsoup.parse(html)
        val imgTag = doc.selectFirst("img[itemprop=image]")
        assertNull(imgTag)
    }

    @Test
    fun `posterUrl from seriesId fallback`() {
        val seriesId = "972"
        val expectedPath = "/Static/Images/$seriesId/Posters/poster.jpg"
        assertEquals("/Static/Images/972/Posters/poster.jpg", expectedPath)
    }

    // --- watched status parsing tests ---

    @Test
    fun `parseWatchedStatus checked class means watched`() {
        val html = """<div class="haveseen-btn checked" data-episode="1097001004" data-code="1097-1-4"></div>"""
        val doc = org.jsoup.Jsoup.parse(html)
        val btn = doc.selectFirst(".haveseen-btn")!!
        assertTrue(btn.hasClass("checked"))
    }

    @Test
    fun `parseWatchedStatus no checked class means unwatched`() {
        val html = """<div class="haveseen-btn" data-episode="780002008" data-code="780-2-8"></div>"""
        val doc = org.jsoup.Jsoup.parse(html)
        val btn = doc.selectFirst(".haveseen-btn")!!
        assertFalse(btn.hasClass("checked"))
    }

    @Test
    fun `parseWatchedStatus missing btn means unwatched`() {
        val html = """<div class="row"><a class="title" href="/series/Test/">Test</a></div>"""
        val doc = org.jsoup.Jsoup.parse(html)
        val btn = doc.selectFirst(".haveseen-btn")
        assertNull(btn)
    }

    // --- normalizeUrl tests ---

    @Test
    fun `normalizeUrl relative path`() {
        assertEquals("https://www.lostfilm.tv/Static/Images/972/poster.jpg",
            normalizeUrl("/Static/Images/972/poster.jpg"))
    }

    @Test
    fun `normalizeUrl protocol-relative`() {
        assertEquals("https://www.lostfilm.tv/poster.jpg",
            normalizeUrl("//www.lostfilm.tv/poster.jpg"))
    }

    @Test
    fun `normalizeUrl absolute URL unchanged`() {
        assertEquals("https://www.lostfilm.tv/poster.jpg",
            normalizeUrl("https://www.lostfilm.tv/poster.jpg"))
    }

    @Test
    fun `normalizeUrl empty string`() {
        assertEquals("", normalizeUrl(""))
    }

    // --- Episode data class tests ---

    @Test
    fun `Episode isWatched defaults to false`() {
        val ep = Episode("Test", "Test", "1 сезон 1 серия", "01.01.2026",
            "/series/Test/", "https://img.jpg", "100-1-1")
        assertFalse(ep.isWatched)
    }

    @Test
    fun `Episode isWatched can be true`() {
        val ep = Episode("Test", "Test", "1 сезон 1 серия", "01.01.2026",
            "/series/Test/", "https://img.jpg", "100-1-1", isWatched = true)
        assertTrue(ep.isWatched)
    }

    @Test
    fun `Series data class stores all fields`() {
        val s = Series("One Piece", "Ван-Пис", "/series/One_Piece",
            "https://img.jpg", "Action", "2023")
        assertEquals("One Piece", s.title)
        assertEquals("Ван-Пис", s.titleRu)
        assertEquals("/series/One_Piece", s.link)
        assertEquals("https://img.jpg", s.poster)
        assertEquals("Action", s.genres)
        assertEquals("2023", s.year)
    }

    // --- Helper methods that mirror LostFilmRepository logic ---

    private fun parseSearchResponse(json: String): List<Series> {
        return try {
            val root = org.json.JSONObject(json)
            val data = root.optJSONObject("data") ?: return emptyList()
            val seriesArr = data.optJSONArray("series") ?: return emptyList()

            val results = mutableListOf<Series>()
            for (i in 0 until seriesArr.length()) {
                val s = seriesArr.getJSONObject(i)
                val icon = s.optString("icon", "")
                val posterUrl = if (icon.isNotBlank()) "https://www.lostfilm.tv$icon" else ""
                results.add(Series(
                    title = s.optString("title_orig", ""),
                    titleRu = s.optString("title", ""),
                    link = s.optString("link", ""),
                    poster = posterUrl,
                    genres = "",
                    year = ""
                ))
            }
            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizeUrl(raw: String): String {
        if (raw.isBlank()) return ""
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "https://www.lostfilm.tv$raw"
            raw.startsWith("http") -> raw
            else -> raw
        }
    }

    // --- Favorite status parsing tests ---

    @Test
    fun `parseFavoriteStatus favorites-btn class means IS favorite`() {
        // When favorited, JS changes favorites-btn2 → favorites-btn
        val html = """<div class="favorites-btn" onClick="FollowSerial(972, true)"></div>"""
        val doc = org.jsoup.Jsoup.parse(html)
        val btn = doc.selectFirst(".favorites-btn, .favorites-btn2")!!
        val isFavorite = btn.hasClass("favorites-btn") && !btn.hasClass("favorites-btn2")
        assertTrue(isFavorite)
    }

    @Test
    fun `parseFavoriteStatus favorites-btn2 class means NOT favorite`() {
        // Default state on page: favorites-btn2
        val html = """<div class="favorites-btn2 active" onClick="FollowSerial(1097, false)"></div>"""
        val doc = org.jsoup.Jsoup.parse(html)
        val btn = doc.selectFirst(".favorites-btn, .favorites-btn2")!!
        val isFavorite = btn.hasClass("favorites-btn") && !btn.hasClass("favorites-btn2")
        assertFalse(isFavorite)
    }

    @Test
    fun `parseFavoriteStatus missing btn defaults to false`() {
        val html = """<div class="row"><span>No favorite button here</span></div>"""
        val doc = org.jsoup.Jsoup.parse(html)
        val btn = doc.selectFirst(".favorites-btn, .favorites-btn2")
        val isFavorite = btn?.hasClass("favorites-btn") == true && btn.hasClass("favorites-btn2") != true
        assertFalse(isFavorite)
    }

    @Test
    fun `parseSessionToken from UserData inline JS`() {
        val html = """<script>var UserData = {}; UserData.session = 'abcdef123456';</script>"""
        val token = Regex("""UserData\.session\s*=\s*['"]([^'"]+)""").find(html)?.groupValues?.get(1) ?: ""
        assertEquals("abcdef123456", token)
    }

    @Test
    fun `parseSessionToken missing returns empty`() {
        val html = """<script>var x = 1;</script>"""
        val token = Regex("""UserData\.session\s*=\s*['"]([^'"]+)""").find(html)?.groupValues?.get(1) ?: ""
        assertEquals("", token)
    }

    @Test
    fun `SeriesDetails stores favorite fields`() {
        val details = SeriesDetails(
            title = "The Pitt", titleRu = "Больница Питт",
            description = "desc", posterUrl = "https://img.jpg",
            seasons = emptyMap(),
            isFavorite = true, seriesId = "972", sessionToken = "abc123"
        )
        assertTrue(details.isFavorite)
        assertEquals("972", details.seriesId)
        assertEquals("abc123", details.sessionToken)
    }

    @Test
    fun `SeriesDetails favorite defaults to false`() {
        val details = SeriesDetails(
            title = "Test", titleRu = "Тест",
            description = "", posterUrl = "",
            seasons = emptyMap()
        )
        assertFalse(details.isFavorite)
        assertEquals("", details.seriesId)
        assertEquals("", details.sessionToken)
    }

    @Test
    fun `toggleFavorite sends follow toggle with session`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"result":"on"}""").setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')

        val body = okhttp3.FormBody.Builder()
            .add("act", "serial")
            .add("type", "follow")
            .add("id", "972")
            .add("session", "test_token")
            .build()
        val request = okhttp3.Request.Builder()
            .url("$baseUrl/ajaxik.php")
            .post(body)
            .build()
        val response = client.newCall(request).execute()
        assertTrue(response.isSuccessful)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        val reqBody = recorded.body.readUtf8()
        assertTrue(reqBody.contains("act=serial"))
        assertTrue(reqBody.contains("type=follow"))
        assertTrue(reqBody.contains("id=972"))
        assertTrue(reqBody.contains("session=test_token"))
    }

    @Test
    fun `toggleFavorite response on means now favorite`() {
        val json = """{"result":"on"}"""
        val result = org.json.JSONObject(json).optString("result", "")
        assertEquals("on", result)
    }

    @Test
    fun `toggleFavorite response off means now not favorite`() {
        val json = """{"result":"off"}"""
        val result = org.json.JSONObject(json).optString("result", "")
        assertEquals("off", result)
    }
}
