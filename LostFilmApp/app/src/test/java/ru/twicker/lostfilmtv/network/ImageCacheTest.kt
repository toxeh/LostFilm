package ru.twicker.lostfilmtv.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ImageCacheTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ImageCache.clear(context)
    }

    @After
    fun tearDown() {
        ImageCache.clear(context)
    }

    private fun createTestBytes(): ByteArray {
        // Create a tiny 1x1 red PNG-like bitmap as JPEG bytes
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bmp.setPixel(0, 0, 0xFFFF0000.toInt())
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 100, out)
        return out.toByteArray()
    }

    @Test
    fun `get returns null for uncached URL`() {
        assertNull(ImageCache.get(context, "https://example.com/image.jpg"))
    }

    @Test
    fun `put and get round-trip`() {
        val url = "https://lostfilm.tv/Static/Images/972/Posters/e_2_11.jpg"
        val bytes = createTestBytes()

        ImageCache.put(context, url, bytes)

        val result = ImageCache.get(context, url)
        assertNotNull("Should return bitmap after put", result)
        assertEquals(1, result!!.width)
        assertEquals(1, result.height)
    }

    @Test
    fun `different URLs have separate cache entries`() {
        val url1 = "https://lostfilm.tv/Static/Images/1/Posters/e_1_1.jpg"
        val url2 = "https://lostfilm.tv/Static/Images/2/Posters/e_2_2.jpg"
        val bytes = createTestBytes()

        ImageCache.put(context, url1, bytes)

        assertNotNull(ImageCache.get(context, url1))
        assertNull(ImageCache.get(context, url2))
    }

    @Test
    fun `totalSizeBytes returns correct size`() {
        assertEquals(0L, ImageCache.totalSizeBytes(context))

        val bytes = createTestBytes()
        ImageCache.put(context, "https://example.com/a.jpg", bytes)

        val size = ImageCache.totalSizeBytes(context)
        assertTrue("Size should be > 0 after put, was $size", size > 0)
        assertEquals(bytes.size.toLong(), size)
    }

    @Test
    fun `clear removes all entries`() {
        val bytes = createTestBytes()
        ImageCache.put(context, "https://example.com/a.jpg", bytes)
        ImageCache.put(context, "https://example.com/b.jpg", bytes)

        assertEquals(2 * bytes.size.toLong(), ImageCache.totalSizeBytes(context))

        ImageCache.clear(context)

        assertEquals(0L, ImageCache.totalSizeBytes(context))
        assertNull(ImageCache.get(context, "https://example.com/a.jpg"))
    }

    @Test
    fun `put with empty bytes is no-op`() {
        ImageCache.put(context, "https://example.com/c.jpg", byteArrayOf())
        assertNull(ImageCache.get(context, "https://example.com/c.jpg"))
    }

    @Test
    fun `empty cache file returns null`() {
        val url = "https://example.com/empty.jpg"
        // Write 0-byte file to cache
        val cacheDir = File(context.filesDir, "image_cache")
        cacheDir.mkdirs()
        val md5 = java.security.MessageDigest.getInstance("MD5")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        File(cacheDir, "$md5.jpg").writeBytes(byteArrayOf())

        // ImageCache.get checks file.length() == 0L → returns null
        val result = ImageCache.get(context, url)
        assertNull("Empty file should return null", result)
    }
}
