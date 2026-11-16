package com.nevergrowold.holdhightheshop

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible

class NotificationsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_notifications)
        BarInsets.apply(this)
    }

    /** Clears the unread highlight and dots. */
    fun markAllRead(view: View) {
        listOf(R.id.notifRow1, R.id.notifRow2).forEach { findViewById<View>(it).background = null }
        listOf(R.id.notifDot1, R.id.notifDot2).forEach { findViewById<View>(it).isVisible = false }
        view.isEnabled = false
        view.alpha = 0.5f
    }

    /** "New message from Miguel Santos": opens that conversation. */
    fun openMessageNotification(view: View) = openChatWith(Listings.sellerByName("Miguel Santos"))

    /** "You received a new rating": the Completed tab lists finished exchanges. */
    fun openRatings(view: View) {
        switchTab(ProfileActivity::class.java) { putExtra(EXTRA_TAB, ProfileActivity.TAB_COMPLETED) }
    }
}
