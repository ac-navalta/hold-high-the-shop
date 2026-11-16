package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder

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
        val completed = closedExchanges() + Listings.COMPLETED
        findViewById<TextView>(R.id.tab1Count).text = mine.size.toString()
        findViewById<TextView>(R.id.tab2Count).text = completed.size.toString()
        findViewById<TextView>(R.id.tab3Count).text = saved.size.toString()

        val list = findViewById<LinearLayout>(R.id.profileList)
        list.removeAllViews()
        when (selected) {
            TAB_ACTIVE -> mine.forEachIndexed { i, item ->
                addRow(
                    list, item.title, Listings.price(item.price),
                    getString(R.string.posted_days_format, item.postedDaysAgo + 2),
                    i < mine.size - 1
                ) { openListingById(item.id) }
            }
            TAB_COMPLETED -> completed.forEachIndexed { i, exchange ->
                addRow(
                    list, exchange.title, Listings.price(exchange.price), exchange.summary,
                    i < completed.size - 1, null
                )
            }
            else -> saved.forEachIndexed { i, item ->
                addRow(
                    list, item.title, Listings.price(item.price),
                    getString(R.string.saved_meta_format, item.program, item.seller.name),
                    i < saved.size - 1
                ) { openListingById(item.id) }
            }
        }
        findViewById<View>(R.id.profileEmpty).isVisible = selected == TAB_SAVED && saved.isEmpty()
    }

    /** Own listings marked as done this session, newest first. */
    private fun closedExchanges() = Listings.ALL.filter { Listings.isMine(it) && UserState.isClosed(it.id) }
        .map { Exchange(it.title, it.price, getString(R.string.closed_summary)) }

    /** The "..." button in the header. */
    fun openMenu(view: View) {
        val options = listOf(
            getString(R.string.menu_saved),
            getString(R.string.menu_notifications),
            getString(R.string.menu_logout)
        )
        val icons = listOf(R.drawable.ic_bookmark, R.drawable.ic_bell, R.drawable.ic_logout)
        Dropdown.show(view, options, icons = icons, widthDp = 210, alignEnd = true) {
            when (it) {
                0 -> openSaved(view)
                1 -> startActivity(Intent(this, NotificationsActivity::class.java))
                else -> confirmLogout()
            }
        }
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logout_title)
            .setMessage(R.string.logout_message)
            .setNegativeButton(R.string.logout_cancel, null)
            .setPositiveButton(R.string.logout_confirm) { _, _ ->
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
            }
            .show()
    }

    companion object {
        const val TAB_ACTIVE = 0
        const val TAB_COMPLETED = 1
        const val TAB_SAVED = 2
    }
}
