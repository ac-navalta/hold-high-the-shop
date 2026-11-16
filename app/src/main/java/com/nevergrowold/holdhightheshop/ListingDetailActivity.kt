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
        findViewById<TextView>(R.id.detailPrice).text = Listings.price(listing.price)
        findViewById<TextView>(R.id.detailDesc).text = listing.description
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
        findViewById<View>(R.id.reservationCard).isVisible = !isMine && reservation != null
        findViewById<TextView>(R.id.reservationDetail).text = reservation
    }

    private fun bindReserveButton() {
        val price = Listings.price(listing.price)
        findViewById<TextView>(R.id.btnReserve).text = when {
            UserState.isReserved(listing.id) -> getString(R.string.btn_reserved)
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
        }
    }

    /** Reserve button: pick a meetup point, day and time, then confirm. */
    fun reserve(view: View) {
        if (UserState.isReserved(listing.id)) {
            openChatWith(listing.seller, listing.id)
            return
        }

        val days = upcomingDays()
        val times = listOf("9:00 AM", "10:30 AM", "1:30 PM", "3:00 PM")
        var place = Listings.MEETUP_POINTS.indexOf(listing.meetup).coerceAtLeast(0)
        var dayLabel: String? = days[0]
        var timeLabel: String? = null

        Sheets.show(this, R.layout.sheet_reserve) { sheet, dialog ->
            sheet.findViewById<TextView>(R.id.reserveItemTitle).text = listing.title
            sheet.findViewById<TextView>(R.id.reserveItemSeller).text = listing.seller.name
            sheet.findViewById<TextView>(R.id.reserveItemPrice).text = Listings.price(listing.price)

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

    /** Calendar for any future weekday. Weekends are refused with a short note. */
    private fun pickDate(onPicked: (String) -> Unit) {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.pick_date_title)
            .setCalendarConstraints(
                CalendarConstraints.Builder().setValidator(DateValidatorPointForward.now()).build()
            )
            .build()
        picker.addOnPositiveButtonClickListener { millis ->
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
            val weekday = cal.get(Calendar.DAY_OF_WEEK)
            if (weekday == Calendar.SATURDAY || weekday == Calendar.SUNDAY) {
                Toast.makeText(this, R.string.toast_weekdays_only, Toast.LENGTH_SHORT).show()
            } else {
                val format = SimpleDateFormat("EEE, MMM d", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                onPicked(format.format(cal.time))
            }
        }
        picker.show(supportFragmentManager, "date")
    }

    /** Clock picker limited to the meetup desks' hours, 9:00 AM to 4:00 PM. */
    private fun pickTime(onPicked: (String) -> Unit) {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_12H)
            .setHour(13)
            .setMinute(30)
            .setTitleText(R.string.pick_time_title)
            .build()
        picker.addOnPositiveButtonClickListener {
            val minutes = picker.hour * 60 + picker.minute
            if (minutes < 9 * 60 || minutes > 16 * 60) {
                Toast.makeText(this, R.string.toast_office_hours, Toast.LENGTH_SHORT).show()
            } else {
                val hour12 = if (picker.hour % 12 == 0) 12 else picker.hour % 12
                onPicked("%d:%02d %s".format(hour12, picker.minute, if (picker.hour < 12) "AM" else "PM"))
            }
        }
        picker.show(supportFragmentManager, "time")
    }

    /** "Cancel reservation" on the reservation card. */
    fun cancelReservation(view: View) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cancel_reservation_title)
            .setMessage(getString(R.string.cancel_reservation_message, listing.seller.name.substringBefore(' ')))
            .setNegativeButton(R.string.cancel_reservation_keep, null)
            .setPositiveButton(R.string.cancel_reservation_confirm) { _, _ ->
                UserState.cancelReservation(listing.id)
                bindActions()
                notify(getString(R.string.toast_reservation_cancelled))
            }
            .show()
    }

    private fun addChips(container: LinearLayout, labels: List<String>): List<TextView> =
        labels.map { label ->
            val chip = LayoutInflater.from(this).inflate(R.layout.item_chip, container, false) as TextView
            chip.text = label
            container.addView(chip)
            chip
        }

    /** The next five weekdays, since the meetup points are open Mon–Fri. */
    private fun upcomingDays(): List<String> {
        val format = SimpleDateFormat("EEE d", Locale.US)
        val cal = Calendar.getInstance()
        val out = mutableListOf<String>()
        var offset = 0
        while (out.size < 5) {
            val weekday = cal.get(Calendar.DAY_OF_WEEK)
            if (weekday != Calendar.SATURDAY && weekday != Calendar.SUNDAY) {
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
