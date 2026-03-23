package ru.twicker.lostfilmtv

import android.os.Bundle
import androidx.fragment.app.FragmentActivity

/**
 * Separate Activity for Leanback browse UI.
 * Hosts MainBrowseFragment declared in activity_browse.xml.
 * This matches the original app pattern where BrowseSupportFragment
 * is hosted in its own Activity with FragmentContainerView in XML.
 */
class BrowseActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)
    }
}
