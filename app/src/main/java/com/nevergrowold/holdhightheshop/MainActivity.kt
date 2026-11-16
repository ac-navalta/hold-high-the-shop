package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge

class MainActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        BarInsets.apply(this, hideBottomOnKeyboard = true)
    }

    /** Opens the notifications screen. */
    fun openNotifications(view: View) {
        startActivity(Intent(this, NotificationsActivity::class.java))
    }

    /** "Items exchanged" card: the Completed tab on the profile lists them. */
    fun openExchanged(view: View) {
        switchTab(ProfileActivity::class.java) { putExtra(EXTRA_TAB, ProfileActivity.TAB_COMPLETED) }
    }

    /** "Active listings" card: the Active tab on the profile lists them. */
    fun openActiveListings(view: View) {
        switchTab(ProfileActivity::class.java) { putExtra(EXTRA_TAB, ProfileActivity.TAB_ACTIVE) }
    }
}
