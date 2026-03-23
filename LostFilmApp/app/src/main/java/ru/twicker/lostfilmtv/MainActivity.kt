package ru.twicker.lostfilmtv

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.FragmentActivity
import ru.twicker.lostfilmtv.ui.LoginFragment
import ru.twicker.lostfilmtv.ui.MainBrowseFragment

private const val TAG = "MainActivity"

/**
 * Single-activity app:
 *   1. Shows LoginFragment (pure XML Views) for startup/auth
 *   2. After successful auth → showBrowseFragment() replaces with MainBrowseFragment (Leanback)
 *   3. EpisodeDetailsFragment is launched from MainBrowseFragment via back-stack
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            // Start with login/startup fragment
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, LoginFragment())
                .commitAllowingStateLoss()
        }
    }

    fun showBrowseFragment() {
        Log.d(TAG, "showBrowseFragment() called")
        runOnUiThread {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, MainBrowseFragment())
                .commitAllowingStateLoss()
            Log.d(TAG, "MainBrowseFragment committed")
        }
    }
}
