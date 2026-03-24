package ru.twicker.lostfilmtv.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.leanback.app.DetailsSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.twicker.lostfilmtv.R
import ru.twicker.lostfilmtv.network.*

/**
 * Fragment displaying full Series details (poster, description at the top)
 * and seasons/episodes as rows at the bottom.
 * Includes "Add to favorites" / "Remove from favorites" toggle button.
 */
class SerieDetailsFragment : DetailsSupportFragment() {

    companion object {
        private const val TAG = "SerieDetails"
        private const val ACTION_FAVORITE = 1L
        private const val ACTION_WATCH_MOVIE = 10L
        private const val ACTION_WATCHED_MOVIE = 11L

        fun newInstance(series: Series): SerieDetailsFragment {
            val fragment = SerieDetailsFragment()
            val args = Bundle()
            args.putSerializable("serie", series)
            fragment.arguments = args
            return fragment
        }
    }

    private lateinit var rowAdapter: ArrayObjectAdapter
    private var client: okhttp3.OkHttpClient? = null
    private val repository = LostFilmRepository()
    private var baseSeries: Series? = null
    private var isFavorite = false
    private var seriesId = ""
    private var sessionToken = ""
    private var movieEpisode: Episode? = null  // non-null for movies

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        baseSeries = arguments?.getSerializable("serie") as? Series
        prepareEntranceTransition()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val bgManager = androidx.leanback.app.BackgroundManager.getInstance(requireActivity())
        if (!bgManager.isAttached) bgManager.attach(requireActivity().window)
        bgManager.color = 0xFF1B1C2A.toInt()

        val cookieJar = LostFilmAuth.cookieJar(requireContext())
        client = NetworkClient.createCiadpiClient(cookieJar)

