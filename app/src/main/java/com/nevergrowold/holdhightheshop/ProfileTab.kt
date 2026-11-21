package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updatePadding

/**
 * Profile tab page hosted inside [MainActivity].
 */
internal class ProfileTab(
    private val activity: MainActivity,
    val root: View,
) {
    private val header: View = root.findViewById(R.id.headerArea)
    private val headerBaseTop: Int = header.paddingTop

    private var tab = ProfileActivity.TAB_ACTIVE
    private var shownTab = -1
    private var group = GROUP_ALL
    private var renderedGroupIds: List<Int> = emptyList()
    private var renderedGroupChips: List<TextView> = emptyList()
    private var lastRenderSignature: Any? = null

    init {
        selectTab(tab)
    }

    fun applyTopInset(top: Int) {
        header.updatePadding(top = headerBaseTop + top)
    }

    fun openSubTab(subTab: Int, scrollDown: Boolean) {
        tab = subTab
        onShow()
        if (scrollDown) scrollToTabs()
    }

    fun onShow() {
        Motion.countUp(root.findViewById(R.id.profileRating), Reliability.rating(), delay = 60)
        Motion.countUp(root.findViewById(R.id.profileOnTime), Reliability.onTime(), delay = 110)
        Motion.countUp(root.findViewById(R.id.profileCompleted), Reliability.completed().toString(), delay = 160)
        selectTab(tab)
    }

    private fun scrollToTabs() {
        val scroll = root.findViewById<ScrollView>(R.id.profileScroll)
        val tabs = root.findViewById<View>(R.id.profileTabs)
        scroll.post { scroll.smoothScrollTo(0, tabs.top) }
    }

    /** Glides the green underline smoothly to the chosen tab and swaps its list directionally. */
    private fun selectTab(selected: Int, fromGroupFilter: Boolean = false) {
        tab = selected
        val tabsTrack = root.findViewById<ViewGroup>(R.id.profileTabs)
        val rows = listOf<View>(root.findViewById(R.id.tab1), root.findViewById(R.id.tab2), root.findViewById(R.id.tab3))
        val labels = listOf<TextView>(
            root.findViewById(R.id.tab1Label),
            root.findViewById(R.id.tab2Label),
            root.findViewById(R.id.tab3Label),
        )
        val bars = listOf<View>(root.findViewById(R.id.tab1Bar), root.findViewById(R.id.tab2Bar), root.findViewById(R.id.tab3Bar))

        val previous = shownTab
        val changed = previous >= 0 && selected != previous
        shownTab = selected

        rows.forEachIndexed { index, row ->
            row.setOnClickListener {
                if (tab == index) return@setOnClickListener
                Motion.tick(row)
                selectTab(index)
            }
            val isOn = index == selected
            Motion.textColor(labels[index], activity.getColor(if (isOn) R.color.uc_green else R.color.text_secondary), changed)
            if (!changed) {
                if (isOn) bars[index].setBackgroundResource(R.drawable.bg_tab_active)
                else bars[index].setBackgroundColor(Color.TRANSPARENT)
            }
        }
        if (changed) {
            Motion.slideIndicator(tabsTrack, bars[previous], bars[selected], R.drawable.bg_tab_active)
        }

        val saved = UserState.saved()
        val mine = Listings.myActive()
        val reserved = UserState.reservedListings()
        val borrowing = UserState.borrowing()
        val lending = UserState.lending()
        val completed = Listings.completed()
        Motion.rollText(root.findViewById(R.id.tab1Count), (borrowing.size + lending.size + reserved.size + mine.size).toString())
        Motion.rollText(root.findViewById(R.id.tab2Count), completed.size.toString())
        Motion.rollText(root.findViewById(R.id.tab3Count), saved.size.toString())

        val signature = listOf(
            selected,
            group,
            saved.map { it.id },
            mine.map { Triple(it.id, UserState.accepted(it.id)?.meetMillis, UserState.pending(it.id).size) },
            reserved.map { Pair(it.id, UserState.reservation(it.id)?.meetMillis) },
            borrowing.map { Triple(it.first.id, it.second.dueMillis, it.second.returnPending) },
            lending.map { Triple(it.first.id, it.second.dueMillis, it.second.returnPending) },
            completed.map { Triple(it.id, it.yourRating, UserState.issue(it.id)?.ref) },
        )
        if (!changed && !fromGroupFilter && signature == lastRenderSignature) return
        lastRenderSignature = signature

        root.findViewById<View>(R.id.activeFiltersScroll).isVisible = selected == ProfileActivity.TAB_ACTIVE
        val list = root.findViewById<LinearLayout>(R.id.profileList)
        list.removeAllViews()
        when (selected) {
            ProfileActivity.TAB_ACTIVE -> {
                bindGroupChips(
                    listOf(
                        GROUP_ALL to activity.getString(R.string.filter_all_format, borrowing.size + lending.size + reserved.size + mine.size),
                        GROUP_BORROWING to activity.getString(R.string.filter_borrowing_format, borrowing.size),
                        GROUP_LENT to activity.getString(R.string.filter_lent_format, lending.size),
                        GROUP_RESERVED to activity.getString(R.string.filter_reserved_format, reserved.size),
                        GROUP_MINE to activity.getString(R.string.filter_mine_format, mine.size),
                    ),
                    listOf(1, borrowing.size, lending.size, reserved.size, mine.size),
                )
                fun shows(g: Int) = group == GROUP_ALL || group == g
                if (shows(GROUP_BORROWING) && borrowing.isNotEmpty()) {
                    activity.addSection(list, R.string.section_borrowing, R.string.section_borrowing_hint)
                }
                if (shows(GROUP_BORROWING)) borrowing.forEachIndexed { i, (item, loan) ->
                    val meta = if (loan.returnPending) {
                        activity.getString(R.string.row_return_pending, loan.partner.name.substringBefore(' '))
                    } else {
                        activity.getString(
                            R.string.row_borrowed_meta,
                            loan.partner.name.substringBefore(' '),
                            Rentals.dateLabel(loan.dueMillis),
                            Rentals.countdown(activity, loan.dueMillis),
                        )
                    }
                    val row = activity.addRow(
                        list, item.title, Listings.priceLabel(activity, item), meta,
                        i < borrowing.size - 1, activity.getString(R.string.role_borrowing), giving = false,
                    ) { activity.openListingById(item.id) }
                    if (!loan.returnPending && Rentals.isOverdue(loan.dueMillis)) {
                        row.findViewById<TextView>(R.id.rowMeta).setTextColor(activity.getColor(R.color.danger))
                    }
                }
                if (shows(GROUP_LENT) && lending.isNotEmpty()) {
                    activity.addSection(list, R.string.section_lent, R.string.section_lent_hint)
                }
                if (shows(GROUP_LENT)) lending.forEachIndexed { i, (item, loan) ->
                    val meta = if (loan.returnPending) {
                        activity.getString(R.string.row_confirm_return, loan.partner.name.substringBefore(' '))
                    } else {
                        activity.getString(
                            R.string.row_lent_meta,
                            loan.partner.name.substringBefore(' '),
                            Rentals.dateLabel(loan.dueMillis),
                            Rentals.countdown(activity, loan.dueMillis),
                        )
                    }
                    val row = activity.addRow(
                        list, item.title, Listings.priceLabel(activity, item), meta,
                        i < lending.size - 1, activity.getString(R.string.role_lent_out), giving = true,
                    ) { activity.openListingById(item.id) }
                    val metaView = row.findViewById<TextView>(R.id.rowMeta)
                    when {
                        loan.returnPending -> metaView.setTextColor(activity.getColor(R.color.swap_text))
                        Rentals.isOverdue(loan.dueMillis) -> metaView.setTextColor(activity.getColor(R.color.danger))
                    }
                }
                if (shows(GROUP_RESERVED) && reserved.isNotEmpty()) {
                    activity.addSection(list, R.string.section_reserved, R.string.section_reserved_hint)
                }
                if (shows(GROUP_RESERVED)) reserved.forEachIndexed { i, item ->
                    val reservation = UserState.reservation(item.id)
                    val missed = Deals.meetupMissed(reservation?.meetMillis)
                    val meta = when {
                        missed -> activity.getString(R.string.row_meetup_missed, item.seller.first)
                        reservation?.meetMillis != null -> activity.getString(
                            R.string.row_reserved_when,
                            item.seller.name,
                            Rentals.whenLabel(activity, reservation.meetMillis),
                        )
                        else -> activity.getString(R.string.reserved_from_format, item.seller.name)
                    }
                    val row = activity.addRow(
                        list, item.title, Listings.priceLabel(activity, item), meta,
                        i < reserved.size - 1, Listings.role(activity, item), giving = false, swap = item.isSwap,
                    ) { activity.openListingById(item.id) }
                    if (missed) row.findViewById<TextView>(R.id.rowMeta).setTextColor(activity.getColor(R.color.swap_text))
                }
                if (shows(GROUP_MINE) && mine.isNotEmpty()) {
                    activity.addSection(list, R.string.section_mine, R.string.section_mine_hint)
                }
                if (shows(GROUP_MINE)) mine.forEachIndexed { i, item ->
                    val deal = UserState.accepted(item.id)
                    val pending = UserState.pending(item.id)
                    val needsYou = (deal != null && Deals.meetupMissed(deal.meetMillis)) || pending.isNotEmpty()
                    val meta = when {
                        deal != null && Deals.meetupMissed(deal.meetMillis) ->
                            activity.getString(R.string.row_meetup_missed, deal.from.first)
                        deal != null -> activity.getString(
                            when {
                                item.isSwap -> R.string.row_deal_swapping
                                item.isRent -> R.string.row_deal_lending
                                else -> R.string.row_deal_reserved
                            },
                            deal.from.first,
                            Rentals.whenLabel(activity, deal.meetMillis),
                        )
                        pending.isNotEmpty() -> activity.resources.getQuantityString(
                            R.plurals.row_requests,
                            pending.size,
                            pending.size,
                            Rentals.timeLeft(activity, pending.minOf { it.expiresMillis }),
                        )
                        else -> activity.getString(R.string.posted_days_format, item.postedDaysAgo + 2)
                    }
                    val row = activity.addRow(
                        list, item.title, Listings.priceLabel(activity, item), meta,
                        i < mine.size - 1, Listings.role(activity, item), giving = true, swap = item.isSwap,
                    ) { activity.openListingById(item.id) }
                    if (needsYou) row.findViewById<TextView>(R.id.rowMeta).setTextColor(activity.getColor(R.color.swap_text))
                }
            }
            ProfileActivity.TAB_COMPLETED -> completed.forEachIndexed { i, exchange ->
                activity.addRow(
                    list, exchange.title, Listings.price(exchange.price), ExchangeText.summary(activity, exchange),
                    i < completed.size - 1, ExchangeText.badge(activity, exchange), exchange.giving,
                    exchange.kind == Exchange.SWAPPED,
                ) {
                    activity.startActivity(
                        Intent(activity, ExchangeDetailActivity::class.java)
                            .putExtra(ExchangeDetailActivity.EXTRA_EXCHANGE, exchange.id)
                    )
                }
            }
            ProfileActivity.TAB_SAVED -> saved.forEachIndexed { i, item ->
                activity.addRow(
                    list, item.title, Listings.priceLabel(activity, item),
                    activity.getString(R.string.saved_meta_format, item.program, item.seller.name),
                    i < saved.size - 1,
                ) { activity.openListingById(item.id) }
            }
        }
        root.findViewById<View>(R.id.profileEmpty).isVisible = selected == ProfileActivity.TAB_SAVED && saved.isEmpty()

        val items = (0 until list.childCount).map { list.getChildAt(it) }
        when {
            changed -> Motion.directionalSwap(items, direction = if (selected > previous) 1 else -1)
            fromGroupFilter -> Motion.staggerIn(items, riseDp = 8f, gapMs = 18L, subtleScale = false)
        }
    }

    private fun bindGroupChips(groups: List<Pair<Int, String>>, counts: List<Int>) {
        val box = root.findViewById<LinearLayout>(R.id.activeFilters)
        val shown = groups.filterIndexed { i, _ -> counts[i] > 0 }
        if (shown.none { it.first == group }) group = GROUP_ALL

        val ids = shown.map { it.first }
        if (ids != renderedGroupIds || renderedGroupChips.size != shown.size) {
            box.removeAllViews()
            renderedGroupIds = ids
            renderedGroupChips = shown.map { (_, label) ->
                (LayoutInflater.from(activity).inflate(R.layout.item_chip, box, false) as TextView).also {
                    it.text = label
                    box.addView(it)
                }
            }
        } else {
            shown.forEachIndexed { i, (_, label) -> renderedGroupChips[i].text = label }
        }

        val activeIndex = shown.indexOfFirst { it.first == group }
        Choice.chips(renderedGroupChips, activeIndex) {
            group = shown[it].first
            selectTab(ProfileActivity.TAB_ACTIVE, fromGroupFilter = true)
        }
    }

    fun openMenu(view: View) {
        val options = listOf(
            activity.getString(R.string.menu_edit_profile),
            activity.getString(R.string.menu_settings),
            activity.getString(R.string.menu_logout),
        )
        val icons = listOf(R.drawable.ic_profile, R.drawable.ic_settings, R.drawable.ic_logout)
        Dropdown.show(view, options, icons = icons, widthDp = 200, alignEnd = true) {
            when (it) {
                0 -> activity.startActivity(Intent(activity, EditProfileActivity::class.java))
                1 -> activity.startActivity(Intent(activity, SettingsActivity::class.java))
                2 -> activity.confirmLogout()
            }
        }
    }

    companion object {
        private const val GROUP_ALL = 0
        private const val GROUP_BORROWING = 1
        private const val GROUP_LENT = 2
        private const val GROUP_RESERVED = 3
        private const val GROUP_MINE = 4
    }
}
