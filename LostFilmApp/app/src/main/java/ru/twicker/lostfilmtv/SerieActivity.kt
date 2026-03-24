package ru.twicker.lostfilmtv

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import ru.twicker.lostfilmtv.network.Series
import ru.twicker.lostfilmtv.ui.SerieDetailsFragment

/**
 * Activity for Serie details page. Hosts SerieDetailsFragment.
 */
class SerieActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse) // reuse the container layout
        
        val series = intent.getSerializableExtra("serie") as? Series
        
        if (savedInstanceState == null && series != null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.browse_container, SerieDetailsFragment.newInstance(series))
                .commit()
        }
    }
}
