package com.nevergrowold.holdhightheshop

import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Activity
import android.util.Property
import android.view.View
import android.view.ViewGroup

/**
 * The full celebratory confirmation moment shared by "Listing posted" and
 * "Report received": both close out something the student finished, so both
 * get the signature UC stamp choreography.
 *
 * Built as keyframe tracks, each property on its own timeline:
 * - the badge overshoots to 112%, dips to 97%, then settles cleanly at 100%;
 * - the verified check twists in a beat later (-30° -> +8° -> 0°), as if being
 *   stamped, landing with a firm confirm haptic;
 * - two staggered mint rings ripple outward from the badge;
 * - the title, reference card and action buttons settle in smoothly underneath.
 */
object Celebration {

    fun play(activity: Activity) {
        if (!Motion.enabled(activity)) return
        val badge = activity.findViewById<View>(R.id.celebrateBadge) ?: return
        val check = activity.findViewById<View>(R.id.celebrateCheck) ?: return
        val content = activity.findViewById<ViewGroup>(R.id.celebrateContent) ?: return

        track(
            badge,
            duration = 640,
            delay = 120,
            View.SCALE_X to floatArrayOf(0f, 0.5f, 0.45f, 1.12f, 0.72f, 0.97f, 1f, 1f),
            View.SCALE_Y to floatArrayOf(0f, 0.5f, 0.45f, 1.12f, 0.72f, 0.97f, 1f, 1f),
            View.ALPHA to floatArrayOf(0f, 0f, 0.3f, 1f, 1f, 1f),
        )
        track(
            check,
            duration = 560,
            delay = 260,
            View.ROTATION to floatArrayOf(0f, -30f, 0.55f, 8f, 1f, 0f),
            View.SCALE_X to floatArrayOf(0f, 0.3f, 0.6f, 1.15f, 1f, 1f),
            View.SCALE_Y to floatArrayOf(0f, 0.3f, 0.6f, 1.15f, 1f, 1f),
            View.ALPHA to floatArrayOf(0f, 0f, 0.25f, 1f, 1f, 1f),
        )
        // The stamp lands: one firm confirm tick when the check reaches full size.
        check.postDelayed({
            if (check.isAttachedToWindow) Motion.confirm(check)
        }, 260 + 330)

        activity.findViewById<View>(R.id.celebrateRing)?.let { ripple(it, delay = 380, strength = 0.9f) }
        activity.findViewById<View>(R.id.celebrateRing2)?.let { ripple(it, delay = 560, strength = 0.5f) }

        val items = (1 until content.childCount).map { content.getChildAt(it) }
        Motion.staggerIn(items, delay = 340, riseDp = 16f, gapMs = 30L, subtleScale = true)
    }

    /** One keyframe track per property: each array is pairs of (fraction, value). */
    private fun track(
        view: View,
        duration: Long,
        delay: Long,
        vararg tracks: Pair<Property<View, Float>, FloatArray>,
    ) {
        val holders = tracks.map { (property, pairs) ->
            val frames = (pairs.indices step 2).map { i ->
                Keyframe.ofFloat(pairs[i], pairs[i + 1]).apply {
                    if (i > 0) interpolator = Motion.EMPHASIZED
                }
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
        ring.animate().cancel()
        ring.scaleX = 1f
        ring.scaleY = 1f
        ring.alpha = 0f
        ring.animate()
            .withStartAction { ring.alpha = strength }
            .alpha(0f)
            .scaleX(2.2f)
            .scaleY(2.2f)
            .setStartDelay(delay)
            .setDuration(900)
            .setInterpolator(Motion.EASE_OUT)
            .start()
    }
}
