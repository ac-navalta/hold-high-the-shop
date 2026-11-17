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
        // Each unread row lets go of its tint and its dot pops away, one after the other.
        listOf(R.id.notifRow1, R.id.notifRow2).forEach {
            Motion.backgroundColor(findViewById(it), android.graphics.Color.TRANSPARENT, animate = true)
        }
        listOf(R.id.notifDot1, R.id.notifDot2).forEachIndexed { i, id ->
            val dot = findViewById<View>(id)
            if (!Motion.enabled(this)) { dot.isVisible = false; return@forEachIndexed }
            dot.animate().scaleX(1.5f).scaleY(1.5f).setStartDelay(i * 70L).setDuration(110).setInterpolator(Motion.EASE_OUT)
                .withEndAction {
                    dot.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(160).setInterpolator(Motion.EASE_OUT)
                        .withEndAction { dot.isVisible = false }.start()
                }.start()
        }
        Motion.confirm(view)
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
