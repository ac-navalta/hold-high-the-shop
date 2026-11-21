package com.nevergrowold.holdhightheshop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.CompositeDateValidator
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class ListingDetailActivity : BaseActivity() {

    private lateinit var listing: Listing

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_listing_detail)
        // Opened from a card: grow out of it, and shrink back into it on Back.
        zoom = ZoomTransition.enter(this)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack(window.decorView)
        })
        BarInsets.apply(this)

        listing = Listings.byId(intent.getStringExtra(EXTRA_LISTING))
        bind()

        // Subtle scroll-linked parallax on the photo hero beneath the overlapping details sheet.
        val scroll = findViewById<android.widget.ScrollView>(R.id.detailScroll)
        val hero = findViewById<View>(R.id.detailHero)
        val heroIcon = findViewById<View>(R.id.detailHeroIcon)
        val fadeSpan = 260f * resources.displayMetrics.density
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            if (!Motion.enabled(this)) return@setOnScrollChangeListener
            hero.translationY = y * 0.36f
            val t = (y / fadeSpan).coerceIn(0f, 1f)
            heroIcon.alpha = 1f - 0.55f * t
        }
    }

    override fun onDestroy() {
        zoom?.dispose()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Saved or reserved state can change on other screens.
        bindSaved()
        bindActions(animate = false)
    }

    private val isMine get() = Listings.isMine(listing)

    private fun bind() {
        val seller = listing.seller
        findViewById<TextView>(R.id.detailCondition).text =
            getString(R.string.condition_badge_format, Listings.conditionLabel(this, listing.condition))
        findViewById<TextView>(R.id.detailTitle).text = listing.title
        findViewById<TextView>(R.id.detailPrice).text = Listings.priceLabel(this, listing)
        findViewById<TextView>(R.id.detailDesc).text = listing.description
        findViewById<View>(R.id.swapWantsCard).isVisible = listing.isSwap && listing.wants != null
        findViewById<TextView>(R.id.swapWants).text = listing.wants
        findViewById<TextView>(R.id.sellerInitials).text = seller.initials
        findViewById<TextView>(R.id.sellerName).text =
            if (isMine) getString(R.string.seller_you_format, seller.name) else seller.name
        // Your own listing: nothing to save or report.
        findViewById<View>(R.id.btnSave).isVisible = !isMine
        findViewById<TextView>(R.id.sellerMeta).text = getString(R.string.seller_meta_format, seller.program, seller.year)
        // Your own reliability is worked out from your history; other students' comes with their profile.
        findViewById<TextView>(R.id.statRating).text = if (isMine) Reliability.rating() else seller.rating
        findViewById<TextView>(R.id.statOnTime).text = if (isMine) Reliability.onTime() else seller.onTime
        findViewById<TextView>(R.id.statCompleted).text = (if (isMine) Reliability.completed() else seller.completed).toString()
        findViewById<TextView>(R.id.meetupPlace).text = listing.meetup
        findViewById<TextView>(R.id.meetupAvailability).text = freeLabel()
    }

    /** "Paolo is free Mon, Wed, Fri • 1:00–4:00 PM", or "You're free …" on your own listing. */
    private fun freeLabel(): String = if (isMine) {
        getString(R.string.you_free_format, listing.availability.label())
    } else {
        getString(R.string.seller_free_format, listing.seller.name.substringBefore(' '), listing.availability.label())
    }

    /** Set when this screen grew out of a card. */
    private var zoom: ZoomTransition.Zoom? = null

    override fun goBack(view: View) {
        zoom?.close() ?: finish()
    }

    private fun bindSaved() {
        val saved = UserState.isSaved(listing.id)
        findViewById<ImageView>(R.id.saveIcon).apply {
            setImageResource(if (saved) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark)
            setColorFilter(getColor(if (saved) R.color.uc_green else R.color.text_primary))
            contentDescription = getString(if (saved) R.string.cd_unsave else R.string.cd_save)
        }
    }

    /**
     * Status card and bottom bar. One set per state, checked in this order:
     * - on loan (either way): due date, Message and the return actions;
     * - your listing promised to someone: who and when, Cancel / Message / Enter code;
     * - your listing with requests waiting: Edit / Review requests;
     * - your listing, nothing going on: Edit / Mark as sold (or Lend it out);
     * - reserved by you: meetup, your handover code, Message / Cancel;
     * - offered to you in a swap you accepted: Message;
     * - bought or swapped for: done, Message;
     * - anyone else's open listing: Report / Reserve.
     */
    private fun bindActions(animate: Boolean = true) {
        if (animate) {
            Motion.layoutTransition(findViewById(R.id.detailSheet), durationMs = 220L)
            Motion.layoutTransition(findViewById(R.id.bottomNav), durationMs = 200L)
        }
        val closed = UserState.isClosed(listing.id)
        val rental = UserState.rental(listing.id)
        val reservation = UserState.reservation(listing.id)
        val onLoan = rental != null
        val open = isMine && !closed && !onLoan
        val deal = if (open) UserState.accepted(listing.id) else null
        val pending = if (open && deal == null) UserState.pending(listing.id) else emptyList()
        val incoming = if (!isMine && !onLoan && reservation == null) UserState.incomingOffer(listing.id) else null
        // Bought or swapped for: finished, no more actions besides messaging.
        val done = !isMine && !onLoan && reservation == null && incoming == null && UserState.hasReceived(listing.id)
        val dealMissed = deal != null && Deals.meetupMissed(deal.meetMillis)
        val reservationMissed = reservation != null && Deals.meetupMissed(reservation.meetMillis)
        val loanIssue = UserState.issue(listing.id)

        // Returns are the lender's call: the borrower can only report it, then waits.
        val lenderToConfirm = rental?.lending == true && rental.returnPending
        val borrowerWaiting = rental?.lending == false && rental.returnPending
        val lentOverdue = rental?.lending == true && !rental.returnPending && Rentals.isOverdue(rental.dueMillis)

        findViewById<View>(R.id.btnReport).isVisible = !isMine && incoming == null
        findViewById<View>(R.id.btnReserve).isVisible = !isMine && !onLoan && !done && incoming == null
        findViewById<View>(R.id.btnEdit).isVisible = open && deal == null
        findViewById<View>(R.id.btnMarkDone).isVisible = open && deal == null && pending.isEmpty()
        findViewById<View>(R.id.closedState).isVisible = isMine && closed
        findViewById<View>(R.id.btnCancelDeal).isVisible = deal != null
        findViewById<View>(R.id.btnProblem).isVisible = lentOverdue && loanIssue == null
        findViewById<TextView>(R.id.btnHandover).apply {
            isVisible = deal != null
            setText(if (dealMissed) R.string.btn_did_it_happen else R.string.btn_enter_code)
        }
        findViewById<TextView>(R.id.btnRequests).apply {
            isVisible = pending.isNotEmpty()
            text = resources.getQuantityString(R.plurals.btn_review_requests, pending.size, pending.size)
        }
        findViewById<View>(R.id.btnMessage).isVisible =
            (onLoan && !lenderToConfirm) || done || deal != null || incoming != null
        findViewById<View>(R.id.btnReturned).isVisible = onLoan && !borrowerWaiting
        findViewById<View>(R.id.btnNotReceived).isVisible = lenderToConfirm
        findViewById<TextView>(R.id.btnReturned).setText(
            when {
                lenderToConfirm -> R.string.btn_confirm_returned
                rental?.lending == true -> R.string.btn_mark_returned
                else -> R.string.btn_ive_returned
            }
        )
        findViewById<View>(R.id.btnCancelReservation).isVisible = !isMine && reservation != null
        findViewById<TextView>(R.id.btnMarkDone).setText(
            when (listing.type) {
                1 -> R.string.btn_lend_out
                2 -> R.string.btn_mark_swapped
                else -> R.string.btn_mark_sold
            }
        )
        findViewById<TextView>(R.id.btnCancelReservation).setText(
            if (listing.isSwap) R.string.btn_withdraw_short else R.string.btn_cancel_short
        )
        if (!isMine && !onLoan && !done) bindReserveButton()
        val partner = rental?.partner ?: deal?.from ?: listing.seller
        findViewById<TextView>(R.id.btnMessage).text = getString(R.string.btn_message_seller, partner.first)

        // Status card above the seller.
        val card = findViewById<View>(R.id.reservationCard)
        val title = findViewById<TextView>(R.id.reservationTitle)
        val detail = findViewById<TextView>(R.id.reservationDetail)
        val due = findViewById<TextView>(R.id.reservationDue)
        val received = findViewById<TextView>(R.id.btnReceived)
        val codeBox = findViewById<View>(R.id.codeBox)
        codeBox.isVisible = false
        received.isVisible = !isMine && reservation != null
        received.setText(
            when {
                reservationMissed -> R.string.btn_did_meetup_happen
                listing.isSwap -> R.string.btn_swap_done
                listing.isRent -> R.string.btn_picked_up
                else -> R.string.btn_got_item
            }
        )
        fun dueLine(text: String, color: Int) {
            due.isVisible = true
            due.text = text
            due.setTextColor(getColor(color))
        }
        card.isVisible = true
        when {
            rental != null -> {
                val who = rental.partner.name
                val date = Rentals.dateLabel(rental.dueMillis)
                title.text = getString(if (rental.lending) R.string.loan_lent_title else R.string.loan_borrowed_title, who)
                detail.text = getString(if (rental.lending) R.string.loan_lent_due else R.string.loan_borrowed_due, date)
                when {
                    lenderToConfirm -> dueLine(getString(R.string.loan_partner_says_returned, rental.partner.first), R.color.swap_text)
                    borrowerWaiting -> dueLine(getString(R.string.loan_waiting_confirm, rental.partner.first), R.color.text_secondary)
                    loanIssue != null -> dueLine(
                        getString(R.string.status_reported_format, Rentals.countdown(this, rental.dueMillis), loanIssue.ref),
                        R.color.danger
                    )
                    else -> dueLine(
                        Rentals.countdown(this, rental.dueMillis),
                        if (Rentals.isOverdue(rental.dueMillis)) R.color.danger else R.color.uc_green
                    )
                }
            }
            deal != null -> {
                title.text = getString(
                    when {
                        listing.isSwap -> R.string.deal_swapping_title
                        listing.isRent -> R.string.deal_lending_title
                        else -> R.string.deal_reserved_title
                    },
                    deal.from.name
                )
                detail.text = requestSummary(deal)
                val waiting = UserState.requests(listing.id).count { UserState.status(it) == Deals.WAITLIST }
                if (dealMissed) {
                    dueLine(getString(R.string.meetup_missed_format, Rentals.whenLabel(this, deal.meetMillis)), R.color.swap_text)
                } else {
                    val hint = getString(R.string.deal_code_hint, deal.from.first)
                    dueLine(
                        if (waiting == 0) hint else hint + " \u2022 " + resources.getQuantityString(R.plurals.waitlist_count, waiting, waiting),
                        R.color.text_secondary
                    )
                }
            }
            pending.isNotEmpty() -> {
                val first = pending.first()
                title.text = if (pending.size == 1) {
                    getString(
                        when {
                            first.offerId != null -> R.string.requests_one_swap
                            listing.isRent -> R.string.requests_one_borrow
                            else -> R.string.requests_one_buy
                        },
                        first.from.name
                    )
                } else {
                    resources.getQuantityString(R.plurals.requests_many, pending.size, pending.size)
                }
                detail.text = if (pending.size == 1) requestSummary(first) else pending.joinToString(", ") { it.from.name }
                dueLine(
                    getString(R.string.answer_within_format, Rentals.timeLeft(this, pending.minOf { it.expiresMillis })),
                    R.color.swap_text
                )
            }
            !isMine && reservation != null -> {
                title.setText(if (listing.isSwap) R.string.swap_card_title else R.string.reservation_card_title)
                detail.text = reservation.summary
                if (reservationMissed) {
                    dueLine(getString(R.string.meetup_missed_format, Rentals.whenLabel(this, reservation.meetMillis!!)), R.color.swap_text)
                } else {
                    due.isVisible = false
                    codeBox.isVisible = true
                    findViewById<TextView>(R.id.codeValue).text = Deals.myCode(listing.id)
                    findViewById<TextView>(R.id.codeHint).text = getString(
                        if (listing.isSwap) R.string.code_your_hint_swap else R.string.code_your_hint, listing.seller.first
                    )
                }
            }
            incoming != null -> {
                title.text = getString(R.string.incoming_title, incoming.from.name)
                detail.text = getString(
                    R.string.incoming_detail, Listings.byId(incoming.listingId).title,
                    Rentals.whenLabel(this, incoming.meetMillis)
                )
                due.isVisible = false
            }
            done -> {
                val swapped = listing.isSwap || UserState.gotInSwap(listing.id)
                title.text = getString(if (swapped) R.string.done_swapped_title else R.string.done_bought_title, listing.seller.name)
                detail.setText(R.string.done_detail)
                due.isVisible = false
            }
            else -> card.isVisible = false
        }
    }

    /** "UC Main Gate Security Desk • Tomorrow, 2:00 PM", plus how long or what they offer. */
    private fun requestSummary(request: Request): String {
        val base = getString(R.string.request_where_when, request.meetup, Rentals.whenLabel(this, request.meetMillis))
        return when {
            request.offerId != null -> base + "\n" + getString(R.string.request_offers, Listings.byId(request.offerId).title)
            listing.isRent -> base + "\n" + getString(
                R.string.request_borrow_for, Listings.weeksLabel(this, request.weeks),
                Listings.price(listing.price * request.weeks)
            )
            else -> base
        }
    }

    // ---- Requests on your listing --------------------------------------------

    /** "Review requests": pick one, then Accept or Decline. Expired ones are listed but greyed out. */
    fun reviewRequests(view: View) {
        val open = UserState.pending(listing.id)
        val expired = UserState.requests(listing.id).filter { UserState.status(it) == Deals.EXPIRED }
        if (open.isEmpty()) return
        var picked = if (open.size == 1) 0 else -1
        Sheets.show(this, R.layout.sheet_choose) { sheet, dialog ->
            sheet.findViewById<TextView>(R.id.chooseKicker).setText(R.string.requests_kicker)
            sheet.findViewById<TextView>(R.id.chooseTitle).text = listing.title
            sheet.findViewById<TextView>(R.id.chooseSub).apply {
                isVisible = true
                setText(R.string.requests_sub)
            }
            val box = sheet.findViewById<LinearLayout>(R.id.chooseRows)
            fun row(request: Request, meta: String): View {
                val row = LayoutInflater.from(this).inflate(R.layout.item_swap_option, box, false)
                row.findViewById<TextView>(R.id.optionTitle).text = request.from.name
                row.findViewById<TextView>(R.id.optionMeta).text =
                    requestSummary(request) + "\n\u201C" + request.message + "\u201D\n" + meta
                box.addView(row)
                return row
            }
            val rows = open.map {
                row(it, getString(R.string.answer_within_format, Rentals.timeLeft(this, it.expiresMillis)))
            }
            Choice.rows(rows, rows.map { it.findViewById(R.id.optionRadio) }, picked) { picked = it }
            // Requests nobody answered in time: shown so the seller knows, but they can't be accepted.
            expired.forEach {
                row(it, getString(R.string.request_expired)).apply {
                    alpha = 0.5f
                    isClickable = false
                    findViewById<View>(R.id.optionRadio).visibility = View.INVISIBLE
                }
            }

            sheet.findViewById<TextView>(R.id.chooseSecondary).apply {
                isVisible = true
                setText(R.string.btn_decline)
                setOnClickListener {
                    val request = open.getOrNull(picked) ?: return@setOnClickListener pickFirst(box)
                    MaterialAlertDialogBuilder(this@ListingDetailActivity)
                        .setTitle(getString(R.string.decline_title, request.from.first))
                        .setMessage(getString(R.string.decline_message, request.from.first))
                        .setNegativeButton(R.string.action_cancel, null)
                        .setPositiveButton(R.string.btn_decline) { _, _ ->
                            UserState.decline(request)
                            dialog.dismiss()
                            bindActions()
                            notify(getString(R.string.toast_declined, request.from.first))
                        }
                        .show()
                }
            }
            sheet.findViewById<TextView>(R.id.choosePrimary).apply {
                setText(R.string.btn_accept)
                setOnClickListener { btn ->
                    val request = open.getOrNull(picked) ?: return@setOnClickListener pickFirst(box)
                    val conflicts = UserState.offersUsing(listing.id)
                    if (conflicts.isEmpty()) {
                        Motion.confirm(btn)
                        accept(request)
                        dialog.dismiss()
                        return@setOnClickListener
                    }
                    // One item can't go to two people: accepting withdraws your own offer that uses it.
                    val other = conflicts.first()
                    MaterialAlertDialogBuilder(this@ListingDetailActivity)
                        .setTitle(R.string.withdraw_other_title)
                        .setMessage(getString(R.string.withdraw_other_message, other.seller.first, other.title, request.from.first))
                        .setNegativeButton(R.string.action_cancel, null)
                        .setPositiveButton(R.string.withdraw_other_confirm) { _, _ ->
                            Motion.confirm(btn)
                            accept(request)
                            dialog.dismiss()
                        }
                        .show()
                }
            }
        }
    }

    private fun pickFirst(box: View) {
        Motion.shake(box)
        Toast.makeText(this, R.string.toast_pick_request, Toast.LENGTH_SHORT).show()
    }

    private fun accept(request: Request) {
        UserState.accept(request)
        bindActions()
        notify(
            getString(R.string.toast_accepted, request.from.first, Rentals.whenLabel(this, request.meetMillis)),
            getString(R.string.action_message)
        ) { openChatWith(request.from, listing.id) }
    }

    /** Bottom bar on a promised listing: "Enter code", or "Did it happen?" once the meetup is long past. */
    fun handOver(view: View) {
        val deal = UserState.accepted(listing.id) ?: return
        if (Deals.meetupMissed(deal.meetMillis)) askMeetupHappened(deal.from, deal.meetMillis, ownerSide = true) else enterCode(deal)
    }

    /**
     * The giver types in the receiver's code to prove they met. The other
     * student never acts here, so the sheet says what their code is.
     */
    private fun enterCode(deal: Request) {
        val first = deal.from.first
        val expected = Deals.code(deal.id)
        Sheets.show(this, R.layout.sheet_code) { sheet, dialog ->
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            sheet.findViewById<TextView>(R.id.codeTitle).text = getString(R.string.code_title, first)
            sheet.findViewById<TextView>(R.id.codeSub).text = getString(
                when {
                    listing.isSwap -> R.string.code_sub_swap
                    listing.isRent -> R.string.code_sub_lend
                    else -> R.string.code_sub_sell
                },
                first
            )
            sheet.findViewById<TextView>(R.id.codeDemo).text = getString(R.string.code_demo, first, expected)
            val input = sheet.findViewById<EditText>(R.id.codeInput)
            val error = sheet.findViewById<TextView>(R.id.codeError)
            fun check() {
                val typed = input.text.toString()
                val wrong = typed != expected
                Motion.setVisiblePop(error, wrong)
                when {
                    typed.length < 4 -> {
                        error.setText(R.string.code_error_short)
                        Motion.shake(input)
                    }
                    wrong -> {
                        error.text = getString(R.string.code_error_wrong, first)
                        Motion.shake(input)
                    }
                    else -> {
                        hideKeyboard(input)
                        Motion.confirm(input)
                        dialog.dismiss()
                        completeHandover(deal, Exchange.PROOF_CODE)
                    }
                }
            }
            sheet.findViewById<View>(R.id.codeConfirm).setOnClickListener { check() }
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) { check(); true } else false
            }
            sheet.findViewById<View>(R.id.codeSkip).setOnClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.no_code_title)
                    .setMessage(getString(R.string.no_code_message, first))
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.btn_confirm_handover) { _, _ ->
                        hideKeyboard(input)
                        Motion.confirm(input)
                        dialog.dismiss()
                        completeHandover(deal, Exchange.PROOF_MANUAL)
                    }
                    .show()
            }
        }
    }

    private fun completeHandover(deal: Request, proof: Int) {
        UserState.handOver(listing, proof)
        bindActions()
        val first = deal.from.first
        when {
            listing.isRent -> notify(
                getString(R.string.toast_lent_until, first, Rentals.dateLabel(UserState.rental(listing.id)?.dueMillis ?: 0))
            )
            else -> notify(
                getString(if (listing.isSwap) R.string.toast_swapped_with else R.string.toast_sold_to, first),
                getString(R.string.action_view)
            ) { switchTab(ProfileActivity::class.java) { putExtra(EXTRA_TAB, ProfileActivity.TAB_COMPLETED) } }
        }
    }

    /** Red X on a promised listing: call the deal off. Late cancels count against on-time. */
    fun cancelDeal(view: View) {
        val deal = UserState.accepted(listing.id) ?: return
        val first = deal.from.first
        val late = Deals.isLate(deal.meetMillis)
        var message = getString(R.string.cancel_deal_message, first)
        if (late) message += "\n\n" + getString(R.string.late_cancel_note)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.cancel_deal_title, first))
            .setMessage(message)
            .setNegativeButton(R.string.cancel_deal_keep, null)
            .setPositiveButton(R.string.cancel_deal_confirm) { _, _ ->
                UserState.cancelAccepted(listing.id)
                bindActions()
                notify(getString(if (late) R.string.toast_deal_cancelled_late else R.string.toast_deal_cancelled, first))
            }
            .show()
    }

    /**
     * The meetup was more than two hours ago and nothing was confirmed.
     * Yes finishes the exchange; "they didn't come" goes to student
     * support; "I couldn't make it" counts against your on-time rate.
     */
    private fun askMeetupHappened(partner: Seller, meetMillis: Long, ownerSide: Boolean) {
        val first = partner.first
        var picked = -1
        Sheets.show(this, R.layout.sheet_choose) { sheet, dialog ->
            sheet.findViewById<TextView>(R.id.chooseKicker).setText(R.string.meetup_check_kicker)
            sheet.findViewById<TextView>(R.id.chooseTitle).setText(R.string.btn_did_meetup_happen)
            sheet.findViewById<TextView>(R.id.chooseSub).apply {
                isVisible = true
                text = getString(R.string.meetup_check_sub, first, Rentals.whenLabel(this@ListingDetailActivity, meetMillis), listing.title)
            }
            val yes = getString(
                when {
                    ownerSide -> R.string.happened_yes_gave
                    listing.isSwap -> R.string.happened_yes_swap
                    listing.isRent -> R.string.happened_yes_borrow
                    else -> R.string.happened_yes_got
                }
            )
            val options = listOf(
                yes to getString(R.string.happened_yes_hint),
                getString(R.string.happened_they_missed, first) to getString(R.string.happened_they_missed_hint),
                getString(R.string.happened_i_missed) to getString(R.string.happened_i_missed_hint)
            )
            val box = sheet.findViewById<LinearLayout>(R.id.chooseRows)
            val rows = options.map { (label, hint) ->
                LayoutInflater.from(this).inflate(R.layout.item_swap_option, box, false).also {
                    it.findViewById<TextView>(R.id.optionTitle).text = label
                    it.findViewById<TextView>(R.id.optionMeta).text = hint
                    box.addView(it)
                }
            }
            Choice.rows(rows, rows.map { it.findViewById(R.id.optionRadio) }, picked) { picked = it }
            sheet.findViewById<TextView>(R.id.choosePrimary).apply {
                setText(R.string.btn_continue)
                setOnClickListener {
                    when (picked) {
                        0 -> {
                            dialog.dismiss()
                            if (ownerSide) UserState.accepted(listing.id)?.let { enterCode(it) } else confirmReceived()
                        }
                        1, 2 -> {
                            val theyMissed = picked == 1
                            MaterialAlertDialogBuilder(this@ListingDetailActivity)
                                .setTitle(if (theyMissed) R.string.no_show_title else R.string.i_missed_title)
                                .setMessage(getString(if (theyMissed) R.string.no_show_message else R.string.i_missed_message, first))
                                .setNegativeButton(R.string.action_cancel, null)
                                .setPositiveButton(if (theyMissed) R.string.no_show_confirm else R.string.i_missed_confirm) { _, _ ->
                                    if (ownerSide) UserState.reportOwnerNoShow(listing, theyMissed)
                                    else UserState.reportNoShow(listing, theyMissed)
                                    dialog.dismiss()
                                    bindActions()
                                    notify(
                                        if (theyMissed) getString(R.string.toast_no_show, UserState.issue(listing.id)?.ref ?: "")
                                        else getString(R.string.toast_i_missed)
                                    )
                                }
                                .show()
                        }
                        else -> {
                            Motion.shake(box)
                            Toast.makeText(this@ListingDetailActivity, R.string.toast_pick_option, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    /** Flag on an overdue loan you lent out: send it to student support. */
    fun reportLoanProblem(view: View) {
        val rental = UserState.rental(listing.id) ?: return
        val reasons = listOf(R.string.problem_not_returned, R.string.problem_damaged, R.string.problem_other)
        reportProblem(listing.id, listing.title, rental.partner, reasons) { bindActions() }
    }

    /** Reservation card: the meetup happened and you have the item. */
    fun markReceived(view: View) {
        val reservation = UserState.reservation(listing.id) ?: return
        if (Deals.meetupMissed(reservation.meetMillis)) {
            askMeetupHappened(listing.seller, reservation.meetMillis!!, ownerSide = false)
            return
        }
        confirmReceived()
    }

    private fun confirmReceived() {
        val reservation = UserState.reservation(listing.id) ?: return
        val first = listing.seller.name.substringBefore(' ')
        val returnBy = Rentals.dateLabel(System.currentTimeMillis() + reservation.weeks.coerceAtLeast(1) * Rentals.WEEK_MS)
        val message = when {
            listing.isSwap -> getString(R.string.received_swap_message, first)
            listing.isRent -> getString(R.string.received_rent_message, returnBy)
            else -> getString(R.string.received_buy_message, first)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.got_item_title, listing.title))
            .setMessage(message)
            .setNegativeButton(R.string.return_not_yet, null)
            .setPositiveButton(R.string.received_confirm) { _, _ ->
                UserState.markReceived(listing)
                bindActions()
                notify(if (listing.isRent) getString(R.string.toast_picked_up, returnBy) else getString(R.string.toast_received))
            }
            .show()
    }

    /** On loan: chat with whoever has it, or whoever you borrowed it from. */
    fun messagePartner(view: View) {
        val partner = UserState.rental(listing.id)?.partner ?: UserState.accepted(listing.id)?.from
            ?: UserState.incomingOffer(listing.id)?.from ?: listing.seller
        openChatWith(partner, listing.id)
    }

    /**
     * On loan. Lender: confirm the item is back (it moves to Completed).
     * Borrower: report that you gave it back; the lender then confirms.
     */
    fun markReturned(view: View) {
        val rental = UserState.rental(listing.id) ?: return
        val first = rental.partner.name.substringBefore(' ')
        if (!rental.lending) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ive_returned_title)
                .setMessage(getString(R.string.ive_returned_message, listing.title, first))
                .setNegativeButton(R.string.return_not_yet, null)
                .setPositiveButton(R.string.ive_returned_confirm) { _, _ ->
                    UserState.reportReturned(listing)
                    bindActions()
                    notify(getString(R.string.toast_return_reported, first))
                }
                .show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.return_title)
            .setMessage(getString(R.string.return_lent_message, first, listing.title))
            .setNegativeButton(R.string.return_not_yet, null)
            .setPositiveButton(R.string.return_confirm) { _, _ ->
                UserState.markReturned(listing)
                bindActions()
                notify(getString(R.string.toast_returned))
            }
            .show()
    }

    /** Lender: the borrower said it was returned, but it was not. */
    fun rejectReturn(view: View) {
        val rental = UserState.rental(listing.id) ?: return
        val first = rental.partner.name.substringBefore(' ')
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.not_received_title)
            .setMessage(getString(R.string.not_received_message, first, listing.title))
            .setNegativeButton(R.string.return_not_yet, null)
            .setPositiveButton(R.string.not_received_confirm) { _, _ ->
                UserState.rejectReturn(listing)
                bindActions()
                notify(getString(R.string.toast_not_received, first))
            }
            .show()
    }

    private fun bindReserveButton() {
        val price = Listings.price(listing.price)
        findViewById<TextView>(R.id.btnReserve).text = when {
            UserState.isReserved(listing.id) -> getString(R.string.btn_message_seller, listing.seller.name.substringBefore(' '))
            listing.type == 1 -> getString(R.string.btn_rent_format, price)
            listing.type == 2 -> getString(R.string.btn_swap)
            else -> getString(R.string.btn_reserve_format, price)
        }
    }

    /** Bookmark button on the photo. */
    fun toggleSave(view: View) {
        val nowSaved = !UserState.isSaved(listing.id)
        UserState.setSaved(listing.id, nowSaved)
        Motion.replaceIcon(findViewById(R.id.saveIcon)) { bindSaved() }
        if (nowSaved) Motion.confirm(view) else Motion.tick(view)
        if (nowSaved) {
            notify(getString(R.string.toast_saved), getString(R.string.action_view)) { openSaved(view) }
        } else {
            notify(getString(R.string.toast_unsaved), getString(R.string.action_undo)) {
                UserState.setSaved(listing.id, true)
                Motion.replaceIcon(findViewById(R.id.saveIcon)) { bindSaved() }
                Motion.confirm(view)
            }
        }
    }

    /**
     * Share button: preview of what will be shared and the listing link.
     * Sending to Messenger, Viber and other apps comes in a later version;
     * for now the link can be copied.
     */
    fun shareListing(view: View) {
        Sheets.show(this, R.layout.sheet_share) { sheet, dialog ->
            val link = getString(R.string.share_link_format, listing.id)
            sheet.findViewById<TextView>(R.id.shareItemTitle).text = listing.title
            sheet.findViewById<TextView>(R.id.shareItemPrice).text = Listings.priceLabel(this, listing)
            sheet.findViewById<TextView>(R.id.shareLink).text = link
            sheet.findViewById<View>(R.id.shareCopy).setOnClickListener { btn ->
                Motion.confirm(btn)
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(listing.title, link))
                dialog.dismiss()
                notify(getString(R.string.toast_link_copied))
            }
        }
    }

    /** Own rent listing: pick who is borrowing it and for how long. */
    private fun lendOut() {
        val people = Listings.SELLERS
        val weekOptions = Listings.RENT_WEEKS.filter { it <= listing.maxWeeks }.ifEmpty { listOf(1) }
        var person = -1
        var weeks = weekOptions.first()
        Sheets.show(this, R.layout.sheet_lend) { sheet, dialog ->
            sheet.findViewById<TextView>(R.id.lendItem).text = listing.title
            val box = sheet.findViewById<LinearLayout>(R.id.lendPeople)
            val rows = people.map { p ->
                val row = LayoutInflater.from(this).inflate(R.layout.item_swap_option, box, false)
                row.findViewById<TextView>(R.id.optionTitle).text = p.name
                row.findViewById<TextView>(R.id.optionMeta).text = getString(R.string.seller_meta_format, p.program, p.year)
                box.addView(row)
                row
            }
            Choice.rows(rows, rows.map { it.findViewById(R.id.optionRadio) }, person) { person = it }

            val dueText = sheet.findViewById<TextView>(R.id.lendDue)
            fun bindDue(roll: Boolean = false) {
                val text = getString(R.string.lend_due_format, Rentals.dateLabel(System.currentTimeMillis() + weeks * Rentals.WEEK_MS))
                if (roll) Motion.rollText(dueText, text) else dueText.text = text
            }
            val chips = addChips(sheet.findViewById(R.id.lendWeeks), weekOptions.map { Listings.weeksLabel(this, it) })
            Choice.chips(chips, 0) { weeks = weekOptions[it]; bindDue(roll = true) }
            bindDue(roll = false)

            sheet.findViewById<View>(R.id.lendStart).setOnClickListener { btn ->
                if (person < 0) {
                    Motion.shake(box)
                    Toast.makeText(this, R.string.toast_pick_borrower, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                Motion.confirm(btn)
                UserState.lend(listing, people[person], weeks)
                bindActions()
                dialog.dismiss()
                notify(getString(R.string.toast_lent, people[person].name.substringBefore(' ')))
            }
        }
    }

    /** Seller card: opens the seller's profile, or your own on your listing. */
    fun openSellerProfile(view: View) {
        if (isMine) openProfile(view) else openSeller(listing.seller)
    }

    /** Own listing: opens the form filled in with this listing. */
    fun editListing(view: View) {
        startActivity(Intent(this, CreateListingActivity::class.java).putExtra(EXTRA_LISTING, listing.id))
    }

    /** Own listing: confirm, then take it off the market. */
    fun markDone(view: View) {
        if (listing.isRent) {
            lendOut()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.mark_sold_title, listing.title))
            .setMessage(R.string.mark_sold_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.mark_sold_confirm) { _, _ ->
                UserState.close(listing.id)
                bindActions()
                notify(getString(R.string.toast_marked_done))
            }
            .show()
    }

    /** Opens the report form for this listing. */
    fun openReport(view: View) {
        startActivity(Intent(this, ReportListingActivity::class.java).putExtra(EXTRA_LISTING, listing.id))
    }

    /** Safe meetup point card: details and directions. */
    fun openMeetup(view: View) {
        Sheets.show(this, R.layout.sheet_meetup) { sheet, dialog ->
            sheet.findViewById<TextView>(R.id.meetupSheetPlace).text = listing.meetup
            sheet.findViewById<View>(R.id.meetupDone).setOnClickListener { dialog.dismiss() }
            sheet.findViewById<View>(R.id.meetupDirections).setOnClickListener {
                dialog.dismiss()
                startActivity(Intent(this, MapActivity::class.java).putExtra(MapActivity.EXTRA_PLACE, listing.meetup))
            }
        }
    }

    /** Reserve button: pick a meetup point, day and time, then confirm. */
    fun reserve(view: View) {
        if (UserState.isReserved(listing.id)) {
            openChatWith(listing.seller, listing.id)
            return
        }
        if (listing.isSwap) {
            offerSwap()
            return
        }

        // Only the days and times the seller said they are free.
        val days = upcomingDays()
        var place = Listings.MEETUP_POINTS.indexOf(listing.meetup).coerceAtLeast(0)
        var picked: MeetDay? = days.firstOrNull()
        var timeLabel: String? = null
        val weekOptions = Listings.RENT_WEEKS.filter { it <= listing.maxWeeks }.ifEmpty { listOf(1) }
        var weeks = weekOptions.first()

        Sheets.show(this, R.layout.sheet_reserve) { sheet, dialog ->
            sheet.findViewById<TextView>(R.id.reserveItemTitle).text = listing.title
            sheet.findViewById<TextView>(R.id.reserveItemSeller).text = listing.seller.name
            sheet.findViewById<TextView>(R.id.reserveItemPrice).text = Listings.priceLabel(this, listing)
            sheet.findViewById<TextView>(R.id.reserveAvailability).text = freeLabel()

            val placeText = sheet.findViewById<TextView>(R.id.reservePlace)
            placeText.text = Listings.MEETUP_POINTS[place]
            val placeRow = sheet.findViewById<View>(R.id.reservePlaceRow)
            placeRow.setOnClickListener {
                Dropdown.show(
                    placeRow,
                    Listings.MEETUP_POINTS,
                    place,
                    icons = listOf(R.drawable.ic_location),
                    rotateChevron = true,
                ) {
                    place = it
                    placeText.text = Listings.MEETUP_POINTS[it]
                }
            }

            // Times depend on the day: each day shows the seller's free slots for it.
            val timeBox = sheet.findViewById<LinearLayout>(R.id.reserveTimes)
            fun bindTimes(animate: Boolean = false) {
                val day = picked ?: return
                val slots = listing.availability.slots(day.weekday).map { Availability.time(it) }
                if (animate) Motion.layoutTransition(timeBox, durationMs = 180L)
                timeBox.removeAllViews()
                val chips = addChips(timeBox, slots + getString(R.string.chip_pick_time))
                val selected = slots.indexOf(timeLabel).let { if (it < 0 && timeLabel != null) slots.size else it }
                if (selected == slots.size) chips.last().text = timeLabel
                Choice.chips(chips, selected, allowReselect = true) { index ->
                    if (index < slots.size) {
                        timeLabel = slots[index]
                    } else {
                        bindTimes() // keep the old choice until a time is confirmed
                        pickTime(day.weekday) { label ->
                            timeLabel = label
                            bindTimes(animate = true)
                        }
                    }
                }
            }

            // Rent: how long, with the return date and total.
            val returnBy = sheet.findViewById<TextView>(R.id.reserveReturnBy)
            fun bindReturn(roll: Boolean = false) {
                val start = picked?.millis ?: System.currentTimeMillis()
                val text = getString(
                    R.string.rent_return_total_format,
                    Rentals.dateLabel(start + weeks * Rentals.WEEK_MS),
                    Listings.price(listing.price * weeks),
                )
                if (roll) Motion.rollText(returnBy, text) else returnBy.text = text
            }
            // Quick picks, plus a last chip that opens the full calendar.
            val dayChips = addChips(sheet.findViewById(R.id.reserveDays), days.map { it.label } + getString(R.string.chip_pick_date))
            fun bindDays() {
                val selected = days.indexOf(picked).let { if (it < 0 && picked != null) days.size else it }
                Choice.chips(dayChips, selected, allowReselect = true) { index ->
                    if (index < days.size) {
                        picked = days[index]
                    } else {
                        bindDays()
                        pickDate { day ->
                            dayChips.last().text = day.label
                            picked = day
                            bindDays()
                            bindTimes(animate = true)
                            if (listing.isRent) bindReturn(roll = true)
                        }
                        return@chips
                    }
                    // A time that the new day does not offer is cleared.
                    if (picked?.let { listing.availability.isFreeAt(it.weekday, minutesOf(timeLabel)) } != true) timeLabel = null
                    bindTimes(animate = true)
                    if (listing.isRent) bindReturn(roll = true)
                }
            }
            if (listing.isRent) {
                sheet.findViewById<View>(R.id.reserveRentSection).isVisible = true
                sheet.findViewById<TextView>(R.id.reserveRentMax).text = getString(
                    R.string.rent_max_format, listing.seller.name.substringBefore(' '),
                    Listings.weeksLabel(this, listing.maxWeeks),
                )
                val weekChips = addChips(sheet.findViewById(R.id.reserveWeeks), weekOptions.map { Listings.weeksLabel(this, it) })
                Choice.chips(weekChips, 0) {
                    weeks = weekOptions[it]
                    bindReturn(roll = true)
                }
                bindReturn(roll = false)
            }

            bindDays()
            bindTimes(animate = false)

            sheet.findViewById<View>(R.id.reserveConfirm).setOnClickListener { btn ->
                val pickedDay = picked?.label
                val pickedTime = timeLabel
                if (pickedDay == null || pickedTime == null) {
                    Motion.shake(if (pickedDay == null) sheet.findViewById(R.id.reserveDays) else timeBox)
                    Toast.makeText(this, R.string.toast_pick_day_time, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                Motion.confirm(btn)
                val summary = if (listing.isRent) {
                    getString(
                        R.string.rent_summary_format, Listings.MEETUP_POINTS[place], pickedDay, pickedTime,
                        Listings.weeksLabel(this, weeks),
                    )
                } else {
                    getString(R.string.sys_reserved_detail, Listings.MEETUP_POINTS[place], pickedDay, pickedTime)
                }
                UserState.setReserved(
                    listing.id, summary, if (listing.isRent) weeks else 0, meetMillis(picked!!, pickedTime),
                )
                bindActions()
                dialog.dismiss()
                Toast.makeText(this, getString(R.string.toast_reserved, listing.seller.name), Toast.LENGTH_SHORT).show()
                startActivity(
                    Intent(this, ChatThreadActivity::class.java)
                        .putExtra(EXTRA_SELLER, listing.seller.name)
                        .putExtra(EXTRA_LISTING, listing.id)
                        .putExtra(EXTRA_RESERVED, true)
                        .putExtra(ChatThreadActivity.EXTRA_RESERVED_DETAIL, summary),
                )
            }
        }
    }

    /** Calendar limited to future days the seller is free. */
    private fun pickDate(onPicked: (MeetDay) -> Unit) {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.pick_date_title)
            .setCalendarConstraints(
                // Days the seller is not free are greyed out.
                CalendarConstraints.Builder().setValidator(
                    CompositeDateValidator.allOf(
                        listOf(DateValidatorPointForward.now(), FreeDaysValidator(listing.availability.days.toIntArray()))
                    )
                ).build()
            )
            .build()
        picker.addOnPositiveButtonClickListener { millis ->
            val utc = TimeZone.getTimeZone("UTC")
            val cal = Calendar.getInstance(utc).apply { timeInMillis = millis }
            val format = SimpleDateFormat("EEE, MMM d", Locale.US).apply { timeZone = utc }
            onPicked(MeetDay(format.format(millis), Availability.weekdayOf(cal.get(Calendar.DAY_OF_WEEK)), millis, utc = true))
        }
        picker.show(supportFragmentManager, "date")
    }

    /** Clock picker; times outside the seller's free time that day are refused with a note. */
    private fun pickTime(weekday: Int, onPicked: (String) -> Unit) {
        val start = listing.availability.slots(weekday).firstOrNull() ?: Availability.DEFAULT_START
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_12H)
            .setHour(start / 60)
            .setMinute(start % 60)
            .setTitleText(R.string.pick_time_title)
            .build()
        picker.addOnPositiveButtonClickListener {
            val minutes = picker.hour * 60 + picker.minute
            if (!listing.availability.isFreeAt(weekday, minutes)) {
                val first = listing.seller.name.substringBefore(' ')
                val msg = getString(
                    R.string.toast_free_that_day, first, Availability.DAY_NAMES[weekday], listing.availability.labelFor(weekday)
                )
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            } else {
                onPicked(Availability.time(minutes))
            }
        }
        picker.show(supportFragmentManager, "time")
    }

    /** "1:30 PM" back to minutes after midnight, or -1. */
    private fun minutesOf(label: String?): Int {
        val m = Regex("""(\d+):(\d+) (AM|PM)""").matchEntire(label ?: return -1) ?: return -1
        val (h, min, ampm) = m.destructured
        return (h.toInt() % 12 + if (ampm == "PM") 12 else 0) * 60 + min.toInt()
    }

    /** "Cancel reservation" on the reservation card. */
    fun cancelReservation(view: View) {
        val first = listing.seller.name.substringBefore(' ')
        val swap = listing.isSwap
        // Under two hours before the meetup (or after it) counts as a late cancel.
        val late = Deals.isLate(UserState.reservation(listing.id)?.meetMillis)
        var message = getString(if (swap) R.string.cancel_swap_message else R.string.cancel_reservation_message, first)
        if (late) message += "\n\n" + getString(R.string.late_cancel_note)
        MaterialAlertDialogBuilder(this)
            .setTitle(if (swap) R.string.cancel_swap_title else R.string.cancel_reservation_title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel_reservation_keep, null)
            .setPositiveButton(if (swap) R.string.cancel_swap_confirm else R.string.cancel_reservation_confirm) { _, _ ->
                UserState.cancelReservation(listing.id)
                bindActions()
                notify(
                    when {
                        late -> getString(R.string.toast_cancelled_late)
                        swap -> getString(R.string.toast_swap_withdrawn)
                        else -> getString(R.string.toast_reservation_cancelled)
                    }
                )
            }
            .show()
    }

    /** "Offer a swap": pick one of your items to trade for this one. */
    private fun offerSwap() {
        // Items already promised to someone else can't be offered.
        val mine = Listings.offerable()
        val first = listing.seller.name.substringBefore(' ')
        var picked = -1
        Sheets.show(this, R.layout.sheet_swap) { sheet, dialog ->
            sheet.findViewById<TextView>(R.id.swapTheirTitle).text = listing.title
            sheet.findViewById<TextView>(R.id.swapTheirWants).apply {
                text = getString(R.string.swap_wants_format, listing.wants ?: "")
                isVisible = listing.wants != null
            }
            sheet.findViewById<TextView>(R.id.swapNote).text = getString(R.string.swap_note_format, first)

            val box = sheet.findViewById<LinearLayout>(R.id.swapOptions)
            val rows = mine.map { item ->
                val row = LayoutInflater.from(this).inflate(R.layout.item_swap_option, box, false)
                row.findViewById<TextView>(R.id.optionTitle).text = item.title
                row.findViewById<TextView>(R.id.optionMeta).text =
                    getString(R.string.saved_meta_format, item.program, Listings.conditionLabel(this, item.condition))
                box.addView(row)
                row
            }
            Choice.rows(rows, rows.map { it.findViewById(R.id.optionRadio) }, picked) { picked = it }

            sheet.findViewById<View>(R.id.swapSend).setOnClickListener { btn ->
                if (picked < 0) {
                    Motion.shake(box)
                    Toast.makeText(this, R.string.toast_pick_swap_item, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                Motion.confirm(btn)
                val summary = getString(R.string.swap_summary_format, mine[picked].title, listing.title)
                UserState.setReserved(listing.id, summary, offerId = mine[picked].id)
                bindActions()
                dialog.dismiss()
                Toast.makeText(this, getString(R.string.toast_swap_sent, listing.seller.name), Toast.LENGTH_SHORT).show()
                startActivity(
                    Intent(this, ChatThreadActivity::class.java)
                        .putExtra(EXTRA_SELLER, listing.seller.name)
                        .putExtra(EXTRA_LISTING, listing.id)
                        .putExtra(EXTRA_RESERVED, true)
                        .putExtra(ChatThreadActivity.EXTRA_RESERVED_DETAIL, summary)
                )
            }
        }
    }

    private fun addChips(container: LinearLayout, labels: List<String>): List<TextView> =
        labels.map { label ->
            val chip = LayoutInflater.from(this).inflate(R.layout.item_chip, container, false) as TextView
            chip.text = label
            container.addView(chip)
            chip
        }

    /** The next five weekdays, since the meetup points are open Mon–Fri. */
    /** A day you can pick for a meetup. [millis] is that day, for rental return dates. */
    data class MeetDay(val label: String, val weekday: Int, val millis: Long, val utc: Boolean = false)

    /** The meetup as a point in time: the picked day at the picked time. */
    private fun meetMillis(day: MeetDay, timeLabel: String): Long {
        val minutes = minutesOf(timeLabel).coerceAtLeast(0)
        val src = Calendar.getInstance(if (day.utc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault())
            .apply { timeInMillis = day.millis }
        return Calendar.getInstance().apply {
            set(src.get(Calendar.YEAR), src.get(Calendar.MONTH), src.get(Calendar.DAY_OF_MONTH), minutes / 60, minutes % 60, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /** The next five days the seller is free (up to four weeks ahead). */
    private fun upcomingDays(): List<MeetDay> {
        val format = SimpleDateFormat("EEE d", Locale.US)
        val cal = Calendar.getInstance()
        val out = mutableListOf<MeetDay>()
        var offset = 0
        while (out.size < 5 && offset < 28) {
            val weekday = Availability.weekdayOf(cal.get(Calendar.DAY_OF_WEEK))
            if (listing.availability.isFreeOn(weekday)) {
                val label = when (offset) {
                    0 -> getString(R.string.day_today_label)
                    1 -> getString(R.string.day_tomorrow_label)
                    else -> format.format(cal.time)
                }
                out += MeetDay(label, weekday, cal.timeInMillis)
            }
            cal.add(Calendar.DAY_OF_MONTH, 1)
            offset++
        }
        return out
    }
}
