package com.nevergrowold.holdhightheshop

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.DynamicAnimation

class NotificationsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_notifications)
        BarInsets.apply(this)

        if (UserState.notificationsRead) {
            listOf(R.id.notifRow1, R.id.notifRow2).forEach {
                findViewById<View>(it).setBackgroundColor(Color.TRANSPARENT)
            }
            listOf(R.id.notifDot1, R.id.notifDot2).forEach {
                findViewById<View>(it).isVisible = false
            }
        }
    }

    /** Clears the unread highlights and smoothly collapses the unread dots. */
    fun markAllRead(view: View) {
        if (UserState.notificationsRead) return
        UserState.notificationsRead = true

        listOf(R.id.notifRow1, R.id.notifRow2).forEach {
            Motion.backgroundColor(findViewById(it), Color.TRANSPARENT, animate = true)
        }
        listOf(R.id.notifDot1, R.id.notifDot2).forEachIndexed { index, id ->
            val dot = findViewById<View>(id)
            if (!Motion.enabled(this)) {
                dot.isVisible = false
                return@forEachIndexed
            }
            dot.postDelayed({
                if (!dot.isAttachedToWindow) return@postDelayed
                Motion.springScale(dot, 0f, Motion.Effects.FAST)
                val alpha = Motion.spring(dot, DynamicAnimation.ALPHA, 0f, Motion.Effects.FAST)
                alpha?.addEndListener { _, canceled, _, _ ->
                    if (!canceled) dot.isVisible = false
                }
            }, index * 55L)
        }
        Motion.confirm(view)
        view.isEnabled = false
        Motion.spring(view, DynamicAnimation.ALPHA, 0.45f, Motion.Effects.DEFAULT)
    }

    /** "New message from Miguel Santos": opens that conversation. */
    fun openMessageNotification(view: View) = openChatWith(Listings.sellerByName("Miguel Santos"))

    /** "You received a new rating": the Completed tab lists finished exchanges. */
    fun openRatings(view: View) {
        switchTab(ProfileActivity::class.java) { putExtra(EXTRA_TAB, ProfileActivity.TAB_COMPLETED) }
    }
}
