package com.nevergrowold.holdhightheshop

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.TransitionDrawable
import android.os.Build
import android.provider.Settings
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.AdapterView
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.card.MaterialCardView

/**
 * Shared physics-based motion system built around Material 3 Expressive tokens
 * and interruptible spring dynamics.
 *
 * Principles:
 * - Spatial motion (position, scale, rotation, size) uses damped springs with
 *   restraint so motion feels taut, physical and immediately interruptible.
 * - Effects motion (opacity, tint, press sink) is critically damped with zero
 *   overshoot so surfaces never wobble or flicker.
 * - Exits are faster than entrances; frequent controls stay crisp (< 250ms);
 *   expressive bounce is reserved for rare moments of delight.
 * - System reduced motion (`ANIMATOR_DURATION_SCALE == 0`) is respected
 *   everywhere: springs snap to their target, loops never start, and nothing
 *   is ever left hidden.
 */
object Motion {

    /** Physics specification for a unit-mass spring. */
    data class SpringSpec(val stiffness: Float, val dampingRatio: Float)

    /** Spatial springs: position, scale, rotation, indicator travel. */
    object Spatial {
        /** Fast, crisp tactile settles: button release, chip focus, chevrons, badges. */
        val SNAPPY = SpringSpec(stiffness = 1200f, dampingRatio = 0.78f)

        /** Fluid tracked motion: bottom nav pill, segment pill, tab underline, FAB, chat bubbles. */
        val RESPONSIVE = SpringSpec(stiffness = 650f, dampingRatio = 0.80f)

        /** Authoritative larger transitions: card zoom open, page shifts. */
        val STANDARD = SpringSpec(stiffness = 460f, dampingRatio = 0.88f)

        /** Brisk, grounded exits: card zoom close, FAB dismiss. */
        val EXIT = SpringSpec(stiffness = 580f, dampingRatio = 0.92f)

        /** Playful highlight reserved for rare delight: bookmark save, rating stars, celebration stamp. */
        val EXPRESSIVE = SpringSpec(stiffness = 520f, dampingRatio = 0.62f)
    }

    /** Critically damped springs (zero overshoot): press sink, opacity, subtle settles. */
    object Effects {
        val FAST = SpringSpec(stiffness = 3800f, dampingRatio = 1.0f)
        val DEFAULT = SpringSpec(stiffness = 1600f, dampingRatio = 1.0f)
        val SLOW = SpringSpec(stiffness = 800f, dampingRatio = 1.0f)
    }

    /** Custom easing curves for color blends, layout transitions and view-property fades. */
    val EASE_OUT by lazy { PathInterpolator(0.22f, 1f, 0.36f, 1f) }
    val EMPHASIZED by lazy { PathInterpolator(0.05f, 0.7f, 0.1f, 1f) }
    val STANDARD_CURVE by lazy { PathInterpolator(0.2f, 0f, 0f, 1f) }

    const val STATE_MS = 140L
    const val ENTER_MS = 220L
    const val STAGGER_MS = 22L
    private const val STAGGER_CAP = 6

