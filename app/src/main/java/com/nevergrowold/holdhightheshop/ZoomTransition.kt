package com.nevergrowold.holdhightheshop

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * Card → screen zoom: the tapped card grows into the listing screen, and
 * Back shrinks the screen into the card again.
 *
 * Done by hand rather than with shared-element activity transitions, which
 * proved fragile (Material's container transform could not find its drawing
 * view on some devices and crashed). Here the opening screen:
 * 1. keeps a snapshot of the screen it came from and draws it behind itself;
 * 2. clips its own content to a rounded rectangle that springs from the
 *    card's bounds to the full screen, while a picture of the card inside
 *    that rectangle fades out and the page fades in (fade-through);
 * 3. dims the snapshot a little as it covers it.
 * Back plays the same spring in reverse, then closes with no window animation.
 */
object ZoomTransition {

    private const val EXTRA_BOUNDS = "zoom_bounds"

    /** The screen behind and the card, kept only between launch and the end of the return. */
    private var backdrop: Bitmap? = null
    private var cardImage: Bitmap? = null

    /** Opens [intent] by growing it out of [card]. Falls back to a normal push with animations off. */
    fun launch(activity: Activity, card: View, intent: Intent) {
        if (!Motion.enabled(activity) || card.width == 0) {
            activity.startActivity(intent)
            return
        }
        val decor = activity.window.decorView
        backdrop = capture(decor, 0.5f)
        cardImage = capture(card, 1f)
        val loc = IntArray(2).also { card.getLocationInWindow(it) }
        intent.putExtra(EXTRA_BOUNDS, Rect(loc[0], loc[1], loc[0] + card.width, loc[1] + card.height))
        activity.startActivity(intent)
        noWindowAnimation(activity)
    }

    /** Draws a view into a bitmap at [scale] (the backdrop is kept at half size: it is dimmed and brief). */
    private fun capture(view: View, scale: Float): Bitmap? = try {
        Bitmap.createBitmap(
            maxOf(1, (view.width * scale).toInt()), maxOf(1, (view.height * scale).toInt()), Bitmap.Config.ARGB_8888
        ).also { bmp ->
            val canvas = Canvas(bmp)
            canvas.scale(scale, scale)
            view.draw(canvas)
        }
    } catch (e: OutOfMemoryError) {
        null
    }

    @Suppress("DEPRECATION")
    private fun noWindowAnimation(activity: Activity) = activity.overridePendingTransition(0, 0)

    /** Live state of a zoomed screen, so Back can reverse it. */
    class Zoom internal constructor(
        private val activity: Activity,
        private val start: Rect,
        private val page: View,
        private val behind: ImageView,
        private val cardView: ImageView,
    ) {
        private val radius = 16 * activity.resources.displayMetrics.density
        private val current = RectF()
        private var progress = 0f
        private var spring: SpringAnimation? = null

        init {
            page.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(
                        current.left.toInt(), current.top.toInt(), current.right.toInt(), current.bottom.toInt(),
                        radius * (1f - progress).coerceIn(0f, 1f)
                    )
                }
            }
            page.clipToOutline = true
            apply(0f)
        }

        /** Positions everything for [p]: 0 = the card, 1 = the full screen. */
        private fun apply(p: Float) {
            progress = p
            val w = page.width.takeIf { it > 0 } ?: activity.window.decorView.width
            val h = page.height.takeIf { it > 0 } ?: activity.window.decorView.height
            val pageLoc = IntArray(2).also { page.getLocationInWindow(it) }
            fun lerp(a: Float, b: Float) = a + (b - a) * p
            // Rectangle in the page's own coordinates.
            current.set(
                lerp(start.left - pageLoc[0].toFloat(), 0f),
                lerp(start.top - pageLoc[1].toFloat(), 0f),
                lerp(start.right - pageLoc[0].toFloat(), w.toFloat()),
                lerp(start.bottom - pageLoc[1].toFloat(), h.toFloat()),
            )
            page.invalidateOutline()

            // Fade-through: the card picture holds for the first part, the page takes over after.
            val cardAlpha = 1f - ((p - 0.05f) / 0.3f).coerceIn(0f, 1f)
            val pageAlpha = ((p - 0.2f) / 0.35f).coerceIn(0f, 1f)
            pageContent().forEach { it.alpha = pageAlpha }
            cardView.alpha = cardAlpha
            cardView.layoutParams = (cardView.layoutParams as FrameLayout.LayoutParams).apply {
                width = maxOf(1, current.width().toInt())
                height = maxOf(1, current.height().toInt())
                leftMargin = (current.left + pageLoc[0]).toInt()
                topMargin = (current.top + pageLoc[1]).toInt()
            }

            // The screen behind dims as it is covered.
            val dim = 1f - 0.22f * p.coerceIn(0f, 1f)
            behind.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(dim, dim, dim, 1f) })
        }

        private fun pageContent(): List<View> =
            (page as? ViewGroup)?.let { g -> (0 until g.childCount).map { g.getChildAt(it) } } ?: listOf(page)

        private fun run(target: Float, spec: Motion.Spring, done: () -> Unit) {
            spring?.cancel()
            spring = SpringAnimation(FloatValueHolder(progress)).apply {
                spring = SpringForce(target).apply {
                    stiffness = spec.stiffness
                    dampingRatio = spec.damping
                }
                setMinimumVisibleChange(0.002f)
                addUpdateListener { _, value, _ -> apply(value) }
                addEndListener { _, canceled, _, _ -> if (!canceled) done() }
                start()
            }
        }

        /** Grows from the card to the full screen with a soft settle. */
        internal fun open() = run(1f, Motion.Spring(0.55f, 0.1f)) {
            page.clipToOutline = false
            behind.visibility = View.GONE
            cardView.visibility = View.GONE
            activity.window.setBackgroundDrawableResource(R.color.uc_cream)
        }

        /** Shrinks back into the card, then closes the screen without another animation. */
        fun close() {
            activity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            behind.visibility = View.VISIBLE
            cardView.visibility = View.VISIBLE
            page.clipToOutline = true
            run(0f, Motion.Spring(0.42f, 0f)) {
                activity.finish()
                noWindowAnimation(activity)
                backdrop = null
                cardImage = null
            }
        }
    }

    /**
     * Called by the opened screen after setContentView. Returns the zoom when
     * this screen was opened from a card, or null for a normal entrance.
     */
    fun enter(activity: Activity): Zoom? {
        val start = activity.intent.getParcelableExtra<Rect>(EXTRA_BOUNDS) ?: return null
        val behindImage = backdrop ?: return null
        val card = cardImage ?: return null
        if (!Motion.enabled(activity)) return null
        val decor = activity.window.decorView as ViewGroup
        val page = activity.findViewById<View>(R.id.main) ?: return null

        val behind = ImageView(activity).apply {
            setImageBitmap(behindImage)
            scaleType = ImageView.ScaleType.FIT_XY
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        decor.addView(behind, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // The window background would paint over the snapshot; the page brings its own.
        activity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        val cardView = ImageView(activity).apply {
            setImageBitmap(card)
            scaleType = ImageView.ScaleType.FIT_XY
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, 16 * view.resources.displayMetrics.density)
            }
        }
        decor.addView(cardView, FrameLayout.LayoutParams(start.width(), start.height()).apply {
            leftMargin = start.left
            topMargin = start.top
        })
        val zoom = Zoom(activity, start, page, behind, cardView)
        page.post { zoom.open() }
        return zoom
    }
}
