package com.nevergrowold.holdhightheshop

import android.animation.AnimatorInflater
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.TransitionDrawable
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.AdapterView
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView

/**
 * The app's motion language, kept in one place so every screen moves the same way.
 *
 * - Feedback: pressable things sink slightly while held ([installPress]).
 * - State: chips, segments and tabs crossfade to their new look instead of
 *   snapping ([background], [textColor]).
 * - Continuity: lists that change arrive in a short stagger ([enter]); tabs
 *   fade through and pushed screens slide on one axis (themes.xml, [tabSwitch]).
 * - Moments: the splash hand-off and "Listing posted" are the only places
 *   that move just to be seen; both happen rarely.
 *
 * When the user turns animations off in system settings, every animator here
 * finishes at once, and the helpers skip setting hidden start states, so
 * nothing is ever left invisible.
 */
object Motion {

    /** Strong ease-out: feedback and state changes. */
    val EASE_OUT = PathInterpolator(0.23f, 1f, 0.32f, 1f)

    /** Material emphasized decelerate: things arriving. */
    val EMPHASIZED = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    const val STATE_MS = 160L
    const val ENTER_MS = 320L
    const val STAGGER_MS = 35L

    /** Staggers stop growing after this many items, so long lists never wait. */
    private const val STAGGER_CAP = 6

    /** False when the user switched off animations (Settings › Accessibility › Remove animations). */
    fun enabled(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    // ---------------------------------------------------------------- press

    /**
     * Gives every pressable control on the screen a small press-and-settle,
     * including ones added later (market cards, chat rows, sheet options).
     *
     * Small controls (buttons, chips, icon buttons) sink to 96%; big ones
     * (cards) to 98.5%, so the travel at the edge feels the same. Plain list
     * rows that already ripple across the full width are left alone: a whole
     * row shrinking reads as a glitch, not a press.
     */
    fun installPress(root: View) {
        scanForPress(root)
        root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (!root.isAttachedToWindow) {
                    root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    return
                }
                scanForPress(root)
            }
        })
    }

    private fun scanForPress(view: View) {
        if (view.getTag(R.id.motion_press) == null && view.width > 0) {
            pressAnimatorFor(view)?.let { view.stateListAnimator = AnimatorInflater.loadStateListAnimator(view.context, it) }
            view.setTag(R.id.motion_press, true)
        }
        if (view is ViewGroup && view !is AdapterView<*>) {
            for (i in 0 until view.childCount) scanForPress(view.getChildAt(i))
        }
    }

    private fun pressAnimatorFor(view: View): Int? {
        // Tab bar items: the icon pill sinks, not the whole tab.
        val parent = view.parent as? View
        if (parent?.id == R.id.bottomNav) {
            (view as? ViewGroup)?.getChildAt(0)?.let { pill ->
                pill.stateListAnimator = AnimatorInflater.loadStateListAnimator(view.context, R.animator.press_scale)
                pill.setTag(R.id.motion_press, true)
            }
            return null
        }
        if (!view.isClickable || view is EditText || view is CompoundButton || view is ScrollView) return null
        if (view is CampusMapView) return null

        val density = view.resources.displayMetrics.density
        val longest = maxOf(view.width, view.height) / density
        val screen = view.resources.displayMetrics.widthPixels
        if (view.width >= screen * 0.98f) return null
        if (isPlainRow(view.background) && longest > 64f) return null
        return if (longest > 140f) R.animator.press_scale_soft else R.animator.press_scale
    }

    /** A row whose only look is the ripple (or nothing): settings rows, list rows. */
    private fun isPlainRow(background: Drawable?): Boolean {
        if (background == null) return true
        if (background !is RippleDrawable) return false
        return (0 until background.numberOfLayers).all { background.getId(it) == android.R.id.mask }
    }

    // ---------------------------------------------------------------- state

    /**
     * Sets a view's background, crossfading from the old one when [animate].
     * Used for chips, pills, segments and radio rows when the user picks one.
     */
    fun background(view: View, resId: Int, animate: Boolean) {
        val next = view.context.getDrawable(resId) ?: ColorDrawable(Color.TRANSPARENT)
        swap(view, next, animate)
    }

    fun backgroundColor(view: View, color: Int, animate: Boolean) = swap(view, ColorDrawable(color), animate)

    private fun swap(view: View, next: Drawable, animate: Boolean) {
        val old = view.background?.let { (it as? TransitionDrawable)?.getDrawable(1) ?: it }
        if (!animate || old == null || !enabled(view.context)) {
            view.background = next
            return
        }
        val fade = TransitionDrawable(arrayOf(old.constantState?.newDrawable()?.mutate() ?: old, next))
        fade.isCrossFadeEnabled = true
        view.background = fade
        fade.startTransition(STATE_MS.toInt())
    }

    /** Sets a text colour, blending from the current one when [animate]. */
    fun textColor(view: TextView, color: Int, animate: Boolean) {
        (view.getTag(R.id.motion_color) as? ValueAnimator)?.cancel()
        val from = view.currentTextColor
        if (!animate || from == color || !enabled(view.context)) {
            view.setTextColor(color)
            return
        }
        val anim = ValueAnimator.ofArgb(from, color).apply {
            duration = STATE_MS
            interpolator = EASE_OUT
            addUpdateListener { view.setTextColor(it.animatedValue as Int) }
        }
        view.setTag(R.id.motion_color, anim)
        anim.start()
    }

    /**
     * Grows a tab underline or indicator out from its centre. Used when the
     * chosen tab changes, so the eye follows the selection.
     */
    fun grow(view: View, delay: Long = 0) {
        if (!enabled(view.context)) return
        view.animate().cancel()
        view.scaleX = 0.3f
        view.alpha = 0f
        view.animate().scaleX(1f).alpha(1f)
            .setStartDelay(delay).setDuration(260).setInterpolator(EMPHASIZED).start()
    }

    /**
     * A quick sink-and-spring for toggles the user just flipped, such as the
     * bookmark. Feedback, so it is short; the spring is small on purpose.
     */
    fun pop(view: View) {
        if (!enabled(view.context)) return
        view.animate().cancel()
        view.scaleX = 0.8f
        view.scaleY = 0.8f
        view.animate().scaleX(1f).scaleY(1f)
            .setStartDelay(0).setDuration(320).setInterpolator(OvershootInterpolator(2.2f)).start()
    }

    // ---------------------------------------------------------------- arrivals

    /**
     * Brings views in one after another: a short rise and fade, 35ms apart.
     * For content that changed because the user asked (a new filter, a new
     * screen's main list), never for content that is merely scrolled to.
     */
    fun enter(views: List<View>, delay: Long = 0, rise: Int = 16) {
        val first = views.firstOrNull() ?: return
        if (!enabled(first.context)) return
        val dy = rise * first.resources.displayMetrics.density
        views.forEachIndexed { i, v ->
            v.animate().cancel()
            v.alpha = 0f
            v.translationY = dy
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(delay + STAGGER_MS * minOf(i, STAGGER_CAP))
                .setDuration(ENTER_MS)
                .setInterpolator(EMPHASIZED)
                .withLayer()
                .start()
        }
    }

    /**
     * Fade-through between tab screens: they are peers, so no sideways travel.
     * Call right after startActivity.
     */
    @Suppress("DEPRECATION")
    fun tabSwitch(activity: Activity) = activity.overridePendingTransition(R.anim.tab_enter, R.anim.tab_exit)
}
