package com.nevergrowold.holdhightheshop

import android.animation.Animator
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible

class MainActivity : BaseActivity() {

    /** The overdue icon's calm breathing loop; only runs while visible. */
    private var reminderPulse: Animator? = null

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
            if (row.childCount > 0) {
                (tile.layoutParams as LinearLayout.LayoutParams).marginStart =
                    resources.getDimensionPixelSize(R.dimen.grid_gap)
            }
            row.addView(tile)
        }

        // Subtle scroll-linked header depth as the student scrolls down Home.
        val scroll = findViewById<ScrollView>(R.id.homeScroll)
        val greeting = findViewById<View>(R.id.homeGreeting)
        val fadeSpan = 120f * resources.displayMetrics.density
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            if (!Motion.enabled(this)) return@setOnScrollChangeListener
            val t = (y / fadeSpan).coerceIn(0f, 1f)
            greeting.translationY = y * 0.22f
            greeting.alpha = 1f - 0.65f * t
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // Pendulum spring chime once per session when unread notifications exist.
        if (!UserState.notificationsRead && !bellRangThisSession) {
            bellRangThisSession = true
            val bell = findViewById<View>(R.id.bellIcon)
            bell.post { Motion.ringBell(bell, delay = 220) }
        }
    }

    override fun onResume() {
        super.onResume()
        Motion.setVisiblePop(findViewById(R.id.bellDot), !UserState.notificationsRead)
        bindReturnReminder()

        // First visit counts the stats up briskly; later visits roll only what changed.
        val exchanged = findViewById<TextView>(R.id.statExchanged)
        val active = findViewById<TextView>(R.id.statListings)
        val exchangedValue = Listings.completed().size.toString()
        val activeValue = (Listings.myActive().size + UserState.lending().size).toString()
        if (exchanged.isLaidOut) {
            Motion.rollText(exchanged, exchangedValue)
            Motion.rollText(active, activeValue)
        } else {
            Motion.countUp(exchanged, exchangedValue, delay = 100)
            Motion.countUp(active, activeValue, delay = 150)
        }

        // Fresh on campus: the newest listings from other students.
        val fresh = findViewById<LinearLayout>(R.id.freshRow)
        fresh.removeAllViews()
        Listings.open().sortedBy { it.postedDaysAgo }.take(FRESH_COUNT).forEach { listing ->
            val card = listingCard(fresh, listing)
            card.layoutParams = LinearLayout.LayoutParams(dp(this, 172), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { if (fresh.childCount > 0) marginStart = resources.getDimensionPixelSize(R.dimen.grid_gap) }
            fresh.addView(card)
        }
    }

    /**
     * Shows the most urgent thing you borrowed when it is due within three
     * days or overdue. Tapping it opens that listing.
     */
    private fun bindReturnReminder() {
        val card = findViewById<View>(R.id.returnReminder)
        val (item, loan) = UserState.borrowing()
            .firstOrNull { !it.second.returnPending && Rentals.isDueSoon(it.second.dueMillis) }
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
            text = getString(
                R.string.row_borrowed_meta,
                loan.partner.name.substringBefore(' '),
                Rentals.dateLabel(loan.dueMillis),
                Rentals.countdown(this@MainActivity, loan.dueMillis),
            )
            setTextColor(getColor(if (overdue) R.color.danger else R.color.swap_text))
        }
        findViewById<ImageView>(R.id.reminderIcon).let { icon ->
            icon.setColorFilter(getColor(if (overdue) R.color.danger else R.color.swap_text))
            reminderPulse?.cancel()
            reminderPulse = if (overdue) Motion.breathe(icon) else null
        }
        card.setOnClickListener { openListingById(item.id) }
    }

    override fun onPause() {
        reminderPulse?.cancel()
        reminderPulse = null
        super.onPause()
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

    companion object {
        private const val FRESH_COUNT = 6
        private var bellRangThisSession = false
    }
}
