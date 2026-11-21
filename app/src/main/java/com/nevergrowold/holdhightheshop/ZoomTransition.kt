package com.nevergrowold.holdhightheshop

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import java.util.concurrent.atomic.AtomicLong

/**
 * GPU-accelerated card → detail container transform.
 *
 * Key properties compared to the previous version:
 * - Zero per-frame `requestLayout()` calls (uses translation, scale and outline clip only).
 * - Zero per-frame object allocations (pre-allocated rect & scrim alpha instead of ColorMatrix).
 * - Preserves card aspect ratio cleanly (uniform width-matched scale clipped to the
 *   expanding rounded container — no FIT_XY stretching or smeared typography).
 * - Subtle spatial depth on the underlying screen (scales to 95.5% under a soft dark scrim).
 * - Keyed by a unique transition id so nested flows (`Home → Listing → Seller → Listing`)
 *   never clobber or leak each other's snapshots.
 * - Brisker close spring than open spring, shrinking cleanly back into the tapped card.
 */
object ZoomTransition {

    private const val EXTRA_ID = "zoom_transition_id"
    private const val EXTRA_LEFT = "zoom_l"
    private const val EXTRA_TOP = "zoom_t"
    private const val EXTRA_RIGHT = "zoom_r"
    private const val EXTRA_BOTTOM = "zoom_b"

    private data class Snapshot(val backdrop: Bitmap, val card: Bitmap)

    private val nextId = AtomicLong(1L)
    private val snapshots = LinkedHashMap<Long, Snapshot>(4)

    /** Launches [intent] by expanding directly out of [card]. */
    fun launch(activity: Activity, card: View, intent: Intent) {
        val decor = activity.window.decorView
        if (!Motion.enabled(activity) || card.width <= 0 || card.height <= 0 || decor.width <= 0 || decor.height <= 0) {
            activity.startActivity(intent)
            return
        }
        val behindBmp = capture(decor, 0.75f)
        val cardBmp = capture(card, 1f)
        if (behindBmp == null || cardBmp == null) {
            activity.startActivity(intent)
            return
        }

        val id = nextId.getAndIncrement()
        // Keep cache bounded; let GC reclaim evicted bitmaps once any active ImageViews detach.
        if (snapshots.size >= 3) {
            snapshots.remove(snapshots.keys.first())
        }
        snapshots[id] = Snapshot(behindBmp, cardBmp)

        val loc = IntArray(2).also { card.getLocationInWindow(it) }
        intent.putExtra(EXTRA_ID, id)
        intent.putExtra(EXTRA_LEFT, loc[0])
        intent.putExtra(EXTRA_TOP, loc[1])
        intent.putExtra(EXTRA_RIGHT, loc[0] + card.width)
        intent.putExtra(EXTRA_BOTTOM, loc[1] + card.height)

        activity.startActivity(intent)
        Motion.noPendingTransition(activity)
    }

