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
import ru.twicker.lostfilmtv.R
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

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        Log.d(TAG, "onActivityCreated")

        title = "LostFilm TV"
        headersState = HEADERS_DISABLED
        isHeadersTransitionOnBackEnabled = false
        brandColor = android.graphics.Color.parseColor("#222538")

        val cookieJar = LostFilmAuth.cookieJar(requireContext())
        client = NetworkClient.createCiadpiClient(cookieJar)

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is Episode) {
                onEpisodeClicked(item)
            }
        }

        loadEpisodes()
    }

    private fun loadEpisodes() {
        val c = client ?: return
        Log.d(TAG, "loadEpisodes starting")
        lifecycleScope.launch {
            try {
                val episodes = withContext(Dispatchers.IO) {
                    repository.getRecentEpisodes(c)
                }
                Log.d(TAG, "loadEpisodes got ${episodes.size} episodes")
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
        Log.d(TAG, "setupRows with ${episodes.size} episodes")
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
        Log.d(TAG, "adapter set with ${browseAdapter.size()} rows")
    }

    private fun showError(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
    }

    private fun onEpisodeClicked(episode: Episode) {
        val detailFragment = EpisodeDetailsFragment.newInstance(episode)
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, detailFragment)
            .addToBackStack(null)
            .commitAllowingStateLoss()
    }
}
