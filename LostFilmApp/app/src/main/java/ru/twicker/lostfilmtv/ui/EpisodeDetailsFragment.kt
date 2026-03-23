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
                }
            }
        }
    }


    private lateinit var episode: Episode
    private lateinit var rowAdapter: ArrayObjectAdapter
    private var client: okhttp3.OkHttpClient? = null
    private val repository = LostFilmRepository()
    private var torrentEntries: List<TorrentEntry> = emptyList()

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
            episodeCode = args.getString("episodeCode", "")
        )

        val cookieJar = LostFilmAuth.cookieJar(requireContext())
        client = NetworkClient.createCiadpiClient(cookieJar)

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

        // Load poster image
        val imgUrl = episode.imageUrl
        if (imgUrl.isNotBlank()) {
            val request = ImageRequest.Builder(requireContext())
                .data(imgUrl)
                .target(object : Target {
                    override fun onSuccess(result: Drawable) {
                        detailsRow.imageDrawable = result
                        rowAdapter.notifyArrayItemRangeChanged(0, 1)
                    }
                    override fun onError(error: Drawable?) {
                        detailsRow.imageDrawable = ColorDrawable(Color.DKGRAY)
                    }
                })
                .build()
            ru.twicker.lostfilmtv.network.CoilFactory.get(requireContext()).enqueue(request)
        }

        // Initial action: "Смотреть"
        val actionAdapter = SparseArrayObjectAdapter()
        actionAdapter.set(ACTION_WATCH.toInt(), Action(ACTION_WATCH, "Смотреть", ""))
        detailsRow.actionsAdapter = actionAdapter

        rowAdapter.add(detailsRow)
        adapter = rowAdapter
        onItemViewClickedListener = this
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
            ACTION_BACK -> {
                // Reset to "Смотреть"
                actionAdapter.clear()
                actionAdapter.set(ACTION_WATCH.toInt(), Action(ACTION_WATCH, "Смотреть", ""))
            }
            ACTION_SD -> downloadTorrent("SD")
            ACTION_HD -> downloadTorrent("720p")
            ACTION_MP4 -> downloadTorrent("MP4")
            ACTION_FHD -> downloadTorrent("1080p")
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
