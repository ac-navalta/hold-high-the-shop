package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Shared behaviour for every screen in the app.
 *
 * The bottom navigation appears on the four tab screens, so the handlers live
 * here. Peer tab switches use `REORDER_TO_FRONT` so once visited, Home, Market,
 * Chats and Profile stay warm in memory for instant, flash-free handoffs.
 */
open class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.hasExtra(EXTRA_FROM_TAB)) Motion.noOpenTransition(this)
    }

    /**
     * Every screen gets the shared press feedback ([Motion.installPress]) and
     * 48dp touch areas for its small controls ([TouchTargets]).
     */
    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        val content = findViewById<View>(android.R.id.content)
        Motion.installPress(content)
        TouchTargets.install(content)
    }

    override fun onPostCreate(savedInstanceState: android.os.Bundle?) {
        super.onPostCreate(savedInstanceState)
        val tabIndex = currentTab()
        if (tabIndex >= 0) {
            onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (tabIndex > 0) switchTab(MainActivity::class.java)
                    else finishAffinity()
                }
            })
        }
        arriveOnTab(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra(EXTRA_FROM_TAB)) Motion.noOpenTransition(this)
        arriveOnTab(intent)
    }

    /**
     * On tab screens: arriving from another tab glides the translucent pill
     * briskly behind the icons while keeping the header and bottom bar rock-solid.
     */
    private fun arriveOnTab(intent: Intent?) {
        val pill = findViewById<View>(R.id.navIndicator) ?: return
        val from = intent?.getIntExtra(EXTRA_FROM_TAB, -1) ?: -1
        intent?.removeExtra(EXTRA_FROM_TAB)
        val to = currentTab()
        if (from < 0 || from == to) Motion.settleIndicator(pill) else Motion.navSlide(this, from, to)
    }

    /** Index of this screen's tab in the bar (Home 0 … Profile 3), or -1 off the tab screens. */
    private fun currentTab(): Int {
        val nav = findViewById<ViewGroup>(R.id.bottomNav) ?: return -1
        val pill = findViewById<View>(R.id.navIndicator) ?: return -1
        var cur: View? = pill
        while (cur != null && cur.parent !== nav) cur = cur.parent as? View
        return (0 until nav.childCount).firstOrNull { nav.getChildAt(it) === cur } ?: -1
    }

    fun openHome(view: View) = switchTab(MainActivity::class.java)

    fun openMarket(view: View) = switchTab(MarketActivity::class.java)

    fun openChats(view: View) = switchTab(ChatsActivity::class.java)

    fun openProfile(view: View) = switchTab(ProfileActivity::class.java)

    /** Opens the Campus Market already filtered to the program in the view's tag. */
    fun openProgram(view: View) {
        switchTab(MarketActivity::class.java) {
            putExtra(EXTRA_PROGRAM, view.tag as? String)
        }
    }

    /** Opens the Create listing form. */
    fun openCreateListing(view: View) {
        startActivity(Intent(this, CreateListingActivity::class.java))
    }

    /**
     * Opens the listing detail screen. The tapped view's tag holds the
     * listing id (item1, item2, ...); views without one open the default.
     */
    fun openListing(view: View) = openListingById(view.tag as? String)

    fun openListingById(id: String?) {
        startActivity(
            Intent(this, ListingDetailActivity::class.java).putExtra(EXTRA_LISTING, id ?: Listings.DEFAULT_ID)
        )
    }

    /** Opens a listing from its card using the app's sleek Shared-Axis transition. */
    fun openListingFromCard(card: View, id: String) = openListingById(id)

    /** Opens the saved items list. */
    fun openSaved(view: View) {
        startActivity(Intent(this, SavedActivity::class.java))
    }

    fun openSeller(seller: Seller) {
        startActivity(Intent(this, SellerProfileActivity::class.java).putExtra(EXTRA_SELLER, seller.name))
    }

    /** Opens the conversation with a seller, optionally about a listing. */
    fun openChatWith(seller: Seller, listingId: String? = null, justReserved: Boolean = false) {
        startActivity(
            Intent(this, ChatThreadActivity::class.java)
                .putExtra(EXTRA_SELLER, seller.name)
                .putExtra(EXTRA_LISTING, listingId)
                .putExtra(EXTRA_RESERVED, justReserved)
        )
    }

    /** Asks before signing out, then returns to the login screen. */
    fun confirmLogout() {
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

    /** Closes the current screen (the back arrow). */
    open fun goBack(view: View) {
        finish()
    }

    protected fun switchTab(target: Class<*>, extras: Intent.() -> Unit = {}) {
        val intent = Intent(this, target)
        intent.extras()
        if (javaClass == target && intent.extras == null) return
        val fromTab = currentTab()
        if (fromTab >= 0) {
            intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION
            intent.putExtra(EXTRA_FROM_TAB, fromTab)
            Motion.tick(window.decorView)
            startActivity(intent)
            Motion.noPendingTransition(this)
        } else {
            intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            startActivity(intent)
            finish()
        }
    }

    /** A product card (photo, condition, price, title, program) that opens the listing. */
    protected fun listingCard(parent: ViewGroup, listing: Listing): View {
        val card = LayoutInflater.from(this).inflate(R.layout.item_listing_card, parent, false)
        card.findViewById<TextView>(R.id.cardPrice).text = Listings.priceLabel(this, listing)
        card.findViewById<TextView>(R.id.cardTitle).text = listing.title
        card.findViewById<TextView>(R.id.cardProgram).text = listing.program
        card.findViewById<TextView>(R.id.cardCondition).text = Listings.conditionLabel(this, listing.condition)
        // Every card says how it is offered: FOR SALE (mint), FOR RENT (outline), FOR SWAP (amber).
        val type = card.findViewById<TextView>(R.id.cardType)
        when {
            listing.isSwap -> styleBadge(type, getString(R.string.tag_for_swap), giving = false, swap = true)
            listing.isRent -> styleBadge(type, getString(R.string.tag_for_rent), giving = true)
            else -> styleBadge(type, getString(R.string.tag_for_sale), giving = false)
        }
        card.setOnClickListener { openListingFromCard(card, listing.id) }
        return card
    }

    /**
     * Adds a listing row (thumbnail, title, price, status) to a list.
     * Returns the row so callers can change its trailing icon.
     */
    protected fun addRow(
        list: LinearLayout,
        title: String,
        price: CharSequence,
        meta: String,
        showDivider: Boolean,
        badge: String? = null,
        giving: Boolean = false,
        swap: Boolean = false,
        onClick: (() -> Unit)?
    ): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_listing_row, list, false)
        row.findViewById<TextView>(R.id.rowTitle).text = title
        row.findViewById<TextView>(R.id.rowPrice).text = price
        row.findViewById<TextView>(R.id.rowMeta).text = meta
        row.findViewById<View>(R.id.rowDivider).visibility = if (showDivider) View.VISIBLE else View.GONE
        if (badge != null) styleBadge(row.findViewById(R.id.rowBadge), badge, giving, swap)
        val body = row.findViewById<View>(R.id.rowBody)
        if (onClick != null) {
            body.setOnClickListener { onClick() }
        } else {
            body.background = null
            row.findViewById<View>(R.id.rowAction).visibility = View.GONE
        }
        list.addView(row, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return row
    }

    /**
     * Role tag such as LENDING or BORROWING. Giving (your own things) is
     * outlined; getting (things from others) is filled mint; swaps, where
     * both sides give and get, are amber.
     */
    protected fun styleBadge(view: TextView, label: String, giving: Boolean, swap: Boolean = false) {
        view.text = label
        view.setBackgroundResource(
            when {
                swap -> R.drawable.bg_badge_swap
                giving -> R.drawable.bg_badge_outline
                else -> R.drawable.bg_badge_mint
            }
        )
        view.setTextColor(getColor(if (swap) R.color.swap_text else R.color.uc_green))
        view.visibility = View.VISIBLE
    }

    /**
     * "Report a problem": pick a reason, add details if you like, and it goes
     * to student support as an issue with a reference number. Used for
     * finished exchanges and for overdue loans.
     *
     * @param subject the exchange or listing id the problem is about.
     */
    protected fun reportProblem(
        subject: String,
        itemTitle: String,
        partner: Seller?,
        reasons: List<Int>,
        onReported: (Issue) -> Unit = {}
    ) {
        var picked = -1
        Sheets.show(this, R.layout.sheet_choose) { sheet, dialog ->
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            sheet.findViewById<TextView>(R.id.chooseKicker).setText(R.string.problem_kicker)
            sheet.findViewById<TextView>(R.id.chooseTitle).text = itemTitle
            val box = sheet.findViewById<LinearLayout>(R.id.chooseRows)
            val rows = reasons.map { reason ->
                LayoutInflater.from(this).inflate(R.layout.item_swap_option, box, false).also {
                    it.findViewById<TextView>(R.id.optionTitle).setText(reason)
                    it.findViewById<View>(R.id.optionMeta).visibility = View.GONE
                    box.addView(it)
                }
            }
            Choice.rows(rows, rows.map { it.findViewById(R.id.optionRadio) }, picked) { picked = it }
            val input = sheet.findViewById<android.widget.EditText>(R.id.chooseInput)
            input.visibility = View.VISIBLE
            input.setHint(R.string.problem_details_hint)
            sheet.findViewById<TextView>(R.id.chooseNote).apply {
                visibility = View.VISIBLE
                text = getString(R.string.problem_note, partner?.first ?: getString(R.string.problem_note_nobody))
            }
            sheet.findViewById<TextView>(R.id.choosePrimary).apply {
                setText(R.string.btn_send_report)
                setOnClickListener {
                    if (picked < 0) {
                        Motion.shake(box)
                        android.widget.Toast.makeText(this@BaseActivity, R.string.toast_select_reason, android.widget.Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    hideKeyboard(input)
                    Motion.confirm(this)
                    val issue = UserState.report(subject, reasons[picked], partner, input.text.toString().trim())
                    dialog.dismiss()
                    onReported(issue)
                    notify(getString(R.string.toast_problem_reported, issue.ref))
                }
            }
        }
    }

    /** Adds a heading with a one-line hint to a list. */
    protected fun addSection(list: LinearLayout, title: Int, hint: Int) {
        val header = LayoutInflater.from(this).inflate(R.layout.item_section_header, list, false)
        header.findViewById<TextView>(R.id.sectionTitle).setText(title)
        header.findViewById<TextView>(R.id.sectionHint).setText(hint)
        list.addView(header)
    }

    companion object {
        const val EXTRA_LISTING = "listing"
        const val EXTRA_PROGRAM = "program"
        const val EXTRA_SELLER = "seller"
        const val EXTRA_RESERVED = "reserved"
        const val EXTRA_TAB = "tab"
        const val EXTRA_FROM_TAB = "from_tab"

    }
}
