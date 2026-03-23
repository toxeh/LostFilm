package ru.twicker.lostfilmtv.network

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Singleton Coil ImageLoader that uses ciadpi proxy for loading images
 * from lostfilm.tv (which is blocked without DPI bypass).
 *
 * Key: limits max parallel requests to 2 per host because
 * ciadpi can't handle many concurrent connections.
 */
object CoilFactory {

    @Volatile
    private var imageLoader: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        return imageLoader ?: synchronized(this) {
            imageLoader ?: buildImageLoader(context.applicationContext).also { imageLoader = it }
        }
    }

    private fun buildImageLoader(context: Context): ImageLoader {
        val cookieJar = LostFilmAuth.cookieJar(context)
        val baseClient = NetworkClient.createCiadpiClient(cookieJar)

        // Limit parallelism — ciadpi proxy can't handle many concurrent connections
        val dispatcher = Dispatcher().apply {
            maxRequests = 4
            maxRequestsPerHost = 2
        }

        val imageClient = baseClient.newBuilder()
            .dispatcher(dispatcher)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
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
