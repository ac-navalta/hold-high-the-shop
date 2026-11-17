package com.nevergrowold.holdhightheshop

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
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

    /** Finger down: a firm, quick sink with no wobble. */
    private val PRESS_IN by lazy { Spring(0.2f, 0f) }

    /** Finger up: springs back past its size by a hair, then settles. */
    private val PRESS_OUT by lazy { Spring(0.5f, 0.45f) }

    /**
     * Gives every pressable control on the screen a spring press, including
     * ones added later (market cards, chat rows, sheet options): it sinks while
     * held and bounces back when let go. Being a spring, a quick tap that
     * releases mid-sink turns around smoothly instead of snapping.
     *
     * Small controls (buttons, chips, icon buttons) sink to 95%; big ones
     * (cards) to 98%, so the travel at the edge feels the same. Plain list
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
            view.setTag(R.id.motion_press, true)
            pressTarget(view)
        }
        if (view is ViewGroup && view !is AdapterView<*>) {
            for (i in 0 until view.childCount) scanForPress(view.getChildAt(i))
        }
    }

    private fun pressTarget(view: View) {
        // Tab bar items: the icon pill sinks, not the whole tab.
        val parent = view.parent as? View
        if (parent?.id == R.id.bottomNav) {
            (view as? ViewGroup)?.getChildAt(0)?.let { pill -> listenForPress(pill, pill, 0.86f) }
            return
        }
        if (!view.isClickable || view is EditText || view is CompoundButton || view is ScrollView) return
        if (view is CampusMapView) return

        val density = view.resources.displayMetrics.density
        val longest = maxOf(view.width, view.height) / density
        val screen = view.resources.displayMetrics.widthPixels
        if (view.width >= screen * 0.98f) return
        if (isPlainRow(view.background) && longest > 64f) return
        val depth = if (longest > 140f) 0.98f else 0.95f
        // Material cards own their foreground; their content child sees the same pressed state.
        val host = if (view is com.google.android.material.card.MaterialCardView) view.getChildAt(0) ?: return else view
        listenForPress(host, view, depth)
    }

    /** Watches [host]'s pressed state and springs [target] down to [depth] and back. */
    private fun listenForPress(host: View, target: View, depth: Float) {
        val spy = PressSpy { down -> springScale(target, if (down) depth else 1f, if (down) PRESS_IN else PRESS_OUT) }
        val current = host.foreground
        host.foreground = if (current == null) spy else LayerDrawable(arrayOf(current, spy))
    }

    /**
     * An invisible drawable that only listens: views hand their drawables
     * every state change, so this hears "pressed" exactly when the ripple
     * does, including the tap delay inside scrolling lists.
     */
    private class PressSpy(private val onPressed: (Boolean) -> Unit) : Drawable() {
        private var pressed = false
        override fun isStateful() = true
        override fun onStateChange(state: IntArray): Boolean {
            val now = android.R.attr.state_pressed in state && android.R.attr.state_enabled in state
            if (now != pressed) {
                pressed = now
                onPressed(now)
            }
            return false
        }
        override fun draw(canvas: android.graphics.Canvas) {}
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSPARENT
    }

    // ---------------------------------------------------------------- haptics

    /** A light tick: selecting a chip, a segment, a tab, a toggle. */
    fun tick(view: View) {
        view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }

    /** A firmer confirm: something was saved, sent or finished. */
    fun confirm(view: View) {
        view.performHapticFeedback(
            if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM
            else android.view.HapticFeedbackConstants.VIRTUAL_KEY
        )
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
     * Moves a selection pill from [from] to [to] on a spring, as one object
     * sliding along its track rather than two views fading. [to] gets the
     * pill as its background, drawn shifted back to [from]'s position at
     * first and sprung home; the parent stops clipping so it can travel.
     */
    fun slidePill(from: View, to: View, resId: Int) {
        val pill = to.context.getDrawable(resId) ?: return
        from.background = ColorDrawable(Color.TRANSPARENT)
        if (!enabled(to.context)) {
            to.background = pill
            return
        }
        (to.parent as? ViewGroup)?.clipChildren = false
        val slide = SlideDrawable(pill, (from.left - to.left).toFloat())
        to.background = slide
        SpringAnimation(androidx.dynamicanimation.animation.FloatValueHolder(slide.offset)).apply {
            spring = SpringForce(0f).apply { stiffness = SNAPPY.stiffness; dampingRatio = 0.72f }
            setMinimumVisibleChange(1f)
            addUpdateListener { _, value, _ -> slide.offset = value; slide.invalidateSelf() }
            start()
        }
    }

    /** Draws another drawable shifted sideways by [offset] pixels. */
    private class SlideDrawable(private val inner: Drawable, var offset: Float) :
        android.graphics.drawable.DrawableWrapper(inner) {
        override fun draw(canvas: android.graphics.Canvas) {
            val save = canvas.save()
            canvas.translate(offset, 0f)
            inner.draw(canvas)
            canvas.restoreToCount(save)
        }
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

    /**
     * Tab change, played by the screen that just arrived. Every tab screen
     * has the same bar, so if the new bar's pill starts where the old one was
     * and springs to its own tab, the bar looks like it never left:
     * - the pill travels, stretched while it moves, settling with a small bounce;
     * - the new tab's icon bounces up and its label lifts into place;
     * - the page drifts in from the side of the tab you came from;
     * - a Post button springs in.
     */
    fun navSlide(activity: Activity, from: Int, to: Int) {
        if (!enabled(activity)) return
        val nav = activity.findViewById<ViewGroup>(R.id.bottomNav) ?: return
        val pill = activity.findViewById<View>(R.id.navIndicator) ?: return
        val root = activity.findViewById<ViewGroup>(R.id.main) ?: return
        val dp = activity.resources.displayMetrics.density
        val direction = if (to > from) 1 else -1

        // The page: everything but the bar starts a little to the side.
        val page = (0 until root.childCount).map { root.getChildAt(it) }.filter { it !== nav && it.id != R.id.fabPost }
        page.forEach { it.translationX = direction * 36 * dp }
        activity.findViewById<View>(R.id.fabPost)?.let { it.scaleX = 0.6f; it.scaleY = 0.6f; it.alpha = 0f }
        pill.alpha = 0f

        nav.clipChildren = false
        (0 until nav.childCount).forEach { (nav.getChildAt(it) as? ViewGroup)?.clipChildren = false }
        nav.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                nav.viewTreeObserver.removeOnPreDrawListener(this)
                val fromItem = nav.getChildAt(from) ?: return true
                val toItem = nav.getChildAt(to) ?: return true
                fun centre(v: View) = v.left + v.width / 2f

                pill.alpha = 1f
                pill.translationX = centre(fromItem) - centre(toItem)
                pill.scaleX = 1.45f
                spring(pill, DynamicAnimation.TRANSLATION_X, 0f, Spring(0.5f, 0.22f))
                spring(pill, DynamicAnimation.SCALE_X, 1f, Spring(0.55f, 0.3f))

                (pill as? ViewGroup)?.getChildAt(0)?.let { icon ->
                    icon.scaleX = 0.55f
                    icon.scaleY = 0.55f
                    springScale(icon, 1f, BOUNCY)
                }
                (toItem as? ViewGroup)?.getChildAt(1)?.let { label ->
                    label.translationY = 5 * dp
                    spring(label, DynamicAnimation.TRANSLATION_Y, 0f, BOUNCY)
                }
                page.forEach { spring(it, DynamicAnimation.TRANSLATION_X, 0f, Spring(0.5f, 0f)) }
                activity.findViewById<View>(R.id.fabPost)?.let { fab ->
                    spring(fab, DynamicAnimation.ALPHA, 1f, SMOOTH)
                    springScale(fab, 1f, BOUNCY)
                }
                return true
            }
        })
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

    /** A sink-and-spring for something the user just flipped: from [from] back to full size. */
    fun pop(view: View, from: Float = 0.8f) {
        if (!enabled(view.context)) return
        view.scaleX = minOf(view.scaleX, from)
        view.scaleY = minOf(view.scaleY, from)
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

    /** Numbers already counted up this session, so a revisit shows them still. */
    private val counted = mutableSetOf<Int>()

    /**
     * Counts a number up from zero the first time a screen shows it in this
     * session (Home stats, the reliability card). Keeps any suffix ("93%")
     * and decimals ("4.8"). Later visits show the value at rest.
     */
    fun countUp(view: TextView, value: String, delay: Long = 0) {
        val match = Regex("^(\\d+)(?:\\.(\\d+))?(.*)$").find(value)
        if (!enabled(view.context) || match == null || !counted.add(view.id)) {
            view.text = value
            return
        }
        val decimals = match.groupValues[2].length
        val suffix = match.groupValues[3]
        val target = value.removeSuffix(suffix).toFloat()
        view.text = "0" + suffix
        ValueAnimator.ofFloat(0f, target).apply {
            duration = 900
            startDelay = delay
            interpolator = EMPHASIZED
            addUpdateListener {
                val v = it.animatedValue as Float
                view.text = (if (decimals > 0) "%.${decimals}f".format(v) else v.toInt().toString()) + suffix
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { view.text = value }
            })
            start()
        }
    }

    /**
     * Contents of a sheet or dropdown settling in just behind the surface
     * itself: each row rises on a spring, a beat after the one above it.
     */
    fun settle(views: List<View>, delay: Long = 40, rise: Int = 14, gap: Long = 28) {
        val first = views.firstOrNull() ?: return
        if (!enabled(first.context)) return
        val dy = rise * first.resources.displayMetrics.density
        views.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = dy
            v.postDelayed({
                spring(v, DynamicAnimation.ALPHA, 1f, SMOOTH)
                spring(v, DynamicAnimation.TRANSLATION_Y, 0f, Spring(0.45f, 0.2f))
            }, delay + gap * minOf(i, STAGGER_CAP + 2))
        }
    }

    /**
     * Springs a floating button away while the user scrolls down through
     * content and back when they scroll up, so it never covers what they read.
     */
    fun hideOnScroll(scroll: View, fab: View) {
        var hidden = false
        val travel = 120 * fab.resources.displayMetrics.density
        scroll.setOnScrollChangeListener { _, _, y, _, oldY ->
            val down = y > oldY + 2
            val up = y < oldY - 2
            if (down && !hidden && y > 40) {
                hidden = true
                spring(fab, DynamicAnimation.TRANSLATION_Y, travel, Spring(0.4f, 0f))
            } else if (up && hidden) {
                hidden = false
                spring(fab, DynamicAnimation.TRANSLATION_Y, 0f, Spring(0.5f, 0.3f))
            }
        }
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
