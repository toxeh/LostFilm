package ru.twicker.lostfilmtv.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.DetailsSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import coil.Coil
import coil.request.ImageRequest
import coil.target.Target
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.twicker.lostfilmtv.R
import ru.twicker.lostfilmtv.network.*

/**
 * Leanback DetailsSupportFragment — episode detail page
 * Based on decompiled EpisodeDetailsFragment.java
 *
 * Flow: MainBrowseFragment card click → this fragment → "Смотреть" → quality picker → download torrent
 */
class EpisodeDetailsFragment : DetailsSupportFragment(), OnItemViewClickedListener {

    companion object {
        private const val TAG = "EpisodeDetails"
        private const val ACTION_WATCH = 0L
        private const val ACTION_BACK = 1L
        private const val ACTION_SD = 2L
        private const val ACTION_HD = 3L
        private const val ACTION_MP4 = 4L
        private const val ACTION_FHD = 5L
        private const val ACTION_WATCHED = 6L
        private const val ACTION_SERIE = 8L
        private const val ACTION_SEASON = 9L

        fun newInstance(episode: Episode): EpisodeDetailsFragment {
            return EpisodeDetailsFragment().apply {
                arguments = Bundle().apply {
                    putString("title", episode.title)
                    putString("titleEn", episode.titleEn)
                    putString("episodeInfo", episode.episodeInfo)
                    putString("date", episode.date)
                    putString("detailsUrl", episode.detailsUrl)
                    putString("imageUrl", episode.imageUrl)
                    putString("episodeCode", episode.episodeCode)
                    putBoolean("isWatched", episode.isWatched)
                }
            }
        }
    }


    private lateinit var episode: Episode
    private lateinit var rowAdapter: ArrayObjectAdapter
    private var client: okhttp3.OkHttpClient? = null
    private val repository = LostFilmRepository()
    private var torrentEntries: List<TorrentEntry> = emptyList()
    private var dataEpisode: String = ""  // e.g. "972002011" for watched API
    private var sessionToken: String = ""  // CSRF token from UserData.session

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val args = arguments ?: run { activity?.onBackPressed(); return }
        episode = Episode(
            title = args.getString("title", ""),
            titleEn = args.getString("titleEn", ""),
            episodeInfo = args.getString("episodeInfo", ""),
            date = args.getString("date", ""),
            detailsUrl = args.getString("detailsUrl", ""),
            imageUrl = args.getString("imageUrl", ""),
            episodeCode = args.getString("episodeCode", ""),
            isWatched = args.getBoolean("isWatched", false)
        )

        val cookieJar = LostFilmAuth.cookieJar(requireContext())
        client = NetworkClient.createSmartClient(cookieJar)

