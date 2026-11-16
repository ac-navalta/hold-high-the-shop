package com.nevergrowold.holdhightheshop

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
import androidx.core.content.ContextCompat

/**
 * Pinch-to-zoom, drag-to-pan campus map with a walking route drawn on top.
 * The map is a placeholder picture (res/drawable-nodpi/campus_map.png);
 * route points are in that picture's pixel coordinates.
 */
class CampusMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val map = BitmapFactory.decodeResource(resources, R.drawable.campus_map)
    private val transform = Matrix()
    private var minScale = 1f
    private var maxScale = 5f

    /** Walking route from the start (first point) to the meetup point (last point). */
    var route: List<PointF> = emptyList()
        set(value) {
            field = value
            if (width > 0) recenter()
        }

    private val density = resources.displayMetrics.density
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
    private val pin = ContextCompat.getDrawable(context, R.drawable.ic_location)!!.mutate().apply { setTint(green) }

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            transform.postTranslate(-dx, -dy)
            clamp()
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomBy(2f, e.x, e.y)
            return true
        }
    })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        minScale = minOf(w.toFloat() / map.width, h.toFloat() / map.height)
        maxScale = minScale * 6
        recenter()
    }

    /** Fits the route on screen with some room around it. */
    fun recenter() {
        if (width == 0) return
        val xs = route.map { it.x }.ifEmpty { listOf(map.width / 2f) }
        val ys = route.map { it.y }.ifEmpty { listOf(map.height / 2f) }
        val pad = 260f
        val spanX = (xs.max() - xs.min() + pad * 2).coerceAtLeast(1f)
        val spanY = (ys.max() - ys.min() + pad * 2).coerceAtLeast(1f)
        val scale = minOf(width / spanX, height / spanY).coerceIn(minScale, maxScale)
        val cx = (xs.max() + xs.min()) / 2
        val cy = (ys.max() + ys.min()) / 2
        transform.reset()
        transform.postScale(scale, scale)
        transform.postTranslate(width / 2f - cx * scale, height / 2f - cy * scale)
        clamp()
        invalidate()
    }

    /** Zoom buttons: zoom about the centre of the view. */
    fun zoomBy(factor: Float) = zoomBy(factor, width / 2f, height / 2f)

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val current = currentScale()
        val target = (current * factor).coerceIn(minScale, maxScale)
        transform.postScale(target / current, target / current, fx, fy)
        clamp()
        invalidate()
    }

    private fun currentScale(): Float = FloatArray(9).also { transform.getValues(it) }[Matrix.MSCALE_X]

    /** Keeps the picture covering the view, or centred when it is smaller. */
    private fun clamp() {
        val v = FloatArray(9).also { transform.getValues(it) }
        val scale = v[Matrix.MSCALE_X]
        val mw = map.width * scale
        val mh = map.height * scale
        var tx = v[Matrix.MTRANS_X]
        var ty = v[Matrix.MTRANS_Y]
        tx = if (mw <= width) (width - mw) / 2 else tx.coerceIn(width - mw, 0f)
        ty = if (mh <= height) (height - mh) / 2 else ty.coerceIn(height - mh, 0f)
        v[Matrix.MTRANS_X] = tx
        v[Matrix.MTRANS_Y] = ty
        transform.setValues(v)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(ContextCompat.getColor(context, R.color.uc_cream))
        canvas.drawBitmap(map, transform, null)
        if (route.isEmpty()) return

        // Route and markers are drawn in screen space so they keep their size while zooming.
        val pts = FloatArray(route.size * 2) { i -> if (i % 2 == 0) route[i / 2].x else route[i / 2].y }
        transform.mapPoints(pts)
        if (route.size > 1) {
            val path = Path().apply {
                moveTo(pts[0], pts[1])
                for (i in 2 until pts.size step 2) lineTo(pts[i], pts[i + 1])
            }
            canvas.drawPath(path, routePaint)
        }

        // Start: white dot with a green ring.
        fill.color = green
        canvas.drawCircle(pts[0], pts[1], 9 * density, fill)
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(pts[0], pts[1], 5 * density, fill)

        // Destination: green pin on a white disc, tip on the point.
        val ex = pts[pts.size - 2]
        val ey = pts[pts.size - 1]
        val size = (40 * density).toInt()
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(ex, ey - size * 0.6f, size * 0.42f, fill)
        pin.setBounds((ex - size / 2).toInt(), (ey - size).toInt(), (ex + size / 2).toInt(), ey.toInt())
        pin.draw(canvas)
    }
}
