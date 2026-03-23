package ru.twicker.lostfilmtv

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import ru.twicker.lostfilmtv.ui.MainBrowseFragment

/**
 * Hosts Leanback UI: MainBrowseFragment → EpisodeDetailsFragment.
 * Fragment replace for detail navigation uses R.id.browse_container.
 */
class BrowseActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.browse_container, MainBrowseFragment())
                .commitAllowingStateLoss()
        }
    }
}