        buildAdapter()
    }

    private fun buildAdapter() {
        // Detail presenter — shows title, subtitle, body text
        val detailPresenter = object : AbstractDetailsDescriptionPresenter() {
            override fun onBindDescription(vh: ViewHolder, item: Any?) {
                val ep = item as? Episode ?: return
                vh.title.text = ep.title
                vh.subtitle.text = ep.titleEn
                vh.body.text = "${ep.episodeInfo}  ·  ${ep.date}"
            }
        }

        // Full-width details row presenter with dark background
        val rowPresenter = FullWidthDetailsOverviewRowPresenter(detailPresenter)
        rowPresenter.backgroundColor = Color.parseColor("#D6000000")

        val selector = ClassPresenterSelector()
        selector.addClassPresenter(DetailsOverviewRow::class.java, rowPresenter)
        selector.addClassPresenter(ListRow::class.java, ListRowPresenter())

        rowAdapter = ArrayObjectAdapter(selector)

        // Details overview row with poster image + actions
        val detailsRow = DetailsOverviewRow(episode)

        // Episode image URL (for background)
        val codeParts = episode.episodeCode.split("-")
        val episodeImgUrl = if (codeParts.size >= 3) {
            "https://${ru.twicker.lostfilmtv.network.NetworkClient.getImageHost()}/Static/Images/${codeParts[0]}/Posters/e_${codeParts[1]}_${codeParts[2]}.jpg"
        } else ""

        // Series poster URL (for small thumbnail in details row)
        val seriesPosterUrl = if (codeParts.isNotEmpty()) {
            "https://${ru.twicker.lostfilmtv.network.NetworkClient.getImageHost()}/Static/Images/${codeParts[0]}/Posters/image.jpg"
        } else ""

        // Setup BackgroundManager for the full-width top area
        val bgManager = androidx.leanback.app.BackgroundManager.getInstance(requireActivity())
        if (!bgManager.isAttached) bgManager.attach(requireActivity().window)

        val displayMetrics = resources.displayMetrics
        val screenW = displayMetrics.widthPixels
        val screenH = displayMetrics.heightPixels

        fun applyBackground(bitmap: android.graphics.Bitmap) {
            val scaledBitmap = android.graphics.Bitmap.createBitmap(screenW, screenH, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(scaledBitmap)
            val scale = maxOf(screenW.toFloat() / bitmap.width, screenH.toFloat() / bitmap.height)
            val dstW = (bitmap.width * scale).toInt()
            val dstH = (bitmap.height * scale).toInt()
            canvas.drawBitmap(bitmap, null, android.graphics.Rect(
                (screenW - dstW) / 2, (screenH - dstH) / 2,
                (screenW + dstW) / 2, (screenH + dstH) / 2
            ), null)
            bgManager.setBitmap(scaledBitmap)
        }

        fun applyPoster(bitmap: android.graphics.Bitmap) {
            detailsRow.imageDrawable = android.graphics.drawable.BitmapDrawable(resources, bitmap)
            rowAdapter.notifyArrayItemRangeChanged(0, 1)
        }

        // Init download queue
        val imgClient = client ?: ru.twicker.lostfilmtv.network.NetworkClient.createSmartClient(
            ru.twicker.lostfilmtv.network.LostFilmAuth.cookieJar(requireContext())
        )
        ru.twicker.lostfilmtv.network.ImageDownloadQueue.init(requireContext(), imgClient)

        // Small poster (series poster)
        val cachedPoster = if (seriesPosterUrl.isNotBlank()) {
            ru.twicker.lostfilmtv.network.ImageCache.get(requireContext(), seriesPosterUrl)
        } else null

        if (cachedPoster != null) {
            applyPoster(cachedPoster)
        } else {
            detailsRow.imageDrawable = ColorDrawable(Color.parseColor("#33FFFFFF"))
            if (seriesPosterUrl.isNotBlank()) {
                ru.twicker.lostfilmtv.network.ImageDownloadQueue.enqueue(seriesPosterUrl) { bitmap ->
                    if (bitmap != null) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            if (isAdded) applyPoster(bitmap)
                        }
                    }
                }
            }
        }

        // Background image (episode image)
        val cachedBg = if (episodeImgUrl.isNotBlank()) {
            ru.twicker.lostfilmtv.network.ImageCache.get(requireContext(), episodeImgUrl)
        } else null

        if (cachedBg != null) {
            applyBackground(cachedBg)
        } else if (episodeImgUrl.isNotBlank()) {
            ru.twicker.lostfilmtv.network.ImageDownloadQueue.enqueue(episodeImgUrl) { bitmap ->
                if (bitmap != null) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (isAdded) applyBackground(bitmap)
                    }
                }
            }
        }

        // Initial actions
        rebuildDefaultActions(detailsRow)

        rowAdapter.add(detailsRow)
        adapter = rowAdapter
        onItemViewClickedListener = this

        // Load description and runtime
        loadEpisodeInfo(detailsRow)
    }

    private fun rebuildDefaultActions(detailsRow: DetailsOverviewRow) {
        val actionAdapter = SparseArrayObjectAdapter()
        actionAdapter.set(ACTION_WATCH.toInt(), Action(ACTION_WATCH, "Смотреть", ""))
        
        // Show status based on whether it is watched
        val watchedIcon = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_watched)?.mutate()
        if (episode.isWatched) {
            watchedIcon?.setTint(android.graphics.Color.parseColor("#FF745ec5"))
        } else {
            watchedIcon?.setTint(android.graphics.Color.parseColor("#FFCCCCCC"))
        }
        actionAdapter.set(ACTION_WATCHED.toInt(), Action(ACTION_WATCHED, "", "", watchedIcon))
        
        // Don't show "go to series" for movies
        if (episode.episodeInfo != "Фильм") {
            actionAdapter.set(ACTION_SERIE.toInt(), Action(ACTION_SERIE, "Перейти к сериалу", ""))
        }
        detailsRow.actionsAdapter = actionAdapter
    }

    private fun loadEpisodeInfo(detailsRow: DetailsOverviewRow) {
        val c = client ?: return
        val code = episode.episodeCode
        if (code.isBlank()) return

        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                repository.getEpisodeInfo(c, code, episode.detailsUrl)
            }
            if (info != null) {
                if (!info.description.isNullOrBlank()) {
                    episode.episodeInfo = episode.episodeInfo + "\n\n" + info.description
                }
                if (!info.runtime.isNullOrBlank()) {
                    episode.date = episode.date + "  ·  " + info.runtime
                }
                
                // Store data-episode and session for watched API
                if (info.dataEpisode.isNotBlank()) {
                    dataEpisode = info.dataEpisode
                }
                if (info.sessionToken.isNotBlank()) {
                    sessionToken = info.sessionToken
                }
                Log.d(TAG, "Loaded episode info: dataEpisode=$dataEpisode session=${sessionToken.take(8)}...")
                
                // Update the row item to trigger re-bind of the description presenter
                detailsRow.item = episode
                val index = rowAdapter.indexOf(detailsRow)
                if (index >= 0) {
                    rowAdapter.notifyArrayItemRangeChanged(index, 1)
                }
            }
        }
    }

    override fun onItemClicked(
        itemViewHolder: Presenter.ViewHolder?,
        item: Any?,
        rowViewHolder: RowPresenter.ViewHolder?,
        row: Row?
    ) {
        if (item !is Action) return
        val detailsRow = row as? DetailsOverviewRow ?: return
        val actionAdapter = detailsRow.actionsAdapter as? SparseArrayObjectAdapter ?: return

        when (item.id) {
            ACTION_WATCH -> {
                if (torrentEntries.isNotEmpty()) {
                    showQualityActions(actionAdapter)
                } else {
                    loadTorrents(actionAdapter)
                }
            }
            ACTION_WATCHED -> {
                // Toggle watched status
                toggleWatchedStatus(detailsRow, actionAdapter)
            }
            ACTION_SERIE -> {
                // Navigate to Serie
                navigateToSerie()
            }
            ACTION_SEASON -> {
                // Navigate to Season
                navigateToSeason()
            }
            ACTION_BACK -> {
                // Reset to default actions — fresh adapter to guarantee quality buttons removed
                rebuildDefaultActions(detailsRow)
            }
            ACTION_SD -> downloadTorrent("SD")
            ACTION_HD -> downloadTorrent("720p")
            ACTION_MP4 -> downloadTorrent("MP4")
            ACTION_FHD -> downloadTorrent("1080p")
        }
    }

    private fun toggleWatchedStatus(detailsRow: DetailsOverviewRow, actionAdapter: SparseArrayObjectAdapter) {
        val c = client ?: return
        val code = episode.episodeCode
        if (code.isBlank()) return

        val newStatus = !episode.isWatched
        episode.isWatched = newStatus

        // Update only the watched icon in-place (no clear → no focus jump)
        updateWatchedIcon(actionAdapter)
        
        // Fire-and-forget: NonCancellable so the request completes even if fragment is destroyed
        lifecycleScope.launch {
            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                try {
                    repository.setWatched(c, dataEpisode, sessionToken, newStatus)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to toggle watch status", e)
                }
            }
        }
    }

    private fun updateWatchedIcon(actionAdapter: SparseArrayObjectAdapter) {
        val watchedIcon = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_watched)?.mutate()
        if (episode.isWatched) {
            watchedIcon?.setTint(android.graphics.Color.parseColor("#FF745ec5"))
        } else {
            watchedIcon?.setTint(android.graphics.Color.parseColor("#FFCCCCCC"))
        }
        actionAdapter.set(ACTION_WATCHED.toInt(), Action(ACTION_WATCHED, "", "", watchedIcon))
    }

    private fun navigateToSerie() {
        val url = episode.detailsUrl
        if (url.isBlank()) return
        
        val path = url.replace("https://www.lostfilm.tv", "")
                      .replace("https://lostfilm.tv", "")
                      .replace("http://lostfilm.tv", "")
                      .replace("//lostfilm.tv", "")
        
        val parts = path.split("/").filter { it.isNotBlank() }
        if (parts.size >= 2) {
            val serieUrl = "/${parts[0]}/${parts[1]}/" // /series/The_Penguin/
            
            val series = Series(
                title = episode.titleEn,
                titleRu = episode.title,
                link = serieUrl,
                poster = "",
                genres = "",
                year = ""
            )
            
            val intent = android.content.Intent(requireContext(), ru.twicker.lostfilmtv.SerieActivity::class.java).apply {
                putExtra("serie", series)
            }
            startActivity(intent)
        }
    }

    private fun navigateToSeason() {
        val url = episode.detailsUrl
        if (url.isBlank()) return
        
        val path = url.replace("https://www.lostfilm.tv", "")
                      .replace("https://lostfilm.tv", "")
                      .replace("http://lostfilm.tv", "")
                      .replace("//lostfilm.tv", "")
        
        val parts = path.split("/").filter { it.isNotBlank() }
        if (parts.size >= 3) {
            val seasonUrl = "/${parts[0]}/${parts[1]}/${parts[2]}/" // /series/The_Penguin/season_1/
            
            val series = Series(
                title = episode.titleEn,
                titleRu = episode.title,
                link = seasonUrl,
                poster = "",
                genres = "",
                year = ""
            )
            
            val intent = android.content.Intent(requireContext(), ru.twicker.lostfilmtv.SerieActivity::class.java).apply {
                putExtra("serie", series)
            }
            startActivity(intent)
        }
    }

    private fun loadTorrents(actionAdapter: SparseArrayObjectAdapter) {
        actionAdapter.clear()
        actionAdapter.set(0, Action(99, "Загрузка...", ""))

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.getTorrents(client!!, episode.episodeCode)
            }
            when (result) {
                is TorrentsResult.Success -> {
                    torrentEntries = result.entries
                    if (torrentEntries.isEmpty()) {
                        actionAdapter.clear()
                        actionAdapter.set(0, Action(ACTION_WATCH, "Торренты не найдены", ""))
                    } else {
                        showQualityActions(actionAdapter)
                    }
                }
                is TorrentsResult.NeedAuth -> {
                    actionAdapter.clear()
                    actionAdapter.set(0, Action(ACTION_WATCH, "Необходима авторизация", ""))
                    Toast.makeText(requireContext(), "Необходима авторизация", Toast.LENGTH_LONG).show()
                }
                is TorrentsResult.Error -> {
                    actionAdapter.clear()
                    actionAdapter.set(0, Action(ACTION_WATCH, "Ошибка", result.message))
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showQualityActions(actionAdapter: SparseArrayObjectAdapter) {
        actionAdapter.clear()
        actionAdapter.set(ACTION_BACK.toInt(), Action(ACTION_BACK, "← Назад", ""))

        for (entry in torrentEntries) {
            val (actionId, label, sublabel) = when {
                "1080" in entry.quality -> Triple(ACTION_FHD, "FHD", "1080p  ${entry.sizeMb}")
                "720" in entry.quality  -> Triple(ACTION_HD, "HD", "720p  ${entry.sizeMb}")
                "SD" in entry.quality || "MP4" in entry.quality || "480" in entry.quality ->
                    Triple(ACTION_SD, "SD", "480p  ${entry.sizeMb}")
                else -> Triple(ACTION_MP4, entry.quality, entry.sizeMb)
            }
            actionAdapter.set(actionId.toInt(), Action(actionId, label, sublabel))
        }
    }

    private fun downloadTorrent(quality: String) {
        val entry = torrentEntries.firstOrNull { quality in it.quality }
            ?: torrentEntries.firstOrNull()
            ?: return

        val c = client ?: return
        Toast.makeText(requireContext(), "Скачивание ${entry.quality}...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val torrentBytes = withContext(Dispatchers.IO) {
                    val req = okhttp3.Request.Builder()
                        .url(entry.url)
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                    c.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                        resp.body?.bytes() ?: throw Exception("Empty body")
                    }
                }

                // UDP Broadcast .torrent to local network if enabled
                if (SettingsFragment.isBroadcastEnabled(requireContext())) {
                    val port = SettingsFragment.getBroadcastPort(requireContext())
                    withContext(Dispatchers.IO) {
                        try {
                            val sock = java.net.DatagramSocket()
                            sock.broadcast = true
                            val packet = java.net.DatagramPacket(
                                torrentBytes, torrentBytes.size,
                                java.net.InetAddress.getByName("255.255.255.255"), port
                            )
                            sock.send(packet)
                            sock.close()
                            Log.d(TAG, "Broadcast .torrent (${torrentBytes.size} bytes) to port $port")
                        } catch (e: Exception) {
                            Log.e(TAG, "Broadcast failed: ${e.message}", e)
                        }
                    }
                }

                val file = java.io.File(requireContext().cacheDir, "download.torrent")
                file.writeBytes(torrentBytes)

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    file
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/x-bittorrent")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    // Extra metadata for TorrServer
                    putExtra("title", "${episode.episodeInfo} ${episode.title}")
                    putExtra("poster", episode.imageUrl)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}", e)
                Toast.makeText(requireContext(), "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
