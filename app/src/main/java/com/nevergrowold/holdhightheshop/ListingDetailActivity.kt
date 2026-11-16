package com.nevergrowold.holdhightheshop

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
        findViewById<View>(R.id.btnReport).isVisible = !isMine
        findViewById<View>(R.id.btnReserve).isVisible = !isMine
        findViewById<View>(R.id.btnEdit).isVisible = isMine && !closed
        findViewById<View>(R.id.btnMarkDone).isVisible = isMine && !closed
        findViewById<View>(R.id.closedState).isVisible = isMine && closed
        findViewById<TextView>(R.id.btnMarkDone).setText(
            when (listing.type) {
                1 -> R.string.btn_mark_rented
                2 -> R.string.btn_mark_swapped
                else -> R.string.btn_mark_sold
            }
        )
        if (!isMine) bindReserveButton()

        val reservation = UserState.reservation(listing.id)
        findViewById<View>(R.id.btnCancelReservation).isVisible = !isMine && reservation != null
        findViewById<TextView>(R.id.btnCancelReservation).setText(
            if (listing.isSwap) R.string.btn_withdraw_short else R.string.btn_cancel_short
        )
        findViewById<TextView>(R.id.reservationTitle).setText(
            if (listing.isSwap) R.string.swap_card_title else R.string.reservation_card_title
        )
        findViewById<View>(R.id.reservationCard).isVisible = !isMine && reservation != null
        findViewById<TextView>(R.id.reservationDetail).text = reservation
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

    /** Share button: send the listing to one of your chats (in-app only for now). */
    fun shareListing(view: View) {
        Sheets.show(this, R.layout.sheet_share) { sheet, dialog ->
            sheet.findViewById<TextView>(R.id.shareItemTitle).text = listing.title
            sheet.findViewById<TextView>(R.id.shareItemPrice).text = Listings.priceLabel(this, listing)
            val people = sheet.findViewById<LinearLayout>(R.id.sharePeople)
            Listings.SELLERS.filter { it != listing.seller }.forEach { person ->
                val row = LayoutInflater.from(this).inflate(R.layout.item_share_person, people, false)
                row.findViewById<TextView>(R.id.personInitials).text = person.initials
                row.findViewById<TextView>(R.id.personName).text = person.name
                row.findViewById<TextView>(R.id.personProgram).text = person.program
                row.setOnClickListener {
                    dialog.dismiss()
                    Toast.makeText(this, getString(R.string.toast_shared, person.name), Toast.LENGTH_SHORT).show()
                    startActivity(
                        Intent(this, ChatThreadActivity::class.java)
                            .putExtra(EXTRA_SELLER, person.name)
                            .putExtra(
                                ChatThreadActivity.EXTRA_SHARE_TEXT,
                                getString(R.string.share_message_format, listing.title, Listings.priceLabel(this, listing))
                            )
                    )
                }
                people.addView(row)
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
        val times = listing.availability.slots().map { Availability.time(it) }
        var place = Listings.MEETUP_POINTS.indexOf(listing.meetup).coerceAtLeast(0)
        var dayLabel: String? = days.firstOrNull()
        var timeLabel: String? = null

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

            // Quick picks, plus a last chip that opens the full date or time picker.
            val dayChips = addChips(sheet.findViewById(R.id.reserveDays), days + getString(R.string.chip_pick_date))
            var day = 0
            fun bindDays() {
                Choice.chips(dayChips, day) { picked ->
                    if (picked < days.size) {
                        day = picked
                        dayLabel = days[picked]
                    } else {
                        bindDays() // keep the old choice until a date is confirmed
                        pickDate { label ->
                            dayChips.last().text = label
                            day = days.size
                            dayLabel = label
                            bindDays()
                        }
                    }
                }
            }
            bindDays()

            val timeChips = addChips(sheet.findViewById(R.id.reserveTimes), times + getString(R.string.chip_pick_time))
            var time = -1
            fun bindTimes() {
                Choice.chips(timeChips, time) { picked ->
                    if (picked < times.size) {
                        time = picked
                        timeLabel = times[picked]
                    } else {
                        bindTimes()
                        pickTime { label ->
                            timeChips.last().text = label
                            time = times.size
                            timeLabel = label
                            bindTimes()
                        }
                    }
                }
            }
            bindTimes()

            sheet.findViewById<View>(R.id.reserveConfirm).setOnClickListener {
                val pickedDay = dayLabel
                val pickedTime = timeLabel
                if (pickedDay == null || pickedTime == null) {
                    Toast.makeText(this, R.string.toast_pick_day_time, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val summary = getString(R.string.sys_reserved_detail, Listings.MEETUP_POINTS[place], pickedDay, pickedTime)
                UserState.setReserved(listing.id, summary)
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
    private fun pickDate(onPicked: (String) -> Unit) {
        val free = listing.availability
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.pick_date_title)
            .setCalendarConstraints(
                // Days the seller is not free are greyed out.
                CalendarConstraints.Builder().setValidator(
                    CompositeDateValidator.allOf(
                        listOf(DateValidatorPointForward.now(), FreeDaysValidator(free.days.toIntArray()))
                    )
                ).build()
            )
            .build()
        picker.addOnPositiveButtonClickListener { millis ->
            val format = SimpleDateFormat("EEE, MMM d", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            onPicked(format.format(millis))
        }
        picker.show(supportFragmentManager, "date")
    }

    /** Clock picker; times outside the seller's window are refused with a note. */
    private fun pickTime(onPicked: (String) -> Unit) {
        val free = listing.availability
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_12H)
            .setHour(free.from / 60)
            .setMinute(free.from % 60)
            .setTitleText(R.string.pick_time_title)
            .build()
        picker.addOnPositiveButtonClickListener {
            val minutes = picker.hour * 60 + picker.minute
            if (!free.isFreeAt(minutes)) {
                Toast.makeText(this, freeLabel(), Toast.LENGTH_SHORT).show()
            } else {
                onPicked(Availability.time(minutes))
            }
        }
        picker.show(supportFragmentManager, "time")
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
    /** The next five days the seller is free (up to four weeks ahead). */
    private fun upcomingDays(): List<String> {
        val format = SimpleDateFormat("EEE d", Locale.US)
        val cal = Calendar.getInstance()
        val out = mutableListOf<String>()
        var offset = 0
        while (out.size < 5 && offset < 28) {
            if (listing.availability.isFreeOn(Availability.weekdayOf(cal.get(Calendar.DAY_OF_WEEK)))) {
                out += when (offset) {
                    0 -> getString(R.string.day_today_label)
                    1 -> getString(R.string.day_tomorrow_label)
                    else -> format.format(cal.time)
                }
            }
            cal.add(Calendar.DAY_OF_MONTH, 1)
            offset++
        }
        return out
    }
}
