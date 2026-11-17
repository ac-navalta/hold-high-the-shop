package com.nevergrowold.holdhightheshop

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible

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
                list, item.title, Listings.priceLabel(this, item),
                getString(R.string.saved_meta_format, item.program, item.seller.name),
                index < saved.size - 1
            ) { openListingById(item.id) }

            // Trailing filled bookmark: tap to remove, with Undo.
            row.findViewById<ImageView>(R.id.rowActionIcon).apply {
                setImageResource(R.drawable.ic_bookmark_filled)
                setColorFilter(getColor(R.color.uc_green))
                contentDescription = getString(R.string.cd_unsave)
            }
            row.findViewById<View>(R.id.rowAction).setOnClickListener { action ->
                action.isEnabled = false
                UserState.setSaved(item.id, false)
                // The row slides away and the list closes up before it is rebuilt.
                Motion.remove(row) { render() }
                notify(getString(R.string.toast_unsaved), getString(R.string.action_undo)) {
                    UserState.setSaved(item.id, true)
                    render(restored = item.id)
                }
            }
            if (item.id == restored) Motion.insert(row, fromEnd = false)
        }

        findViewById<TextView>(R.id.savedCount).apply {
            text = resources.getQuantityString(R.plurals.saved_count, saved.size, saved.size)
            isVisible = saved.isNotEmpty()
        }
        findViewById<View>(R.id.savedEmpty).isVisible = saved.isEmpty()
    }
}