    /** True unless the user disabled animations in system Accessibility settings. */
    fun enabled(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    // ---------------------------------------------------------------- core springs

    /**
     * Springs [property] of [view] to [target]. Reuses an active spring on the
     * same property so mid-flight retargets keep their current velocity.
     */
    fun spring(
        view: View,
        property: DynamicAnimation.ViewProperty,
        target: Float,
        spec: SpringSpec = Spatial.SNAPPY,
        startVelocity: Float? = null,
    ): SpringAnimation? {
        if (!enabled(view.context)) {
            cancelSpring(view, property)
            property.setValue(view, target)
            return null
        }
        ensureAttachCleanup(view)
        val key = springKey(property)
        val anim = (view.getTag(key) as? SpringAnimation) ?: SpringAnimation(view, property).also {
            configureMinVisibleChange(it, property)
            view.setTag(key, it)
        }
        val force = anim.spring ?: SpringForce(target).also { anim.spring = it }
        force.stiffness = spec.stiffness
        force.dampingRatio = spec.dampingRatio
        force.finalPosition = target
        if (startVelocity != null) {
            anim.setStartVelocity(startVelocity)
        }
        if (anim.isRunning) {
            anim.animateToFinalPosition(target)
        } else {
            anim.start()
        }
        return anim
    }

    fun springScale(
        view: View,
        target: Float,
        spec: SpringSpec = Spatial.SNAPPY,
        startVelocity: Float? = null,
    ) {
        spring(view, DynamicAnimation.SCALE_X, target, spec, startVelocity)
        spring(view, DynamicAnimation.SCALE_Y, target, spec, startVelocity)
    }

    fun cancelSpring(view: View, property: DynamicAnimation.ViewProperty) {
        (view.getTag(springKey(property)) as? SpringAnimation)?.cancel()
    }

    fun cancelAllSprings(view: View) {
        SPRING_TAG_IDS.forEach { id -> (view.getTag(id) as? SpringAnimation)?.cancel() }
    }

    private fun configureMinVisibleChange(anim: SpringAnimation, property: DynamicAnimation.ViewProperty) {
        when (property) {
            DynamicAnimation.ALPHA -> anim.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_ALPHA)
            DynamicAnimation.SCALE_X, DynamicAnimation.SCALE_Y ->
                anim.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
            DynamicAnimation.ROTATION ->
                anim.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_ROTATION_DEGREES)
            else -> anim.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS)
        }
    }

    private fun ensureAttachCleanup(view: View) {
        if (view.getTag(R.id.motion_attach_cleanup) != null) return
        view.setTag(R.id.motion_attach_cleanup, true)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                cancelAllSprings(v)
                (v.getTag(R.id.motion_color) as? Animator)?.cancel()
                (v.getTag(R.id.motion_tint) as? Animator)?.cancel()
                (v.getTag(R.id.motion_count_anim) as? Animator)?.cancel()
                (v.getTag(R.id.motion_slide_anim) as? SlideState)?.anim?.cancel()
            }
        })
    }

    private fun springKey(property: DynamicAnimation.ViewProperty) = when (property) {
        DynamicAnimation.SCALE_X -> R.id.spring_scale_x
        DynamicAnimation.SCALE_Y -> R.id.spring_scale_y
        DynamicAnimation.ALPHA -> R.id.spring_alpha
        DynamicAnimation.ROTATION -> R.id.spring_rotation
        DynamicAnimation.TRANSLATION_X -> R.id.spring_translation_x
        DynamicAnimation.TRANSLATION_Z -> R.id.spring_translation_z
        else -> R.id.spring_translation_y
    }

    private val SPRING_TAG_IDS = intArrayOf(
        R.id.spring_scale_x,
        R.id.spring_scale_y,
        R.id.spring_alpha,
        R.id.spring_rotation,
        R.id.spring_translation_x,
        R.id.spring_translation_y,
        R.id.spring_translation_z,
    )

    // ---------------------------------------------------------------- haptics

    /** Light crisp detent: selecting a chip, segment, tab, switch or star. */
    fun tick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Firm positive confirm: saving, posting, completing a flow. */
    fun confirm(view: View) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.VIRTUAL_KEY
        )
    }

    /** Distinct rejection buzz: missing required field or wrong handover code. */
    fun reject(view: View) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT
            else HapticFeedbackConstants.LONG_PRESS
        )
    }

    // ---------------------------------------------------------------- tactile press

    /**
     * Installs crisp, physics-driven press feedback across [root] and any
     * controls inflated later (market cards, bottom sheets, dropdowns).
     *
     * Pressing sinks immediately (`Effects.FAST`, zero lag); releasing settles
     * cleanly (`Spatial.SNAPPY`, taut with zero sluggish wobble). Cards also
     * gently depress their Z shadow while held. Full-width plain list rows keep
     * their native ripple untouched.
     */
    fun installPress(root: View) {
        scanForPress(root)
        var pending = false
        val runScan = Runnable {
            pending = false
            if (root.isAttachedToWindow) scanForPress(root)
        }
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (!root.isAttachedToWindow) {
                    root.removeCallbacks(runScan)
                    root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    return
                }
                if (!pending) {
                    pending = true
                    root.post(runScan)
                }
            }
        }
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    private fun scanForPress(view: View) {
        if (view.getTag(R.id.motion_press) == null && view.width > 0) {
            view.setTag(R.id.motion_press, true)
            attachPressIfEligible(view)
        }
        if (view is ViewGroup && view !is AdapterView<*>) {
            for (i in 0 until view.childCount) scanForPress(view.getChildAt(i))
        }
    }

    private fun attachPressIfEligible(view: View) {
        val parent = view.parent as? ViewGroup
        // Persistent bottom tab slots: gently press only the icon container, never the full bar slot.
        if (parent?.id == R.id.navSlots) {
            if (view.isClickable) {
                val iconHolder = (view as? ViewGroup)?.getChildAt(0) ?: return
                listenForPress(view, iconHolder, fixedDepth = 0.88f, depressCard = false)
            }
            return
        }
        if (!view.isClickable || view is EditText || view is CompoundButton || view is ScrollView) return
        if (view is CampusMapView) return

        val density = view.resources.displayMetrics.density
        val screenWidth = view.resources.displayMetrics.widthPixels
        val longestDp = maxOf(view.width, view.height) / density
        // Full-width rows (chats, settings, notifications, dropdown rows) keep ripple only.
        if (view.width >= screenWidth * 0.96f) return
        if (isPlainRow(view.background) && longestDp > 68f) return

        val isCard = view is MaterialCardView
        // MaterialCardView owns its rounded-corner foreground; its content child receives pressed state.
        val host = if (isCard) view.getChildAt(0) ?: return else view
        listenForPress(host, view, fixedDepth = null, depressCard = isCard)
    }

    private fun listenForPress(host: View, target: View, fixedDepth: Float?, depressCard: Boolean) {
        val dp = target.resources.displayMetrics.density
        val spy = PressSpy { down ->
            val depth = fixedDepth ?: run {
                val longestDp = maxOf(target.width, target.height) / dp
                when {
                    longestDp <= 64f -> 0.93f
                    longestDp <= 150f -> 0.96f
                    else -> 0.978f
                }
            }
            if (target.width > 0 && target.height > 0) {
                target.pivotX = target.width * 0.5f
                target.pivotY = target.height * 0.5f
            }
            val spec = if (down) Effects.FAST else Spatial.SNAPPY
            springScale(target, if (down) depth else 1f, spec)
            if (depressCard) {
                spring(target, DynamicAnimation.TRANSLATION_Z, if (down) -1.5f * dp else 0f, spec)
            }
        }
        val current = host.foreground
        host.foreground = if (current == null) spy else LayerDrawable(arrayOf(current, spy))
    }

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
        override fun draw(canvas: Canvas) {}
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSPARENT
    }

    private fun isPlainRow(background: Drawable?): Boolean {
        if (background == null) return true
        if (background !is RippleDrawable) return false
        return (0 until background.numberOfLayers).all { background.getId(it) == android.R.id.mask }
    }

    // ---------------------------------------------------------------- state & color

    /** Crossfades a view's background smoothly when [animate] is true. */
    fun background(view: View, resId: Int, animate: Boolean) {
        val next = AppCompatResources.getDrawable(view.context, resId) ?: ColorDrawable(Color.TRANSPARENT)
        swapBackground(view, next, animate)
    }

    fun backgroundColor(view: View, color: Int, animate: Boolean) {
        swapBackground(view, ColorDrawable(color), animate)
    }

    private fun swapBackground(view: View, next: Drawable, animate: Boolean) {
        val current = view.background?.let { (it as? TransitionDrawable)?.getDrawable(1) ?: it }
        if (!animate || current == null || !enabled(view.context)) {
            view.background = next
            return
        }
        val start = current.constantState?.newDrawable(view.resources)?.mutate() ?: current
        val fade = TransitionDrawable(arrayOf(start, next)).apply {
            isCrossFadeEnabled = true
        }
        view.background = fade
        fade.startTransition(STATE_MS.toInt())
    }

    /** Smoothly blends a TextView's color on state change. */
    fun textColor(view: TextView, color: Int, animate: Boolean) {
        (view.getTag(R.id.motion_color) as? ValueAnimator)?.cancel()
        val from = view.currentTextColor
        if (!animate || from == color || !enabled(view.context)) {
            view.setTextColor(color)
            return
        }
        ensureAttachCleanup(view)
        val anim = ValueAnimator.ofArgb(from, color).apply {
            duration = STATE_MS
            interpolator = EASE_OUT
            addUpdateListener { view.setTextColor(it.animatedValue as Int) }
        }
        view.setTag(R.id.motion_color, anim)
        anim.start()
    }

    /** Smoothly blends an ImageView's color filter tint on state change. */
    fun iconTint(view: ImageView, fromColor: Int, toColor: Int, animate: Boolean) {
        (view.getTag(R.id.motion_tint) as? ValueAnimator)?.cancel()
        if (!animate || fromColor == toColor || !enabled(view.context)) {
            view.setColorFilter(toColor)
            return
        }
        ensureAttachCleanup(view)
        val anim = ValueAnimator.ofArgb(fromColor, toColor).apply {
            duration = STATE_MS
            interpolator = EASE_OUT
            addUpdateListener { view.setColorFilter(it.animatedValue as Int) }
        }
        view.setTag(R.id.motion_tint, anim)
        anim.start()
    }

    // ---------------------------------------------------------------- sliding track indicator

    private class SlideState(
        var anim: SpringAnimation,
        var x: Float,
        var velocity: Float = 0f,
    )

    /**
     * Slides a shared pill or tab underline across [track] from [from] to [to]
     * on an interruptible spring. Rapid taps mid-flight carry their exact visual
     * position and velocity into the new target without snapping.
     */
    fun slideIndicator(track: ViewGroup, from: View, to: View, drawableRes: Int) {
        val indicator = AppCompatResources.getDrawable(to.context, drawableRes) ?: return
        from.background = ColorDrawable(Color.TRANSPARENT)

        if (!enabled(to.context) || to.width == 0 || from.width == 0) {
            (track.getTag(R.id.motion_slide_anim) as? SlideState)?.anim?.cancel()
            to.background = indicator
            track.setTag(R.id.motion_slide_x, leftInAncestor(to, track))
            return
        }

        unclipPath(to, track)
        val targetX = leftInAncestor(to, track)
        val previous = track.getTag(R.id.motion_slide_anim) as? SlideState
        val currentX = previous?.takeIf { it.anim.isRunning }?.x
            ?: (track.getTag(R.id.motion_slide_x) as? Float)
            ?: leftInAncestor(from, track)
        val startVel = if (previous?.anim?.isRunning == true) {
            val v = previous.velocity
            previous.anim.cancel()
            v
        } else 0f

        val slide = SlideDrawable(indicator, currentX - targetX)
        to.background = slide

        ensureAttachCleanup(track)
        val holder = FloatValueHolder(currentX)
        lateinit var state: SlideState
        val anim = SpringAnimation(holder).apply {
            spring = SpringForce(targetX).apply {
                stiffness = Spatial.RESPONSIVE.stiffness
                dampingRatio = Spatial.RESPONSIVE.dampingRatio
            }
            setMinimumVisibleChange(0.5f)
            if (startVel != 0f) setStartVelocity(startVel)
            addUpdateListener { _, value, velocity ->
                state.x = value
                state.velocity = velocity
                track.setTag(R.id.motion_slide_x, value)
                slide.offset = value - targetX
                to.invalidate()
            }
            addEndListener { _, canceled, _, _ ->
                if (!canceled) {
                    state.x = targetX
                    state.velocity = 0f
                    track.setTag(R.id.motion_slide_x, targetX)
                    if (to.background === slide) to.background = indicator
                }
            }
        }
        state = SlideState(anim, currentX, startVel)
        track.setTag(R.id.motion_slide_anim, state)
        anim.start()
    }

    private fun leftInAncestor(view: View, ancestor: ViewGroup): Float {
        var x = 0f
        var cur: View? = view
        while (cur != null && cur !== ancestor) {
            x += cur.left
            cur = cur.parent as? View
        }
        return x
    }

    private fun unclipPath(view: View, stopAt: ViewGroup) {
        var cur = view.parent as? ViewGroup
        while (cur != null) {
            cur.clipChildren = false
            if (cur === stopAt) break
            cur = cur.parent as? ViewGroup
        }
    }

    private class SlideDrawable(private val inner: Drawable, var offset: Float) : DrawableWrapper(inner) {
        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            canvas.translate(offset, 0f)
            inner.draw(canvas)
            canvas.restoreToCount(save)
        }
    }

    // ---------------------------------------------------------------- layout reflow & visibility

    /** Smoothly animates layout bounds and visibility changes inside [container]. */
    fun layoutTransition(container: ViewGroup, durationMs: Long = 220L) {
        if (!enabled(container.context) || !container.isLaidOut) return
        val transition = TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            duration = durationMs
            interpolator = STANDARD_CURVE
            addTransition(ChangeBounds())
            addTransition(Fade(Fade.OUT).apply { duration = (durationMs * 0.65f).toLong() })
            addTransition(Fade(Fade.IN).apply { duration = durationMs })
        }
        TransitionManager.beginDelayedTransition(container, transition)
    }

    /** Shows or hides a small badge/button (clear X, filter dot) with crisp spring scale + fade. */
    fun setVisiblePop(view: View, visible: Boolean) {
        val current = view.getTag(R.id.motion_reveal_state) as? Boolean
        if (current == visible && view.isVisible == visible) return
        view.setTag(R.id.motion_reveal_state, visible)

        if (!enabled(view.context) || !view.isAttachedToWindow) {
            cancelAllSprings(view)
            view.scaleX = 1f
            view.scaleY = 1f
            view.alpha = 1f
            view.isVisible = visible
            return
        }
        if (visible) {
            if (!view.isVisible) {
                view.scaleX = 0.78f
                view.scaleY = 0.78f
                view.alpha = 0f
                view.isVisible = true
            }
            springScale(view, 1f, Spatial.SNAPPY)
            spring(view, DynamicAnimation.ALPHA, 1f, Effects.FAST)
        } else {
            if (!view.isVisible) return
            springScale(view, 0.78f, Effects.FAST)
            val a = spring(view, DynamicAnimation.ALPHA, 0f, Effects.FAST)
            a?.addEndListener(object : DynamicAnimation.OnAnimationEndListener {
                override fun onAnimationEnd(anim: DynamicAnimation<*>?, canceled: Boolean, value: Float, velocity: Float) {
                    anim?.removeEndListener(this)
                    if (!canceled && view.getTag(R.id.motion_reveal_state) == false) {
                        view.isVisible = false
                        view.scaleX = 1f
                        view.scaleY = 1f
                        view.alpha = 1f
                    }
                }
            })
        }
    }

    // ---------------------------------------------------------------- expressive micro-effects

    /** Crisp bookmark / toggle icon morph using expressive spring overshoot. */
    fun replaceIcon(view: ImageView, swap: () -> Unit) {
        if (!enabled(view.context)) {
            swap()
            return
        }
        view.animate().cancel()
        cancelAllSprings(view)
        view.animate()
            .scaleX(0.76f)
            .scaleY(0.76f)
            .alpha(0.55f)
            .setDuration(75)
            .setStartDelay(0)
            .setInterpolator(EASE_OUT)
            .withEndAction {
                swap()
                view.alpha = 1f
                springScale(view, 1f, Spatial.EXPRESSIVE)
            }
            .start()
    }

    /** Physical damped-spring horizontal shake for validation errors & wrong codes. */
    fun shake(view: View) {
        reject(view)
        if (!enabled(view.context)) return
        val dp = view.resources.displayMetrics.density
        cancelSpring(view, DynamicAnimation.TRANSLATION_X)
        view.translationX = 0f
        spring(
            view,
            DynamicAnimation.TRANSLATION_X,
            target = 0f,
            spec = SpringSpec(stiffness = 2200f, dampingRatio = 0.24f),
            startVelocity = 1150f * dp,
        )
    }

    /** Real pendulum-physics bell chime pivoted near the top of the bell. */
    fun ringBell(view: View, delay: Long = 220) {
        if (!enabled(view.context)) return
        view.postDelayed({
            if (!view.isAttachedToWindow) return@postDelayed
            view.pivotX = view.width * 0.5f
            view.pivotY = view.height * 0.14f
            view.rotation = 0f
            spring(
                view,
                DynamicAnimation.ROTATION,
                target = 0f,
                spec = SpringSpec(stiffness = 320f, dampingRatio = 0.24f),
                startVelocity = 760f,
            )
        }, delay)
    }

    /** Calm, restrained breathing loop for an overdue loan indicator. */
    fun breathe(view: View): Animator? {
        if (!enabled(view.context)) return null
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1350
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = PathInterpolator(0.4f, 0f, 0.6f, 1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                val s = 1f + 0.09f * t
                view.scaleX = s
                view.scaleY = s
                view.alpha = 1f - 0.24f * t
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    view.scaleX = 1f
                    view.scaleY = 1f
                    view.alpha = 1f
                }
            })
            start()
        }
        return anim
    }

    /** Smoothly rotates a chevron or + icon to [degrees] on a snappy spring. */
    fun rotateTo(view: View, degrees: Float) {
        spring(view, DynamicAnimation.ROTATION, degrees, Spatial.SNAPPY)
    }

    // ---------------------------------------------------------------- numeric & content motion

    /**
     * Vertical slot-roll for changing counts ("4 items" -> "2 items", "0 / 4" -> "1 / 4").
     * Going up rolls upward; going down rolls downward. Interruptible on rapid updates.
     */
    fun rollText(view: TextView, text: CharSequence) {
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
        val dy = (view.height * 0.42f).coerceAtMost(10f * view.resources.displayMetrics.density) * (if (up) -1 else 1)
        view.animate()
            .translationY(dy)
            .alpha(0f)
            .setDuration(85)
            .setStartDelay(0)
            .setInterpolator(EASE_OUT)
            .withEndAction {
                view.text = text
                view.translationY = -dy
                view.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(175)
                    .setInterpolator(EMPHASIZED)
                    .start()
            }
            .start()
    }

    /** Counts already animated this session so revisits render at rest. */
    private val countedIds = mutableSetOf<Int>()

    /** Crisp first-visit count-up for stats & reliability numbers (keeps decimals and suffixes). */
    fun countUp(view: TextView, value: String, delay: Long = 0, sessionKey: Int = view.id) {
        (view.getTag(R.id.motion_count_anim) as? ValueAnimator)?.cancel()
        val match = Regex("^(\\d+)(?:\\.(\\d+))?(.*)$").find(value)
        if (!enabled(view.context) || match == null || !countedIds.add(sessionKey)) {
            view.text = value
            return
        }
        val decimals = match.groupValues[2].length
        val suffix = match.groupValues[3]
        val target = value.removeSuffix(suffix).toFloat()
        view.text = (if (decimals > 0) "%.${decimals}f".format(0f) else "0") + suffix
        ensureAttachCleanup(view)
        val anim = ValueAnimator.ofFloat(0f, target).apply {
            duration = 460
            startDelay = delay
            interpolator = EASE_OUT
            addUpdateListener {
                val v = it.animatedValue as Float
                view.text = (if (decimals > 0) "%.${decimals}f".format(v) else v.toInt().toString()) + suffix
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.text = value
                }
            })
        }
        view.setTag(R.id.motion_count_anim, anim)
        anim.start()
    }

    // ---------------------------------------------------------------- list & scroll choreography

    /**
     * Tight, restrained cascade for user-triggered list refreshes (filter chips,
     * sort changes, celebration copy). Never starts from scale(0).
     */
    fun staggerIn(
        views: List<View>,
        delay: Long = 0,
        riseDp: Float = 10f,
        gapMs: Long = STAGGER_MS,
        subtleScale: Boolean = true,
    ) {
        val first = views.firstOrNull() ?: return
        if (!enabled(first.context)) return
        val dy = riseDp * first.resources.displayMetrics.density
        views.forEachIndexed { index, v ->
            v.animate().cancel()
            v.alpha = 0f
            v.translationY = dy
            if (subtleScale) {
                v.scaleX = 0.975f
                v.scaleY = 0.975f
            }
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(delay + gapMs * minOf(index, STAGGER_CAP))
                .setDuration(ENTER_MS)
                .setInterpolator(EMPHASIZED)
                .withLayer()
                .start()
        }
    }

    /** Directional slide+fade for in-place tab lists (Profile Active / Completed / Saved). */
    fun directionalSwap(views: List<View>, direction: Int) {
        val first = views.firstOrNull() ?: return
        if (!enabled(first.context)) return
        val dx = direction * 14f * first.resources.displayMetrics.density
        views.forEachIndexed { index, v ->
            v.animate().cancel()
            v.alpha = 0f
            v.translationX = dx
            v.animate()
                .alpha(1f)
                .translationX(0f)
                .setStartDelay(16L * minOf(index, STAGGER_CAP))
                .setDuration(200)
                .setInterpolator(EMPHASIZED)
                .withLayer()
                .start()
        }
    }

    /**
     * Subtle content cohesion inside a rising bottom sheet: never touches the
     * drag handle and uses critically damped motion (no jelly bounce).
     */
    fun settleSheetContent(views: List<View>, delay: Long = 20L) {
        val first = views.firstOrNull() ?: return
        if (!enabled(first.context)) return
        val dy = 7f * first.resources.displayMetrics.density
        views.forEachIndexed { index, v ->
            v.animate().cancel()
            v.alpha = 0f
            v.translationY = dy
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delay + 14L * minOf(index, 5))
                .setDuration(180)
                .setInterpolator(EASE_OUT)
                .start()
        }
    }

    /**
     * Inserts a new message bubble or photo from its corner anchor with crisp
     * spring physics (never from scale(0)).
     */
    fun insert(view: View, fromEnd: Boolean) {
        if (!enabled(view.context)) return
        val dp = view.resources.displayMetrics.density
        view.alpha = 0f
        view.scaleX = 0.88f
        view.scaleY = 0.88f
        view.translationY = 10f * dp
        view.post {
            if (!view.isAttachedToWindow) return@post
            view.pivotX = if (fromEnd) view.width.toFloat() else 0f
            view.pivotY = view.height.toFloat()
            spring(view, DynamicAnimation.ALPHA, 1f, Effects.FAST)
            spring(view, DynamicAnimation.TRANSLATION_Y, 0f, Spatial.RESPONSIVE)
            springScale(view, 1f, Spatial.RESPONSIVE)
        }
    }

    /**
     * Removes a list row in one fluid overlapping beat: glides aside & fades
     * while the row height smoothly closes up, then calls [onEnd].
     */
    fun remove(view: View, onEnd: () -> Unit) {
        if (!enabled(view.context)) {
            onEnd()
            return
        }
        val parent = view.parent as? ViewGroup
        val dp = view.resources.displayMetrics.density
        val startHeight = view.height
        view.animate().cancel()
        view.animate()
            .translationX(44f * dp)
            .alpha(0f)
            .setDuration(160)
            .setStartDelay(0)
            .setInterpolator(EASE_OUT)
            .withLayer()
            .start()

        ValueAnimator.ofInt(startHeight, 0).apply {
            startDelay = 65
            duration = 200
            interpolator = STANDARD_CURVE
            addUpdateListener {
                view.layoutParams.height = it.animatedValue as Int
                view.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    parent?.post(onEnd) ?: onEnd()
                }
            })
            start()
        }
    }

    /**
     * Smoothly tucks a floating button away while scrolling down and brings it
     * back on upward scroll, with hysteresis so small finger jitter never flickers it.
     */
    fun hideOnScroll(scroll: View, fab: View) {
        val dp = fab.resources.displayMetrics.density
        val travel = 108f * dp
        val threshold = (10f * dp).toInt()
        var hidden = false
        var accum = 0

        scroll.setOnScrollChangeListener { _, _, y, _, oldY ->
            val dy = y - oldY
            if ((dy > 0 && accum < 0) || (dy < 0 && accum > 0)) accum = 0
            accum += dy

            if (accum > threshold && !hidden && y > (48 * dp).toInt()) {
                hidden = true
                accum = 0
                fab.setTag(R.id.motion_reveal_state, false)
                spring(fab, DynamicAnimation.TRANSLATION_Y, travel, Spatial.EXIT)
                springScale(fab, 0.88f, Spatial.EXIT)
                spring(fab, DynamicAnimation.ALPHA, 0f, Effects.FAST)
            } else if ((accum < -threshold || y <= (16 * dp).toInt()) && hidden) {
                hidden = false
                accum = 0
                fab.setTag(R.id.motion_reveal_state, true)
                spring(fab, DynamicAnimation.TRANSLATION_Y, 0f, Spatial.RESPONSIVE)
                springScale(fab, 1f, Spatial.RESPONSIVE)
                spring(fab, DynamicAnimation.ALPHA, 1f, Effects.FAST)
            }
        }
    }
}
