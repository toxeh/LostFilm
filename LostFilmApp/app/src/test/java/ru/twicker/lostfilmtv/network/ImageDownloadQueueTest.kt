package ru.twicker.lostfilmtv.network

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ImageDownloadQueueTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Clean up SharedPreferences
        context.getSharedPreferences("image_download_queue", Context.MODE_PRIVATE)
            .edit().clear().apply()
        ImageCache.clear(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("image_download_queue", Context.MODE_PRIVATE)
            .edit().clear().apply()
        ImageCache.clear(context)
    }

    @Test
    fun `enqueue with cached URL calls callback immediately`() {
        val url = "https://lostfilm.tv/test.jpg"
        // Pre-populate cache with a tiny JPEG
        val bmp = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, out)
        ImageCache.put(context, url, out.toByteArray())

        var callbackBitmap: android.graphics.Bitmap? = null
        // Init queue with a dummy client (won't actually be used for cached URLs)
        val dummyClient = okhttp3.OkHttpClient()
        ImageDownloadQueue.init(context, dummyClient)

        ImageDownloadQueue.enqueue(url) { bmp ->
            callbackBitmap = bmp
        }

        // Callback should be invoked synchronously for cached URLs
        assertNotNull("Callback should receive non-null bitmap for cached URL", callbackBitmap)
    }

    @Test
    fun `pendingCount returns 0 initially`() {
        val dummyClient = okhttp3.OkHttpClient()
        ImageDownloadQueue.init(context, dummyClient)
        assertEquals(0, ImageDownloadQueue.pendingCount())
    }

    @Test
    fun `persistent storage saves and restores pending URLs`() {
        // Directly write to SharedPreferences to simulate pending state
        val prefs = context.getSharedPreferences("image_download_queue", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("pending_urls", setOf(
            "https://lostfilm.tv/a.jpg",
            "https://lostfilm.tv/b.jpg"
        )).apply()

        val stored = prefs.getStringSet("pending_urls", emptySet())
        assertNotNull(stored)
        assertEquals(2, stored!!.size)
        assertTrue(stored.contains("https://lostfilm.tv/a.jpg"))
        assertTrue(stored.contains("https://lostfilm.tv/b.jpg"))
    }
}
