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
 * The bottom navigation appears on several layouts, so the handlers for it
 * live here instead of being copied into each Activity. Tab switches use
 * CLEAR_TOP so moving between Home, Market, Chats and Profile reuses the
 * screen that is already open rather than stacking another copy of it.
 */
open class BaseActivity : AppCompatActivity() {

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
        growTabIndicator()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        growTabIndicator()
    }

    /** On tab screens, the green pill behind the current tab grows in as the screen arrives. */
    private fun growTabIndicator() {
        findViewById<View>(R.id.navIndicator)?.let { Motion.grow(it, delay = 60) }
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

    /**
     * Opens a listing from its card with a container transform: the card
     * itself grows into the listing screen, and Back shrinks it back into the
     * card (Android's counterpart of a navigation zoom). With animations off,
     * it opens like any other screen.
     */
    fun openListingFromCard(card: View, id: String) {
        if (!Motion.enabled(this)) return openListingById(id)
        // Only one view may carry the shared name at a time.
        window.decorView.findViewWithTag<View>(LISTING_ZOOM)?.let { it.transitionName = null; it.tag = null }
        card.transitionName = LISTING_ZOOM
        card.tag = LISTING_ZOOM
        zoomedListing = id
        setExitSharedElementCallback(com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback())
        window.sharedElementsUseOverlay = false
        val options = android.app.ActivityOptions.makeSceneTransitionAnimation(this, card, LISTING_ZOOM)
        startActivity(
            Intent(this, ListingDetailActivity::class.java)
                .putExtra(EXTRA_LISTING, id)
                .putExtra(EXTRA_ZOOM, true),
            options.toBundle()
        )
    }

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

    /** Closes the current screen, reversing a shared-element entrance when there was one. */
    fun goBack(view: View) {
        supportFinishAfterTransition()
    }

    protected fun switchTab(target: Class<*>, extras: Intent.() -> Unit = {}) {
        val intent = Intent(this, target)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        intent.extras()
        if (javaClass == target && intent.extras == null) return
        startActivity(intent)
        Motion.tabSwitch(this)
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
        // The card that opened the listing keeps its name, so going back can shrink into it again.
        if (listing.id == zoomedListing) {
            card.transitionName = LISTING_ZOOM
            card.tag = LISTING_ZOOM
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
                        android.widget.Toast.makeText(this@BaseActivity, R.string.toast_select_reason, android.widget.Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    hideKeyboard(input)
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
        const val EXTRA_ZOOM = "zoom"

        /** Shared-element name for the card → listing container transform. */
        const val LISTING_ZOOM = "listing_zoom"

        /** Listing last opened from a card, so a rebuilt list can name the card again for the return. */
        var zoomedListing: String? = null
    }
}
