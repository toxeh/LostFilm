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
 *   - rowsAdapter created in onCreateView
 *   - setAdapter() in onViewCreated
 *   - prepareEntranceTransition() in onCreate
 *   - startEntranceTransition() after data ready
 */
class MainBrowseFragment : BrowseSupportFragment() {

    companion object {
        private const val TAG = "MainBrowse"
    }

    private var client: okhttp3.OkHttpClient? = null
    private val repository = LostFilmRepository()
    private lateinit var rowsAdapter: ArrayObjectAdapter
    private var episodesLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        headersState = HEADERS_HIDDEN
        isHeadersTransitionOnBackEnabled = false
        brandColor = 0xFF222538.toInt()
        title = "LostFilm TV"

        // Search affordance (magnifying glass in top-left)
        searchAffordanceColor = 0xFF6200EE.toInt()
        setOnSearchClickedListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.browse_container, LostFilmSearchFragment())
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }

        prepareEntranceTransition()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Log.d(TAG, "onCreateView")
        rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        episodesLoaded = false  // new adapter is empty, must reload
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated")

        // Prevent screensaver from interrupting entrance transition
        view.keepScreenOn = true

        adapter = rowsAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is Episode -> onEpisodeClicked(item)
                is Series -> onSeriesClicked(item)
                is String  -> if (item.contains("Настройки")) onSettingsClicked()
            }
        }

        val cookieJar = LostFilmAuth.cookieJar(requireContext())
        client = NetworkClient.createCiadpiClient(cookieJar)

        // Skip reload on back navigation (preserves focus position)
        if (!episodesLoaded) {
            loadEpisodes()
        } else {
            startEntranceTransition()
        }
    }

    override fun onResume() {
        super.onResume()
        // Fallback: if entrance transition got stuck (screensaver/pause), retry
        if (episodesLoaded) {
            view?.post { startEntranceTransition() }
        }
    }

    private fun loadEpisodes() {
        val c = client ?: return
        Log.d(TAG, "loadEpisodes starting")
        lifecycleScope.launch {
            try {
                val episodes = withContext(Dispatchers.IO) {
                    repository.getRecentEpisodes(c)
                }
                val favorites = withContext(Dispatchers.IO) {
                    try { repository.getFavoriteSeries(c) } catch (_: Exception) { emptyList() }
                }
                
                Log.d(TAG, "loadEpisodes got ${episodes.size} episodes, ${favorites.size} favorites")
                if (episodes.isEmpty()) {
                    Toast.makeText(requireContext(), "Нет новых эпизодов", Toast.LENGTH_LONG).show()
                } else {
                    setupRows(episodes, favorites)
                    episodesLoaded = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Load episodes failed", e)
                Toast.makeText(requireContext(), "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            }
            startEntranceTransition()
            // Focus on first row of cards, not search orb
            setSelectedPosition(0)
        }
    }

    private fun setupRows(episodes: List<Episode>, favorites: List<Series> = emptyList()) {
        Log.d(TAG, "setupRows with ${episodes.size} episodes, ${favorites.size} favorites")
        val episodePresenter = EpisodeCardPresenter()

        // Избранное row FIRST (favorite series)
        if (favorites.isNotEmpty()) {
            val seriesPresenter = SeriesCardPresenter()
            val favAdapter = ArrayObjectAdapter(seriesPresenter)
            favorites.forEach { favAdapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem(0, "Избранное"), favAdapter))
        }

        val mainAdapter = ArrayObjectAdapter(episodePresenter)
        episodes.forEach { mainAdapter.add(it) }
        rowsAdapter.add(ListRow(HeaderItem(1, "Новые эпизоды"), mainAdapter))

        if (episodes.size > 5) {
            val recentAdapter = ArrayObjectAdapter(episodePresenter)
            episodes.take(5).forEach { recentAdapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem(2, "Свежие"), recentAdapter))

            val olderAdapter = ArrayObjectAdapter(episodePresenter)
            episodes.drop(5).forEach { olderAdapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem(3, "Ещё"), olderAdapter))
        }

        // Settings row
        val settingsPresenter = object : Presenter() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup): ViewHolder {
                val tv = android.widget.TextView(parent.context).apply {
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 20f
                    setPadding(40, 20, 40, 20)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setBackgroundResource(android.R.drawable.list_selector_background)
                }
                return ViewHolder(tv)
            }
            override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
                (viewHolder.view as android.widget.TextView).text = item as? String ?: ""
            }
            override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
        }
        val settingsAdapter = ArrayObjectAdapter(settingsPresenter)
        settingsAdapter.add("⚙ Настройки")
        rowsAdapter.add(ListRow(HeaderItem(99, ""), settingsAdapter))

        Log.d(TAG, "rowsAdapter size: ${rowsAdapter.size()}")
    }

    private fun onEpisodeClicked(episode: Episode) {
        val detailFragment = EpisodeDetailsFragment.newInstance(episode)
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.browse_container, detailFragment)
            .addToBackStack(null)
            .commitAllowingStateLoss()
    }

    private fun onSeriesClicked(series: Series) {
        val intent = android.content.Intent(requireContext(), ru.twicker.lostfilmtv.SerieActivity::class.java)
        intent.putExtra("series", series)
        startActivity(intent)
    }

    private fun onSettingsClicked() {
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.browse_container, SettingsFragment())
            .addToBackStack(null)
            .commitAllowingStateLoss()
    }
}
