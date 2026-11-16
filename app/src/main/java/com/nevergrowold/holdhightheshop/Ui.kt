package com.nevergrowold.holdhightheshop

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar

/**
 * Dropdown list that opens next to the field or button that was tapped, styled
 * like the dropdown components in the Figma file: a white card, one option
 * per row, and a green bar beside the selected option.
 */
object Dropdown {

    /**
     * @param anchor the view the list opens under.
     * @param selected index of the current option, or -1 for none.
     * @param icons optional icon per row (a single-item list repeats it).
     * @param widthDp fixed width; by default the list matches the anchor.
     * @param alignEnd line the list up with the anchor's right edge.
     */
    fun show(
        anchor: View,
        options: List<String>,
        selected: Int = -1,
        icons: List<Int>? = null,
        widthDp: Int? = null,
        alignEnd: Boolean = false,
        onPick: (Int) -> Unit
    ) = whenKeyboardClosed(anchor) { open(anchor, options, selected, icons, widthDp, alignEnd, onPick) }

    private fun open(
        anchor: View,
        options: List<String>,
        selected: Int,
        icons: List<Int>?,
        widthDp: Int?,
        alignEnd: Boolean,
        onPick: (Int) -> Unit
    ) {
        val context = anchor.context

        val content = LayoutInflater.from(context).inflate(R.layout.popup_dropdown, null)
        val rows = content.findViewById<LinearLayout>(R.id.dropdownRows)

        val width = widthDp?.let { dp(context, it) } ?: anchor.width
        val popup = PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = dp(context, 8).toFloat()
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true

        options.forEachIndexed { index, label ->
            val row = LayoutInflater.from(context).inflate(R.layout.item_dropdown_row, rows, false)
            val isOn = index == selected
            row.findViewById<View>(R.id.rowBar).visibility = if (isOn) View.VISIBLE else View.INVISIBLE
            row.findViewById<TextView>(R.id.rowText).apply {
                text = label
                setTextColor(context.getColor(if (isOn) R.color.uc_green else R.color.text_primary))
            }
            val icon = icons?.let { it.getOrNull(index) ?: it.firstOrNull() }
            if (icon != null) {
                row.findViewById<ImageView>(R.id.rowIcon).apply {
                    setImageResource(icon)
                    visibility = View.VISIBLE
                }
            }
            row.setOnClickListener {
                popup.dismiss()
                onPick(index)
            }
            rows.addView(row)
        }

        // Open where there is room. Below the anchor when the whole list fits,
        // otherwise above it (fields near the bottom of a form, the chat input),
        // otherwise on whichever side is bigger, scrolling inside that space.
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val listHeight = minOf(content.measuredHeight, dp(context, MAX_HEIGHT_DP))
        val gap = dp(context, 6)
        val edge = dp(context, 12)
        val frame = Rect().also { anchor.getWindowVisibleDisplayFrame(it) }
        val loc = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val spaceBelow = frame.bottom - (loc[1] + anchor.height) - gap - edge
        val spaceAbove = loc[1] - frame.top - gap - edge
        val below = listHeight <= spaceBelow || (listHeight > spaceAbove && spaceBelow >= spaceAbove)
        val height = minOf(listHeight, if (below) spaceBelow else spaceAbove)
        popup.height = height

        val x = loc[0] + if (alignEnd) anchor.width - width else 0
        val y = if (below) loc[1] + anchor.height + gap else loc[1] - gap - height
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, x, y)
    }

    /** About seven rows; longer lists scroll inside the card. */
    private const val MAX_HEIGHT_DP = 46 * 7 + 12
}

/** Bottom sheets that share the cream, round-cornered look of the app. */
object Sheets {

    fun show(activity: Activity, layoutRes: Int, setup: (View, BottomSheetDialog) -> Unit) {
        val root = activity.findViewById<View>(R.id.main) ?: activity.window.decorView
        whenKeyboardClosed(root) { open(activity, layoutRes, setup) }
    }

    private fun open(activity: Activity, layoutRes: Int, setup: (View, BottomSheetDialog) -> Unit) {
        if (activity.isFinishing) return
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(layoutRes, null)
        dialog.setContentView(view)
        setup(view, dialog)
        dialog.setOnShowListener {
            val container = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            container?.setBackgroundColor(Color.TRANSPARENT)
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
        }
        dialog.show()
    }
}

fun dp(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).toInt()

fun showKeyboard(input: EditText) {
    input.requestFocus()
    val imm = input.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    input.post { imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
}

/**
 * Runs [action] once the keyboard is fully closed and the screen has settled.
 *
 * Opening a dropdown or bottom sheet while typing used to place it against
 * the keyboard-sized screen; the keyboard then closed and left the popup
 * floating mid-screen. Waiting for the layout to stop moving avoids that.
 * When the keyboard is not open, [action] runs straight away.
 */
fun whenKeyboardClosed(view: View, action: () -> Unit) {
    if (ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) != true) {
        action()
        return
    }
    hideKeyboard(view)
    val root = view.rootView
    val started = SystemClock.uptimeMillis()
    var lastHeight = -1
    var lastPad = -1
    var steadyFrames = 0
    val check = object : Runnable {
        override fun run() {
            val imeOpen = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            val main = root.findViewById<View>(R.id.main) ?: root
            val height = main.height
            val pad = main.paddingBottom
            steadyFrames = if (height == lastHeight && pad == lastPad) steadyFrames + 1 else 0
            lastHeight = height
            lastPad = pad
            val settled = !imeOpen && steadyFrames >= 3
            if (settled || SystemClock.uptimeMillis() - started > 1000) {
                if (view.isAttachedToWindow) action()
            } else {
                view.postDelayed(this, 32)
            }
        }
    }
    view.postDelayed(check, 32)
}

fun hideKeyboard(view: View) {
    val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(view.windowToken, 0)
}

/** Short confirmation at the bottom of the screen, with an optional action. */
fun Activity.notify(message: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val root = findViewById<View>(R.id.main) ?: return
    val bar = Snackbar.make(root, message, Snackbar.LENGTH_SHORT)
    findViewById<View>(R.id.bottomNav)?.takeIf { it.visibility == View.VISIBLE }?.let { bar.anchorView = it }
    if (action != null && onAction != null) {
        bar.setAction(action) { onAction() }
        bar.setActionTextColor(getColor(R.color.uc_mint))
    }
    bar.show()
}
