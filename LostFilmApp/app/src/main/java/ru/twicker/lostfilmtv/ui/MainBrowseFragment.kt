package ru.twicker.lostfilmtv.ui

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.twicker.lostfilmtv.network.*

/**
 * Leanback BrowseSupportFragment — based on decompiled MainFragment.java
 * Netflix-style TV UI with horizontal rows and left sidebar
 */
class MainBrowseFragment : BrowseSupportFragment() {

    private val repository = LostFilmRepository()
    private lateinit var browseAdapter: ArrayObjectAdapter
    private var client: okhttp3.OkHttpClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        title = "LostFilm.TV"

        // Colors from original app
        brandColor = Color.parseColor("#1a1a2e")
        searchAffordanceColor = Color.parseColor("#e94560")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val cookieJar = LostFilmAuth.cookieJar(requireContext())
        client = NetworkClient.createCiadpiClient(cookieJar)

        setupAdapter()
        setupListeners()
        loadEpisodes()
    }

    private fun setupAdapter() {
        val rowPresenter = ListRowPresenter().apply {
            shadowEnabled = true
        }
        browseAdapter = ArrayObjectAdapter(rowPresenter)
        adapter = browseAdapter
    }

    private fun setupListeners() {
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is Episode) {
                onEpisodeClicked(item)
            }
        }
    }

    private fun loadEpisodes() {
        lifecycleScope.launch {
            try {
                // Show shimmer/loading placeholder
                showLoading()

                val episodes = withContext(Dispatchers.IO) {
                    repository.getRecentEpisodes(client!!)
                }

                if (episodes.isEmpty()) {
                    showError("Не удалось загрузить эпизоды")
                    return@launch
                }

                showEpisodes(episodes)

            } catch (e: Exception) {
                Log.e("MainBrowse", "Failed to load: ${e.message}", e)
                showError("Ошибка загрузки: ${e.message}")
            }
        }
    }

    private fun showLoading() {
        browseAdapter.clear()
        val loadingPresenter = object : Presenter() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup): ViewHolder {
                val tv = android.widget.TextView(parent.context).apply {
                    text = "Загрузка..."
                    setTextColor(Color.WHITE)
                    textSize = 20f
                    setPadding(40, 40, 40, 40)
                }
                return ViewHolder(tv)
            }
            override fun onBindViewHolder(vh: ViewHolder, item: Any?) {}
            override fun onUnbindViewHolder(vh: ViewHolder) {}
        }
        val row = ArrayObjectAdapter(loadingPresenter).apply { add("loading") }
        browseAdapter.add(ListRow(HeaderItem("Загрузка..."), row))
    }

    private fun showEpisodes(episodes: List<Episode>) {
        browseAdapter.clear()

        // Row: "Новые эпизоды" — all episodes as horizontal scroll
        val episodePresenter = EpisodeCardPresenter()
        val episodeAdapter = ArrayObjectAdapter(episodePresenter)
        episodes.forEach { episodeAdapter.add(it) }
        browseAdapter.add(
            ListRow(HeaderItem(0, "Новые эпизоды"), episodeAdapter)
        )

        // If we have enough episodes, split into additional thematic rows
        if (episodes.size > 5) {
            // First half as "Свежие"
            val recentAdapter = ArrayObjectAdapter(episodePresenter)
            episodes.take(5).forEach { recentAdapter.add(it) }
            browseAdapter.add(
                ListRow(HeaderItem(1, "Свежие"), recentAdapter)
            )

            // Second half as "Больше серий"
            val olderAdapter = ArrayObjectAdapter(episodePresenter)
            episodes.drop(5).forEach { olderAdapter.add(it) }
            browseAdapter.add(
                ListRow(HeaderItem(2, "Ещё"), olderAdapter)
            )
        }
    }

    private fun showError(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
    }

    private fun onEpisodeClicked(episode: Episode) {
        val c = client ?: return
        Toast.makeText(requireContext(), "Загрузка торрентов: ${episode.title}", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.getTorrents(c, episode.episodeCode)
            }
            when (result) {
                is TorrentsResult.Success -> {
                    if (result.entries.isEmpty()) {
                        Toast.makeText(requireContext(), "Торренты не найдены", Toast.LENGTH_SHORT).show()
                    } else {
                        showTorrentDialog(episode, result.entries)
                    }
                }
                is TorrentsResult.NeedAuth -> {
                    Toast.makeText(requireContext(), "Необходима авторизация", Toast.LENGTH_LONG).show()
                }
                is TorrentsResult.Error -> {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showTorrentDialog(episode: Episode, entries: List<TorrentEntry>) {
        val titles = entries.map { it.quality }.toTypedArray()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(episode.title)
            .setItems(titles) { _, which ->
                val entry = entries[which]
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(entry.url))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Не удалось открыть: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
