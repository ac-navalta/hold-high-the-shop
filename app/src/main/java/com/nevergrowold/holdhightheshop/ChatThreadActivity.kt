package com.nevergrowold.holdhightheshop

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.DynamicAnimation
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat

/**
 * One conversation. Opened from the chat list, from a seller's profile, or
 * right after reserving a listing.
 */
class ChatThreadActivity : BaseActivity() {

    private lateinit var seller: Seller
    private lateinit var listing: Listing
    private lateinit var messages: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendButton: View

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
        sendButton = findViewById(R.id.btnSend)

        bindHeader()
        bindHistory()
        updateSendReady(ready = false, animate = false)

        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                updateSendReady(ready = !s.isNullOrBlank(), animate = true)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage(sendButton)
                true
            } else {
                false
            }
        }
    }

    /** Send button softens slightly while empty and springs vivid when ready to send. */
    private fun updateSendReady(ready: Boolean, animate: Boolean) {
        val targetAlpha = if (ready) 1f else 0.48f
        val targetScale = if (ready) 1f else 0.94f
        if (!animate) {
            sendButton.alpha = targetAlpha
            sendButton.scaleX = targetScale
            sendButton.scaleY = targetScale
            return
        }
        Motion.spring(sendButton, DynamicAnimation.ALPHA, targetAlpha, Motion.Effects.FAST)
        Motion.springScale(sendButton, targetScale, Motion.Spatial.SNAPPY)
    }

    private fun bindHeader() {
        findViewById<TextView>(R.id.threadInitials).text = seller.initials
        findViewById<TextView>(R.id.threadName).text = seller.name
        findViewById<View>(R.id.threadDot).isVisible = seller.online
        findViewById<TextView>(R.id.threadStatus).text = getString(
            R.string.thread_status_format,
            getString(if (seller.online) R.string.status_active else R.string.status_away),
            seller.program,
        )
        input.hint = getString(R.string.hint_message_format, seller.name.substringBefore(' '))

        findViewById<TextView>(R.id.stripKicker).setText(Listings.roleKicker(listing))
        findViewById<TextView>(R.id.stripTitle).text = listing.title
        findViewById<TextView>(R.id.stripPrice).text = Listings.priceLabel(this, listing)
    }

    /** The design's sample history belongs to Paolo's Casio conversation. */
    private fun isSampleThread() = seller.name == "Paolo Mendoza" && listing.id == Listings.DEFAULT_ID

    private fun bindHistory() {
        if (!isSampleThread()) {
            while (messages.childCount > 1) messages.removeViewAt(1)
            val request = UserState.requestFrom(seller, listing.id)
            if (request != null) {
                addBubble(request.message, outgoing = false)
                if (UserState.status(request) == Deals.ACCEPTED) {
                    val card = LayoutInflater.from(this).inflate(R.layout.item_system_card, messages, false)
                    card.findViewById<TextView>(R.id.systemTitle).setText(R.string.sys_request_accepted)
                    card.findViewById<TextView>(R.id.systemDetail).text = getString(
                        R.string.request_where_when, request.meetup, Rentals.whenLabel(this, request.meetMillis)
                    )
                    messages.addView(card)
                }
            } else if (!intent.getBooleanExtra(EXTRA_RESERVED, false)) {
                addBubble(seller.lastMessage, outgoing = false)
            }
        }
        val detail = intent.getStringExtra(EXTRA_RESERVED_DETAIL)
        if (intent.getBooleanExtra(EXTRA_RESERVED, false) && detail != null) {
            val card = LayoutInflater.from(this).inflate(R.layout.item_system_card, messages, false)
            card.findViewById<TextView>(R.id.systemTitle).setText(
                if (listing.isSwap) R.string.sys_swap_offered else R.string.sys_reserved
            )
            card.findViewById<TextView>(R.id.systemDetail).text = detail
            messages.addView(card)
            scrollToEnd(smooth = false)
        }
    }

    /** Opens the listing this conversation is about. */
    fun openThreadListing(view: View) = openListingById(listing.id)

    /** Send button and keyboard send key. */
    fun sendMessage(view: View) {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        addBubble(text, outgoing = true)
        input.setText("")
        Motion.tick(view)
        // Send glyph flicks diagonally toward the sent bubble, then springs home.
        findViewById<View>(R.id.sendIcon)?.let { icon ->
            val dp = resources.displayMetrics.density
            icon.translationX = 0f
            icon.translationY = 0f
            Motion.spring(icon, DynamicAnimation.TRANSLATION_X, 0f, Motion.Spatial.SNAPPY, startVelocity = 620f * dp)
            Motion.spring(icon, DynamicAnimation.TRANSLATION_Y, 0f, Motion.Spatial.SNAPPY, startVelocity = -360f * dp)
        }
    }

    private fun addBubble(text: String, outgoing: Boolean) {
        val layout = if (outgoing) R.layout.item_bubble_out else R.layout.item_bubble_in
        val bubble = LayoutInflater.from(this).inflate(layout, messages, false)
        bubble.findViewById<TextView>(R.id.bubbleText).text = text
        messages.addView(bubble)
        if (outgoing) Motion.insert(bubble, fromEnd = true)
        scrollToEnd(smooth = outgoing)
    }

    /** Placeholder photo message. Real photos come with the backend. */
    private fun addPhoto() {
        val photo = LayoutInflater.from(this).inflate(R.layout.item_bubble_photo, messages, false)
        messages.addView(photo)
        Motion.insert(photo, fromEnd = true)
        Motion.confirm(photo)
        scrollToEnd(smooth = true)
    }

    private fun scrollToEnd(smooth: Boolean) {
        val scroll = findViewById<ScrollView>(R.id.messagesScroll)
        scroll.post {
            val targetY = (messages.bottom - scroll.height).coerceAtLeast(0)
            if (smooth && Motion.enabled(this)) scroll.smoothScrollTo(0, targetY)
            else scroll.scrollTo(0, targetY)
        }
    }

    /** The + button: rotates into an X while the actions sheet is open. */
    fun openChatActions(view: View) {
        Motion.tick(view)
        val plus = findViewById<View>(R.id.attachIcon)
        plus?.let { Motion.rotateTo(it, 45f) }
        Sheets.show(
            this,
            R.layout.sheet_chat_actions,
            onDismiss = { plus?.let { Motion.rotateTo(it, 0f) } },
        ) { sheet, dialog ->
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
    }
}
