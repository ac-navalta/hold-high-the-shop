package com.nevergrowold.holdhightheshop

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.DynamicAnimation
import kotlin.math.abs

/**
 * Interactive, interruptible spring-physics photo carousel for [ListingDetailActivity].
 *
 * Supports:
 * - 1:1 horizontal finger drag tracking with parent `ScrollView` touch-intercept lock
 * - Elastic rubber-band edge resistance at the first and last photos
 * - Velocity-preserving damped spring transitions (`stiffness = 430f, dampingRatio = 0.76f`)
 * - Tap-to-advance with smooth spring slide & long-press to inspect full photo
 * - Animated pill pagination dots + live `1 / N` counter badge
 */
internal class SpringPhotoCarousel(
    private val stage: FrameLayout,
    private val prevPhoto: ImageView,
    private val currentPhoto: ImageView,
    private val nextPhoto: ImageView,
    private val placeholderIcon: View,
    private val countPill: TextView,
    private val dotsContainer: LinearLayout,
    private val onViewSinglePhoto: (String) -> Unit,
) {
    private val context = stage.context
    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingThresholdPxPerSec = 520f * density

    /** Fluid, springy spatial spec tuned for full-bleed photo paging. */
    private val slideSpring = Motion.SpringSpec(stiffness = 430f, dampingRatio = 0.76f)

    private var photos: List<String> = emptyList()
    var currentIndex: Int = 0
        private set

    private var downX = 0f
    private var downY = 0f
    private var draggingHorizontally = false
    private var lastDragDx = 0f
    private var velocityTracker: VelocityTracker? = null

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (photos.isEmpty()) return false
                if (photos.size == 1) {
                    onViewSinglePhoto(photos.first())
                } else {
                    Motion.tick(stage)
                    val tapOnLeftThird = stage.width > 0 && e.x < stage.width * 0.30f
                    if (tapOnLeftThird && currentIndex > 0) {
                        slideTo(currentIndex - 1, direction = -1)
                    } else {
                        val nextIdx = (currentIndex + 1) % photos.size
                        slideTo(nextIdx, direction = 1)
                    }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val ref = photos.getOrNull(currentIndex) ?: return
                Motion.tick(stage)
                onViewSinglePhoto(ref)
            }
        },
    )

    init {
        installTouchHandling()
        stage.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val newW = right - left
            val oldW = oldRight - oldLeft
            if (newW > 0 && newW != oldW && !draggingHorizontally) {
                positionIdleSlots(newW.toFloat())
            }
        }
    }

    fun setPhotos(uris: List<String>, keepIndex: Boolean = true) {
        val changedList = uris != photos
        photos = uris
        if (photos.isEmpty()) {
            currentIndex = 0
            prevPhoto.isVisible = false
            currentPhoto.isVisible = false
            nextPhoto.isVisible = false
            placeholderIcon.isVisible = true
            countPill.isVisible = false
            dotsContainer.isVisible = false
            return
        }

        currentIndex = if (keepIndex) currentIndex.coerceIn(0, photos.lastIndex) else 0
        countPill.isVisible = true
        bindSlotImages(overridePrevRef = null, overrideNextRef = null)
        updateIndicators(animate = false)
        if (changedList) {
            positionIdleSlots(stageWidth())
        }
    }

    fun cycleNext() {
        if (photos.isEmpty()) return
        if (photos.size == 1) {
            onViewSinglePhoto(photos.first())
            return
        }
        val nextIdx = (currentIndex + 1) % photos.size
        slideTo(nextIdx, direction = 1)
    }

    /**
     * Smoothly slides from the current photo to [targetIndex].
     * @param direction `+1` when advancing forward (slides right-to-left), `-1` when going back (slides left-to-right).
     */
    fun slideTo(targetIndex: Int, direction: Int, startVelocity: Float? = null) {
        if (photos.size <= 1 || targetIndex !in photos.indices) return
        val outgoingRef = photos[currentIndex]
        val w = stageWidth()
        val dragOffset = lastDragDx
        lastDragDx = 0f
        currentIndex = targetIndex

        if (w <= 0f || !Motion.enabled(context)) {
            bindSlotImages(overridePrevRef = null, overrideNextRef = null)
            positionIdleSlots(w)
            updateIndicators(animate = false)
            return
        }

        // Bind new current photo + place outgoing photo in the opposite slot so both spring together!
        if (direction >= 0) {
            bindSlotImages(overridePrevRef = outgoingRef, overrideNextRef = null)
            prevPhoto.isVisible = true
            prevPhoto.translationX = dragOffset
            prevPhoto.scaleX = 1f
            prevPhoto.scaleY = 1f

            currentPhoto.isVisible = true
            currentPhoto.translationX = w + dragOffset
            currentPhoto.scaleX = 0.96f
            currentPhoto.scaleY = 0.96f

            nextPhoto.translationX = w

            Motion.spring(prevPhoto, DynamicAnimation.TRANSLATION_X, -w, slideSpring, startVelocity)
            Motion.springScale(prevPhoto, 0.95f, slideSpring)
            Motion.spring(currentPhoto, DynamicAnimation.TRANSLATION_X, 0f, slideSpring, startVelocity)
            Motion.springScale(currentPhoto, 1f, slideSpring)
        } else {
            bindSlotImages(overridePrevRef = null, overrideNextRef = outgoingRef)
            nextPhoto.isVisible = true
            nextPhoto.translationX = dragOffset
            nextPhoto.scaleX = 1f
            nextPhoto.scaleY = 1f

            currentPhoto.isVisible = true
            currentPhoto.translationX = -w + dragOffset
            currentPhoto.scaleX = 0.96f
            currentPhoto.scaleY = 0.96f

            prevPhoto.translationX = -w

            Motion.spring(nextPhoto, DynamicAnimation.TRANSLATION_X, w, slideSpring, startVelocity)
            Motion.springScale(nextPhoto, 0.95f, slideSpring)
            Motion.spring(currentPhoto, DynamicAnimation.TRANSLATION_X, 0f, slideSpring, startVelocity)
            Motion.springScale(currentPhoto, 1f, slideSpring)
        }

        updateIndicators(animate = true)
    }

    private fun springBackToCenter(startVelocity: Float) {
        val w = stageWidth()
        lastDragDx = 0f
        Motion.spring(currentPhoto, DynamicAnimation.TRANSLATION_X, 0f, slideSpring, startVelocity)
        Motion.springScale(currentPhoto, 1f, slideSpring)
        if (prevPhoto.isVisible) {
            Motion.spring(prevPhoto, DynamicAnimation.TRANSLATION_X, -w, slideSpring, startVelocity)
            Motion.springScale(prevPhoto, 1f, slideSpring)
        }
        if (nextPhoto.isVisible) {
            Motion.spring(nextPhoto, DynamicAnimation.TRANSLATION_X, w, slideSpring, startVelocity)
            Motion.springScale(nextPhoto, 1f, slideSpring)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installTouchHandling() {
        stage.setOnTouchListener { _, event ->
            if (photos.isEmpty()) return@setOnTouchListener false
            gestureDetector.onTouchEvent(event)

            if (photos.size <= 1) return@setOnTouchListener true

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    draggingHorizontally = false
                    lastDragDx = 0f
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    // Ensure neighbor slots hold true adjacent photos before a potential drag
                    bindAdjacentNeighbors()
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    val rawDx = event.x - downX
                    val rawDy = event.y - downY

                    if (!draggingHorizontally && abs(rawDx) > touchSlop && abs(rawDx) > abs(rawDy) * 1.1f) {
                        draggingHorizontally = true
                        stage.parent?.requestDisallowInterceptTouchEvent(true)
                        Motion.cancelAllSprings(prevPhoto)
                        Motion.cancelAllSprings(currentPhoto)
                        Motion.cancelAllSprings(nextPhoto)
                    }

                    if (draggingHorizontally) {
                        val w = stageWidth().coerceAtLeast(1f)
                        val atLeftEdge = currentIndex == 0 && rawDx > 0f
                        val atRightEdge = currentIndex == photos.lastIndex && rawDx < 0f
                        val effectiveDx = if (atLeftEdge || atRightEdge) rawDx * 0.28f else rawDx
                        lastDragDx = effectiveDx

                        val progress = (abs(effectiveDx) / w).coerceIn(0f, 1f)
                        currentPhoto.translationX = effectiveDx
                        currentPhoto.scaleX = 1f - 0.03f * progress
                        currentPhoto.scaleY = 1f - 0.03f * progress

                        if (effectiveDx > 0f && currentIndex > 0) {
                            prevPhoto.isVisible = true
                            prevPhoto.translationX = -w + effectiveDx
                            val inScale = 0.96f + 0.04f * progress
                            prevPhoto.scaleX = inScale
                            prevPhoto.scaleY = inScale
                        } else if (effectiveDx < 0f && currentIndex < photos.lastIndex) {
                            nextPhoto.isVisible = true
                            nextPhoto.translationX = w + effectiveDx
                            val inScale = 0.96f + 0.04f * progress
                            nextPhoto.scaleX = inScale
                            nextPhoto.scaleY = inScale
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vx = velocityTracker?.xVelocity ?: 0f
                    velocityTracker?.recycle()
                    velocityTracker = null

                    if (draggingHorizontally) {
                        stage.parent?.requestDisallowInterceptTouchEvent(false)
                        draggingHorizontally = false
                        val w = stageWidth().coerceAtLeast(1f)
                        val dx = lastDragDx
                        val commitNext =
                            currentIndex < photos.lastIndex && (dx < -w * 0.20f || vx < -flingThresholdPxPerSec)
                        val commitPrev =
                            currentIndex > 0 && (dx > w * 0.20f || vx > flingThresholdPxPerSec)

                        when {
                            event.actionMasked == MotionEvent.ACTION_CANCEL -> springBackToCenter(vx)
                            commitNext -> {
                                Motion.tick(stage)
                                slideTo(currentIndex + 1, direction = 1, startVelocity = vx)
                            }
                            commitPrev -> {
                                Motion.tick(stage)
                                slideTo(currentIndex - 1, direction = -1, startVelocity = vx)
                            }
                            else -> springBackToCenter(vx)
                        }
                    }
                }
            }
            true
        }
    }

    private fun bindAdjacentNeighbors() {
        val prevRef = photos.getOrNull(currentIndex - 1)
        val nextRef = photos.getOrNull(currentIndex + 1)
        val w = stageWidth()
        if (prevRef != null) {
            AppIntents.bindImageUri(prevPhoto, null, prevRef)
            prevPhoto.isVisible = true
            prevPhoto.translationX = -w
        } else {
            prevPhoto.isVisible = false
        }
        if (nextRef != null) {
            AppIntents.bindImageUri(nextPhoto, null, nextRef)
            nextPhoto.isVisible = true
            nextPhoto.translationX = w
        } else {
            nextPhoto.isVisible = false
        }
    }

    private fun bindSlotImages(overridePrevRef: String?, overrideNextRef: String?) {
        val curRef = photos.getOrNull(currentIndex)
        if (curRef != null) {
            AppIntents.bindImageUri(currentPhoto, placeholderIcon, curRef)
        }
        val pRef = overridePrevRef ?: photos.getOrNull(currentIndex - 1)
        if (pRef != null) {
            AppIntents.bindImageUri(prevPhoto, null, pRef)
            prevPhoto.isVisible = true
        } else {
            prevPhoto.isVisible = false
        }
        val nRef = overrideNextRef ?: photos.getOrNull(currentIndex + 1)
        if (nRef != null) {
            AppIntents.bindImageUri(nextPhoto, null, nRef)
            nextPhoto.isVisible = true
        } else {
            nextPhoto.isVisible = false
        }
    }

    private fun positionIdleSlots(w: Float) {
        if (w <= 0f) return
        Motion.cancelAllSprings(prevPhoto)
        Motion.cancelAllSprings(currentPhoto)
        Motion.cancelAllSprings(nextPhoto)
        currentPhoto.translationX = 0f
        currentPhoto.scaleX = 1f
        currentPhoto.scaleY = 1f
        prevPhoto.translationX = -w
        prevPhoto.scaleX = 1f
        prevPhoto.scaleY = 1f
        nextPhoto.translationX = w
        nextPhoto.scaleX = 1f
        nextPhoto.scaleY = 1f
    }

    private fun updateIndicators(animate: Boolean) {
        val label = context.getString(R.string.photo_count_format, currentIndex + 1, photos.size)
        if (animate) Motion.rollText(countPill, label) else countPill.text = label

        if (photos.size <= 1) {
            dotsContainer.isVisible = false
            return
        }
        dotsContainer.isVisible = true
        val dotHeight = (6f * density).toInt().coerceAtLeast(4)
        val inactiveW = dotHeight
        val activeW = (18f * density).toInt()
        val marginPx = (3f * density).toInt()

        if (dotsContainer.childCount != photos.size) {
            dotsContainer.removeAllViews()
            repeat(photos.size) { idx ->
                val dot = View(context).apply {
                    val w = if (idx == currentIndex) activeW else inactiveW
                    layoutParams = LinearLayout.LayoutParams(w, dotHeight).apply {
                        marginStart = marginPx
                        marginEnd = marginPx
                    }
                    setBackgroundResource(
                        if (idx == currentIndex) R.drawable.bg_photo_dot_active else R.drawable.bg_photo_dot_inactive,
                    )
                }
                dotsContainer.addView(dot)
            }
            return
        }

        for (i in 0 until dotsContainer.childCount) {
            val dot = dotsContainer.getChildAt(i)
            val isActive = i == currentIndex
            dot.setBackgroundResource(
                if (isActive) R.drawable.bg_photo_dot_active else R.drawable.bg_photo_dot_inactive,
            )
            val lp = dot.layoutParams as LinearLayout.LayoutParams
            val targetW = if (isActive) activeW else inactiveW
            if (lp.width != targetW) {
                lp.width = targetW
                dot.layoutParams = lp
                if (animate && Motion.enabled(context)) {
                    Motion.nudge(dot, velocity = if (isActive) 1.4f else 0.5f)
                }
            }
        }
    }

    private fun stageWidth(): Float =
        (if (stage.width > 0) stage.width else context.resources.displayMetrics.widthPixels).toFloat()
}
