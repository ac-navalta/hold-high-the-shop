package com.nevergrowold.holdhightheshop

import android.animation.Animator
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updatePadding

/**
 * Home tab page hosted inside [MainActivity].
 */
internal class HomeTab(
    private val activity: MainActivity,
    val root: View,
) {
    private val header: View = root.findViewById(R.id.headerArea)
    private val headerBaseTop: Int = header.paddingTop
    private var reminderPulse: Animator? = null
    private var lastFreshIds: List<String> = emptyList()
    private var active = false

    init {
        val row = root.findViewById<LinearLayout>(R.id.programRow)
        Listings.COLLEGES.forEach { program ->
            val tile = LayoutInflater.from(activity).inflate(R.layout.item_program_tile, row, false)
            tile.findViewById<ImageView>(R.id.programIcon).setImageResource(program.icon)
            tile.findViewById<TextView>(R.id.programCode).text = program.code
            tile.tag = program.code
            tile.setOnClickListener { activity.openProgram(it) }
            if (row.childCount > 0) {
                (tile.layoutParams as LinearLayout.LayoutParams).marginStart =
                    activity.resources.getDimensionPixelSize(R.dimen.grid_gap)
            }
            row.addView(tile)
        }

        // Subtle scroll-linked header depth as the student scrolls down Home.
        val scroll = root.findViewById<ScrollView>(R.id.homeScroll)
        val greeting = root.findViewById<View>(R.id.homeGreeting)
        val fadeSpan = 120f * activity.resources.displayMetrics.density
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            if (!Motion.enabled(activity)) return@setOnScrollChangeListener
            val t = (y / fadeSpan).coerceIn(0f, 1f)
            greeting.translationY = y * 0.22f
            greeting.alpha = 1f - 0.65f * t
        }
    }

    fun applyTopInset(top: Int) {
        header.updatePadding(top = headerBaseTop + top)
    }

    fun ringBellOnceIfUnread() {
        if (!UserState.notificationsRead && !bellRangThisSession) {
            bellRangThisSession = true
            val bell = root.findViewById<View>(R.id.bellIcon)
            bell.post { Motion.ringBell(bell, delay = 220) }
        }
    }

    fun onShow() {
        active = true
        refresh()
    }

    fun onHide() {
        active = false
        reminderPulse?.cancel()
        reminderPulse = null
    }

    fun refresh() {
        root.findViewById<TextView>(R.id.homeGreetingTitle)?.text =
            activity.getString(R.string.greeting_format, UserState.profile.first)
        Motion.setVisiblePop(root.findViewById(R.id.bellDot), !UserState.notificationsRead)
        bindReturnReminder()

        // First visit counts the stats up briskly; later visits roll only what changed.
        val exchanged = root.findViewById<TextView>(R.id.statExchanged)
        val activeCount = root.findViewById<TextView>(R.id.statListings)
        val exchangedValue = Listings.completed().size.toString()
        val activeValue = (Listings.myActive().size + UserState.lending().size).toString()
        if (exchanged.isLaidOut) {
            Motion.rollText(exchanged, exchangedValue)
            Motion.rollText(activeCount, activeValue)
        } else {
            Motion.countUp(exchanged, exchangedValue, delay = 100)
            Motion.countUp(activeCount, activeValue, delay = 150)
        }

        // Fresh on campus: rebuild cards when the newest open listings or their details change.
        val freshItems = Listings.open().sortedBy { it.postedDaysAgo }.take(FRESH_COUNT)
        val freshSig = freshItems.map { "${it.id}:${it.title}:${it.price}:${it.photoUris.firstOrNull()}" }
        if (freshSig != lastFreshIds) {
            lastFreshIds = freshSig
            val fresh = root.findViewById<LinearLayout>(R.id.freshRow)
            fresh.removeAllViews()
            freshItems.forEach { listing ->
                val card = activity.listingCard(fresh, listing)
                card.layoutParams = LinearLayout.LayoutParams(dp(activity, 172), LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { if (fresh.childCount > 0) marginStart = activity.resources.getDimensionPixelSize(R.dimen.grid_gap) }
                fresh.addView(card)
            }
        }
    }

    /**
     * Shows the most urgent thing you borrowed when it is due within three
     * days or overdue. Tapping it opens that listing.
     */
    private fun bindReturnReminder() {
        val card = root.findViewById<View>(R.id.returnReminder)
        val (item, loan) = UserState.borrowing()
            .firstOrNull { !it.second.returnPending && Rentals.isDueSoon(it.second.dueMillis) }
            ?: run {
                card.isVisible = false
                reminderPulse?.cancel()
                reminderPulse = null
                return
            }
        val overdue = Rentals.isOverdue(loan.dueMillis)
        card.isVisible = true
        card.setBackgroundResource(if (overdue) R.drawable.bg_reminder_overdue else R.drawable.bg_badge_swap)
        root.findViewById<TextView>(R.id.reminderTitle).text = if (overdue) {
            activity.getString(R.string.reminder_overdue_title, item.title)
        } else {
            activity.getString(R.string.reminder_title, item.title, loan.partner.name.substringBefore(' '))
        }
        root.findViewById<TextView>(R.id.reminderDue).apply {
            text = activity.getString(
                R.string.row_borrowed_meta,
                loan.partner.name.substringBefore(' '),
                Rentals.dateLabel(loan.dueMillis),
                Rentals.countdown(activity, loan.dueMillis),
            )
            setTextColor(activity.getColor(if (overdue) R.color.danger else R.color.swap_text))
        }
        root.findViewById<ImageView>(R.id.reminderIcon).let { icon ->
            icon.setColorFilter(activity.getColor(if (overdue) R.color.danger else R.color.swap_text))
            reminderPulse?.cancel()
            reminderPulse = if (overdue && active) Motion.breathe(icon) else null
        }
        card.setOnClickListener { activity.openListingById(item.id) }
    }

    companion object {
        private const val FRESH_COUNT = 6
        private var bellRangThisSession = false
    }
}
