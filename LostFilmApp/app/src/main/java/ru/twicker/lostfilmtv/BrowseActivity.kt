package ru.twicker.lostfilmtv

import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import ru.twicker.lostfilmtv.ui.MainBrowseFragment

/**
 * Hosts Leanback UI: MainBrowseFragment → EpisodeDetailsFragment.
 * Fragment replace for detail navigation uses R.id.browse_container.
 * Double-back-press to exit.
 */
class BrowseActivity : FragmentActivity() {

    private var backPressedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.browse_container, MainBrowseFragment())
                .commitAllowingStateLoss()
        }

        // Double-back-press to exit
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // If there are fragments on the back stack, pop them normally
                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                    return
                }
                // Double-press to exit from main screen
                if (backPressedOnce) {
                    finish()
                    return
                }
                backPressedOnce = true
                Toast.makeText(this@BrowseActivity,
                    "Нажмите назад ещё раз для выхода", Toast.LENGTH_SHORT).show()
                // Reset after 2 seconds
                window.decorView.postDelayed({ backPressedOnce = false }, 2000)
            }
        })
    }
}
