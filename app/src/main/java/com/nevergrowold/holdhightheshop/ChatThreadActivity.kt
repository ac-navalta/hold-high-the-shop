package com.nevergrowold.holdhightheshop

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.DynamicAnimation
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private var pendingCameraUri: Uri? = null

    /** Implicit intent launcher: pick a photo from gallery to send in chat. */
    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            sendPhotoMessage(uri.toString())
        }
    }

    /** Implicit intent launcher: take a photo with camera to send in chat. */
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = pendingCameraUri ?: return@registerForActivityResult
            sendPhotoMessage(uri.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_chat_thread)
        BarInsets.apply(this)

        seller = Listings.sellerByName(intent.getStringExtra(EXTRA_SELLER))
        UserState.markChatRead(seller.name)
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
                addBubble(request.message, outgoing = false, animate = false)
                if (UserState.status(request) == Deals.ACCEPTED) {
                    addSystemCard(
                        title = getString(R.string.sys_request_accepted),
                        detail = getString(R.string.request_where_when, request.meetup, Rentals.whenLabel(this, request.meetMillis)),
                        meetupPlace = request.meetup,
                        meetupMillis = request.meetMillis,
                    )
                }
            } else if (!intent.getBooleanExtra(EXTRA_RESERVED, false)) {
                addBubble(seller.lastMessage, outgoing = false, animate = false)
            }
        }

        val persisted = UserState.chatMessages(seller.name)
        persisted.forEach { msg ->
            when {
                msg.systemTitle != null -> addSystemCard(
                    title = msg.systemTitle,
                    detail = msg.systemDetail.orEmpty(),
                    meetupPlace = msg.meetupPlace ?: listing.meetup,
                    meetupMillis = msg.meetupMillis,
                )
                msg.photoUri != null -> addPhotoBubble(msg.photoUri, animate = false)
                msg.text != null -> addBubble(msg.text, outgoing = msg.outgoing, animate = false)
            }
        }

        val detail = intent.getStringExtra(EXTRA_RESERVED_DETAIL)
        val alreadyPersisted = detail != null && persisted.any { it.systemDetail == detail }
        if (intent.getBooleanExtra(EXTRA_RESERVED, false) && detail != null && !alreadyPersisted) {
            val sysTitle = getString(if (listing.isSwap) R.string.sys_swap_offered else R.string.sys_reserved)
            val meetMillis = UserState.reservation(listing.id)?.meetMillis
            UserState.appendChatMessage(
                seller.name,
                UserState.ChatMessage(
                    systemTitle = sysTitle,
                    systemDetail = detail,
                    meetupPlace = listing.meetup,
                    meetupMillis = meetMillis,
                ),
            )
            addSystemCard(sysTitle, detail, listing.meetup, meetMillis)
        }
        scrollToEnd(smooth = false)
    }

    /** Tapping the seller's avatar/name in the header opens their profile via explicit Intent. */
    fun openThreadSeller(view: View) {
        Motion.tick(view)
        openSeller(seller)
    }

    /** Opens the listing this conversation is about. */
    fun openThreadListing(view: View) = openListingById(listing.id)

    /** Tapping the sample meetup confirmation card offers Directions (explicit) or Calendar (implicit). */
    fun openSampleMeetupOptions(view: View) {
        val meetMillis = UserState.reservation(listing.id)?.meetMillis ?: Rentals.at(1, 13 * 60 + 30)
        showMeetupActions(listing.meetup, meetMillis)
    }

    private fun showMeetupActions(place: String, meetMillis: Long?) {
        val options = mutableListOf(getString(R.string.chat_meetup_directions))
        if (meetMillis != null) options += getString(R.string.chat_meetup_calendar)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_meetup_sheet_title)
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    startActivity(Intent(this, MapActivity::class.java).putExtra(MapActivity.EXTRA_PLACE, place))
                } else if (meetMillis != null) {
                    val calIntent = AppIntents.calendarEventIntent(
                        title = getString(R.string.calendar_meetup_title, listing.title),
                        location = place,
                        description = getString(R.string.calendar_meetup_desc, seller.name, listing.title),
                        beginMillis = meetMillis,
                    )
                    if (AppIntents.launch(this, calIntent)) {
                        Toast.makeText(this, R.string.toast_calendar_opened, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    /** Send button and keyboard send key. */
    fun sendMessage(view: View) {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        UserState.appendChatMessage(
            seller.name,
            UserState.ChatMessage(text = text, outgoing = true, timeLabel = getString(R.string.msg_sent_now)),
        )
        addBubble(text, outgoing = true, animate = true)
        input.setText("")
        Motion.tick(view)
        // Send glyph flicks diagonally toward the sent bubble, then springs home.
        findViewById<View>(R.id.sendIcon)?.let { icon ->
            val dp = resources.displayMetrics.density
            icon.translationX = 0f
            icon.translationY = 0f
            Motion.spring(icon, DynamicAnimation.TRANSLATION_X, 0f, Motion.Spatial.EXPRESSIVE, startVelocity = 680f * dp)
            Motion.spring(icon, DynamicAnimation.TRANSLATION_Y, 0f, Motion.Spatial.EXPRESSIVE, startVelocity = -400f * dp)
        }
    }

    private fun addBubble(text: String, outgoing: Boolean, animate: Boolean = outgoing) {
        val layout = if (outgoing) R.layout.item_bubble_out else R.layout.item_bubble_in
        val bubble = LayoutInflater.from(this).inflate(layout, messages, false)
        bubble.findViewById<TextView>(R.id.bubbleText).text = text
        messages.addView(bubble)
        if (animate && outgoing) Motion.insert(bubble, fromEnd = true)
        scrollToEnd(smooth = animate && outgoing)
    }

    private fun addSystemCard(title: String, detail: String, meetupPlace: String, meetupMillis: Long?) {
        val card = LayoutInflater.from(this).inflate(R.layout.item_system_card, messages, false)
        card.findViewById<TextView>(R.id.systemTitle).text = title
        card.findViewById<TextView>(R.id.systemDetail).text = detail
        card.isClickable = true
        card.isFocusable = true
        card.setOnClickListener { showMeetupActions(meetupPlace, meetupMillis) }
        messages.addView(card)
    }

    private fun sendPhotoMessage(uriString: String) {
        UserState.appendChatMessage(
            seller.name,
            UserState.ChatMessage(photoUri = uriString, outgoing = true, timeLabel = getString(R.string.msg_sent_now)),
        )
        addPhotoBubble(uriString, animate = true)
        Toast.makeText(this, R.string.toast_photo_sent, Toast.LENGTH_SHORT).show()
    }

    private fun addPhotoBubble(uriString: String, animate: Boolean) {
        val photo = LayoutInflater.from(this).inflate(R.layout.item_bubble_photo, messages, false)
        val image = photo.findViewById<ImageView>(R.id.bubblePhotoImage)
        val placeholder = photo.findViewById<View>(R.id.bubblePhotoPlaceholder)
        val frame = photo.findViewById<View>(R.id.bubblePhotoFrame)
        val uri = Uri.parse(uriString)
        AppIntents.bindImageUri(image, placeholder, uriString)
        frame.setOnClickListener {
            AppIntents.launch(this, AppIntents.viewImageIntent(uri))
        }
        messages.addView(photo)
        if (animate) {
            Motion.insert(photo, fromEnd = true)
            Motion.confirm(photo)
        }
        scrollToEnd(smooth = animate)
    }

    internal fun launchChatGallery() {
        try {
            galleryLauncher.launch(AppIntents.pickImageIntent())
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_no_app_available, Toast.LENGTH_SHORT).show()
        }
    }

    internal fun launchChatCamera() {
        try {
            val (captureIntent, uri) = AppIntents.capturePhotoIntent(this)
            pendingCameraUri = uri
            cameraLauncher.launch(captureIntent)
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_no_camera, Toast.LENGTH_SHORT).show()
        }
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
                val options = arrayOf(
                    getString(R.string.photo_option_gallery),
                    getString(R.string.photo_option_camera),
                )
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.photo_picker_title)
                    .setItems(options) { _, which ->
                        if (which == 0) launchChatGallery() else launchChatCamera()
                    }
                    .show()
            }
            sheet.findViewById<View>(R.id.actionMeetup).setOnClickListener {
                dialog.dismiss()
                Dropdown.show(findViewById(R.id.bottomNav), Listings.MEETUP_POINTS, icons = listOf(R.drawable.ic_location)) {
                    val msg = getString(R.string.msg_meetup_format, Listings.MEETUP_POINTS[it])
                    UserState.appendChatMessage(
                        seller.name,
                        UserState.ChatMessage(text = msg, outgoing = true, timeLabel = getString(R.string.msg_sent_now)),
                    )
                    addBubble(msg, outgoing = true, animate = true)
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
                    val msg = getString(R.string.msg_time_format, time)
                    UserState.appendChatMessage(
                        seller.name,
                        UserState.ChatMessage(text = msg, outgoing = true, timeLabel = getString(R.string.msg_sent_now)),
                    )
                    addBubble(msg, outgoing = true, animate = true)
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
