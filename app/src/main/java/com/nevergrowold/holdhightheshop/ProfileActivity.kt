package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible

class ProfileActivity : BaseActivity() {

    private var tab = TAB_ACTIVE

    /** The tab currently drawn as selected, so only a real change animates. -1 before the first draw. */
    private var shownTab = -1

    /** Active tab: which group to show. One of the GROUP_ constants. */
    private var group = GROUP_ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_profile)
        BarInsets.apply(this, hideBottomOnKeyboard = true)
        tab = intent.getIntExtra(EXTRA_TAB, TAB_ACTIVE)
        if (intent.hasExtra(EXTRA_TAB)) scrollToTabs()
    }

    /** Arriving from a Home stat card while the profile is already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(EXTRA_TAB)) {
            tab = intent.getIntExtra(EXTRA_TAB, TAB_ACTIVE)
            selectTab(tab)
            scrollToTabs()
        }
    }

    override fun onResume() {
        super.onResume()
        // Saved items, deals and ratings can change on other screens, so redraw on return.
        // The reliability card counts up the first time you see it.
        Motion.countUp(findViewById(R.id.profileRating), Reliability.rating(), delay = 150)
        Motion.countUp(findViewById(R.id.profileOnTime), Reliability.onTime(), delay = 210)
        Motion.countUp(findViewById(R.id.profileCompleted), Reliability.completed().toString(), delay = 270)
        selectTab(tab)
    }

    private fun scrollToTabs() {
        val scroll = findViewById<ScrollView>(R.id.profileScroll)
        val tabs = findViewById<View>(R.id.profileTabs)
        scroll.post { scroll.smoothScrollTo(0, tabs.top) }
    }

    /** Moves the green underline, highlights the chosen tab and shows its list. */
    private fun selectTab(selected: Int) {
        tab = selected
        val rows = listOf<View>(findViewById(R.id.tab1), findViewById(R.id.tab2), findViewById(R.id.tab3))
        val labels = listOf<TextView>(
            findViewById(R.id.tab1Label), findViewById(R.id.tab2Label), findViewById(R.id.tab3Label)
        )
        val bars = listOf<View>(findViewById(R.id.tab1Bar), findViewById(R.id.tab2Bar), findViewById(R.id.tab3Bar))

        val changed = selected != shownTab && shownTab >= 0
        shownTab = selected
        rows.forEachIndexed { index, row ->
            row.setOnClickListener { selectTab(index) }
            val isOn = index == selected
            Motion.textColor(labels[index], getColor(if (isOn) R.color.uc_green else R.color.text_secondary), changed)
            if (isOn) {
                bars[index].setBackgroundResource(R.drawable.bg_tab_active)
                // The underline grows under the newly chosen tab.
                if (changed) Motion.grow(bars[index])
            } else {
                bars[index].setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        }

        val saved = UserState.saved()
        val mine = Listings.myActive()
        val reserved = UserState.reservedListings()
        val borrowing = UserState.borrowing()
        val lending = UserState.lending()
        val completed = Listings.completed()
        Motion.rollText(findViewById(R.id.tab1Count), (borrowing.size + lending.size + reserved.size + mine.size).toString())
        Motion.rollText(findViewById(R.id.tab2Count), completed.size.toString())
        Motion.rollText(findViewById(R.id.tab3Count), saved.size.toString())

        findViewById<View>(R.id.activeFiltersScroll).isVisible = selected == TAB_ACTIVE
        val list = findViewById<LinearLayout>(R.id.profileList)
        list.removeAllViews()
        when (selected) {
            // Active: loans with due dates first, then reservations, then your open listings.
            // The chips above narrow it to one group; "All" shows every group with its heading.
            TAB_ACTIVE -> {
                bindGroupChips(
                    listOf(
                        GROUP_ALL to getString(R.string.filter_all_format, borrowing.size + lending.size + reserved.size + mine.size),
                        GROUP_BORROWING to getString(R.string.filter_borrowing_format, borrowing.size),
                        GROUP_LENT to getString(R.string.filter_lent_format, lending.size),
                        GROUP_RESERVED to getString(R.string.filter_reserved_format, reserved.size),
                        GROUP_MINE to getString(R.string.filter_mine_format, mine.size)
                    ),
                    listOf(1, borrowing.size, lending.size, reserved.size, mine.size)
                )
                fun shows(g: Int) = group == GROUP_ALL || group == g
                if (shows(GROUP_BORROWING) && borrowing.isNotEmpty()) addSection(list, R.string.section_borrowing, R.string.section_borrowing_hint)
                if (shows(GROUP_BORROWING)) borrowing.forEachIndexed { i, (item, loan) ->
                    val meta = if (loan.returnPending) {
                        getString(R.string.row_return_pending, loan.partner.name.substringBefore(' '))
                    } else {
                        getString(
                            R.string.row_borrowed_meta, loan.partner.name.substringBefore(' '),
                            Rentals.dateLabel(loan.dueMillis), Rentals.countdown(this, loan.dueMillis)
                        )
                    }
                    val row = addRow(
                        list, item.title, Listings.priceLabel(this, item), meta,
                        i < borrowing.size - 1, getString(R.string.role_borrowing), giving = false
                    ) { openListingById(item.id) }
                    if (!loan.returnPending && Rentals.isOverdue(loan.dueMillis)) {
                        row.findViewById<TextView>(R.id.rowMeta).setTextColor(getColor(R.color.danger))
                    }
                }
                if (shows(GROUP_LENT) && lending.isNotEmpty()) addSection(list, R.string.section_lent, R.string.section_lent_hint)
                if (shows(GROUP_LENT)) lending.forEachIndexed { i, (item, loan) ->
                    val meta = if (loan.returnPending) {
                        getString(R.string.row_confirm_return, loan.partner.name.substringBefore(' '))
                    } else {
                        getString(
                            R.string.row_lent_meta, loan.partner.name.substringBefore(' '),
                            Rentals.dateLabel(loan.dueMillis), Rentals.countdown(this, loan.dueMillis)
                        )
                    }
                    val row = addRow(
                        list, item.title, Listings.priceLabel(this, item), meta,
                        i < lending.size - 1, getString(R.string.role_lent_out), giving = true
                    ) { openListingById(item.id) }
                    val metaView = row.findViewById<TextView>(R.id.rowMeta)
                    when {
                        loan.returnPending -> metaView.setTextColor(getColor(R.color.swap_text))
                        Rentals.isOverdue(loan.dueMillis) -> metaView.setTextColor(getColor(R.color.danger))
                    }
                }
                if (shows(GROUP_RESERVED) && reserved.isNotEmpty()) addSection(list, R.string.section_reserved, R.string.section_reserved_hint)
                if (shows(GROUP_RESERVED)) reserved.forEachIndexed { i, item ->
                    val reservation = UserState.reservation(item.id)
                    val missed = Deals.meetupMissed(reservation?.meetMillis)
                    val meta = when {
                        missed -> getString(R.string.row_meetup_missed, item.seller.first)
                        reservation?.meetMillis != null -> getString(
                            R.string.row_reserved_when, item.seller.name, Rentals.whenLabel(this, reservation.meetMillis)
                        )
                        else -> getString(R.string.reserved_from_format, item.seller.name)
                    }
                    val row = addRow(
                        list, item.title, Listings.priceLabel(this, item), meta,
                        i < reserved.size - 1, Listings.role(this, item), giving = false, swap = item.isSwap
                    ) { openListingById(item.id) }
                    if (missed) row.findViewById<TextView>(R.id.rowMeta).setTextColor(getColor(R.color.swap_text))
                }
                if (shows(GROUP_MINE) && mine.isNotEmpty()) addSection(list, R.string.section_mine, R.string.section_mine_hint)
                if (shows(GROUP_MINE)) mine.forEachIndexed { i, item ->
                    // Promised to someone, or waiting for your answer: say so, in amber when it needs you.
                    val deal = UserState.accepted(item.id)
                    val pending = UserState.pending(item.id)
                    val needsYou = (deal != null && Deals.meetupMissed(deal.meetMillis)) || pending.isNotEmpty()
                    val meta = when {
                        deal != null && Deals.meetupMissed(deal.meetMillis) -> getString(R.string.row_meetup_missed, deal.from.first)
                        deal != null -> getString(
                            when {
                                item.isSwap -> R.string.row_deal_swapping
                                item.isRent -> R.string.row_deal_lending
                                else -> R.string.row_deal_reserved
                            },
                            deal.from.first, Rentals.whenLabel(this, deal.meetMillis)
                        )
                        pending.isNotEmpty() -> resources.getQuantityString(
                            R.plurals.row_requests, pending.size, pending.size,
                            Rentals.timeLeft(this, pending.minOf { it.expiresMillis })
                        )
                        else -> getString(R.string.posted_days_format, item.postedDaysAgo + 2)
                    }
                    val row = addRow(
                        list, item.title, Listings.priceLabel(this, item), meta,
                        i < mine.size - 1, Listings.role(this, item), giving = true, swap = item.isSwap
                    ) { openListingById(item.id) }
                    if (needsYou) row.findViewById<TextView>(R.id.rowMeta).setTextColor(getColor(R.color.swap_text))
                }
            }
            TAB_COMPLETED -> completed.forEachIndexed { i, exchange ->
                addRow(
                    list, exchange.title, Listings.price(exchange.price), ExchangeText.summary(this, exchange),
                    i < completed.size - 1, ExchangeText.badge(this, exchange), exchange.giving,
                    exchange.kind == Exchange.SWAPPED
                ) {
                    startActivity(
                        Intent(this, ExchangeDetailActivity::class.java).putExtra(ExchangeDetailActivity.EXTRA_EXCHANGE, exchange.id)
                    )
                }
            }
            else -> saved.forEachIndexed { i, item ->
                addRow(
                    list, item.title, Listings.priceLabel(this, item),
                    getString(R.string.saved_meta_format, item.program, item.seller.name),
                    i < saved.size - 1
                ) { openListingById(item.id) }
            }
        }
        findViewById<View>(R.id.profileEmpty).isVisible = selected == TAB_SAVED && saved.isEmpty()
        // Switching tabs swaps the whole list: let the new one land so the change is visible.
        if (changed) Motion.enter((0 until list.childCount).map { list.getChildAt(it) }, rise = 10)
    }

    /**
     * Chips above the Active list. Groups with nothing in them are hidden;
     * if the chosen group empties out, the list falls back to All.
     */
    private fun bindGroupChips(groups: List<Pair<Int, String>>, counts: List<Int>) {
        val box = findViewById<LinearLayout>(R.id.activeFilters)
        box.removeAllViews()
        val shown = groups.filterIndexed { i, _ -> counts[i] > 0 }
        if (shown.none { it.first == group }) group = GROUP_ALL
        val chips = shown.map { (_, label) ->
            (LayoutInflater.from(this).inflate(R.layout.item_chip, box, false) as TextView).also {
                it.text = label
                box.addView(it)
            }
        }
        Choice.chips(chips, shown.indexOfFirst { it.first == group }) {
            group = shown[it].first
            selectTab(TAB_ACTIVE)
        }
    }

    /** The "..." button in the header. */
    fun openMenu(view: View) {
        val options = listOf(
            getString(R.string.menu_edit_profile),
            getString(R.string.menu_settings),
            getString(R.string.menu_logout)
        )
        val icons = listOf(R.drawable.ic_profile, R.drawable.ic_settings, R.drawable.ic_logout)
        Dropdown.show(view, options, icons = icons, widthDp = 200, alignEnd = true) {
            when (it) {
                0 -> startActivity(Intent(this, EditProfileActivity::class.java))
                1 -> startActivity(Intent(this, SettingsActivity::class.java))
                else -> confirmLogout()
            }
        }
    }

    companion object {
        const val TAB_ACTIVE = 0
        const val TAB_COMPLETED = 1
        const val TAB_SAVED = 2

        private const val GROUP_ALL = 0
        private const val GROUP_BORROWING = 1
        private const val GROUP_LENT = 2
        private const val GROUP_RESERVED = 3
        private const val GROUP_MINE = 4
    }
}
