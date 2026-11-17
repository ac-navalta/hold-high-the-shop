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
    fun pills(views: List<TextView>, selected: Int, onPick: (Int) -> Unit = {}) = pills(views, selected, false, onPick)

    private fun pills(views: List<TextView>, selected: Int, animate: Boolean, onPick: (Int) -> Unit) {
        views.forEachIndexed { index, view ->
            view.setOnClickListener {
                pills(views, index, true, onPick)
                Motion.tick(view)
                Motion.pop(view, 0.92f)
                onPick(index)
            }
            val isOn = index == selected
            Motion.background(view, if (isOn) R.drawable.bg_chip_active else R.drawable.bg_input, animate)
            Motion.textColor(view, view.context.getColor(if (isOn) R.color.white else R.color.text_primary), animate)
        }
    }

    /** The Sell / Rent / Swap segmented control. */
    fun segments(views: List<TextView>, selected: Int, onPick: (Int) -> Unit = {}) = segments(views, selected, false, onPick)

    private fun segments(views: List<TextView>, selected: Int, animate: Boolean, onPick: (Int) -> Unit) {
        views.forEachIndexed { index, view ->
            view.setOnClickListener {
                segments(views, index, true, onPick)
                onPick(index)
            }
            val isOn = index == selected
            if (!animate) {
                if (isOn) view.setBackgroundResource(R.drawable.bg_segment_active)
                else view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
            Motion.textColor(view, view.context.getColor(if (isOn) R.color.white else R.color.text_secondary), animate)
        }
        // The green pill slides from the old option to the new one.
        val track = views.first().parent as? View
        val previous = track?.getTag(R.id.choice_on) as? Int
        if (animate && previous != null && previous != selected) {
            Motion.slidePill(views[previous], views[selected], R.drawable.bg_segment_active)
            Motion.tick(views[selected])
        }
        track?.setTag(R.id.choice_on, selected)
    }

    /** Filter chips on the Campus Market. */
    fun chips(views: List<TextView>, selected: Int, onPick: (Int) -> Unit = {}) = chips(views, selected, false, onPick)

    private fun chips(views: List<TextView>, selected: Int, animate: Boolean, onPick: (Int) -> Unit) {
        views.forEachIndexed { index, view ->
            view.setOnClickListener {
                chips(views, index, true, onPick)
                Motion.tick(view)
                Motion.pop(view, 0.92f)
                onPick(index)
            }
            val isOn = index == selected
            Motion.background(view, if (isOn) R.drawable.bg_chip_active else R.drawable.bg_chip, animate)
            Motion.textColor(view, view.context.getColor(if (isOn) R.color.white else R.color.text_primary), animate)
        }
    }

    /**
     * Chips where several can be on at once, such as Condition in the
     * market filters. Tapping a chip turns it on or off.
     */
    fun toggles(views: List<TextView>, selected: Set<Int>, onChange: (Set<Int>) -> Unit) {
        views.forEachIndexed { index, view ->
            val isOn = index in selected
            view.setOnClickListener {
                Motion.tick(view)
                Motion.pop(view, 0.92f)
                onChange(if (isOn) selected - index else selected + index)
            }
            // Only the chip whose state actually changes animates; the rest are re-bound as they were.
            val was = view.getTag(R.id.choice_on) as? Boolean
            val animate = was != null && was != isOn
            view.setTag(R.id.choice_on, isOn)
            Motion.background(view, if (isOn) R.drawable.bg_chip_active else R.drawable.bg_chip, animate)
            Motion.textColor(view, view.context.getColor(if (isOn) R.color.white else R.color.text_primary), animate)
        }
    }

    /** Radio style rows, used by the report reasons. */
    fun rows(
        rows: List<View>,
        icons: List<android.widget.ImageView>,
        selected: Int,
        onPick: (Int) -> Unit = {}
    ) = rows(rows, icons, selected, false, onPick)

    private fun rows(
        rows: List<View>,
        icons: List<android.widget.ImageView>,
        selected: Int,
        animate: Boolean,
        onPick: (Int) -> Unit
    ) {
        rows.forEachIndexed { index, row ->
            row.setOnClickListener {
                rows(rows, icons, index, true, onPick)
                Motion.tick(row)
                onPick(index)
            }
            val isOn = index == selected
            Motion.background(row, if (isOn) R.drawable.bg_input_selected else R.drawable.bg_input, animate)
            if (animate && isOn) Motion.pop(icons[index])
            icons[index].setImageResource(
                if (isOn) R.drawable.ic_radio_on else R.drawable.ic_radio_off
            )
            icons[index].setColorFilter(
                row.context.getColor(if (isOn) R.color.uc_green else R.color.text_secondary)
            )
        }
    }
}
