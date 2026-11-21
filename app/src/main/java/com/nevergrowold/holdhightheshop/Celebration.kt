package com.nevergrowold.holdhightheshop

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.dynamicanimation.animation.DynamicAnimation

/**
 * Confirmation choreography shared by "Listing posted" and "Report received".
 *
 * Uses expressive spring physics rather than long multi-beat keyframe tracks:
 * - The badge springs immediately from 0.78 -> 1.0 (`Spatial.EXPRESSIVE`);
 * - The verified mark rotates gently (-14° -> 0°) and lands with a confirm haptic;
 * - A single restrained mint halo expands once around the badge;
 * - Title, reference card and action button glide in right behind it so the
 *   whole moment feels celebratory, crisp and interactive within ~480ms.
 */
object Celebration {

    fun play(activity: Activity) {
        if (!Motion.enabled(activity)) return
        val badge = activity.findViewById<View>(R.id.celebrateBadge) ?: return
        val check = activity.findViewById<View>(R.id.celebrateCheck) ?: return
        val ring = activity.findViewById<View>(R.id.celebrateRing)
        val ring2 = activity.findViewById<View>(R.id.celebrateRing2)
        val content = activity.findViewById<ViewGroup>(R.id.celebrateContent) ?: return

        badge.alpha = 0f
        badge.scaleX = 0.78f
        badge.scaleY = 0.78f

        check.alpha = 0f
        check.scaleX = 0.72f
        check.scaleY = 0.72f
        check.rotation = -14f

        ring?.apply {
            alpha = 0f
            scaleX = 0.92f
            scaleY = 0.92f
        }
        ring2?.alpha = 0f

        badge.postDelayed({
            if (!badge.isAttachedToWindow) return@postDelayed
            Motion.spring(badge, DynamicAnimation.ALPHA, 1f, Motion.Effects.FAST)
            Motion.springScale(badge, 1f, Motion.Spatial.EXPRESSIVE)
        }, 40)

        check.postDelayed({
            if (!check.isAttachedToWindow) return@postDelayed
            Motion.spring(check, DynamicAnimation.ALPHA, 1f, Motion.Effects.FAST)
            Motion.springScale(check, 1f, Motion.Spatial.EXPRESSIVE)
            Motion.spring(check, DynamicAnimation.ROTATION, 0f, Motion.Spatial.RESPONSIVE)
            Motion.confirm(check)
        }, 120)

        ring?.let { r ->
            r.animate().cancel()
            r.animate()
                .withStartAction { r.alpha = 0.55f }
                .alpha(0f)
                .scaleX(1.9f)
                .scaleY(1.9f)
                .setStartDelay(130)
                .setDuration(560)
                .setInterpolator(Motion.EASE_OUT)
                .start()
        }

        val items = (1 until content.childCount).map { content.getChildAt(it) }
        Motion.staggerIn(items, delay = 140, riseDp = 12f, gapMs = 26L, subtleScale = true)
    }
}
