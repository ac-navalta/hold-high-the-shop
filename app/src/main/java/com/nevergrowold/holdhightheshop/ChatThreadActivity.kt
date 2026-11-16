package com.nevergrowold.holdhightheshop

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat

/**
 * One conversation. Opened from the chat list, from a seller's profile, or
 * right after reserving a listing. The Paolo thread keeps the sample history
 * from the design; other people start with their last message from the list.
 */
class ChatThreadActivity : BaseActivity() {

    private lateinit var seller: Seller
    private lateinit var listing: Listing
    private lateinit var messages: LinearLayout
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_chat_thread)
        BarInsets.apply(this)

        seller = Listings.sellerByName(intent.getStringExtra(EXTRA_SELLER))
        listing = intent.getStringExtra(EXTRA_LISTING)?.let { Listings.byId(it) }
            ?: Listings.CHAT_LISTINGS[seller]?.let { Listings.byId(it) }
            ?: Listings.bySeller(seller).firstOrNull()
            ?: Listings.byId(null)
        messages = findViewById(R.id.messageList)
        input = findViewById(R.id.inputMessage)

        bindHeader()
        bindHistory()

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage(input)
                true
            } else {
                false
            }
        }
    }

    private fun bindHeader() {
        findViewById<TextView>(R.id.threadInitials).text = seller.initials
        findViewById<TextView>(R.id.threadName).text = seller.name
        findViewById<View>(R.id.threadDot).isVisible = seller.online
        findViewById<TextView>(R.id.threadStatus).text = getString(
            R.string.thread_status_format,
            getString(if (seller.online) R.string.status_active else R.string.status_away),
            seller.program
        )
        input.hint = getString(R.string.hint_message_format, seller.name.substringBefore(' '))

        // Say who is giving and who is getting, e.g. YOU'RE BORROWING.
        findViewById<TextView>(R.id.stripKicker).setText(Listings.roleKicker(listing))
        findViewById<TextView>(R.id.stripTitle).text = listing.title
        findViewById<TextView>(R.id.stripPrice).text = Listings.priceLabel(this, listing)
    }

    /** The design's sample history belongs to Paolo's Casio conversation. */
    private fun isSampleThread() = seller.name == "Paolo Mendoza" && listing.id == Listings.DEFAULT_ID

    private fun bindHistory() {
        if (!isSampleThread()) {
            // Keep only the "TODAY" label, then start from the last message in the list.
            while (messages.childCount > 1) messages.removeViewAt(1)
            if (!intent.getBooleanExtra(EXTRA_RESERVED, false)) addBubble(seller.lastMessage, outgoing = false)
        }
        val detail = intent.getStringExtra(EXTRA_RESERVED_DETAIL)
        if (intent.getBooleanExtra(EXTRA_RESERVED, false) && detail != null) {
            val card = LayoutInflater.from(this).inflate(R.layout.item_system_card, messages, false)
            card.findViewById<TextView>(R.id.systemTitle).setText(R.string.sys_reserved)
            card.findViewById<TextView>(R.id.systemDetail).text = detail
            messages.addView(card)
            scrollToEnd()
        }
        intent.getStringExtra(EXTRA_SHARE_TEXT)?.let { addBubble(it, outgoing = true) }
    }

    /** Opens the listing this conversation is about. */
    fun openThreadListing(view: View) = openListingById(listing.id)

    /** Send button and the keyboard's send key. */
    fun sendMessage(view: View) {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        addBubble(text, outgoing = true)
        input.setText("")
    }

    private fun addBubble(text: String, outgoing: Boolean) {
        val layout = if (outgoing) R.layout.item_bubble_out else R.layout.item_bubble_in
        val bubble = LayoutInflater.from(this).inflate(layout, messages, false)
        bubble.findViewById<TextView>(R.id.bubbleText).text = text
        messages.addView(bubble)
        scrollToEnd()
    }

    /** Placeholder photo message. Real photos come with the backend. */
    private fun addPhoto() {
        messages.addView(LayoutInflater.from(this).inflate(R.layout.item_bubble_photo, messages, false))
        scrollToEnd()
    }

    private fun scrollToEnd() {
        val scroll = findViewById<ScrollView>(R.id.messagesScroll)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** The + button: things you can share besides text. */
    fun openChatActions(view: View) {
        Sheets.show(this, R.layout.sheet_chat_actions) { sheet, dialog ->
            sheet.findViewById<View>(R.id.actionPhoto).setOnClickListener {
                dialog.dismiss()
                addPhoto()
            }
            sheet.findViewById<View>(R.id.actionMeetup).setOnClickListener {
                dialog.dismiss()
                Dropdown.show(findViewById(R.id.bottomNav), Listings.MEETUP_POINTS, icons = listOf(R.drawable.ic_location)) {
                    addBubble(getString(R.string.msg_meetup_format, Listings.MEETUP_POINTS[it]), outgoing = true)
                }
            }
            sheet.findViewById<View>(R.id.actionTime).setOnClickListener {
                dialog.dismiss()
                val picker = MaterialTimePicker.Builder()
                    .setTimeFormat(TimeFormat.CLOCK_12H)
                    .setHour(13)
                    .setMinute(30)
                    .setTitleText(R.string.action_time)
                    .build()
                picker.addOnPositiveButtonClickListener {
                    val hour12 = if (picker.hour % 12 == 0) 12 else picker.hour % 12
                    val amPm = if (picker.hour < 12) "AM" else "PM"
                    val time = "%d:%02d %s".format(hour12, picker.minute, amPm)
                    addBubble(getString(R.string.msg_time_format, time), outgoing = true)
                }
                picker.show(supportFragmentManager, "time")
            }
            sheet.findViewById<View>(R.id.actionListing).setOnClickListener {
                dialog.dismiss()
                openListingById(listing.id)
            }
        }
    }

    companion object {
        const val EXTRA_RESERVED_DETAIL = "reserved_detail"

        /** A listing shared from its detail screen, sent as the newest message. */
        const val EXTRA_SHARE_TEXT = "share_text"
    }
}
