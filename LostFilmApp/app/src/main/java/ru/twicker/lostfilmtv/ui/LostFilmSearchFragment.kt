package ru.twicker.lostfilmtv.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.twicker.lostfilmtv.R
import ru.twicker.lostfilmtv.network.*

/**
 * Leanback search fragment with voice + text search.
 * Uses LostFilm ajaxik.php `act=common&type=search` API.
 */
class LostFilmSearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    companion object {
        private const val TAG = "LFSearch"
    }

    private val repository = LostFilmRepository()
    private lateinit var rowsAdapter: ArrayObjectAdapter
    private var client: okhttp3.OkHttpClient? = null
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        setSearchResultProvider(this)

        // Handle click on search result
        setOnItemViewClickedListener { _, item, _, _ ->
            if (item is Series) {
                Log.d(TAG, "Clicked series: ${item.titleRu} link=${item.link}")
                val fragment = SerieDetailsFragment.newInstance(item)
                requireActivity().supportFragmentManager.beginTransaction()
                    .replace(R.id.browse_container, fragment)
                    .addToBackStack(null)
                    .commitAllowingStateLoss()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val cookieJar = LostFilmAuth.cookieJar(requireContext())
        client = NetworkClient.createSmartClient(cookieJar)

        // Request mic permission for voice search
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
    }

    // --- SearchResultProvider ---

    override fun getResultsAdapter(): ObjectAdapter = rowsAdapter

    override fun onQueryTextChange(newQuery: String?): Boolean {
        searchDebounced(newQuery ?: "")
        return true
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        searchDebounced(query ?: "")
        return true
    }

    private fun searchDebounced(query: String) {
        searchJob?.cancel()
        if (query.length < 2) {
            rowsAdapter.clear()
            return
        }
        searchJob = lifecycleScope.launch {
            delay(300) // debounce
            performSearch(query)
        }
    }

    private suspend fun performSearch(query: String) {
        val c = client ?: return
        val results = withContext(Dispatchers.IO) {
            repository.searchSeries(c, query)
        }
        if (!isAdded) return

        rowsAdapter.clear()
        if (results.isEmpty()) {
            Log.d(TAG, "No results for '$query'")
            return
        }

        val cardPresenter = SeriesCardPresenter()
        val listRowAdapter = ArrayObjectAdapter(cardPresenter)
        results.forEach { listRowAdapter.add(it) }
        rowsAdapter.add(ListRow(HeaderItem(0, "Сериалы"), listRowAdapter))
        Log.d(TAG, "Showing ${results.size} results for '$query'")
    }
}
