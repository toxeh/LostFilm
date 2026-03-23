package ru.twicker.lostfilmtv

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.FragmentActivity
import ru.twicker.lostfilmtv.ui.LoginFragment

private const val TAG = "MainActivity"

/**
 * Entry point — shows LoginFragment for auth / session check.
 * On success → launches BrowseActivity (separate Activity for Leanback UI).
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.login_container, LoginFragment())
                .commitAllowingStateLoss()
        }
    }

    fun showBrowseFragment() {
        Log.d(TAG, "showBrowseFragment() → launching BrowseActivity")
        val intent = Intent(this, BrowseActivity::class.java)
        startActivity(intent)
        finish() // close login activity
    }
}