        setupUIElements()
        loadSeriesData()
    }

    private fun setupUIElements() {
        val detailsPresenter = FullWidthDetailsOverviewRowPresenter(SeriesDetailsDescriptionPresenter())
        detailsPresenter.backgroundColor = Color.parseColor("#801B1C2A")
        detailsPresenter.actionsBackgroundColor = Color.parseColor("#801B1C2A")

        val selector = ClassPresenterSelector()
        selector.addClassPresenter(DetailsOverviewRow::class.java, detailsPresenter)
        selector.addClassPresenter(ListRow::class.java, ListRowPresenter())

        rowAdapter = ArrayObjectAdapter(selector)
        adapter = rowAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, row ->
            when {
                item is Episode -> onEpisodeClicked(item)
                item is Action && item.id == ACTION_FAVORITE -> onFavoriteClicked(row)
                item is Action && item.id == ACTION_WATCH_MOVIE -> onWatchMovieClicked()
                item is Action && item.id == ACTION_WATCHED_MOVIE -> onWatchedMovieClicked(row)
            }
        }
    }

    private fun loadSeriesData() {
        val c = client ?: return
        val link = baseSeries?.link ?: return

        lifecycleScope.launch {
            try {
                val details = withContext(Dispatchers.IO) {
                    repository.getSeriesDetails(c, link)
                }

                if (!isAdded) return@launch
                if (details == null) {
                    if (isAdded) Toast.makeText(requireContext(), "Ошибка загрузки данных сериала", Toast.LENGTH_LONG).show()
                } else {
                    isFavorite = details.isFavorite
                    seriesId = details.seriesId
                    sessionToken = details.sessionToken
                    populateUI(details)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fetch series failed", e)
                if (isAdded) Toast.makeText(requireContext(), "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            }
            startEntranceTransition()
        }
    }

    private fun populateUI(details: SeriesDetails) {
        val detailsRow = DetailsOverviewRow(details)

        val actionAdapter = SparseArrayObjectAdapter()
        
        // Check if this is a movie (single episode in "Фильм" season)
        val isMovie = details.seasons.size == 1 && details.seasons.containsKey("Фильм")
        if (isMovie) {
            movieEpisode = details.seasons["Фильм"]?.firstOrNull()
            // Add Watch button
            actionAdapter.set(ACTION_WATCH_MOVIE.toInt(), Action(ACTION_WATCH_MOVIE, "Смотреть", ""))
            // Add Watched toggle
            updateWatchedMovieAction(actionAdapter)
        }
        
        updateFavoriteAction(actionAdapter)
        detailsRow.actionsAdapter = actionAdapter

        rowAdapter.add(detailsRow)

        // Load Poster
        if (details.posterUrl.isNotBlank()) {
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

            val imgClient = client ?: NetworkClient.createCiadpiClient(LostFilmAuth.cookieJar(requireContext()))
            ImageDownloadQueue.init(requireContext(), imgClient)

            val cached = ImageCache.get(requireContext(), details.posterUrl)
            if (cached != null) {
                applyBackground(cached)
                applyPoster(cached)
            } else {
                detailsRow.imageDrawable = ColorDrawable(Color.DKGRAY)
                ImageDownloadQueue.enqueue(details.posterUrl) { bitmap ->
                    Log.d(TAG, "Poster callback: bitmap=${bitmap != null} isAdded=$isAdded")
                    if (bitmap != null) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            if (isAdded) {
                                applyBackground(bitmap)
                                applyPoster(bitmap)
                            }
                        }
                    }
                }
            }
        }

        // Seasons rows (skip for movies — already handled via action bar)
        if (!isMovie) {
            val episodePresenter = EpisodeCardPresenter()
            var idCounter = 100L
            for ((seasonName, episodes) in details.seasons) {
                val rowItemsAdapter = ArrayObjectAdapter(episodePresenter)
                episodes.reversed().forEach { rowItemsAdapter.add(it) }
                rowAdapter.add(ListRow(HeaderItem(idCounter++, seasonName), rowItemsAdapter))
            }
        }
    }

    private fun updateFavoriteAction(actionAdapter: SparseArrayObjectAdapter) {
        val label = if (isFavorite) "★ В избранном" else "☆ В избранное"
        actionAdapter.set(ACTION_FAVORITE.toInt(), Action(ACTION_FAVORITE, label, ""))
    }
    
    private fun updateWatchedMovieAction(actionAdapter: SparseArrayObjectAdapter) {
        val ep = movieEpisode ?: return
        val watchedIcon = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_watched)?.mutate()
        if (ep.isWatched) {
            watchedIcon?.setTint(Color.parseColor("#FF745ec5"))
        } else {
            watchedIcon?.setTint(Color.parseColor("#FFCCCCCC"))
        }
        actionAdapter.set(ACTION_WATCHED_MOVIE.toInt(), Action(ACTION_WATCHED_MOVIE, "", "", watchedIcon))
    }

    private fun onFavoriteClicked(row: Row?) {
        val c = client ?: return
        if (seriesId.isBlank()) {
            Toast.makeText(requireContext(), "ID сериала не найден", Toast.LENGTH_SHORT).show()
            return
        }

        // Optimistic UI update
        isFavorite = !isFavorite
        val detailsRow = row as? DetailsOverviewRow
        val actionAdapter = detailsRow?.actionsAdapter as? SparseArrayObjectAdapter
        if (actionAdapter != null) {
            updateFavoriteAction(actionAdapter)
        }

        // API call — toggle, server returns actual new state
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                try {
                    repository.toggleFavorite(c, seriesId, sessionToken)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to toggle favorite", e)
                    null
                }
            }
            if (isAdded) {
                if (result != null) {
                    isFavorite = result
                    if (actionAdapter != null) updateFavoriteAction(actionAdapter)
                    val msg = if (result) "Добавлено в избранное" else "Удалено из избранного"
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                } else {
                    // Revert optimistic update
                    isFavorite = !isFavorite
                    if (actionAdapter != null) updateFavoriteAction(actionAdapter)
                    Toast.makeText(requireContext(), "Ошибка", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun onEpisodeClicked(episode: Episode) {
        val detailFragment = EpisodeDetailsFragment.newInstance(episode)
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.browse_container, detailFragment)
            .addToBackStack(null)
            .commitAllowingStateLoss()
    }
    
    private fun onWatchMovieClicked() {
        val ep = movieEpisode ?: return
        onEpisodeClicked(ep)
    }
    
    private fun onWatchedMovieClicked(row: Row?) {
        val c = client ?: return
        val ep = movieEpisode ?: return
        val detailsRow = row as? DetailsOverviewRow ?: return
        val actionAdapter = detailsRow.actionsAdapter as? SparseArrayObjectAdapter ?: return
        
        // Optimistic toggle
        ep.isWatched = !ep.isWatched
        updateWatchedMovieAction(actionAdapter)
        
        // Construct data-episode value: e.g. code "815-1-1" -> "815001001"
        val parts = ep.episodeCode.split("-")
        val dataEpisode = if (parts.size == 3) {
            "${parts[0]}${parts[1].padStart(3, '0')}${parts[2].padStart(3, '0')}"
        } else ep.episodeCode
        
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                try {
                    repository.setWatched(c, dataEpisode, sessionToken, ep.isWatched)
                } catch (e: Exception) {
                    Log.e(TAG, "setWatched failed", e)
                    false
                }
            }
            if (!ok && isAdded) {
                ep.isWatched = !ep.isWatched
                updateWatchedMovieAction(actionAdapter)
                Toast.makeText(requireContext(), "Ошибка", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