    private fun capture(view: View, scale: Float): Bitmap? = try {
        val w = maxOf(1, (view.width * scale).toInt())
        val h = maxOf(1, (view.height * scale).toInt())
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            val canvas = Canvas(bmp)
            canvas.scale(scale, scale)
            view.draw(canvas)
        }
    } catch (_: Throwable) {
        null
    }

    /** Active container transform handle owned by the destination Activity. */
    class Zoom internal constructor(
        private val activity: Activity,
        private val transitionId: Long,
        private val start: Rect,
        private val page: View,
        private val behind: ImageView,
        private val scrim: View,
        private val cardClipHost: FrameLayout,
        private val cardImage: ImageView,
        private val detailSheet: View?,
        private val headerArea: View?,
        private val bottomBar: View?,
    ) {
        private val dp = activity.resources.displayMetrics.density
        private val startRadius = 16f * dp
        private val current = RectF()
        private val pageLoc = IntArray(2)
        private val clipRect = RectF()
        private var progress = 0f
        private var closing = false
        private var spring: SpringAnimation? = null

        private val pageChildren: List<View> =
            (page as? ViewGroup)?.let { g -> (0 until g.childCount).map { g.getChildAt(it) } } ?: listOf(page)

        init {
            page.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val r = startRadius * (1f - progress).coerceIn(0f, 1f)
                    outline.setRoundRect(
                        current.left.toInt(),
                        current.top.toInt(),
                        current.right.toInt(),
                        current.bottom.toInt(),
                        r,
                    )
                }
            }
            page.clipToOutline = true

            cardClipHost.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val r = startRadius * (1f - progress).coerceIn(0f, 1f)
                    outline.setRoundRect(
                        clipRect.left.toInt(),
                        clipRect.top.toInt(),
                        clipRect.right.toInt(),
                        clipRect.bottom.toInt(),
                        r,
                    )
                }
            }
            cardClipHost.clipToOutline = true
        }

        internal fun apply(p: Float) {
            progress = p
            val clamped = p.coerceIn(0f, 1f)
            val w = page.width.takeIf { it > 0 } ?: activity.window.decorView.width
            val h = page.height.takeIf { it > 0 } ?: activity.window.decorView.height
            page.getLocationInWindow(pageLoc)

            val startL = start.left - pageLoc[0].toFloat()
            val startT = start.top - pageLoc[1].toFloat()
            val startR = start.right - pageLoc[0].toFloat()
            val startB = start.bottom - pageLoc[1].toFloat()

            current.set(
                lerp(startL, 0f, p),
                lerp(startT, 0f, p),
                lerp(startR, w.toFloat(), p),
                lerp(startB, h.toFloat(), p),
            )
            page.invalidateOutline()

            // Clip host matches the expanding window bounds using GPU outline clipping only.
            clipRect.set(
                current.left + pageLoc[0],
                current.top + pageLoc[1],
                current.right + pageLoc[0],
                current.bottom + pageLoc[1],
            )
            cardClipHost.invalidateOutline()

            // Card bitmap scales uniformly by width (pinned to top-left of expanding container)
            // so text and photo never smear or stretch vertically.
            val startW = start.width().coerceAtLeast(1).toFloat()
            val uniformScale = (clipRect.width() / startW).coerceAtLeast(0.01f)
            cardImage.pivotX = 0f
            cardImage.pivotY = 0f
            cardImage.translationX = clipRect.left
            cardImage.translationY = clipRect.top
            cardImage.scaleX = uniformScale
            cardImage.scaleY = uniformScale

            // Clean cross-reveal: card hands off to detail content in the first ~35%.
            val cardAlpha = 1f - ((clamped - 0.02f) / 0.26f).coerceIn(0f, 1f)
            val pageAlpha = ((clamped - 0.14f) / 0.34f).coerceIn(0f, 1f)
            cardImage.alpha = cardAlpha
            pageChildren.forEach { it.alpha = pageAlpha }

            // Subtle choreographed settle on the detail sheet, floating controls and action bar.
            val detailSettle = (1f - ((clamped - 0.22f) / 0.78f).coerceIn(0f, 1f))
            detailSheet?.translationY = 18f * dp * detailSettle
            headerArea?.alpha = ((clamped - 0.35f) / 0.45f).coerceIn(0f, 1f)
            bottomBar?.translationY = 20f * dp * detailSettle

            // Underlying screen recedes subtly with depth scrim (zero per-frame allocations).
            val behindScale = 1f - 0.045f * clamped
            behind.scaleX = behindScale
            behind.scaleY = behindScale
            scrim.alpha = 0.24f * clamped
        }

        private var velocity = 0f

        private fun run(target: Float, spec: Motion.SpringSpec, done: () -> Unit) {
            val startVelocity = if (spring?.isRunning == true) velocity else 0f
            spring?.cancel()
            spring = SpringAnimation(FloatValueHolder(progress)).apply {
                spring = SpringForce(target).apply {
                    stiffness = spec.stiffness
                    dampingRatio = spec.dampingRatio
                }
                setMinimumVisibleChange(0.0015f)
                if (startVelocity != 0f) setStartVelocity(startVelocity)
                addUpdateListener { _, value, v ->
                    velocity = v
                    apply(value)
                }
                addEndListener { _, canceled, _, _ ->
                    if (!canceled) {
                        velocity = 0f
                        done()
                    }
                }
                start()
            }
        }

        internal fun open() {
            run(1f, Motion.Spatial.STANDARD) {
                page.clipToOutline = false
                pageChildren.forEach { it.alpha = 1f }
                detailSheet?.translationY = 0f
                headerArea?.alpha = 1f
                bottomBar?.translationY = 0f
                behind.visibility = View.GONE
                scrim.visibility = View.GONE
                cardClipHost.visibility = View.GONE
                activity.window.setBackgroundDrawableResource(R.color.uc_cream)
            }
        }

        /** Shrinks briskly back into the originating card, then closes cleanly. */
        fun close() {
            if (closing) return
            closing = true
            activity.window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            behind.visibility = View.VISIBLE
            scrim.visibility = View.VISIBLE
            cardClipHost.visibility = View.VISIBLE
            page.clipToOutline = true
            run(0f, Motion.Spatial.EXIT) {
                snapshots.remove(transitionId)
                Motion.finishWithoutTransition(activity)
            }
        }

        /** Releases snapshot references when the Activity is destroyed. */
        fun dispose() {
            spring?.cancel()
            snapshots.remove(transitionId)
            behind.setImageDrawable(null)
            cardImage.setImageDrawable(null)
        }

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    }

    /**
     * Attaches the container transform on the destination Activity right after
     * `setContentView`. Returns null when opened without a source card or when
     * system animations are disabled.
     */
    fun enter(activity: Activity): Zoom? {
        if (!Motion.enabled(activity)) return null
        val id = activity.intent.getLongExtra(EXTRA_ID, -1L)
        if (id < 0L) return null
        val snapshot = snapshots[id] ?: return null
        val start = Rect(
            activity.intent.getIntExtra(EXTRA_LEFT, 0),
            activity.intent.getIntExtra(EXTRA_TOP, 0),
            activity.intent.getIntExtra(EXTRA_RIGHT, 0),
            activity.intent.getIntExtra(EXTRA_BOTTOM, 0),
        )
        if (start.width() <= 0 || start.height() <= 0) return null
        Motion.noOpenTransition(activity)

        val decor = activity.window.decorView as? ViewGroup ?: return null
        val page = activity.findViewById<View>(R.id.main) ?: return null

        val behind = ImageView(activity).apply {
            setImageBitmap(snapshot.backdrop)
            scaleType = ImageView.ScaleType.FIT_XY
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val scrim = View(activity).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val cardImage = ImageView(activity).apply {
            setImageBitmap(snapshot.card)
            scaleType = ImageView.ScaleType.FIT_XY
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val cardClipHost = FrameLayout(activity).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(cardImage, FrameLayout.LayoutParams(start.width(), start.height()))
        }

        val matchParent = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        decor.addView(behind, 0, matchParent)
        decor.addView(scrim, 1, matchParent)
        decor.addView(cardClipHost, matchParent)
        activity.window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val zoom = Zoom(
            activity = activity,
            transitionId = id,
            start = start,
            page = page,
            behind = behind,
            scrim = scrim,
            cardClipHost = cardClipHost,
            cardImage = cardImage,
            detailSheet = activity.findViewById(R.id.detailSheet),
            headerArea = activity.findViewById(R.id.headerArea),
            bottomBar = activity.findViewById(R.id.bottomNav),
        )

        // Start cleanly on the very first pre-draw so the full page never flashes unclipped.
        page.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                page.viewTreeObserver.removeOnPreDrawListener(this)
                zoom.apply(0f)
                zoom.open()
                return true
            }
        })
        return zoom
    }
}
