package ru.twicker.lostfilmtv.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Simple persistent image cache backed by the app's filesDir.
 *
 * Key: MD5(url) → hex string
 * Storage: <filesDir>/image_cache/<md5>.jpg
 *
 * Images (episode posters, season posters) are static assets on lostfilm CDN —
 * they never change once published, so we cache indefinitely without expiry.
 *
 * Note: Coil's disk cache covers in-process sessions, but this cache
 * persists across app restarts, crucial for avoiding slow re-downloads
 * through the ciadpi proxy on every fresh launch.
 */
object ImageCache {

    private const val TAG = "ImageCache"
    private const val CACHE_DIR = "image_cache"

    private fun cacheDir(context: Context): File {
        return File(context.filesDir, CACHE_DIR).also { it.mkdirs() }
    }

    private fun cacheKey(url: String): String {
        val md5 = MessageDigest.getInstance("MD5")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "$md5.jpg"
    }

    /** Returns cached Bitmap if exists, null otherwise */
    fun get(context: Context, url: String): Bitmap? {
        val file = File(cacheDir(context), cacheKey(url))
        if (!file.exists() || file.length() == 0L) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath).also {
                if (it != null) Log.d(TAG, "Cache HIT: $url")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cache decode failed: ${e.message}")
            file.delete()   // remove corrupt entry
            null
        }
    }

    /** Saves raw JPEG/PNG bytes to cache. No-op if bytes is empty. */
    fun put(context: Context, url: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        try {
            val file = File(cacheDir(context), cacheKey(url))
            file.writeBytes(bytes)
            Log.d(TAG, "Cache MISS → saved ${bytes.size} bytes for $url")
        } catch (e: Exception) {
            Log.w(TAG, "Cache write failed: ${e.message}")
        }
    }

    /** Returns total size of the cache in bytes */
    fun totalSizeBytes(context: Context): Long =
        cacheDir(context).walkTopDown().sumOf { if (it.isFile) it.length() else 0L }

    /** Clears all cached images */
    fun clear(context: Context) {
        cacheDir(context).deleteRecursively()
        Log.d(TAG, "Cache cleared")
    }
}
