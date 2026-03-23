package ru.twicker.lostfilmtv.ui

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
 * Main Leanback browse fragment — shows episode cards in horizontal rows.
 * Click on episode card → EpisodeDetailsFragment (detail page).
 */
class MainBrowseFragment : BrowseSupportFragment() {

    companion object {
        private const val TAG = "MainBrowse"
    }

    private var client: okhttp3.OkHttpClient? = null
    private val repository = LostFilmRepository()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val cookieJar = LostFilmAuth.cookieJar(requireContext())
        client = NetworkClient.createCiadpiClient(cookieJar)

        setupAdapter()
        setupListeners()
        loadEpisodes()
    }

    private fun setupAdapter() {
        title = "LostFilm TV"
        headersState = HEADERS_DISABLED
    }

    private fun setupListeners() {
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is Episode) {
                onEpisodeClicked(item)
            }
        }
    }

    private fun loadEpisodes() {
        val c = client ?: return
        lifecycleScope.launch {
            try {
                val episodes = withContext(Dispatchers.IO) {
                    repository.getRecentEpisodes(c)
                }
                if (episodes.isEmpty()) {
                    showError("Нет новых эпизодов")
                    return@launch
                }
                setupRows(episodes)
            } catch (e: Exception) {
                Log.e(TAG, "Load episodes failed", e)
                showError("Ошибка: ${e.message}")
            }
        }
    }

    private fun setupRows(episodes: List<Episode>) {
        val browseAdapter = ArrayObjectAdapter(ListRowPresenter())

        val episodePresenter = EpisodeCardPresenter()
        val episodeAdapter = ArrayObjectAdapter(episodePresenter)
        episodes.forEach { episodeAdapter.add(it) }
        browseAdapter.add(
            ListRow(HeaderItem(0, "Новые эпизоды"), episodeAdapter)
        )

        if (episodes.size > 5) {
            val recentAdapter = ArrayObjectAdapter(episodePresenter)
            episodes.take(5).forEach { recentAdapter.add(it) }
            browseAdapter.add(
                ListRow(HeaderItem(1, "Свежие"), recentAdapter)
            )

            val olderAdapter = ArrayObjectAdapter(episodePresenter)
            episodes.drop(5).forEach { olderAdapter.add(it) }
            browseAdapter.add(
                ListRow(HeaderItem(2, "Ещё"), olderAdapter)
            )
        }

        adapter = browseAdapter
    }

    private fun showError(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
    }

    private fun onEpisodeClicked(episode: Episode) {
        // Navigate to episode detail page (like original app)
        val detailFragment = EpisodeDetailsFragment.newInstance(episode)
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, detailFragment)
            .addToBackStack(null)
            .commitAllowingStateLoss()
    }
}
