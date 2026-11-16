package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible

class MainActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        BarInsets.apply(this, hideBottomOnKeyboard = true)

        val row = findViewById<LinearLayout>(R.id.programRow)
        Listings.COLLEGES.forEach { program ->
            val tile = LayoutInflater.from(this).inflate(R.layout.item_program_tile, row, false)
            tile.findViewById<ImageView>(R.id.programIcon).setImageResource(program.icon)
            tile.findViewById<TextView>(R.id.programCode).text = program.code
            tile.tag = program.code
            tile.setOnClickListener { openProgram(it) }
            row.addView(tile)
        }
    }

    override fun onResume() {
        super.onResume()
        bindReturnReminder()
        // Same numbers as Profile: everything finished, and your listings still out there.
        findViewById<TextView>(R.id.statExchanged).text = Listings.completed().size.toString()
        findViewById<TextView>(R.id.statListings).text =
            (Listings.myActive().size + UserState.lending().size).toString()

        // Fresh on campus: the newest listings from other students.
        val fresh = findViewById<LinearLayout>(R.id.freshRow)
        fresh.removeAllViews()
        Listings.open().sortedBy { it.postedDaysAgo }.take(FRESH_COUNT).forEach { listing ->
            val card = listingCard(fresh, listing)
            card.layoutParams = LinearLayout.LayoutParams(dp(this, 172), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(this@MainActivity, 4) }
            fresh.addView(card)
        }
    }

    /**
     * Shows the most urgent thing you borrowed when it is due within three
     * days or overdue. Tapping it opens that listing.
     */
    private fun bindReturnReminder() {
        val card = findViewById<View>(R.id.returnReminder)
        val (item, loan) = UserState.borrowing().firstOrNull { !it.second.returnPending && Rentals.isDueSoon(it.second.dueMillis) }
            ?: run { card.isVisible = false; return }
        val overdue = Rentals.isOverdue(loan.dueMillis)
        card.isVisible = true
        card.setBackgroundResource(if (overdue) R.drawable.bg_reminder_overdue else R.drawable.bg_badge_swap)
        findViewById<TextView>(R.id.reminderTitle).text = if (overdue) {
            getString(R.string.reminder_overdue_title, item.title)
        } else {
            getString(R.string.reminder_title, item.title, loan.partner.name.substringBefore(' '))
        }
        findViewById<TextView>(R.id.reminderDue).apply {
            text = getString(R.string.row_borrowed_meta, loan.partner.name.substringBefore(' '), Rentals.dateLabel(loan.dueMillis), Rentals.countdown(this@MainActivity, loan.dueMillis))
            setTextColor(getColor(if (overdue) R.color.danger else R.color.swap_text))
        }
        findViewById<ImageView>(R.id.reminderIcon).setColorFilter(getColor(if (overdue) R.color.danger else R.color.swap_text))
        card.setOnClickListener { openListingById(item.id) }
    }

    companion object {
        private const val FRESH_COUNT = 6
    }

    /** Opens the notifications screen. */
    fun openNotifications(view: View) {
        startActivity(Intent(this, NotificationsActivity::class.java))
    }

    /** "Items exchanged" card: the Completed tab on the profile lists them. */
    fun openExchanged(view: View) {
        switchTab(ProfileActivity::class.java) { putExtra(EXTRA_TAB, ProfileActivity.TAB_COMPLETED) }
    }

    /** "Active listings" card: the Active tab on the profile lists them. */
    fun openActiveListings(view: View) {
        switchTab(ProfileActivity::class.java) { putExtra(EXTRA_TAB, ProfileActivity.TAB_ACTIVE) }
    }
}
