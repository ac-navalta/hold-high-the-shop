package com.nevergrowold.holdhightheshop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
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
        BarInsets.apply(this)

        listing = Listings.byId(intent.getStringExtra(EXTRA_LISTING))
        bind()
    }

    override fun onResume() {
        super.onResume()
        // Saved or reserved state can change on other screens.
        bindSaved()
        bindActions()
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
        findViewById<TextView>(R.id.statRating).text = seller.rating
        findViewById<TextView>(R.id.statOnTime).text = seller.onTime
        findViewById<TextView>(R.id.statCompleted).text = seller.completed.toString()
        findViewById<TextView>(R.id.meetupPlace).text = listing.meetup
        findViewById<TextView>(R.id.meetupAvailability).text = freeLabel()
    }

    /** "Paolo is free Mon, Wed, Fri • 1:00–4:00 PM", or "You're free …" on your own listing. */
    private fun freeLabel(): String = if (isMine) {
        getString(R.string.you_free_format, listing.availability.label())
    } else {
        getString(R.string.seller_free_format, listing.seller.name.substringBefore(' '), listing.availability.label())
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
     * Bottom bar. Other people's listings get Report and Reserve; the
     * student's own listing gets Edit listing and Mark as sold instead.
     */
    private fun bindActions() {
        val closed = UserState.isClosed(listing.id)
        val rental = UserState.rental(listing.id)
        val reservation = UserState.reservation(listing.id)
        val onLoan = rental != null

        // Bottom bar. One set of buttons per state:
        // own listing (edit / mark done), on loan (message / mark returned),
        // reserved (message / cancel), or open (report / reserve).
        findViewById<View>(R.id.btnReport).isVisible = !isMine
        findViewById<View>(R.id.btnReserve).isVisible = !isMine && !onLoan
        findViewById<View>(R.id.btnEdit).isVisible = isMine && !closed && !onLoan
        findViewById<View>(R.id.btnMarkDone).isVisible = isMine && !closed && !onLoan
        findViewById<View>(R.id.closedState).isVisible = isMine && closed
        findViewById<View>(R.id.btnMessage).isVisible = onLoan
        findViewById<View>(R.id.btnReturned).isVisible = onLoan
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
        if (!isMine && !onLoan) bindReserveButton()

        // Status card above the seller: a loan with its due date, or a pending reservation.
        val card = findViewById<View>(R.id.reservationCard)
        val title = findViewById<TextView>(R.id.reservationTitle)
        val detail = findViewById<TextView>(R.id.reservationDetail)
        val due = findViewById<TextView>(R.id.reservationDue)
        when {
            rental != null -> {
                card.isVisible = true
                val who = rental.partner.name
                val date = Rentals.dateLabel(rental.dueMillis)
                title.text = getString(if (rental.lending) R.string.loan_lent_title else R.string.loan_borrowed_title, who)
                detail.text = getString(if (rental.lending) R.string.loan_lent_due else R.string.loan_borrowed_due, date)
                due.isVisible = true
                due.text = Rentals.countdown(this, rental.dueMillis)
                due.setTextColor(getColor(if (Rentals.isOverdue(rental.dueMillis)) R.color.danger else R.color.uc_green))
                findViewById<TextView>(R.id.btnMessage).text = getString(R.string.btn_message_seller, who.substringBefore(' '))
            }
            !isMine && reservation != null -> {
                card.isVisible = true
                title.setText(if (listing.isSwap) R.string.swap_card_title else R.string.reservation_card_title)
                detail.text = reservation.summary
                due.isVisible = false
            }
            else -> card.isVisible = false
        }
    }

    /** On loan: chat with whoever has it, or whoever you borrowed it from. */
    fun messagePartner(view: View) {
        UserState.rental(listing.id)?.let { openChatWith(it.partner, listing.id) }
    }

    /** On loan: confirm the item came back (or that you gave it back). */
    fun markReturned(view: View) {
        val rental = UserState.rental(listing.id) ?: return
        val first = rental.partner.name.substringBefore(' ')
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.return_title)
            .setMessage(
                getString(if (rental.lending) R.string.return_lent_message else R.string.return_borrowed_message, first, listing.title)
            )
            .setNegativeButton(R.string.return_not_yet, null)
            .setPositiveButton(R.string.return_confirm) { _, _ ->
                UserState.markReturned(listing)
                bindActions()
                notify(getString(R.string.toast_returned))
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
        bindSaved()
        if (nowSaved) {
            notify(getString(R.string.toast_saved), getString(R.string.action_view)) { openSaved(view) }
        } else {
            notify(getString(R.string.toast_unsaved), getString(R.string.action_undo)) {
                UserState.setSaved(listing.id, true)
                bindSaved()
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
            sheet.findViewById<View>(R.id.shareCopy).setOnClickListener {
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
            fun bindDue() {
                dueText.text = getString(R.string.lend_due_format, Rentals.dateLabel(System.currentTimeMillis() + weeks * Rentals.WEEK_MS))
            }
            val chips = addChips(sheet.findViewById(R.id.lendWeeks), weekOptions.map { Listings.weeksLabel(this, it) })
            Choice.chips(chips, 0) { weeks = weekOptions[it]; bindDue() }
            bindDue()

            sheet.findViewById<View>(R.id.lendStart).setOnClickListener {
                if (person < 0) {
                    Toast.makeText(this, R.string.toast_pick_borrower, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
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
                Dropdown.show(placeRow, Listings.MEETUP_POINTS, place, icons = listOf(R.drawable.ic_location)) {
                    place = it
                    placeText.text = Listings.MEETUP_POINTS[it]
                }
            }

            // Times depend on the day: each day shows the seller's free slots for it.
            val timeBox = sheet.findViewById<LinearLayout>(R.id.reserveTimes)
            fun bindTimes() {
                val day = picked ?: return
                val slots = listing.availability.slots(day.weekday).map { Availability.time(it) }
                timeBox.removeAllViews()
                val chips = addChips(timeBox, slots + getString(R.string.chip_pick_time))
                val selected = slots.indexOf(timeLabel).let { if (it < 0 && timeLabel != null) slots.size else it }
                if (selected == slots.size) chips.last().text = timeLabel
                Choice.chips(chips, selected) { index ->
                    if (index < slots.size) {
                        timeLabel = slots[index]
                    } else {
                        bindTimes() // keep the old choice until a time is confirmed
                        pickTime(day.weekday) { label ->
                            timeLabel = label
                            bindTimes()
                        }
                    }
                }
            }

            // Rent: how long, with the return date and total.
            val returnBy = sheet.findViewById<TextView>(R.id.reserveReturnBy)
            fun bindReturn() {
                val start = picked?.millis ?: System.currentTimeMillis()
                returnBy.text = getString(
                    R.string.rent_return_total_format,
                    Rentals.dateLabel(start + weeks * Rentals.WEEK_MS),
                    Listings.price(listing.price * weeks)
                )
            }
            // Quick picks, plus a last chip that opens the full calendar.
            val dayChips = addChips(sheet.findViewById(R.id.reserveDays), days.map { it.label } + getString(R.string.chip_pick_date))
            fun bindDays() {
                val selected = days.indexOf(picked).let { if (it < 0 && picked != null) days.size else it }
                Choice.chips(dayChips, selected) { index ->
                    if (index < days.size) {
                        picked = days[index]
                    } else {
                        bindDays()
                        pickDate { day ->
                            dayChips.last().text = day.label
                            picked = day
                            bindDays()
                            bindTimes()
                            if (listing.isRent) bindReturn()
                        }
                        return@chips
                    }
                    // A time that the new day does not offer is cleared.
                    if (picked?.let { listing.availability.isFreeAt(it.weekday, minutesOf(timeLabel)) } != true) timeLabel = null
                    bindTimes()
                    if (listing.isRent) bindReturn()
                }
            }
            if (listing.isRent) {
                sheet.findViewById<View>(R.id.reserveRentSection).isVisible = true
                sheet.findViewById<TextView>(R.id.reserveRentMax).text = getString(
                    R.string.rent_max_format, listing.seller.name.substringBefore(' '),
                    Listings.weeksLabel(this, listing.maxWeeks)
                )
                val weekChips = addChips(sheet.findViewById(R.id.reserveWeeks), weekOptions.map { Listings.weeksLabel(this, it) })
                Choice.chips(weekChips, 0) {
                    weeks = weekOptions[it]
                    bindReturn()
                }
                bindReturn()
            }

            bindDays()
            bindTimes()

            sheet.findViewById<View>(R.id.reserveConfirm).setOnClickListener {
                val pickedDay = picked?.label
                val pickedTime = timeLabel
                if (pickedDay == null || pickedTime == null) {
                    Toast.makeText(this, R.string.toast_pick_day_time, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val summary = if (listing.isRent) {
                    getString(
                        R.string.rent_summary_format, Listings.MEETUP_POINTS[place], pickedDay, pickedTime,
                        Listings.weeksLabel(this, weeks)
                    )
                } else {
                    getString(R.string.sys_reserved_detail, Listings.MEETUP_POINTS[place], pickedDay, pickedTime)
                }
                UserState.setReserved(listing.id, summary, if (listing.isRent) weeks else 0)
                bindActions()
                dialog.dismiss()
                Toast.makeText(this, getString(R.string.toast_reserved, listing.seller.name), Toast.LENGTH_SHORT).show()
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
            onPicked(MeetDay(format.format(millis), Availability.weekdayOf(cal.get(Calendar.DAY_OF_WEEK)), millis))
        }
        picker.show(supportFragmentManager, "date")
    }

    /** Clock picker; times outside the seller's free time that day are refused with a note. */
    private fun pickTime(weekday: Int, onPicked: (String) -> Unit) {
        val start = listing.availability.slots(weekday).firstOrNull() ?: Availability.CAMPUS_OPENS
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
        MaterialAlertDialogBuilder(this)
            .setTitle(if (swap) R.string.cancel_swap_title else R.string.cancel_reservation_title)
            .setMessage(getString(if (swap) R.string.cancel_swap_message else R.string.cancel_reservation_message, first))
            .setNegativeButton(R.string.cancel_reservation_keep, null)
            .setPositiveButton(if (swap) R.string.cancel_swap_confirm else R.string.cancel_reservation_confirm) { _, _ ->
                UserState.cancelReservation(listing.id)
                bindActions()
                notify(getString(if (swap) R.string.toast_swap_withdrawn else R.string.toast_reservation_cancelled))
            }
            .show()
    }

    /** "Offer a swap": pick one of your items to trade for this one. */
    private fun offerSwap() {
        val mine = Listings.myActive()
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

            sheet.findViewById<View>(R.id.swapSend).setOnClickListener {
                if (picked < 0) {
                    Toast.makeText(this, R.string.toast_pick_swap_item, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val summary = getString(R.string.swap_summary_format, mine[picked].title, listing.title)
                UserState.setReserved(listing.id, summary)
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
    data class MeetDay(val label: String, val weekday: Int, val millis: Long)

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
