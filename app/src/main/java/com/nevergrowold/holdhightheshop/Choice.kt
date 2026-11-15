package com.nevergrowold.holdhightheshop

import android.view.View
import android.widget.TextView

/**
 * Small helpers for "pick one of these" controls.
 *
 * The app has four of them (condition, listing type, report reason, market
 * filter) and they all behave the same way: tapping one option selects it and
 * clears the rest. Keeping the logic here means each screen only has to say
 * which views belong to the group and what the selected style looks like.
 */
object Choice {

    /** Pill buttons such as the Condition options on the create form. */
    fun pills(views: List<TextView>, selected: Int, onPick: (Int) -> Unit = {}) {
        views.forEachIndexed { index, view ->
            view.setOnClickListener {
                pills(views, index, onPick)
                onPick(index)
            }
            val isOn = index == selected
            view.setBackgroundResource(
                if (isOn) R.drawable.bg_chip_active else R.drawable.bg_input
            )
            view.setTextColor(
                view.context.getColor(if (isOn) R.color.white else R.color.text_primary)
            )
        }
    }

    /** The Sell / Rent / Swap segmented control. */
    fun segments(views: List<TextView>, selected: Int, onPick: (Int) -> Unit = {}) {
        views.forEachIndexed { index, view ->
            view.setOnClickListener {
                segments(views, index, onPick)
                onPick(index)
            }
            val isOn = index == selected
            if (isOn) {
                view.setBackgroundResource(R.drawable.bg_segment_active)
            } else {
                view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
            view.setTextColor(
                view.context.getColor(if (isOn) R.color.white else R.color.text_secondary)
            )
        }
    }

    /** Filter chips on the Campus Market. */
    fun chips(views: List<TextView>, selected: Int, onPick: (Int) -> Unit = {}) {
        views.forEachIndexed { index, view ->
            view.setOnClickListener {
                chips(views, index, onPick)
                onPick(index)
            }
            val isOn = index == selected
            view.setBackgroundResource(
                if (isOn) R.drawable.bg_chip_active else R.drawable.bg_chip
            )
            view.setTextColor(
                view.context.getColor(if (isOn) R.color.white else R.color.text_primary)
            )
        }
    }

    /** Radio style rows, used by the report reasons. */
    fun rows(
        rows: List<View>,
        icons: List<android.widget.ImageView>,
        selected: Int,
        onPick: (Int) -> Unit = {}
    ) {
        rows.forEachIndexed { index, row ->
            row.setOnClickListener {
                rows(rows, icons, index, onPick)
                onPick(index)
            }
            val isOn = index == selected
            row.setBackgroundResource(
                if (isOn) R.drawable.bg_input_selected else R.drawable.bg_input
            )
            icons[index].setImageResource(
                if (isOn) R.drawable.ic_radio_on else R.drawable.ic_radio_off
            )
            icons[index].setColorFilter(
                row.context.getColor(if (isOn) R.color.uc_green else R.color.text_secondary)
            )
        }
    }
}
