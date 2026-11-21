package com.nevergrowold.holdhightheshop

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.DynamicAnimation

/**
 * Listings the student bookmarked. Opened from the bookmark in the Campus
 * Market header, the "View" action after saving, and the profile menu.
 */
class SavedActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_saved)
        BarInsets.apply(this)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render(restored: String? = null) {
        val saved = UserState.saved()
        val list = findViewById<LinearLayout>(R.id.savedList)
        list.removeAllViews()

        saved.forEachIndexed { index, item ->
            val row = addRow(
                list,
                item.title,
                Listings.priceLabel(this, item),
                getString(R.string.saved_meta_format, item.program, item.seller.name),
                index < saved.size - 1,
            ) { openListingById(item.id) }

            row.findViewById<ImageView>(R.id.rowActionIcon).apply {
                setImageResource(R.drawable.ic_bookmark_filled)
                setColorFilter(getColor(R.color.uc_green))
                contentDescription = getString(R.string.cd_unsave)
            }
            row.findViewById<View>(R.id.rowAction).setOnClickListener { action ->
                action.isEnabled = false
                Motion.tick(action)
                UserState.setSaved(item.id, false)
                Motion.remove(row) { render() }
                notify(getString(R.string.toast_unsaved), getString(R.string.action_undo)) {
                    UserState.setSaved(item.id, true)
                    Motion.confirm(list)
                    render(restored = item.id)
                }
            }
            if (item.id == restored) Motion.insert(row, fromEnd = false)
        }

        val countView = findViewById<TextView>(R.id.savedCount)
        countView.isVisible = saved.isNotEmpty()
        if (saved.isNotEmpty()) {
            Motion.rollText(countView, resources.getQuantityString(R.plurals.saved_count, saved.size, saved.size))
        }

        val empty = findViewById<View>(R.id.savedEmpty)
        val wasEmpty = empty.isVisible
        empty.isVisible = saved.isEmpty()
        if (saved.isEmpty() && !wasEmpty && Motion.enabled(this)) {
            val dp = resources.displayMetrics.density
            empty.alpha = 0f
            empty.scaleX = 0.95f
            empty.scaleY = 0.95f
            empty.translationY = 10f * dp
            Motion.spring(empty, DynamicAnimation.ALPHA, 1f, Motion.Effects.DEFAULT)
            Motion.spring(empty, DynamicAnimation.TRANSLATION_Y, 0f, Motion.Spatial.RESPONSIVE)
            Motion.springScale(empty, 1f, Motion.Spatial.RESPONSIVE)
        }
    }
}
