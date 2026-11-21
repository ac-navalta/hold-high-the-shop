package com.nevergrowold.holdhightheshop

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * Interactive pinch-to-zoom, drag-with-inertia campus map with animated zoom,
 * smooth recenter, and a gentle destination beacon pulse.
 */
class CampusMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val map = BitmapFactory.decodeResource(resources, R.drawable.campus_map)
    private val transform = Matrix()
    private val matrixValues = FloatArray(9)
    private val clampValues = FloatArray(9)
    private var minScale = 1f
    private var maxScale = 5f

    /** Walking route from the start (first point) to the meetup point (last point). */
    var route: List<PointF> = emptyList()
        set(value) {
            field = value
            routeSrcPts = FloatArray(value.size * 2) { i -> if (i % 2 == 0) value[i / 2].x else value[i / 2].y }
            routeDstPts = FloatArray(value.size * 2)
            if (width > 0) recenter(animate = false)
        }

    private var routeSrcPts = FloatArray(0)
    private var routeDstPts = FloatArray(0)
    private val routePath = Path()

    private val density = resources.displayMetrics.density
    private val creamBg = ContextCompat.getColor(context, R.color.uc_cream)
    private val green = ContextCompat.getColor(context, R.color.uc_green)
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = green
        style = Paint.Style.STROKE
        strokeWidth = 5 * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(10 * density, 8 * density), 0f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val beaconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pin = ContextCompat.getDrawable(context, R.drawable.ic_location)!!.mutate().apply { setTint(green) }

    private val scroller = OverScroller(context)
    private var lastFlingX = 0
    private var lastFlingY = 0
    private var matrixAnim: ValueAnimator? = null
    private var beaconAnim: ValueAnimator? = null
    private var beaconPhase = 0f

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            cancelCameraMotion()
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            applyZoomImmediate(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            cancelCameraMotion()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            transform.postTranslate(-dx, -dy)
            clamp(transform)
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (!Motion.enabled(context)) return false
            lastFlingX = 0
            lastFlingY = 0
            scroller.fling(0, 0, vx.toInt(), vy.toInt(), -4000, 4000, -4000, 4000)
            postInvalidateOnAnimation()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            Motion.tick(this@CampusMapView)
            val current = currentScale()
            val factor = if (current > minScale * 3.2f) minScale / current else 2f
            zoomBy(factor, e.x, e.y)
            return true
        }
    })

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startBeacon()
    }

    override fun onDetachedFromWindow() {
        cancelCameraMotion()
        beaconAnim?.cancel()
        beaconAnim = null
        super.onDetachedFromWindow()
    }

    private fun startBeacon() {
        beaconAnim?.cancel()
        if (!Motion.enabled(context)) return
        beaconAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1900
            repeatCount = ValueAnimator.INFINITE
            interpolator = Motion.EASE_OUT
            addUpdateListener {
                beaconPhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        minScale = minOf(w.toFloat() / map.width, h.toFloat() / map.height)
        maxScale = minScale * 6
        recenter(animate = false)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val dx = scroller.currX - lastFlingX
            val dy = scroller.currY - lastFlingY
            lastFlingX = scroller.currX
            lastFlingY = scroller.currY
            transform.postTranslate(dx.toFloat(), dy.toFloat())
            clamp(transform)
            postInvalidateOnAnimation()
        }
    }

    /** Smoothly frames the walking route on screen. */
    fun recenter(animate: Boolean = true) {
        if (width == 0) return
        val xs = route.map { it.x }.ifEmpty { listOf(map.width / 2f) }
        val ys = route.map { it.y }.ifEmpty { listOf(map.height / 2f) }
        val pad = 260f
        val spanX = (xs.max() - xs.min() + pad * 2).coerceAtLeast(1f)
        val spanY = (ys.max() - ys.min() + pad * 2).coerceAtLeast(1f)
        val scale = minOf(width / spanX, height / spanY).coerceIn(minScale, maxScale)
        val cx = (xs.max() + xs.min()) / 2
        val cy = (ys.max() + ys.min()) / 2

        val target = Matrix().apply {
            postScale(scale, scale)
            postTranslate(width / 2f - cx * scale, height / 2f - cy * scale)
        }
        clamp(target)
        if (animate && Motion.enabled(context)) animateToMatrix(target, durationMs = 300L)
        else {
            cancelCameraMotion()
            transform.set(target)
            invalidate()
        }
    }

    /** Zoom buttons: smoothly zooms about the view centre. */
    fun zoomBy(factor: Float) = zoomBy(factor, width / 2f, height / 2f)

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val current = currentScale()
        val targetScale = (current * factor).coerceIn(minScale, maxScale)
        val ratio = targetScale / current
        val target = Matrix(transform).apply { postScale(ratio, ratio, fx, fy) }
        clamp(target)
        if (Motion.enabled(context)) animateToMatrix(target, durationMs = 240L)
        else {
            cancelCameraMotion()
            transform.set(target)
            invalidate()
        }
    }

    private fun applyZoomImmediate(factor: Float, fx: Float, fy: Float) {
        val current = currentScale()
        val target = (current * factor).coerceIn(minScale, maxScale)
        transform.postScale(target / current, target / current, fx, fy)
        clamp(transform)
        invalidate()
    }

    private fun animateToMatrix(target: Matrix, durationMs: Long) {
        cancelCameraMotion()
        val startVals = FloatArray(9).also { transform.getValues(it) }
        val endVals = FloatArray(9).also { target.getValues(it) }
        val interpVals = FloatArray(9)
        matrixAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = Motion.EMPHASIZED
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                for (i in 0 until 9) interpVals[i] = startVals[i] + (endVals[i] - startVals[i]) * t
                transform.setValues(interpVals)
                invalidate()
            }
            start()
        }
    }

    private fun cancelCameraMotion() {
        matrixAnim?.cancel()
        matrixAnim = null
        if (!scroller.isFinished) scroller.forceFinished(true)
    }

    private fun currentScale(): Float {
        transform.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    /** Keeps the picture covering the view, or centred when it is smaller. */
    private fun clamp(matrix: Matrix) {
        matrix.getValues(clampValues)
        val scale = clampValues[Matrix.MSCALE_X]
        val mw = map.width * scale
        val mh = map.height * scale
        var tx = clampValues[Matrix.MTRANS_X]
        var ty = clampValues[Matrix.MTRANS_Y]
        tx = if (mw <= width) (width - mw) / 2 else tx.coerceIn(width - mw, 0f)
        ty = if (mh <= height) (height - mh) / 2 else ty.coerceIn(height - mh, 0f)
        clampValues[Matrix.MTRANS_X] = tx
        clampValues[Matrix.MTRANS_Y] = ty
        matrix.setValues(clampValues)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(creamBg)
        canvas.drawBitmap(map, transform, null)
        if (routeSrcPts.isEmpty()) return

        transform.mapPoints(routeDstPts, routeSrcPts)
        if (route.size > 1) {
            routePath.rewind()
            routePath.moveTo(routeDstPts[0], routeDstPts[1])
            for (i in 2 until routeDstPts.size step 2) routePath.lineTo(routeDstPts[i], routeDstPts[i + 1])
            canvas.drawPath(routePath, routePaint)
        }

        // Start: white dot with a green ring.
        fill.color = green
        canvas.drawCircle(routeDstPts[0], routeDstPts[1], 9 * density, fill)
        fill.color = Color.WHITE
        canvas.drawCircle(routeDstPts[0], routeDstPts[1], 5 * density, fill)

        // Destination: subtle expanding green beacon ring + pin on white disc.
        val ex = routeDstPts[routeDstPts.size - 2]
        val ey = routeDstPts[routeDstPts.size - 1]
        if (beaconAnim?.isRunning == true) {
            val alpha = ((1f - beaconPhase) * 90f).toInt().coerceIn(0, 255)
            beaconPaint.color = ColorUtils.setAlphaComponent(green, alpha)
            canvas.drawCircle(ex, ey, (8f + 22f * beaconPhase) * density, beaconPaint)
        }
        val size = (40 * density).toInt()
        fill.color = Color.WHITE
        canvas.drawCircle(ex, ey - size * 0.6f, size * 0.42f, fill)
        pin.setBounds((ex - size / 2).toInt(), (ey - size).toInt(), (ex + size / 2).toInt(), ey.toInt())
        pin.draw(canvas)
    }
}
