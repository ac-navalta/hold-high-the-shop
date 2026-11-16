package com.nevergrowold.holdhightheshop

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar

/**
 * Dropdown list that opens under the field or button that was tapped, styled
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
    ): PopupWindow {
        val context = anchor.context
        hideKeyboard(anchor)

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

        val xOff = if (alignEnd) anchor.width - width else 0
        popup.showAsDropDown(anchor, xOff, dp(context, 6), Gravity.START)
        return popup
    }
}

/** Bottom sheets that share the cream, round-cornered look of the app. */
object Sheets {

    fun show(activity: Activity, layoutRes: Int, setup: (View, BottomSheetDialog) -> Unit): BottomSheetDialog {
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
        return dialog
    }
}

fun dp(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).toInt()

fun showKeyboard(input: EditText) {
    input.requestFocus()
    val imm = input.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    input.post { imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
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
