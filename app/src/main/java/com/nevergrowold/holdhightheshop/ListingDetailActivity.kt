package com.nevergrowold.holdhightheshop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.CompositeDateValidator
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.nevergrowold.holdhightheshop.data.AppRepository
import com.nevergrowold.holdhightheshop.data.ListingStatus
import com.nevergrowold.holdhightheshop.system.MarketplaceBroadcasts
import com.nevergrowold.holdhightheshop.system.MarketplaceEventReceiver
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class ListingDetailActivity : BaseActivity() {

    private lateinit var listing: Listing
    private lateinit var photoCarousel: SpringPhotoCarousel

    private val eventReceiver = MarketplaceEventReceiver { _, _ ->
        reloadAndBind(animate = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_listing_detail)
        BarInsets.apply(this)

        photoCarousel = SpringPhotoCarousel(
            stage = findViewById(R.id.detailPhotoStage),
            prevPhoto = findViewById(R.id.detailHeroPhotoPrev),
            currentPhoto = findViewById(R.id.detailHeroPhoto),
            nextPhoto = findViewById(R.id.detailHeroPhotoNext),
            placeholderIcon = findViewById(R.id.detailHeroIcon),
            countPill = findViewById(R.id.detailPhotoCount),
            dotsContainer = findViewById(R.id.detailPhotoDots),
            onViewSinglePhoto = { ref -> AppIntents.viewMediaRef(this, ref) },
        )

        val listingId = intent.getStringExtra(EXTRA_ID) ?: intent.getStringExtra(EXTRA_LISTING)
        listing = Listings.byId(listingId)
        bind()

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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppRepository.stateVersion.collect {
                    reloadAndBind(animate = false)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        MarketplaceBroadcasts.registerInternalReceiver(
            this,
            eventReceiver,
            MarketplaceBroadcasts.ACTION_STATE_CHANGED,
            MarketplaceBroadcasts.ACTION_DEAL_UPDATED,
            MarketplaceBroadcasts.ACTION_PROPOSAL_UPDATED,
        )
    }

    override fun onStop() {
        runCatching { unregisterReceiver(eventReceiver) }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        reloadAndBind(animate = false)
    }

    private fun reloadAndBind(animate: Boolean) {
        val latest = Listings.byIdOrNull(listing.id)
        if (latest == null) {
            finish()
            return
        }
        listing = latest
        bind()
        bindSaved()
        bindActions(animate = animate)
    }

    private val isMine get() = Listings.isMine(listing)

    private fun bind() {
        val seller = Listings.sellerOf(listing)
        bindHeroPhoto()
        findViewById<View>(R.id.underReviewBanner).isVisible = listing.status == ListingStatus.UNDER_REVIEW
        findViewById<TextView>(R.id.detailCondition).text =
            getString(R.string.condition_badge_format, Listings.conditionLabel(this, listing.condition))
        findViewById<TextView>(R.id.detailColleges).text = Listings.targetProgramsLabel(this, listing)
        findViewById<TextView>(R.id.detailTitle).text = listing.title
        findViewById<TextView>(R.id.detailPrice).text = Listings.priceLabel(this, listing)
        findViewById<TextView>(R.id.detailDesc).text = listing.description
        findViewById<View>(R.id.swapWantsCard).isVisible = listing.isSwap && !listing.wants.isNullOrBlank()
        findViewById<TextView>(R.id.swapWants).text = listing.wants
        findViewById<TextView>(R.id.sellerInitials).text = seller.initials
        findViewById<TextView>(R.id.sellerName).text =
            if (isMine) getString(R.string.seller_you_format, seller.name) else seller.name
        findViewById<View>(R.id.btnSave).isVisible = !isMine
        findViewById<TextView>(R.id.sellerMeta).text =
            if (isMine && !UserState.settings.showProgram) seller.year
            else getString(R.string.seller_meta_format, seller.program, seller.year)
        findViewById<TextView>(R.id.statRating).text = if (isMine) Reliability.rating() else seller.rating
        findViewById<TextView>(R.id.statOnTime).text = if (isMine) Reliability.onTime() else seller.onTime
        findViewById<TextView>(R.id.statCompleted).text =
            (if (isMine) Reliability.completed() else seller.completed).toString()
        findViewById<TextView>(R.id.meetupPlace).text = if (listing.meetupNote.isNotBlank()) {
            "${listing.meetup} \u2022 ${listing.meetupNote}"
        } else {
            listing.meetup
        }
        findViewById<TextView>(R.id.meetupAvailability).text = freeLabel()
    }

    private fun bindHeroPhoto() {
        photoCarousel.setPhotos(listing.photoUris, keepIndex = true)
    }

    fun cycleHeroPhoto(view: View) {
        Motion.tick(view)
        photoCarousel.cycleNext()
    }

    private fun freeLabel(): String = if (isMine) {
        getString(R.string.you_free_format, listing.availability.label())
    } else {
        getString(R.string.seller_free_format, Listings.sellerOf(listing).first, listing.availability.label())
    }

    private fun bindSaved() {
        val saved = UserState.isSaved(listing.id)
        findViewById<ImageView>(R.id.saveIcon).apply {
            setImageResource(if (saved) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark)
            setColorFilter(getColor(if (saved) R.color.uc_green else R.color.text_primary))
            contentDescription = getString(if (saved) R.string.cd_unsave else R.string.cd_save)
        }
    }

    private fun bindActions(animate: Boolean = true) {
        if (animate) {
            Motion.layoutTransition(findViewById(R.id.detailSheet), durationMs = 220L)
            Motion.layoutTransition(findViewById(R.id.bottomNav), durationMs = 200L)
        }
        val closed = UserState.isClosed(listing.id)
        val paused = UserState.isPaused(listing.id)
        val rental = UserState.rental(listing.id)
        val reservation = UserState.reservation(listing.id)
        val onLoan = rental != null
        val ownerManageable = isMine && !closed && !onLoan
        val deal = if (ownerManageable) UserState.accepted(listing.id) else null
        val pending = if (ownerManageable && deal == null) UserState.pending(listing.id) else emptyList()
        val incoming = if (!isMine && !onLoan && reservation == null) UserState.incomingOffer(listing.id) else null
        val done = !isMine && !onLoan && reservation == null && incoming == null && UserState.hasReceived(listing.id)
        val dealMissed = deal != null && Deals.meetupMissed(deal.meetMillis)
        val reservationMissed = reservation != null && reservation.isAccepted && Deals.meetupMissed(reservation.meetMillis)
        val loanIssue = UserState.issue(listing.id)

        val lenderToConfirm = rental?.lending == true && rental.returnPending
        val borrowerWaiting = rental?.lending == false && rental.returnPending
        val lentOverdue = rental?.lending == true && !rental.returnPending && Rentals.isOverdue(rental.dueMillis)

        // Bottom bar button states
        findViewById<View>(R.id.btnReport).isVisible = !isMine && incoming == null
        findViewById<View>(R.id.btnQuickChat).isVisible = !isMine && !onLoan && !done && reservation == null && incoming == null
        findViewById<View>(R.id.btnReserve).isVisible = !isMine && !onLoan && !done && incoming == null
        findViewById<View>(R.id.btnDeleteListing).isVisible =
            ownerManageable && AppRepository.canDeleteListing(listing.id) && deal == null
        findViewById<View>(R.id.btnEdit).isVisible = ownerManageable && deal == null
        findViewById<View>(R.id.btnMarkDone).isVisible = ownerManageable && deal == null && pending.isEmpty()
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
            },
        )
        findViewById<View>(R.id.btnCancelReservation).isVisible = !isMine && reservation != null
        findViewById<TextView>(R.id.btnMarkDone).setText(
            when {
                paused -> R.string.btn_resume_listing
                listing.type == 1 -> R.string.btn_lend_out
                listing.type == 2 -> R.string.btn_mark_swapped
                else -> R.string.btn_mark_sold
            },
        )
        findViewById<TextView>(R.id.btnCancelReservation).setText(
            if (listing.isSwap) R.string.btn_withdraw_short else R.string.btn_cancel_short,
        )
        if (!isMine && !onLoan && !done) bindReserveButton()
        val partner = rental?.partner ?: deal?.from ?: Listings.sellerOf(listing)
        findViewById<TextView>(R.id.btnMessage).text = getString(R.string.btn_message_seller, partner.first)

        // Status card above Seller
        val card = findViewById<View>(R.id.reservationCard)
        val title = findViewById<TextView>(R.id.reservationTitle)
        val detail = findViewById<TextView>(R.id.reservationDetail)
        val due = findViewById<TextView>(R.id.reservationDue)
        val received = findViewById<TextView>(R.id.btnReceived)
        val codeBox = findViewById<View>(R.id.codeBox)
        val addCalendar = findViewById<View>(R.id.btnAddCalendar)
        codeBox.isVisible = false
        addCalendar.isVisible = rental != null ||
            (deal != null && !dealMissed) ||
            (!isMine && reservation?.isAccepted == true && reservation.meetMillis != null && !reservationMissed)
        received.isVisible = !isMine && reservation?.isAccepted == true
        received.setText(
            when {
                reservationMissed -> R.string.btn_did_meetup_happen
                listing.isSwap -> R.string.btn_swap_done
                listing.isRent -> R.string.btn_picked_up
                else -> R.string.btn_got_item
            },
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
                        R.color.danger,
                    )
                    else -> dueLine(
                        Rentals.countdown(this, rental.dueMillis),
                        if (Rentals.isOverdue(rental.dueMillis)) R.color.danger else R.color.uc_green,
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
                    deal.from.name,
                )
                detail.text = requestSummary(deal)
                val waiting = UserState.requests(listing.id).count { UserState.status(it) == Deals.WAITLIST }
                if (dealMissed) {
                    dueLine(getString(R.string.meetup_missed_format, Rentals.whenLabel(this, deal.meetMillis)), R.color.swap_text)
                } else {
                    val hint = getString(R.string.deal_code_hint, deal.from.first)
                    dueLine(
                        if (waiting == 0) hint else hint + " \u2022 " + resources.getQuantityString(R.plurals.waitlist_count, waiting, waiting),
                        R.color.text_secondary,
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
                        first.from.name,
                    )
                } else {
                    resources.getQuantityString(R.plurals.requests_many, pending.size, pending.size)
                }
                detail.text = if (pending.size == 1) requestSummary(first) else pending.joinToString(", ") { it.from.name }
                dueLine(
                    getString(R.string.answer_within_format, Rentals.timeLeft(this, pending.minOf { it.expiresMillis })),
                    R.color.swap_text,
                )
            }
            !isMine && reservation != null -> {
                val sellerFirst = Listings.sellerOf(listing).first
                if (reservation.isPending) {
                    title.text = getString(R.string.status_pending_seller_title, sellerFirst)
                    detail.text = reservation.summary
                    dueLine(getString(R.string.status_pending_seller_sub, sellerFirst), R.color.swap_text)
                } else {
                    title.setText(if (listing.isSwap) R.string.swap_card_title else R.string.reservation_card_title)
                    detail.text = reservation.summary
                    if (reservationMissed) {
                        dueLine(
                            getString(R.string.meetup_missed_format, Rentals.whenLabel(this, reservation.meetMillis!!)),
                            R.color.swap_text,
                        )
                    } else {
                        due.isVisible = false
                        codeBox.isVisible = reservation.handoverCode.isNotBlank()
                        findViewById<TextView>(R.id.codeValue).text = reservation.handoverCode
                        findViewById<TextView>(R.id.codeHint).text = getString(
                            if (listing.isSwap) R.string.code_your_hint_swap else R.string.code_your_hint,
                            sellerFirst,
                        )
                    }
                }
            }
            incoming != null -> {
                title.text = getString(R.string.incoming_title, incoming.from.name)
                detail.text = getString(
                    R.string.incoming_detail,
                    Listings.byId(incoming.listingId).title,
                    Rentals.whenLabel(this, incoming.meetMillis),
                )
                due.isVisible = false
            }
            done -> {
                val swapped = listing.isSwap || UserState.gotInSwap(listing.id)
                title.text = getString(if (swapped) R.string.done_swapped_title else R.string.done_bought_title, Listings.sellerOf(listing).name)
                detail.setText(R.string.done_detail)
                due.isVisible = false
            }
            isMine && paused -> {
                title.setText(R.string.toast_listing_paused)
                detail.text = "Hidden from Campus Market until you tap Resume listing."
                due.isVisible = false
            }
            else -> card.isVisible = false
        }
    }

    fun addToCalendar(view: View) {
        Motion.tick(view)
        val rental = UserState.rental(listing.id)
        val deal = if (isMine) UserState.accepted(listing.id) else null
        val reservation = if (!isMine) UserState.reservation(listing.id) else null

        val calIntent = when {
            rental != null -> {
                val title = if (rental.lending) {
                    getString(R.string.calendar_lent_return_title, listing.title)
                } else {
                    getString(R.string.calendar_return_title, listing.title)
                }
                val desc = if (rental.lending) {
                    getString(R.string.calendar_lent_return_desc, rental.partner.name, listing.title)
                } else {
                    getString(R.string.calendar_return_desc, listing.title, rental.partner.name)
                }
                AppIntents.calendarEventIntent(title, listing.meetup, desc, rental.dueMillis)
            }
            deal != null -> AppIntents.calendarEventIntent(
                title = getString(R.string.calendar_meetup_title, listing.title),
                location = deal.meetup,
                description = getString(R.string.calendar_meetup_desc, deal.from.name, listing.title),
                beginMillis = deal.meetMillis,
            )
            reservation?.meetMillis != null -> AppIntents.calendarEventIntent(
                title = getString(R.string.calendar_meetup_title, listing.title),
                location = reservation.meetup.ifBlank { listing.meetup },
                description = getString(R.string.calendar_meetup_desc, Listings.sellerOf(listing).name, listing.title),
                beginMillis = reservation.meetMillis,
            )
            else -> return
        }
        if (AppIntents.launch(this, calIntent)) {
            Toast.makeText(this, R.string.toast_calendar_opened, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestSummary(request: Request): String {
        val base = getString(R.string.request_where_when, request.meetup, Rentals.whenLabel(this, request.meetMillis))
        return when {
            request.offerId != null -> base + "\n" + getString(R.string.request_offers, Listings.byId(request.offerId).title)
            listing.isRent -> base + "\n" + getString(
                R.string.request_borrow_for,
                Listings.daysLabel(this, request.days.coerceAtLeast(1)),
                Listings.price(request.agreedPrice),
            )
            else -> base
        }
    }

    // ---- Requests & Handover (Strict 2-Step SecureRandom Code) ---------------

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
                val quote = if (request.message.isNotBlank()) "\n\u201C${request.message}\u201D" else ""
                row.findViewById<TextView>(R.id.optionMeta).text = requestSummary(request) + quote + "\n" + meta
                box.addView(row)
                return row
            }
            val rows = open.map {
                row(it, getString(R.string.answer_within_format, Rentals.timeLeft(this, it.expiresMillis)))
            }
            Choice.rows(rows, rows.map { it.findViewById(R.id.optionRadio) }, picked) { picked = it }
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
            getString(R.string.action_message),
        ) { openChatWith(request.from, listing.id) }
    }

    fun handOver(view: View) {
        val deal = UserState.accepted(listing.id) ?: return
        if (Deals.meetupMissed(deal.meetMillis)) {
            askMeetupHappened(deal.from, deal.meetMillis, ownerSide = true)
        } else {
            enterCode(deal)
        }
    }

    /**
     * Real production handover code check:
     * NEVER displays the buyer's secret code on the seller's screen!
     * Seller must ask the buyer for their 4-digit SecureRandom code at meetup.
     */
    private fun enterCode(deal: Request) {
        val first = deal.from.first
        val expected = deal.handoverCode
        Sheets.show(this, R.layout.sheet_code) { sheet, dialog ->
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            sheet.findViewById<TextView>(R.id.codeTitle).text = getString(R.string.code_title, first)
            sheet.findViewById<TextView>(R.id.codeSub).text = getString(
                when {
                    listing.isSwap -> R.string.code_sub_swap
                    listing.isRent -> R.string.code_sub_lend
                    else -> R.string.code_sub_sell
                },
                first,
            )
            sheet.findViewById<View>(R.id.codeDemo).isVisible = false
            val input = sheet.findViewById<EditText>(R.id.codeInput)
            val error = sheet.findViewById<TextView>(R.id.codeError)
            fun check() {
                val typed = input.text.toString().trim()
                val wrong = typed != expected || expected.length != 4
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
        if (listing.isRent) {
            notify(
                getString(
                    R.string.toast_lent_until,
                    first,
                    Rentals.dateLabel(UserState.rental(listing.id)?.dueMillis ?: 0L),
                ),
            )
        } else {
            notify(
                getString(if (listing.isSwap) R.string.toast_swapped_with else R.string.toast_sold_to, first),
                getString(R.string.action_view),
            ) { switchTab(MainActivity.TAB_PROFILE) { putExtra(EXTRA_TAB, ProfileActivity.TAB_COMPLETED) } }
        }
    }

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

    private fun askMeetupHappened(partner: Seller, meetMillis: Long, ownerSide: Boolean) {
        val first = partner.first
        var picked = -1
        Sheets.show(this, R.layout.sheet_choose) { sheet, dialog ->
            sheet.findViewById<TextView>(R.id.chooseKicker).setText(R.string.meetup_check_kicker)
            sheet.findViewById<TextView>(R.id.chooseTitle).setText(R.string.btn_did_meetup_happen)
            sheet.findViewById<TextView>(R.id.chooseSub).apply {
                isVisible = true
                text = getString(
                    R.string.meetup_check_sub,
                    first,
                    Rentals.whenLabel(this@ListingDetailActivity, meetMillis),
                    listing.title,
                )
            }
            val yes = getString(
                when {
                    ownerSide -> R.string.happened_yes_gave
                    listing.isSwap -> R.string.happened_yes_swap
                    listing.isRent -> R.string.happened_yes_borrow
                    else -> R.string.happened_yes_got
                },
            )
            val options = listOf(
                yes to getString(R.string.happened_yes_hint),
                getString(R.string.happened_they_missed, first) to getString(R.string.happened_they_missed_hint),
                getString(R.string.happened_i_missed) to getString(R.string.happened_i_missed_hint),
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
                                    if (theyMissed) {
                                        UserState.report(listing.id, R.string.problem_no_show, partner)
                                    }
                                    if (ownerSide) UserState.reportOwnerNoShow(listing, theyMissed)
                                    else UserState.reportNoShow(listing, theyMissed)
                                    dialog.dismiss()
                                    bindActions()
                                    notify(
                                        if (theyMissed) getString(R.string.toast_no_show, UserState.issue(listing.id)?.ref ?: "")
                                        else getString(R.string.toast_i_missed),
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

    fun reportLoanProblem(view: View) {
        val rental = UserState.rental(listing.id) ?: return
        val reasons = listOf(R.string.problem_not_returned, R.string.problem_damaged, R.string.problem_other)
        reportProblem(listing.id, listing.title, rental.partner, reasons) { bindActions() }
    }

    fun markReceived(view: View) {
        val reservation = UserState.reservation(listing.id) ?: return
        if (!reservation.isAccepted) return
        if (Deals.meetupMissed(reservation.meetMillis)) {
            askMeetupHappened(Listings.sellerOf(listing), reservation.meetMillis!!, ownerSide = false)
            return
        }
        confirmReceived()
    }

    private fun confirmReceived() {
        val reservation = UserState.reservation(listing.id) ?: return
        val first = Listings.sellerOf(listing).first
        val returnBy = Rentals.dateLabel(System.currentTimeMillis() + reservation.days.coerceAtLeast(1) * Rentals.DAY_MS)
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
                UserState.handOver(listing, Exchange.PROOF_RECEIVED)
                bindActions()
                notify(if (listing.isRent) getString(R.string.toast_picked_up, returnBy) else getString(R.string.toast_received))
            }
            .show()
    }

    fun messagePartner(view: View) {
        val partner = UserState.rental(listing.id)?.partner
            ?: UserState.accepted(listing.id)?.from
            ?: UserState.incomingOffer(listing.id)?.from
            ?: Listings.sellerOf(listing)
        openChatWith(partner, listing.id)
    }

    /**
     * Lender return confirmation includes post-return prompt:
     * "Keep listed for other UC students to borrow, or pause it for now?"
     */
    fun markReturned(view: View) {
        val rental = UserState.rental(listing.id) ?: return
        val first = rental.partner.first
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
            .setTitle(R.string.post_return_title)
            .setMessage(getString(R.string.post_return_message, listing.title))
            .setNeutralButton(R.string.return_not_yet, null)
            .setNegativeButton(R.string.btn_pause_listing) { _, _ ->
                UserState.markReturned(listing, keepOpen = false)
                bindActions()
                notify(getString(R.string.toast_listing_paused))
            }
            .setPositiveButton(R.string.btn_keep_listed) { _, _ ->
                UserState.markReturned(listing, keepOpen = true)
                bindActions()
                notify(getString(R.string.toast_returned))
            }
            .show()
    }

    fun rejectReturn(view: View) {
        val rental = UserState.rental(listing.id) ?: return
        val first = rental.partner.first
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
        val sellerFirst = Listings.sellerOf(listing).first
        findViewById<TextView>(R.id.btnReserve).text = when {
            UserState.isReserved(listing.id) -> getString(R.string.btn_message_seller, sellerFirst)
            listing.type == 1 -> getString(R.string.btn_rent_format, Listings.priceLabel(this, listing))
            listing.type == 2 -> getString(R.string.btn_swap)
            else -> getString(R.string.btn_reserve_format, price)
        }
    }

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

    fun shareListing(view: View) {
        Sheets.show(this, R.layout.sheet_share) { sheet, dialog ->
            val link = AppIntents.listingUrl(listing.id)
            sheet.findViewById<TextView>(R.id.shareItemTitle).text = listing.title
            sheet.findViewById<TextView>(R.id.shareItemPrice).text = Listings.priceLabel(this, listing)
            sheet.findViewById<TextView>(R.id.shareLink).text = link
            sheet.findViewById<View>(R.id.shareCopy).setOnClickListener { btn ->
                Motion.confirm(btn)
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(listing.title, link))
                dialog.dismiss()
                Toast.makeText(this, R.string.toast_link_copied, Toast.LENGTH_SHORT).show()
            }
            sheet.findViewById<View>(R.id.shareExternal).setOnClickListener { btn ->
                Motion.confirm(btn)
                dialog.dismiss()
                AppIntents.launch(this, AppIntents.shareListingChooser(this, listing))
            }
        }
    }

    private fun lendOut() {
        val people = Listings.SELLERS.filter { it.uid != UserState.currentUid && !it.suspended }
        if (people.isEmpty()) {
            Toast.makeText(this, "Wait for a UC student to send a borrow request.", Toast.LENGTH_SHORT).show()
            return
        }
        val dayOptions = Listings.RENT_DAYS.filter { it <= listing.maxDays }.ifEmpty { listOf(1) }
        var person = -1
        var days = dayOptions.first()
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
                val text = getString(R.string.lend_due_format, Rentals.dateLabel(System.currentTimeMillis() + days * Rentals.DAY_MS))
                if (roll) Motion.rollText(dueText, text) else dueText.text = text
            }
            val chips = addChips(sheet.findViewById(R.id.lendWeeks), dayOptions.map { Listings.daysLabel(this, it) })
            Choice.chips(chips, 0) { days = dayOptions[it]; bindDue(roll = true) }
            bindDue(roll = false)

            sheet.findViewById<View>(R.id.lendStart).setOnClickListener { btn ->
                if (person < 0) {
                    Motion.shake(box)
                    Toast.makeText(this, R.string.toast_pick_borrower, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                Motion.confirm(btn)
                UserState.lend(listing, people[person], days)
                bindActions()
                dialog.dismiss()
                notify(getString(R.string.toast_lent, people[person].first))
            }
        }
    }

    fun openSellerProfile(view: View) {
        if (isMine) openProfile(view) else openSeller(Listings.sellerOf(listing))
    }

    fun editListing(view: View) {
        startActivity(Intent(this, CreateListingActivity::class.java).putExtra(EXTRA_LISTING, listing.id))
    }

    fun deleteListing(view: View) {
        if (!AppRepository.canDeleteListing(listing.id)) {
            Toast.makeText(this, R.string.toast_cannot_delete_active, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_listing_title, listing.title))
            .setMessage(R.string.delete_listing_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.delete_listing_confirm) { _, _ ->
                if (Listings.deleteListing(listing.id)) {
                    Toast.makeText(this, R.string.toast_listing_deleted, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .show()
    }

    fun markDone(view: View) {
        if (UserState.isPaused(listing.id)) {
            UserState.setPaused(listing.id, false)
            bindActions()
            notify(getString(R.string.toast_listing_resumed))
            return
        }
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

    fun openReport(view: View) {
        if (UserState.issue(listing.id) != null) {
            Toast.makeText(this, R.string.toast_already_reported, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, ReportListingActivity::class.java).putExtra(EXTRA_LISTING, listing.id))
    }

    /** Safe Meetup Point bottom sheet (replaces placeholder campus map). */
    fun openMeetup(view: View) {
        showSafeMeetupSheet(listing.meetup, listing.meetupNote)
    }

    fun reserve(view: View) {
        val seller = Listings.sellerOf(listing)
        if (UserState.isReserved(listing.id)) {
            openChatWith(seller, listing.id)
            return
        }
        if (listing.isSwap) {
            offerSwap()
            return
        }

        val days = upcomingDays()
        var place = Listings.MEETUP_POINTS.indexOf(listing.meetup).coerceAtLeast(0)
        var picked: MeetDay? = days.firstOrNull()
        var timeLabel: String? = null
        val dayOptions = Listings.RENT_DAYS.filter { it <= listing.maxDays }.ifEmpty { listOf(1) }
        var borrowDays = dayOptions.first()

        Sheets.show(this, R.layout.sheet_reserve) { sheet, dialog ->
            sheet.findViewById<TextView>(R.id.reserveItemTitle).text = listing.title
            sheet.findViewById<TextView>(R.id.reserveItemSeller).text = seller.name
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
                        bindTimes()
                        pickTime(day.weekday) { label ->
                            timeLabel = label
                            bindTimes(animate = true)
                        }
                    }
                }
            }

            val returnBy = sheet.findViewById<TextView>(R.id.reserveReturnBy)
            fun bindReturn(roll: Boolean = false) {
                val start = picked?.millis ?: System.currentTimeMillis()
                val safeDays = borrowDays.coerceIn(1, listing.maxDays.coerceAtLeast(1))
                val totalPrice = listing.price * safeDays
                val text = getString(
                    R.string.rent_return_total_format,
                    Rentals.dateLabel(start + safeDays * Rentals.DAY_MS),
                    Listings.price(totalPrice),
                )
                if (roll) Motion.rollText(returnBy, text) else returnBy.text = text
            }

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
                    if (picked?.let { listing.availability.isFreeAt(it.weekday, minutesOf(timeLabel)) } != true) timeLabel = null
                    bindTimes(animate = true)
                    if (listing.isRent) bindReturn(roll = true)
                }
            }

            if (listing.isRent) {
                sheet.findViewById<View>(R.id.reserveRentSection).isVisible = true
                sheet.findViewById<TextView>(R.id.reserveRentMax).text = getString(
                    R.string.rent_max_format,
                    seller.first,
                    Listings.daysLabel(this, listing.maxDays),
                )
                val customDaysInput = sheet.findViewById<EditText>(R.id.reserveCustomDays)
                customDaysInput.hint = getString(R.string.custom_days_hint, listing.maxDays)
                val durChips = addChips(sheet.findViewById(R.id.reserveWeeks), dayOptions.map { Listings.daysLabel(this, it) })
                Choice.chips(durChips, 0) {
                    borrowDays = dayOptions[it]
                    if (!customDaysInput.text.isNullOrEmpty()) customDaysInput.setText("")
                    bindReturn(roll = true)
                }
                customDaysInput.addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        val typed = s?.toString()?.toIntOrNull()
                        if (typed != null && typed >= 1) {
                            borrowDays = typed.coerceAtMost(listing.maxDays.coerceAtLeast(1))
                            bindReturn(roll = true)
                        }
                    }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
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
                val scheduledMillis = meetMillis(picked!!, pickedTime)
                val finalDays = if (listing.isRent) borrowDays.coerceIn(1, listing.maxDays.coerceAtLeast(1)) else 0
                UserState.submitRequest(
                    listing = listing,
                    meetup = Listings.MEETUP_POINTS[place],
                    meetupNote = listing.meetupNote,
                    meetMillis = scheduledMillis,
                    days = finalDays,
                )
                bindActions()
                dialog.dismiss()
                Toast.makeText(this, getString(R.string.toast_reserved, seller.name), Toast.LENGTH_SHORT).show()
                openChatWith(seller, listing.id, justReserved = true)
            }
        }
    }

    private fun pickDate(onPicked: (MeetDay) -> Unit) {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.pick_date_title)
            .setCalendarConstraints(
                CalendarConstraints.Builder().setValidator(
                    CompositeDateValidator.allOf(
                        listOf(DateValidatorPointForward.now(), FreeDaysValidator(listing.availability.days.toIntArray())),
                    ),
                ).build(),
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
                val first = Listings.sellerOf(listing).first
                val msg = getString(
                    R.string.toast_free_that_day,
                    first,
                    Availability.DAY_NAMES[weekday],
                    listing.availability.labelFor(weekday),
                )
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            } else {
                onPicked(Availability.time(minutes))
            }
        }
        picker.show(supportFragmentManager, "time")
    }

    private fun minutesOf(label: String?): Int {
        val m = Regex("""(\d+):(\d+) (AM|PM)""").matchEntire(label ?: return -1) ?: return -1
        val (h, min, ampm) = m.destructured
        return (h.toInt() % 12 + if (ampm == "PM") 12 else 0) * 60 + min.toInt()
    }

    fun cancelReservation(view: View) {
        val first = Listings.sellerOf(listing).first
        val swap = listing.isSwap
        val res = UserState.reservation(listing.id)
        val late = res?.isAccepted == true && Deals.isLate(res.meetMillis)
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
                    },
                )
            }
            .show()
    }

    /** Swap Offer sheet with clean empty state CTA ("Post an item to swap") when student has 0 offerable items! */
    private fun offerSwap() {
        val mine = Listings.offerable()
        val seller = Listings.sellerOf(listing)
        val first = seller.first
        var picked = -1
        Sheets.show(this, R.layout.sheet_swap) { sheet, dialog ->
            sheet.findViewById<TextView>(R.id.swapTheirTitle).text = listing.title
            sheet.findViewById<TextView>(R.id.swapTheirWants).apply {
                text = getString(R.string.swap_wants_format, listing.wants ?: "")
                isVisible = !listing.wants.isNullOrBlank()
            }
            sheet.findViewById<TextView>(R.id.swapNote).text = getString(R.string.swap_note_format, first)

            val box = sheet.findViewById<LinearLayout>(R.id.swapOptions)
            val sendBtn = sheet.findViewById<TextView>(R.id.swapSend)
            if (mine.isEmpty()) {
                val emptyView = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.bg_input)
                    setPadding(dp(this@ListingDetailActivity, 16), dp(this@ListingDetailActivity, 16), dp(this@ListingDetailActivity, 16), dp(this@ListingDetailActivity, 16))
                    addView(
                        TextView(this@ListingDetailActivity).apply {
                            setText(R.string.swap_empty_title)
                            setTextColor(getColor(R.color.text_primary))
                            textSize = 14f
                        },
                    )
                    addView(
                        TextView(this@ListingDetailActivity).apply {
                            text = getString(R.string.swap_empty_sub, first)
                            setTextColor(getColor(R.color.text_secondary))
                            textSize = 12f
                        },
                    )
                }
                box.addView(emptyView)
                sendBtn.setText(R.string.btn_post_item_to_swap)
                sendBtn.setOnClickListener {
                    dialog.dismiss()
                    startActivity(Intent(this, CreateListingActivity::class.java))
                }
                return@show
            }

            val rows = mine.map { item ->
                val row = LayoutInflater.from(this).inflate(R.layout.item_swap_option, box, false)
                row.findViewById<TextView>(R.id.optionTitle).text = item.title
                row.findViewById<TextView>(R.id.optionMeta).text = getString(
                    R.string.saved_meta_format,
                    Listings.targetProgramsLabel(this, item),
                    Listings.conditionLabel(this, item.condition),
                )
                box.addView(row)
                row
            }
            Choice.rows(rows, rows.map { it.findViewById(R.id.optionRadio) }, picked) { picked = it }

            sendBtn.setOnClickListener { btn ->
                if (picked < 0) {
                    Motion.shake(box)
                    Toast.makeText(this, R.string.toast_pick_swap_item, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                Motion.confirm(btn)
                val offeredItem = mine[picked]
                val nextDay = upcomingDays().firstOrNull()
                val slotMin = nextDay?.let { listing.availability.slots(it.weekday).firstOrNull() } ?: Availability.DEFAULT_START
                val meetTime = if (nextDay != null) {
                    meetMillis(nextDay, Availability.time(slotMin))
                } else {
                    System.currentTimeMillis() + Rentals.DAY_MS
                }
                UserState.submitRequest(
                    listing = listing,
                    meetup = listing.meetup,
                    meetupNote = listing.meetupNote,
                    meetMillis = meetTime,
                    offerId = offeredItem.id,
                    message = getString(R.string.swap_summary_format, offeredItem.title, listing.title),
                )
                bindActions()
                dialog.dismiss()
                Toast.makeText(this, getString(R.string.toast_swap_sent, seller.name), Toast.LENGTH_SHORT).show()
                openChatWith(seller, listing.id, justReserved = true)
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

    data class MeetDay(val label: String, val weekday: Int, val millis: Long, val utc: Boolean = false)

    private fun meetMillis(day: MeetDay, timeLabel: String): Long {
        val minutes = minutesOf(timeLabel).coerceAtLeast(0)
        val src = Calendar.getInstance(if (day.utc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault())
            .apply { timeInMillis = day.millis }
        return Calendar.getInstance().apply {
            set(src.get(Calendar.YEAR), src.get(Calendar.MONTH), src.get(Calendar.DAY_OF_MONTH), minutes / 60, minutes % 60, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

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

    companion object {
        const val EXTRA_ID = EXTRA_LISTING
    }
}
