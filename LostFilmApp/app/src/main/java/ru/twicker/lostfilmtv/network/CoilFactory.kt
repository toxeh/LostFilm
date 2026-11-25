package ru.twicker.lostfilmtv.network

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Singleton Coil ImageLoader via ciadpi proxy.
 * Images use lostfilm.tv (no www) — the bare domain works through ciadpi,
 * while www.lostfilm.tv Cloudflare IPs are not reachable via the proxy.
 */
object CoilFactory {

    @Volatile
    private var imageLoader: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        return imageLoader ?: synchronized(this) {
            imageLoader ?: buildImageLoader(context.applicationContext).also { imageLoader = it }
        }
    }

    fun reset() { imageLoader = null }

    private fun buildImageLoader(context: Context): ImageLoader {
        val cookieJar = LostFilmAuth.cookieJar(context)
        val baseClient = NetworkClient.createSmartClient(cookieJar)

        val dispatcher = Dispatcher().apply {
            maxRequests = 4
            maxRequestsPerHost = 2
        }

        val imageClient = baseClient.newBuilder()
            .dispatcher(dispatcher)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        return ImageLoader.Builder(context)
            .okHttpClient(imageClient)
            .crossfade(true)
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.05)
                    .build()
            }
            .build()
    }
}
