package com.nevergrowold.holdhightheshop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nevergrowold.holdhightheshop.data.AppRepository
import com.nevergrowold.holdhightheshop.system.MarketplaceSyncService
import kotlinx.coroutines.launch

/**
 * Shared behaviour for every screen in Hold High The Shop:
 * - Touch targets & expressive press feedback
 * - Live suspension gate (protects authenticated screens if Admin suspends the user)
 * - Clean full logout (wipes session, cancels alarms/notifications, stops sync service)
 * - Upgraded Safe Meetup Point bottom sheet (replaces placeholder map screen)
 * - Per-listing deterministic chat routing & college-tag product cards
 */
open class BaseActivity : AppCompatActivity() {

    /** Override to false on auth/onboarding screens (`LandingActivity`, `LoginActivity`, etc.). */
    protected open val requiresVerifiedAuth: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UserState.ensureLoaded(this)
    }

    override fun onResume() {
        super.onResume()
        if (requiresVerifiedAuth && UserState.isSignedIn && UserState.isSuspended) {
            val intent = Intent(this, AccountSuspendedActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        }
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        val content = findViewById<View>(android.R.id.content)
        content.post {
            if (!isFinishing && !isDestroyed) {
                Motion.installPress(content)
                TouchTargets.install(content)
            }
        }
    }

    open fun openHome(view: View) = switchTab(MainActivity.TAB_HOME)

    open fun openMarket(view: View) = switchTab(MainActivity.TAB_MARKET)

    open fun openChats(view: View) = switchTab(MainActivity.TAB_CHATS)

    open fun openProfile(view: View) = switchTab(MainActivity.TAB_PROFILE)

    /** Opens Campus Market pre-filtered to the college code (or `Listings.FILTER_ALL_COLLEGES`) in the view's tag. */
    open fun openProgram(view: View) {
        switchTab(MainActivity.TAB_MARKET) {
            putExtra(EXTRA_PROGRAM, view.tag as? String)
        }
    }

    fun openCreateListing(view: View) {
        Motion.tick(view)
        startActivity(Intent(this, CreateListingActivity::class.java))
    }

    fun openListing(view: View) = openListingById(view.tag as? String)

    fun openListingById(id: String?) {
        startActivity(
            Intent(this, ListingDetailActivity::class.java)
                .putExtra(EXTRA_LISTING, id ?: Listings.DEFAULT_ID),
        )
    }

    fun openListingFromCard(card: View, id: String) {
        Motion.tick(card)
        openListingById(id)
    }

    fun openSaved(view: View) {
        Motion.tick(view)
        startActivity(Intent(this, SavedActivity::class.java))
    }

    fun openSeller(seller: Seller) {
        startActivity(
            Intent(this, SellerProfileActivity::class.java)
                .putExtra(EXTRA_SELLER, seller.name)
                .putExtra(EXTRA_SELLER_UID, seller.uid),
        )
    }

    /** Opens (or creates) the deterministic per-listing conversation thread with [seller]. */
    fun openChatWith(seller: Seller, listingId: String? = null, justReserved: Boolean = false) {
        val threadId = if (!listingId.isNullOrBlank() && seller.uid.isNotBlank() && UserState.isSignedIn) {
            UserState.threadFor(listingId, seller.uid).threadId
        } else {
            null
        }
        startActivity(
            Intent(this, ChatThreadActivity::class.java)
                .putExtra(EXTRA_SELLER, seller.name)
                .putExtra(EXTRA_SELLER_UID, seller.uid)
                .putExtra(EXTRA_LISTING, listingId)
                .putExtra(ChatThreadActivity.EXTRA_THREAD_ID, threadId)
                .putExtra(EXTRA_RESERVED, justReserved),
        )
    }

    fun openChatThread(threadId: String, listingId: String? = null) {
        startActivity(
            Intent(this, ChatThreadActivity::class.java)
                .putExtra(ChatThreadActivity.EXTRA_THREAD_ID, threadId)
                .putExtra(EXTRA_LISTING, listingId),
        )
    }

    /**
     * Upgraded Safe Meetup Point Bottom Sheet replacing the placeholder campus map.
     * Shows UC landmark details, optional indoor floor/room note from the seller,
     * one-tap clipboard copy, and one-tap UC Campus Security Desk dialer.
     */
    fun showSafeMeetupSheet(place: String, meetupNote: String = "") {
        val spot = place.ifBlank { Listings.MEETUP_POINTS.first() }
        val landmark = Listings.MEETUP_LANDMARKS[spot]
            ?: getString(R.string.meetup_landmark_fallback)
        Sheets.show(this, R.layout.sheet_meetup) { sheet, dialog ->
            sheet.findViewById<TextView>(R.id.meetupSheetPlace).text = spot
            sheet.findViewById<TextView>(R.id.meetupSheetLandmark).text = landmark
            val noteBox = sheet.findViewById<View>(R.id.meetupNoteBox)
            val noteText = sheet.findViewById<TextView>(R.id.meetupSheetNote)
            if (meetupNote.isNotBlank()) {
                noteBox.isVisible = true
                noteText.text = meetupNote
            } else {
                noteBox.isVisible = false
            }
            sheet.findViewById<View>(R.id.meetupCopySpot).setOnClickListener {
                Motion.tick(it)
                val fullText = if (meetupNote.isNotBlank()) {
                    "$spot — $meetupNote (University of the Cordilleras)"
                } else {
                    "$spot (University of the Cordilleras)"
                }
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("UC Safe Meetup Spot", fullText))
                Toast.makeText(this, R.string.toast_meetup_copied, Toast.LENGTH_SHORT).show()
            }
            sheet.findViewById<View>(R.id.meetupCallSecurity).setOnClickListener {
                Motion.tick(it)
                AppIntents.launch(this, AppIntents.dialSecurityIntent(), unavailableToastRes = R.string.toast_no_phone_app)
            }
            sheet.findViewById<View>(R.id.meetupDone).setOnClickListener { dialog.dismiss() }
        }
    }

    /** Clean full logout: clears encrypted session, Firestore listeners, alarms, notifications, and sync service. */
    fun confirmLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logout_title)
            .setMessage(R.string.logout_message)
            .setNegativeButton(R.string.logout_cancel, null)
            .setPositiveButton(R.string.logout_confirm) { _, _ ->
                lifecycleScope.launch {
                    MarketplaceSyncService.stop(this@BaseActivity)
                    AppRepository.signOut(this@BaseActivity)
                    Toast.makeText(this@BaseActivity, R.string.toast_logged_out, Toast.LENGTH_SHORT).show()
                    val intent = Intent(this@BaseActivity, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    finish()
                }
            }
            .show()
    }

    open fun goBack(view: View) {
        Motion.tick(view)
        finish()
    }

    protected fun switchTab(tabIndex: Int, extras: Intent.() -> Unit = {}) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_NAV_TAB, tabIndex)
        intent.extras()
        startActivity(intent)
        if (this !is MainActivity) finish()
    }

    /** A product card (cover photo, condition, price/FREE, title, college tags) that opens the listing. */
    internal fun listingCard(parent: ViewGroup, listing: Listing): View {
        val card = LayoutInflater.from(this).inflate(R.layout.item_listing_card, parent, false)
        card.findViewById<TextView>(R.id.cardPrice).text = Listings.priceLabel(this, listing)
        card.findViewById<TextView>(R.id.cardTitle).text = listing.title
        card.findViewById<TextView>(R.id.cardProgram).text = Listings.targetProgramsLabel(this, listing)
        card.findViewById<TextView>(R.id.cardCondition).text = Listings.conditionLabel(this, listing.condition)
        val photoView = card.findViewById<ImageView>(R.id.cardPhoto)
        val photoIcon = card.findViewById<View>(R.id.cardPhotoIcon)
        val cover = listing.photoUris.firstOrNull()
        if (photoView != null) {
            AppIntents.bindImageUri(photoView, photoIcon, cover)
        }
        val type = card.findViewById<TextView>(R.id.cardType)
        when {
            listing.isSwap -> styleBadge(type, getString(R.string.tag_for_swap), giving = false, swap = true)
            listing.isRent -> styleBadge(type, getString(R.string.tag_for_rent), giving = true)
            else -> styleBadge(type, getString(R.string.tag_for_sale), giving = false)
        }
        card.setOnClickListener { openListingFromCard(card, listing.id) }
        return card
    }

    internal fun addRow(
        list: LinearLayout,
        title: String,
        price: CharSequence,
        meta: String,
        showDivider: Boolean,
        badge: String? = null,
        giving: Boolean = false,
        swap: Boolean = false,
        onClick: (() -> Unit)?,
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

    internal fun styleBadge(view: TextView, label: String, giving: Boolean, swap: Boolean = false) {
        view.text = label
        view.setBackgroundResource(
            when {
                swap -> R.drawable.bg_badge_swap
                giving -> R.drawable.bg_badge_outline
                else -> R.drawable.bg_badge_mint
            },
        )
        view.setTextColor(getColor(if (swap) R.color.swap_text else R.color.uc_green))
        view.visibility = View.VISIBLE
    }

    protected fun reportProblem(
        subject: String,
        itemTitle: String,
        partner: Seller?,
        reasons: List<Int>,
        onReported: (Issue) -> Unit = {},
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
                        Toast.makeText(this@BaseActivity, R.string.toast_select_reason, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    hideKeyboard(input)
                    Motion.confirm(this)
                    val issue = UserState.report(
                        subject = subject,
                        reason = reasons[picked],
                        partner = partner,
                        details = input.text.toString().trim(),
                        isListingReport = false,
                    )
                    dialog.dismiss()
                    onReported(issue)
                    notify(getString(R.string.toast_problem_reported, issue.ref))
                }
            }
        }
    }

    internal fun addSection(list: LinearLayout, title: Int, hint: Int) {
        val header = LayoutInflater.from(this).inflate(R.layout.item_section_header, list, false)
        header.findViewById<TextView>(R.id.sectionTitle).setText(title)
        header.findViewById<TextView>(R.id.sectionHint).setText(hint)
        list.addView(header)
    }

    companion object {
        const val EXTRA_LISTING = "listing"
        const val EXTRA_PROGRAM = "program"
        const val EXTRA_SELLER = "seller"
        const val EXTRA_SELLER_UID = "seller_uid"
        const val EXTRA_RESERVED = "reserved"
        const val EXTRA_TAB = "tab"
        const val EXTRA_FROM_TAB = "from_tab"
        const val EXTRA_SEARCH_QUERY = "search_query"
        const val EXTRA_EMAIL = "email"
        const val EXTRA_PENDING_DEEP_LINK = "pending_deep_link"
    }
}
