package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
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
     * The one celebratory moment in the app, earned by finishing a listing.
     * Built as keyframe tracks, each property on its own timeline:
     * - the badge overshoots to 112%, dips, then settles;
     * - the check twists in a beat later, as if being stamped;
     * - two rings ripple out, like the listing reaching the market;
     * - then the details and actions settle in underneath.
     */
    private fun celebrate() {
        if (!Motion.enabled(this)) return
        val badge = findViewById<View>(R.id.postedBadge)
        val check = findViewById<View>(R.id.postedCheck)

        track(badge, 640, 120,
            View.SCALE_X to floatArrayOf(0f, 0.5f, 0.45f, 1.12f, 0.72f, 0.97f, 1f, 1f),
            View.SCALE_Y to floatArrayOf(0f, 0.5f, 0.45f, 1.12f, 0.72f, 0.97f, 1f, 1f),
            View.ALPHA to floatArrayOf(0f, 0f, 0.3f, 1f, 1f, 1f))
        track(check, 560, 260,
            View.ROTATION to floatArrayOf(0f, -30f, 0.55f, 8f, 1f, 0f),
            View.SCALE_X to floatArrayOf(0f, 0.3f, 0.6f, 1.15f, 1f, 1f),
            View.SCALE_Y to floatArrayOf(0f, 0.3f, 0.6f, 1.15f, 1f, 1f))
        ripple(findViewById(R.id.postedRing), 380, 0.9f)
        ripple(findViewById(R.id.postedRing2), 560, 0.5f)

        val content = findViewById<ViewGroup>(R.id.postedContent)
        Motion.enter((1 until content.childCount).map { content.getChildAt(it) }, delay = 340, rise = 14)
    }

    /**
     * One keyframe track per property. Each array is pairs of (fraction, value);
     * segments between keyframes use the emphasized curve.
     */
    private fun track(view: View, duration: Long, delay: Long, vararg tracks: Pair<android.util.Property<View, Float>, FloatArray>) {
        val holders = tracks.map { (property, pairs) ->
            val frames = (pairs.indices step 2).map { i ->
                Keyframe.ofFloat(pairs[i], pairs[i + 1]).apply { if (i > 0) interpolator = Motion.EMPHASIZED }
            }
            PropertyValuesHolder.ofKeyframe(property, *frames.toTypedArray())
        }
        // Hold the first frame during the delay so nothing flashes at full size.
        tracks.forEach { (property, pairs) -> property.set(view, pairs[1]) }
        ObjectAnimator.ofPropertyValuesHolder(view, *holders.toTypedArray()).apply {
            this.duration = duration
            startDelay = delay
            start()
        }
    }

    private fun ripple(ring: View, delay: Long, strength: Float) {
        ring.scaleX = 1f
        ring.scaleY = 1f
        ring.alpha = 0f
        ring.animate().withStartAction { ring.alpha = strength }
            .alpha(0f).scaleX(2.2f).scaleY(2.2f)
            .setStartDelay(delay).setDuration(900).setInterpolator(Motion.EASE_OUT).start()
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
