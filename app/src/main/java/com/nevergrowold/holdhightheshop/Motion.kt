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
import android.view.animation.PathInterpolator
import android.widget.AdapterView
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * The app's motion language, kept in one place so every screen moves the same way.
 *
 * - Feedback: pressable things sink slightly while held ([installPress]).
 * - State: chips, segments and tabs crossfade to their new look instead of
 *   snapping ([background], [textColor]).
 * - Continuity: lists that change arrive in a short stagger ([enter]); tabs
 *   fade through and pushed screens slide on one axis (themes.xml, [tabSwitch]);
 *   a listing card grows into its screen (BaseActivity.openListingFromCard).
 * - Springs ([spring]): toggles, indicators and inserted rows settle on
 *   interruptible springs that keep their velocity when tapped again.
 * - Content: counters roll to new values ([rollText]); rows grow in and slide
 *   out ([insert], [remove]); icons wiggle, breathe or swap ([wiggle],
 *   [breathe], [replaceIcon]).
 * - Moments: the splash hand-off and "Listing posted" are the only places
 *   that move just to be seen; both happen rarely.
 *
 * When the user turns animations off in system settings, every animator here
 * finishes at once, springs jump to their target, loops never start, and the
 * helpers skip setting hidden start states, so nothing is ever left invisible.
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

    // ---------------------------------------------------------------- springs

    /**
     * A spring described the way designers talk about it: how long it takes
     * to settle and how much it bounces (0 = none). Springs keep their
     * velocity when retargeted, so a second tap mid-animation carries on
     * smoothly instead of restarting.
     */
    class Spring(val duration: Float, val bounce: Float) {
        // Perceptual to physical: a unit-mass spring with this period and damping.
        val stiffness = ((2 * Math.PI / duration) * (2 * Math.PI / duration)).toFloat()
        val damping = 1f - bounce
    }

    /** Fluid, no overshoot: indicators, things settling into place. */
    val SMOOTH = Spring(0.45f, 0f)

    /** Responsive with a hint of overshoot: feedback on a tap. */
    val SNAPPY = Spring(0.35f, 0.15f)

    /** Playful, visible bounce: kept for toggles and the posted badge. */
    val BOUNCY = Spring(0.5f, 0.32f)

    /**
     * Springs [property] of [view] to [target]. Reuses a running spring on the
     * same property so interruptions keep their momentum. With animations
     * switched off it jumps straight to the target (springs ignore the system
     * animator scale, so this check is not optional).
     */
    fun spring(
        view: View,
        property: DynamicAnimation.ViewProperty,
        target: Float,
        spec: Spring = SNAPPY,
        startVelocity: Float? = null,
    ): SpringAnimation? {
        if (!enabled(view.context)) {
            property.setValue(view, target)
            return null
        }
        val key = springKey(property)
        val anim = (view.getTag(key) as? SpringAnimation) ?: SpringAnimation(view, property).also { view.setTag(key, it) }
        anim.spring = (anim.spring ?: SpringForce()).apply {
            stiffness = spec.stiffness
            dampingRatio = spec.damping
            finalPosition = target
        }
        if (property == DynamicAnimation.ALPHA) anim.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_ALPHA)
        if (property == DynamicAnimation.SCALE_X || property == DynamicAnimation.SCALE_Y) {
            anim.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
        }
        if (property == DynamicAnimation.ROTATION) anim.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_ROTATION_DEGREES)
        startVelocity?.let { anim.setStartVelocity(it) }
        anim.start()
        return anim
    }

    private fun springKey(property: DynamicAnimation.ViewProperty) = when (property) {
        DynamicAnimation.SCALE_X -> R.id.spring_scale_x
        DynamicAnimation.SCALE_Y -> R.id.spring_scale_y
        DynamicAnimation.ALPHA -> R.id.spring_alpha
        DynamicAnimation.ROTATION -> R.id.spring_rotation
        DynamicAnimation.TRANSLATION_X -> R.id.spring_translation_x
        else -> R.id.spring_translation_y
    }

    /** Springs both scale axes together. */
    fun springScale(view: View, target: Float, spec: Spring = SNAPPY) {
        spring(view, DynamicAnimation.SCALE_X, target, spec)
        spring(view, DynamicAnimation.SCALE_Y, target, spec)
    }

    /**
     * Grows a tab underline or indicator out from its centre on a smooth
     * spring. Used when the chosen tab changes, so the eye follows the selection.
     */
    fun grow(view: View, delay: Long = 0) {
        if (!enabled(view.context)) return
        view.scaleX = 0.3f
        view.alpha = 0f
        view.postDelayed({
            spring(view, DynamicAnimation.SCALE_X, 1f, SMOOTH)
            spring(view, DynamicAnimation.ALPHA, 1f, SMOOTH)
        }, delay)
    }

    // ---------------------------------------------------------------- icon effects

    /**
     * Icon swap with a bounce, like an SF Symbol `.replace` + `.bounce`: the
     * old glyph shrinks away, the new one is set, and it springs back in.
     * For toggles such as the bookmark.
     */
    fun replaceIcon(view: android.widget.ImageView, swap: () -> Unit) {
        if (!enabled(view.context)) {
            swap()
            return
        }
        view.animate().cancel()
        view.animate().scaleX(0.6f).scaleY(0.6f).alpha(0.4f).setDuration(90).setStartDelay(0)
            .setInterpolator(EASE_OUT)
            .withEndAction {
                swap()
                view.alpha = 1f
                springScale(view, 1f, BOUNCY)
            }.start()
    }

    /** A sink-and-spring for something the user just flipped. */
    fun pop(view: View) {
        if (!enabled(view.context)) return
        view.scaleX = 0.8f
        view.scaleY = 0.8f
        springScale(view, 1f, BOUNCY)
    }

    /**
     * A bell-style wiggle (SF Symbol `.wiggle`): swings from its top a few
     * times, each smaller. Draws the eye to something new once, on arrival.
     */
    fun wiggle(view: View, delay: Long = 0) {
        if (!enabled(view.context)) return
        view.pivotX = view.width / 2f
        view.pivotY = 0f
        val rotation = android.animation.PropertyValuesHolder.ofKeyframe(
            View.ROTATION,
            android.animation.Keyframe.ofFloat(0f, 0f),
            android.animation.Keyframe.ofFloat(0.15f, 16f),
            android.animation.Keyframe.ofFloat(0.32f, -13f),
            android.animation.Keyframe.ofFloat(0.5f, 9f),
            android.animation.Keyframe.ofFloat(0.68f, -5f),
            android.animation.Keyframe.ofFloat(0.84f, 2f),
            android.animation.Keyframe.ofFloat(1f, 0f),
        )
        android.animation.ObjectAnimator.ofPropertyValuesHolder(view, rotation).apply {
            duration = 720
            startDelay = delay
            interpolator = android.view.animation.LinearInterpolator()
            start()
        }
    }

    /**
     * A slow breathe that runs while a condition holds, such as an overdue
     * loan (SF Symbol `.breathe`, a repeating PhaseAnimator). The caller stops
     * it when the screen pauses or the condition clears. Never runs with
     * animations off: a looping cue is exactly what that setting asks to remove.
     */
    fun breathe(view: View): android.animation.Animator? {
        if (!enabled(view.context)) return null
        val scale = android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.14f)
        val scaleY = android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.14f)
        val alpha = android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.7f)
        return android.animation.ObjectAnimator.ofPropertyValuesHolder(view, scale, scaleY, alpha).apply {
            duration = 1100
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            interpolator = android.view.animation.PathInterpolator(0.37f, 0f, 0.63f, 1f)
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    view.scaleX = 1f; view.scaleY = 1f; view.alpha = 1f
                }
            })
            start()
        }
    }

    // ---------------------------------------------------------------- content

    /**
     * Rolls a number to its new value in place (SwiftUI `.numericText`): the
     * old text slides out one way, the new one in from the other. Going up,
     * digits roll upward; going down, downward. Anything else just swaps.
     */
    fun rollText(view: TextView, text: CharSequence) {
        // A newer value always wins: stop any roll in flight and put the text back at rest first.
        view.animate().cancel()
        view.alpha = 1f
        view.translationY = 0f
        val old = view.text?.toString().orEmpty()
        if (old == text.toString()) return
        if (!enabled(view.context) || old.isEmpty() || !view.isLaidOut) {
            view.text = text
            return
        }
        val before = Regex("\\d+").find(old)?.value?.toIntOrNull()
        val after = Regex("\\d+").find(text)?.value?.toIntOrNull()
        val up = before == null || after == null || after >= before
        val dy = view.height * 0.6f * (if (up) -1 else 1)
        view.animate().translationY(dy).alpha(0f).setDuration(110).setStartDelay(0)
            .setInterpolator(EASE_OUT)
            .withEndAction {
                view.text = text
                view.translationY = -dy
                view.animate().translationY(0f).alpha(1f).setDuration(240)
                    .setInterpolator(EMPHASIZED).start()
            }.start()
    }

    // ---------------------------------------------------------------- insert / remove

    /**
     * Inserts a view by growing it from a corner (a chat bubble from the side
     * it was sent from). Asymmetric with [remove] on purpose: arrivals are
     * springy and noticed, departures are quick.
     */
    fun insert(view: View, fromEnd: Boolean) {
        if (!enabled(view.context)) return
        view.alpha = 0f
        view.scaleX = 0.85f
        view.scaleY = 0.85f
        view.translationY = 12 * view.resources.displayMetrics.density
        view.post {
            view.pivotX = if (fromEnd) view.width.toFloat() else 0f
            view.pivotY = view.height.toFloat()
            spring(view, DynamicAnimation.ALPHA, 1f, SMOOTH)
            spring(view, DynamicAnimation.TRANSLATION_Y, 0f, SNAPPY)
            springScale(view, 1f, SNAPPY)
        }
    }

    /**
     * Removes a row: it slides aside and fades, then the space closes up,
     * then [onEnd] runs (usually a re-render). Quick, so it never blocks.
     */
    fun remove(view: View, onEnd: () -> Unit) {
        if (!enabled(view.context)) {
            onEnd()
            return
        }
        val parent = view.parent as? ViewGroup
        val dx = view.width * 0.25f
        view.animate().cancel()
        view.animate().translationX(dx).alpha(0f).setDuration(180).setStartDelay(0)
            .setInterpolator(EASE_OUT).withLayer()
            .withEndAction {
                val start = view.height
                ValueAnimator.ofInt(start, 0).apply {
                    duration = 200
                    interpolator = EMPHASIZED
                    addUpdateListener {
                        view.layoutParams.height = it.animatedValue as Int
                        view.requestLayout()
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            parent?.post(onEnd) ?: onEnd()
                        }
                    })
                    start()
                }
            }.start()
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
