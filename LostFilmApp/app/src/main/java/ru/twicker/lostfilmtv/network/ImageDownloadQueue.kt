package ru.twicker.lostfilmtv.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Persistent background queue for downloading episode images through ciadpi.
 *
 * Design:
 * - Pending URLs are saved to SharedPreferences (survive app restart)
 * - Single-worker coroutine processes downloads sequentially (ciadpi is single-stream)
 * - On success, raw bytes are saved to ImageCache, URL removed from pending
 * - Callers register in-memory callbacks for instant UI updates
 * - Duplicate URLs are skipped if cached or already pending
 *
 * Usage:
 *   ImageDownloadQueue.init(context, ciadpiClient)
 *   ImageDownloadQueue.enqueue(url) { bitmap -> updateUI(bitmap) }
 */
object ImageDownloadQueue {

    private const val TAG = "ImgDownloadQ"
    private const val PREFS_NAME = "image_download_queue"
    private const val KEY_PENDING = "pending_urls"

    private val channel = Channel<String>(Channel.UNLIMITED)
    private val callbacks = mutableMapOf<String, MutableList<(android.graphics.Bitmap?) -> Unit>>()
    private var httpClient: OkHttpClient? = null
    private var appContext: Context? = null
    private var workerJob: Job? = null

    /** Initialize (or re-initialize after ciadpi restart) */
    fun init(context: Context, client: OkHttpClient) {
        appContext = context.applicationContext
        // Always update: ciadpi may have restarted on a new SOCKS port
        httpClient = client.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        startWorker()
        // Restore pending URLs from disk
        restorePending()
    }

    private fun getPrefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun savePending(ctx: Context, urls: Set<String>) {
        getPrefs(ctx).edit().putStringSet(KEY_PENDING, urls).apply()
    }

    private fun loadPending(ctx: Context): Set<String> =
        getPrefs(ctx).getStringSet(KEY_PENDING, emptySet()) ?: emptySet()

    private fun restorePending() {
        val ctx = appContext ?: return
        val pending = loadPending(ctx)
        if (pending.isEmpty()) return
        Log.d(TAG, "Restoring ${pending.size} pending downloads from disk")
        pending.forEach { url ->
            // Skip already cached
            if (ImageCache.get(ctx, url) != null) return@forEach
            channel.trySend(url)
        }
    }

    private fun addToPending(url: String) {
        val ctx = appContext ?: return
        val current = loadPending(ctx).toMutableSet()
        current.add(url)
        savePending(ctx, current)
    }

    private fun removeFromPending(url: String) {
        val ctx = appContext ?: return
        val current = loadPending(ctx).toMutableSet()
        current.remove(url)
        savePending(ctx, current)
    }

    private fun startWorker() {
        if (workerJob?.isActive == true) return
        workerJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            for (url in channel) {
                try {
                    download(url)
                } catch (e: Exception) {
                    Log.e(TAG, "Worker error: ${e.message}")
                }
                removeFromPending(url)
            }
        }
    }

    /**
     * Enqueue a download. No-op if already cached or already pending.
     * @param onComplete called on IO thread when download completes (or immediately if cached)
     */
    fun enqueue(url: String, onComplete: ((android.graphics.Bitmap?) -> Unit)? = null) {
        if (url.isBlank()) return
        val ctx = appContext ?: return

        // Already cached?
        val cached = ImageCache.get(ctx, url)
        if (cached != null) {
            Log.d(TAG, "Already cached: $url")
            onComplete?.invoke(cached)
            return
        }

        // Register callback
        if (onComplete != null) {
            synchronized(callbacks) {
                callbacks.getOrPut(url) { mutableListOf() }.add(onComplete)
            }
        }

        // Already in pending set?
        val pending = loadPending(ctx)
        if (url in pending) {
            Log.d(TAG, "Already pending: $url")
            return
        }

        addToPending(url)
        channel.trySend(url)
        Log.d(TAG, "Enqueued: $url (pending=${loadPending(ctx).size})")
    }

    private fun download(url: String) {
        val ctx = appContext ?: return
        val client = httpClient ?: return

        // Double-check cache
        ImageCache.get(ctx, url)?.let {
            Log.d(TAG, "Cache hit during download: $url")
            fireCallbacks(url, it)
            return
        }

        Log.d(TAG, "Downloading: $url")
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", NetworkClient.UA)
                .build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val bytes = resp.body?.bytes()
                if (bytes != null && bytes.isNotEmpty()) {
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        ImageCache.put(ctx, url, bytes)
                        Log.d(TAG, "Downloaded OK: $url (${bytes.size} bytes)")
                        fireCallbacks(url, bmp)
                        return
                    }
                }
            }
            Log.w(TAG, "Download failed (HTTP ${resp.code}): $url")
        } catch (e: Exception) {
            Log.w(TAG, "Download error: $url: ${e.message}")
        }
        fireCallbacks(url, null)
    }

    private fun fireCallbacks(url: String, bitmap: android.graphics.Bitmap?) {
        val cbs = synchronized(callbacks) {
            callbacks.remove(url) ?: emptyList()
        }
        cbs.forEach { it.invoke(bitmap) }
    }

    /** Number of URLs currently pending download */
    fun pendingCount(): Int = appContext?.let { loadPending(it).size } ?: 0
}
