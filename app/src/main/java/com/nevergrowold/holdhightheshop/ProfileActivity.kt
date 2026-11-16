package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible

class ProfileActivity : BaseActivity() {

    private var tab = TAB_ACTIVE

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
        // Saved items can change on other screens, so redraw on return.
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

        rows.forEachIndexed { index, row ->
            row.setOnClickListener { selectTab(index) }
            val isOn = index == selected
            labels[index].setTextColor(getColor(if (isOn) R.color.uc_green else R.color.text_secondary))
            if (isOn) {
                bars[index].setBackgroundResource(R.drawable.bg_tab_active)
            } else {
                bars[index].setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        }

        val saved = UserState.saved()
        val mine = Listings.myActive()
        val reserved = UserState.reservedListings()
        val completed = Listings.completed()
        findViewById<TextView>(R.id.tab1Count).text = (mine.size + reserved.size).toString()
        findViewById<TextView>(R.id.tab2Count).text = completed.size.toString()
        findViewById<TextView>(R.id.tab3Count).text = saved.size.toString()

        val list = findViewById<LinearLayout>(R.id.profileList)
        list.removeAllViews()
        when (selected) {
            // Active: what you give out, then what you get from others.
            TAB_ACTIVE -> {
                if (mine.isNotEmpty()) addSection(list, R.string.section_mine, R.string.section_mine_hint)
                mine.forEachIndexed { i, item ->
                    addRow(
                        list, item.title, Listings.priceLabel(this, item),
                        getString(R.string.posted_days_format, item.postedDaysAgo + 2),
                        i < mine.size - 1, Listings.role(this, item), giving = true, swap = item.isSwap
                    ) { openListingById(item.id) }
                }
                if (reserved.isNotEmpty()) addSection(list, R.string.section_reserved, R.string.section_reserved_hint)
                reserved.forEachIndexed { i, item ->
                    addRow(
                        list, item.title, Listings.priceLabel(this, item),
                        getString(R.string.reserved_from_format, item.seller.name),
                        i < reserved.size - 1, Listings.role(this, item), giving = false, swap = item.isSwap
                    ) { openListingById(item.id) }
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
    }
}
