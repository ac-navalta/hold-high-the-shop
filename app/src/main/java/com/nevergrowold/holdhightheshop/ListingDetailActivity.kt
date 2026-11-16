package com.nevergrowold.holdhightheshop

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

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
        bindReserveButton()
    }

    private fun bind() {
        val seller = listing.seller
        findViewById<TextView>(R.id.detailCondition).text =
            getString(R.string.condition_badge_format, Listings.conditionLabel(this, listing.condition))
        findViewById<TextView>(R.id.detailTitle).text = listing.title
        findViewById<TextView>(R.id.detailPrice).text = Listings.price(listing.price)
        findViewById<TextView>(R.id.detailDesc).text = listing.description
        findViewById<TextView>(R.id.sellerInitials).text = seller.initials
        findViewById<TextView>(R.id.sellerName).text = seller.name
        findViewById<TextView>(R.id.sellerMeta).text = getString(R.string.seller_meta_format, seller.program, seller.year)
        findViewById<TextView>(R.id.statRating).text = seller.rating
        findViewById<TextView>(R.id.statOnTime).text = seller.onTime
        findViewById<TextView>(R.id.statCompleted).text = seller.completed.toString()
        findViewById<TextView>(R.id.meetupPlace).text = listing.meetup
    }

    private fun bindSaved() {
        val saved = UserState.isSaved(this, listing.id)
        findViewById<ImageView>(R.id.saveIcon).apply {
            setImageResource(if (saved) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark)
            setColorFilter(getColor(if (saved) R.color.uc_green else R.color.text_primary))
            contentDescription = getString(if (saved) R.string.cd_unsave else R.string.cd_save)
        }
    }

    private fun bindReserveButton() {
        val price = Listings.price(listing.price)
        findViewById<TextView>(R.id.btnReserve).text = when {
            UserState.isReserved(this, listing.id) -> getString(R.string.btn_reserved)
            listing.type == 1 -> getString(R.string.btn_rent_format, price)
            listing.type == 2 -> getString(R.string.btn_swap)
            else -> getString(R.string.btn_reserve_format, price)
        }
    }

    /** Bookmark button on the photo. */
    fun toggleSave(view: View) {
        val nowSaved = !UserState.isSaved(this, listing.id)
        UserState.setSaved(this, listing.id, nowSaved)
        bindSaved()
        if (nowSaved) {
            notify(getString(R.string.toast_saved), getString(R.string.action_view)) { openSaved(view) }
        } else {
            notify(getString(R.string.toast_unsaved), getString(R.string.action_undo)) {
                UserState.setSaved(this, listing.id, true)
                bindSaved()
            }
        }
    }

    /** Seller card: opens the seller's profile. */
    fun openSellerProfile(view: View) = openSeller(listing.seller)

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
                val query = Uri.encode("${listing.meetup}, University of the Cordilleras, Baguio City")
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$query")))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, R.string.toast_no_maps, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Reserve button: pick a meetup point, day and time, then confirm. */
    fun reserve(view: View) {
        if (UserState.isReserved(this, listing.id)) {
            openChatWith(listing.seller, listing.id)
            return
        }

        val days = upcomingDays()
        val times = listOf("9:00 AM", "10:30 AM", "1:30 PM", "3:00 PM")
        var place = Listings.MEETUP_POINTS.indexOf(listing.meetup).coerceAtLeast(0)
        var day = 0
        var time = -1

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

            val dayChips = addChips(sheet.findViewById(R.id.reserveDays), days)
            Choice.chips(dayChips, day) { day = it }
            val timeChips = addChips(sheet.findViewById(R.id.reserveTimes), times)
            Choice.chips(timeChips, time) { time = it }

            sheet.findViewById<View>(R.id.reserveConfirm).setOnClickListener {
                if (time < 0) {
                    Toast.makeText(this, R.string.toast_pick_day_time, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                UserState.setReserved(this, listing.id)
                bindReserveButton()
                dialog.dismiss()
                Toast.makeText(this, getString(R.string.toast_reserved, listing.seller.name), Toast.LENGTH_SHORT).show()
                startActivity(
                    Intent(this, ChatThreadActivity::class.java)
                        .putExtra(EXTRA_SELLER, listing.seller.name)
                        .putExtra(EXTRA_LISTING, listing.id)
                        .putExtra(EXTRA_RESERVED, true)
                        .putExtra(
                            ChatThreadActivity.EXTRA_RESERVED_DETAIL,
                            getString(R.string.sys_reserved_detail, Listings.MEETUP_POINTS[place], days[day], times[time])
                        )
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
