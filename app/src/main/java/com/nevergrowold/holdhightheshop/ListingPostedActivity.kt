package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.activity.enableEdgeToEdge

class ListingPostedActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_listing_posted)
        BarInsets.apply(this)
        celebrate()
    }

    /**
     * The one celebratory moment in the app, earned by finishing a listing:
     * the badge springs in, a ring ripples out from it like the listing going
     * live, then the details and actions settle in underneath.
     */
    private fun celebrate() {
        if (!Motion.enabled(this)) return
        val badge = findViewById<View>(R.id.postedBadge)
        val check = findViewById<View>(R.id.postedCheck)
        val ring = findViewById<View>(R.id.postedRing)

        badge.alpha = 0f
        badge.scaleX = 0.5f
        badge.scaleY = 0.5f
        badge.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(120).setDuration(480).setInterpolator(OvershootInterpolator(1.6f)).start()

        check.scaleX = 0.3f
        check.scaleY = 0.3f
        check.rotation = -25f
        check.animate().scaleX(1f).scaleY(1f).rotation(0f)
            .setStartDelay(260).setDuration(420).setInterpolator(OvershootInterpolator(2f)).start()

        ring.scaleX = 1f
        ring.scaleY = 1f
        ring.animate().withStartAction { ring.alpha = 0.9f }
            .alpha(0f).scaleX(2.1f).scaleY(2.1f)
            .setStartDelay(380).setDuration(900).setInterpolator(Motion.EASE_OUT).start()

        val content = findViewById<ViewGroup>(R.id.postedContent)
        Motion.enter((1 until content.childCount).map { content.getChildAt(it) }, delay = 300, rise = 14)
    }

    /** Opens the listing that was just posted. */
    fun viewListing(view: View) {
        startActivity(Intent(this, ListingDetailActivity::class.java))
        finish()
    }

    /** Returns to the Campus Market. */
    fun backToMarket(view: View) {
        val intent = Intent(this, MarketActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        finish()
    }
}
