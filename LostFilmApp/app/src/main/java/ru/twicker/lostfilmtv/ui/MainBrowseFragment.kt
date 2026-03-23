package ru.twicker.lostfilmtv.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
 * Hosted in BrowseActivity via FragmentContainerView in XML (like original app).
 *
 * Pattern matches decompiled MainFragment.java:
 *   - setHeadersState(HEADERS_HIDDEN) in onCreate
 *   - setAdapter() in onViewCreated
 *   - prepareEntranceTransition() before data load
 *   - startEntranceTransition() after data ready
 */
class MainBrowseFragment : BrowseSupportFragment() {

    companion object {
        private const val TAG = "MainBrowse"
    }

    private var client: okhttp3.OkHttpClient? = null
    private val repository = LostFilmRepository()
    private lateinit var rowsAdapter: ArrayObjectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        // Match original: setHeadersState(3) = HEADERS_HIDDEN
        headersState = HEADERS_HIDDEN
        isHeadersTransitionOnBackEnabled = false
        brandColor = 0xFF222538.toInt()
        title = "LostFilm TV"
        prepareEntranceTransition()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Log.d(TAG, "onCreateView")
        rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated")

        adapter = rowsAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is Episode) onEpisodeClicked(item)
        }

        val cookieJar = LostFilmAuth.cookieJar(requireContext())
        client = NetworkClient.createCiadpiClient(cookieJar)

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
                    Toast.makeText(requireContext(), "Нет новых эпизодов", Toast.LENGTH_LONG).show()
                } else {
                    setupRows(episodes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Load episodes failed", e)
                Toast.makeText(requireContext(), "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            }
            startEntranceTransition()
        }
    }

    private fun setupRows(episodes: List<Episode>) {
        Log.d(TAG, "setupRows with ${episodes.size} episodes")
        val episodePresenter = EpisodeCardPresenter()

        val mainAdapter = ArrayObjectAdapter(episodePresenter)
        episodes.forEach { mainAdapter.add(it) }
        rowsAdapter.add(ListRow(HeaderItem(0, "Новые эпизоды"), mainAdapter))

        if (episodes.size > 5) {
            val recentAdapter = ArrayObjectAdapter(episodePresenter)
            episodes.take(5).forEach { recentAdapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem(1, "Свежие"), recentAdapter))

            val olderAdapter = ArrayObjectAdapter(episodePresenter)
            episodes.drop(5).forEach { olderAdapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem(2, "Ещё"), olderAdapter))
        }
        Log.d(TAG, "rowsAdapter size: ${rowsAdapter.size()}")
    }

    private fun onEpisodeClicked(episode: Episode) {
        val detailFragment = EpisodeDetailsFragment.newInstance(episode)
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.main_browse_fragment, detailFragment)
            .addToBackStack(null)
            .commitAllowingStateLoss()
    }
}
