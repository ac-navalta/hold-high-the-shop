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
import com.nevergrowold.holdhightheshop.data.ActiveMeetupCountdown
import com.nevergrowold.holdhightheshop.system.MeetupCountdownService

/**
 * Home tab page hosted inside [MainActivity].
 * Displays real student stats, live bound [MeetupCountdownService] card,
 * urgent return reminders, UC college tiles (starting with "All colleges"),
 * and the newest open listings from fellow UCians.
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

        // First tile: "All colleges" for universal campus supplies
        val allCollegesTile = LayoutInflater.from(activity).inflate(R.layout.item_program_tile, row, false)
        allCollegesTile.findViewById<ImageView>(R.id.programIcon).setImageResource(R.drawable.ic_market)
        allCollegesTile.findViewById<TextView>(R.id.programCode).setText(R.string.tag_all_colleges)
        allCollegesTile.tag = Listings.FILTER_ALL_COLLEGES
        allCollegesTile.setOnClickListener { activity.openProgram(it) }
        row.addView(allCollegesTile)

        Listings.COLLEGES.forEach { program ->
            val tile = LayoutInflater.from(activity).inflate(R.layout.item_program_tile, row, false)
            tile.findViewById<ImageView>(R.id.programIcon).setImageResource(program.icon)
            tile.findViewById<TextView>(R.id.programCode).text = program.code
            tile.tag = program.code
            tile.setOnClickListener { activity.openProgram(it) }
            (tile.layoutParams as LinearLayout.LayoutParams).marginStart =
                activity.resources.getDimensionPixelSize(R.dimen.grid_gap)
            row.addView(tile)
        }

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
        if (UserState.hasUnreadNotifications && !bellRangThisSession) {
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

    /** Updated live every second while [MeetupCountdownService] is bound in [MainActivity]. */
    fun updateCountdown(countdown: ActiveMeetupCountdown?) {
        val card = root.findViewById<View>(R.id.liveCountdownCard) ?: return
        if (countdown == null) {
            card.isVisible = false
            return
        }
        card.isVisible = true
        root.findViewById<TextView>(R.id.countdownKicker).setText(
            if (countdown.isLoanDue) R.string.home_countdown_kicker_loan
            else R.string.home_countdown_kicker_meetup,
        )
        root.findViewById<TextView>(R.id.countdownTitle).text = countdown.listingTitle
        root.findViewById<TextView>(R.id.countdownMeta).text =
            "${countdown.partnerFirstName} \u2022 ${countdown.meetupPlace}"
        val timerView = root.findViewById<TextView>(R.id.countdownTimer)
        timerView.text = MeetupCountdownService.formatRemaining(countdown.remainingMillis)
        card.setOnClickListener { activity.openListingById(countdown.listingId) }
    }

    fun refresh() {
        root.findViewById<TextView>(R.id.homeGreetingTitle)?.text =
            activity.getString(R.string.greeting_format, UserState.profile.first)
        Motion.setVisiblePop(root.findViewById(R.id.bellDot), UserState.hasUnreadNotifications)
        bindReturnReminder()

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

        val freshItems = Listings.open().sortedByDescending { it.createdAt }.take(FRESH_COUNT)
        val freshSig = freshItems.map { "${it.id}:${it.title}:${it.price}:${it.photoUris.firstOrNull()}" }
        val fresh = root.findViewById<LinearLayout>(R.id.freshRow)
        if (freshSig != lastFreshIds || fresh.childCount == 0) {
            lastFreshIds = freshSig
            fresh.removeAllViews()
            if (freshItems.isEmpty()) {
                val emptyPill = TextView(activity).apply {
                    setBackgroundResource(R.drawable.bg_card)
                    setPadding(dp(activity, 18), dp(activity, 20), dp(activity, 18), dp(activity, 20))
                    setText(R.string.home_empty_fresh)
                    setTextColor(activity.getColor(R.color.text_secondary))
                    textSize = 13f
                    setOnClickListener { activity.openCreateListing(it) }
                }
                fresh.addView(emptyPill)
            } else {
                freshItems.forEach { listing ->
                    val card = activity.listingCard(fresh, listing)
                    card.layoutParams = LinearLayout.LayoutParams(dp(activity, 172), LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply {
                            if (fresh.childCount > 0) {
                                marginStart = activity.resources.getDimensionPixelSize(R.dimen.grid_gap)
                            }
                        }
                    fresh.addView(card)
                }
            }
        }
    }

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
            activity.getString(R.string.reminder_title, item.title, loan.partner.first)
        }
        root.findViewById<TextView>(R.id.reminderDue).apply {
            text = activity.getString(
                R.string.row_borrowed_meta,
                loan.partner.first,
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
