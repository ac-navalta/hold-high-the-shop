package com.nevergrowold.holdhightheshop

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

/**
 * Shared selection controls (pills, segmented controls, filter chips, toggle
 * chips, radio rows).
 *
 * Only views whose state actually changed animate, avoiding visual noise on
 * untouched siblings, and selection never fights the tactile press-release spring.
 */
object Choice {

    /** Pill buttons such as Condition options on Create Listing. */
    fun pills(views: List<TextView>, selected: Int, onPick: (Int) -> Unit = {}) =
        bindSingle(views, selected, R.drawable.bg_chip_active, R.drawable.bg_input, false, false, onPick)

    /** Filter chips on Campus Market, Reserve sheet, and Profile Active groups. */
    fun chips(
        views: List<TextView>,
        selected: Int,
        allowReselect: Boolean = false,
        onPick: (Int) -> Unit = {},
    ) = bindSingle(views, selected, R.drawable.bg_chip_active, R.drawable.bg_chip, false, allowReselect, onPick)

    private fun bindSingle(
        views: List<TextView>,
        selected: Int,
        activeBg: Int,
        inactiveBg: Int,
        animate: Boolean,
        allowReselect: Boolean = false,
        onPick: (Int) -> Unit,
    ) {
        views.forEachIndexed { index, view ->
            val isOn = index == selected
            val wasOn = view.getTag(R.id.choice_on) as? Boolean
            val shouldAnimate = animate && wasOn != null && wasOn != isOn
            view.setTag(R.id.choice_on, isOn)

            view.setOnClickListener {
                val alreadyOn = view.getTag(R.id.choice_on) == true
                if (alreadyOn && !allowReselect) return@setOnClickListener
                Motion.tick(view)
                if (!alreadyOn) bindSingle(views, index, activeBg, inactiveBg, true, allowReselect, onPick)
                onPick(index)
            }

            if (wasOn == null || wasOn != isOn) {
                Motion.background(view, if (isOn) activeBg else inactiveBg, shouldAnimate)
                Motion.textColor(
                    view,
                    view.context.getColor(if (isOn) R.color.white else R.color.text_primary),
                    shouldAnimate,
                )
            }
        }
    }

    /** Multi-select toggle chips (Condition / Type in Market filters, Days in Create listing). */
    fun toggles(
        views: List<TextView>,
        selected: Set<Int>,
        inactiveBg: Int = R.drawable.bg_chip,
        onChange: (Set<Int>) -> Unit,
    ) {
        views.forEachIndexed { index, view ->
            val isOn = index in selected
            val wasOn = view.getTag(R.id.choice_on) as? Boolean
            val shouldAnimate = wasOn != null && wasOn != isOn
            view.setTag(R.id.choice_on, isOn)

            view.setOnClickListener {
                Motion.tick(view)
                onChange(if (isOn) selected - index else selected + index)
            }

            if (wasOn == null || wasOn != isOn) {
                Motion.background(view, if (isOn) R.drawable.bg_chip_active else inactiveBg, shouldAnimate)
                Motion.textColor(
                    view,
                    view.context.getColor(if (isOn) R.color.white else R.color.text_primary),
                    shouldAnimate,
                )
            }
        }
    }

    /** Sell / Rent / Swap segmented control with interruptible sliding pill. */
    fun segments(views: List<TextView>, selected: Int, onPick: (Int) -> Unit = {}) =
        segments(views, selected, animate = false, onPick = onPick)

    private fun segments(views: List<TextView>, selected: Int, animate: Boolean, onPick: (Int) -> Unit) {
        val track = views.firstOrNull()?.parent as? ViewGroup
        val previous = track?.getTag(R.id.choice_on) as? Int

        views.forEachIndexed { index, view ->
            val isOn = index == selected
            view.setOnClickListener {
                val current = track?.getTag(R.id.choice_on) as? Int
                if (current == index) return@setOnClickListener
                Motion.tick(view)
                segments(views, index, animate = true, onPick = onPick)
                onPick(index)
            }
            if (!animate || previous == null) {
                if (isOn) view.setBackgroundResource(R.drawable.bg_segment_active)
                else view.setBackgroundColor(Color.TRANSPARENT)
            }
            val changed = animate && (index == selected || index == previous)
            Motion.textColor(
                view,
                view.context.getColor(if (isOn) R.color.white else R.color.text_secondary),
                changed,
            )
        }

        if (animate && track != null && previous != null && previous != selected) {
            Motion.slideIndicator(track, views[previous], views[selected], R.drawable.bg_segment_active)
        }
        track?.setTag(R.id.choice_on, selected)
    }

    /** Radio-style option rows (Report reasons, Swap items, Review requests, Meetup check). */
    fun rows(
        rows: List<View>,
        icons: List<ImageView>,
        selected: Int,
        onPick: (Int) -> Unit = {},
    ) = rows(rows, icons, selected, animate = false, onPick = onPick)

    private fun rows(
        rows: List<View>,
        icons: List<ImageView>,
        selected: Int,
        animate: Boolean,
        onPick: (Int) -> Unit,
    ) {
        rows.forEachIndexed { index, row ->
            val isOn = index == selected
            val wasOn = row.getTag(R.id.choice_on) as? Boolean
            val shouldAnimate = animate && wasOn != null && wasOn != isOn
            row.setTag(R.id.choice_on, isOn)

            row.setOnClickListener {
                if (row.getTag(R.id.choice_on) == true) return@setOnClickListener
                Motion.tick(row)
                rows(rows, icons, index, animate = true, onPick = onPick)
                onPick(index)
            }

            if (wasOn == null || wasOn != isOn) {
                Motion.background(row, if (isOn) R.drawable.bg_input_selected else R.drawable.bg_input, shouldAnimate)
                val icon = icons[index]
                val onColor = row.context.getColor(R.color.uc_green)
                val offColor = row.context.getColor(R.color.text_secondary)
                icon.setImageResource(if (isOn) R.drawable.ic_radio_on else R.drawable.ic_radio_off)
                Motion.iconTint(icon, if (isOn) offColor else onColor, if (isOn) onColor else offColor, shouldAnimate)
                if (shouldAnimate && isOn) {
                    icon.scaleX = 0.82f
                    icon.scaleY = 0.82f
                    Motion.springScale(icon, 1f, Motion.Spatial.SNAPPY)
                }
            }
        }
    }
}
