package com.nevergrowold.holdhightheshop

import android.graphics.Rect
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import kotlin.math.max

/**
 * Gives every small control a touch area of at least 48 x 48dp without
 * changing how it looks. Chips (35dp), back buttons (42dp), the search icon
 * (20dp) and text links such as "Mark all read" were all under Android's
 * minimum, so taps on their edges used to miss.
 *
 * Each small control is registered with the nearest ancestor that has room
 * for the bigger area. That ancestor only sees touches its children did not
 * take, so a tap that lands on a real neighbouring control still goes to the
 * neighbour; the extra area only catches near-misses.
 */
object TouchTargets {

    private const val MIN_DP = 48

    fun install(root: View) {
        scan(root)
        var pending = false
        val runScan = Runnable {
            pending = false
            if (root.isAttachedToWindow) scan(root)
        }
        root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
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
        })
    }

    private fun scan(view: View) {
        if (view.getTag(R.id.touch_target) == null && view.width > 0 && view.isClickable) {
            view.setTag(R.id.touch_target, true)
            register(view)
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) scan(view.getChildAt(i))
    }

    private fun register(target: View) {
        val min = (MIN_DP * target.resources.displayMetrics.density).toInt()
        if (target.width >= min && target.height >= min) return
        val padX = max(0, min - target.width) / 2
        val padY = max(0, min - target.height) / 2

        // Walk up to the first ancestor whose bounds hold the enlarged area.
        val area = Rect(-padX, -padY, target.width + padX, target.height + padY)
        var child: View = target
        var host = target.parent as? ViewGroup ?: return
        while (true) {
            area.offset(child.left - host.scrollX, child.top - host.scrollY)
            if (area.left >= 0 && area.top >= 0 && area.right <= host.width && area.bottom <= host.height) break
            child = host
            host = host.parent as? ViewGroup ?: break
        }

        val group = (host.touchDelegate as? Group) ?: Group(host).also {
            if (host.touchDelegate != null) return // someone else's delegate: leave it alone
            host.touchDelegate = it
        }
        group.add(target, padX, padY)
    }

    /** Several enlarged controls sharing one host; each touch area is measured when it is touched. */
    private class Group(private val host: ViewGroup) : TouchDelegate(Rect(), host) {
        private val targets = mutableListOf<Triple<View, Int, Int>>()
        private val slop = ViewConfiguration.get(host.context).scaledTouchSlop
        private var active: View? = null
        private val area = Rect()

        fun add(view: View, padX: Int, padY: Int) {
            targets += Triple(view, padX, padY)
        }

        private fun areaOf(view: View, padX: Int, padY: Int, extra: Int): Boolean {
            if (!view.isShown || !view.isEnabled || !view.isAttachedToWindow) return false
            area.set(0, 0, view.width, view.height)
            try {
                host.offsetDescendantRectToMyCoords(view, area)
            } catch (e: IllegalArgumentException) {
                return false // no longer a descendant
            }
            area.inset(-(padX + extra), -(padY + extra))
            return true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x.toInt()
            val y = event.y.toInt()
            var target: View? = null
            var inside = true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    active = targets.firstOrNull { (v, px, py) -> areaOf(v, px, py, 0) && area.contains(x, y) }?.first
                    target = active
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_POINTER_UP -> {
                    target = active
                    val entry = targets.firstOrNull { it.first === target }
                    inside = entry != null && areaOf(entry.first, entry.second, entry.third, slop) && area.contains(x, y)
                    if (event.actionMasked == MotionEvent.ACTION_UP) active = null
                }
                MotionEvent.ACTION_CANCEL -> {
                    target = active
                    active = null
                }
            }
            target ?: return false
            // Like the framework's TouchDelegate: inside, aim at the centre; outside, aim off the view so it cancels.
            val copy = MotionEvent.obtain(event)
            if (inside) copy.setLocation(target.width / 2f, target.height / 2f)
            else copy.setLocation(-(slop * 2f), -(slop * 2f))
            val handled = target.dispatchTouchEvent(copy)
            copy.recycle()
            return handled
        }
    }
}
