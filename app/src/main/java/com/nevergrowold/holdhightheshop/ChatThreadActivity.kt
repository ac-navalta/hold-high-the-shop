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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.nevergrowold.holdhightheshop.data.AppRepository
import com.nevergrowold.holdhightheshop.data.ProposalPayload
import com.nevergrowold.holdhightheshop.data.ProposalStatus
import com.nevergrowold.holdhightheshop.data.ProposalType
import com.nevergrowold.holdhightheshop.media.ImagePipeline
import com.nevergrowold.holdhightheshop.system.MarketplaceBroadcasts
import com.nevergrowold.holdhightheshop.system.MarketplaceEventReceiver
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Per-listing conversation thread with:
 * - Zero-permission Photo Picker (`PickVisualMedia`) + Camera (`FileProvider`, `onSaveInstanceState`-safe)
 * - Context-aware `+` menu:
 *   - **Photo** (always)
 *   - **Propose meetup change** (ONLY when listing has an active ACCEPTED deal!)
 *   - **Propose return extension** (ONLY when listing has an active ON_LOAN rental!)
 *   - **View listing** (always)
 * - Two-party interactive Proposal Cards (Confirm / Decline / Withdraw) that atomically update
 *   the deal schedule / loan due date AND reschedule AlarmManager reminders!
 * - Header overflow menu for **Block student**, **Safe meetup point**, and **View listing**.
 */
class ChatThreadActivity : BaseActivity() {

    private lateinit var threadId: String
    private lateinit var partner: Seller
    private lateinit var listing: Listing
    private lateinit var messages: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendButton: View

    private var pendingCameraUri: Uri? = null

    private val eventReceiver = MarketplaceEventReceiver { _, _ ->
        renderMessages(scrollToBottom = false)
    }

    /** Zero-permission Android Photo Picker for sending a chat photo. */
    private val photoPickerLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@registerForActivityResult
            ingestAndSendPhoto(uri)
        }

    /** Camera capture via scoped FileProvider. */
    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = pendingCameraUri ?: return@registerForActivityResult
                ingestAndSendPhoto(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_chat_thread)
        BarInsets.apply(this)

        savedInstanceState?.getString(STATE_CAMERA_URI)?.let { pendingCameraUri = Uri.parse(it) }

        resolveThreadContext()
        UserState.markChatRead(threadId)

        messages = findViewById(R.id.messageList)
        input = findViewById(R.id.inputMessage)
        sendButton = findViewById(R.id.btnSend)

        bindHeader()
        renderMessages(scrollToBottom = true)
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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppRepository.stateVersion.collect {
                    Listings.byIdOrNull(listing.id)?.let { listing = it }
                    renderMessages(scrollToBottom = false)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppRepository.attachThreadListener(threadId)
        MarketplaceBroadcasts.registerInternalReceiver(
            this,
            eventReceiver,
            MarketplaceBroadcasts.ACTION_STATE_CHANGED,
            MarketplaceBroadcasts.ACTION_NEW_CHAT_MESSAGE,
            MarketplaceBroadcasts.ACTION_PROPOSAL_UPDATED,
            MarketplaceBroadcasts.ACTION_DEAL_UPDATED,
        )
    }

    override fun onStop() {
        runCatching { unregisterReceiver(eventReceiver) }
        AppRepository.detachThreadListener(threadId)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingCameraUri?.let { outState.putString(STATE_CAMERA_URI, it.toString()) }
    }

    private fun resolveThreadContext() {
        val explicitThreadId = intent.getStringExtra(EXTRA_THREAD_ID)
        val existingThread = explicitThreadId?.let { AppRepository.threadById(it) }
        if (existingThread != null) {
            threadId = existingThread.threadId
            listing = Listings.byId(existingThread.listingId)
            val partnerUid = existingThread.partnerUid(UserState.currentUid)
            partner = Listings.sellerByUid(partnerUid) ?: Seller(
                name = existingThread.partnerName(UserState.currentUid).ifBlank { "UC Student" },
                initials = UserState.UserProfile(name = existingThread.partnerName(UserState.currentUid)).initials,
                program = existingThread.partnerProgram(UserState.currentUid).ifBlank { "UC" },
                year = "UCian",
                rating = "New",
                onTime = "\u2013",
                completed = 0,
                lastMessage = "",
                online = true,
                uid = partnerUid,
            )
            return
        }

        val listingId = intent.getStringExtra(EXTRA_LISTING_ID) ?: intent.getStringExtra(EXTRA_LISTING)
        listing = Listings.byId(listingId)
        val partnerUidExtra = intent.getStringExtra(EXTRA_SELLER_UID)
        val partnerNameExtra = intent.getStringExtra(EXTRA_SELLER)
        partner = Listings.sellerByUid(partnerUidExtra)
            ?: if (!partnerNameExtra.isNullOrBlank()) Listings.sellerByName(partnerNameExtra) else Listings.sellerOf(listing)

        val thread = UserState.threadFor(listing.id, partner.uid)
        threadId = thread.threadId
    }

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
        findViewById<TextView>(R.id.threadInitials).text = partner.initials
        findViewById<TextView>(R.id.threadName).text = partner.name
        findViewById<View>(R.id.threadDot).isVisible = partner.online
        findViewById<TextView>(R.id.threadStatus).text = getString(
            R.string.thread_status_format,
            getString(if (partner.online) R.string.status_active else R.string.status_away),
            partner.program,
        )
        input.hint = getString(R.string.hint_message_format, partner.first)

        findViewById<TextView>(R.id.stripKicker).setText(Listings.roleKicker(listing))
        findViewById<TextView>(R.id.stripTitle).text = listing.title
        findViewById<TextView>(R.id.stripPrice).text = Listings.priceLabel(this, listing)
        AppIntents.bindImageUri(
            findViewById(R.id.stripPhoto),
            findViewById(R.id.stripPhotoPlaceholder),
            listing.photoUris.firstOrNull(),
        )
    }

    private fun renderMessages(scrollToBottom: Boolean) {
        val history = UserState.chatMessages(threadId)
        messages.removeAllViews()

        if (history.isEmpty()) {
            val hint = TextView(this).apply {
                text = "Start coordinating with ${partner.first} about ${listing.title}."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setPadding(dp(this@ChatThreadActivity, 16), dp(this@ChatThreadActivity, 32), dp(this@ChatThreadActivity, 16), 0)
            }
            messages.addView(hint)
            return
        }

        history.forEach { msg ->
            when {
                msg.proposal != null -> addProposalCard(msg.proposal)
                msg.systemTitle != null -> addSystemCard(
                    title = msg.systemTitle,
                    detail = msg.systemDetail.orEmpty(),
                    meetupPlace = msg.meetupPlace ?: listing.meetup,
                    meetupMillis = msg.meetupMillis,
                )
                msg.photoUri != null -> addPhotoBubble(msg.photoUri, animate = false)
                !msg.text.isNullOrBlank() -> addBubble(msg.text, outgoing = msg.outgoing, animate = false)
            }
        }
        if (scrollToBottom) scrollToEnd(smooth = false)
    }

    fun openThreadSeller(view: View) {
        Motion.tick(view)
        openSeller(partner)
    }

    fun openThreadListing(view: View) = openListingById(listing.id)

    /** Header 3-dot overflow: Safe meetup spot, View listing, Block student. */
    fun openChatOverflow(view: View) {
        Motion.tick(view)
        val options = arrayOf(
            getString(R.string.action_listing),
            getString(R.string.chat_meetup_directions),
            getString(R.string.menu_block_user),
        )
        MaterialAlertDialogBuilder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openListingById(listing.id)
                    1 -> showSafeMeetupSheet(listing.meetup, listing.meetupNote)
                    2 -> confirmBlockPartner()
                }
            }
            .show()
    }

    private fun confirmBlockPartner() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.block_user_title, partner.first))
            .setMessage(R.string.block_user_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.btn_block) { _, _ ->
                UserState.blockUser(partner.uid)
                Toast.makeText(this, getString(R.string.toast_user_blocked, partner.first), Toast.LENGTH_SHORT).show()
                finish()
            }
            .show()
    }

    private fun showMeetupActions(place: String, meetMillis: Long?) {
        val options = mutableListOf(getString(R.string.chat_meetup_directions))
        if (meetMillis != null) options += getString(R.string.chat_meetup_calendar)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_meetup_sheet_title)
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    showSafeMeetupSheet(place, listing.meetupNote)
                } else if (meetMillis != null) {
                    val calIntent = AppIntents.calendarEventIntent(
                        title = getString(R.string.calendar_meetup_title, listing.title),
                        location = place,
                        description = getString(R.string.calendar_meetup_desc, partner.name, listing.title),
                        beginMillis = meetMillis,
                    )
                    if (AppIntents.launch(this, calIntent)) {
                        Toast.makeText(this, R.string.toast_calendar_opened, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    fun sendMessage(view: View) {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        UserState.appendChatMessage(
            threadId,
            UserState.ChatMessage(
                senderUid = UserState.currentUid,
                text = text,
                outgoing = true,
                timeLabel = getString(R.string.msg_sent_now),
            ),
        )
        input.setText("")
        renderMessages(scrollToBottom = true)
        Motion.tick(view)
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

    /** Renders an interactive two-party Meetup Change or Return Extension Proposal card. */
    private fun addProposalCard(proposal: ProposalPayload) {
        val card = LayoutInflater.from(this).inflate(R.layout.item_proposal_card, messages, false)
        val isMeetup = proposal.proposalType == ProposalType.MEETUP_CHANGE
        card.findViewById<TextView>(R.id.proposalKicker).setText(
            if (isMeetup) R.string.proposal_meetup_kicker else R.string.proposal_extension_kicker,
        )

        val badge = card.findViewById<TextView>(R.id.proposalStatusBadge)
        when (proposal.proposalStatus) {
            ProposalStatus.ACCEPTED -> {
                badge.setText(R.string.proposal_status_accepted)
                badge.setBackgroundResource(R.drawable.bg_badge_mint)
                badge.setTextColor(getColor(R.color.uc_green))
            }
            ProposalStatus.DECLINED -> {
                badge.setText(R.string.proposal_status_declined)
                badge.setBackgroundResource(R.drawable.bg_reminder_overdue)
                badge.setTextColor(getColor(R.color.danger))
            }
            ProposalStatus.WITHDRAWN -> {
                badge.setText(R.string.proposal_status_withdrawn)
                badge.setBackgroundResource(R.drawable.bg_input)
                badge.setTextColor(getColor(R.color.text_secondary))
            }
            ProposalStatus.EXPIRED -> {
                badge.setText(R.string.proposal_status_expired)
                badge.setBackgroundResource(R.drawable.bg_input)
                badge.setTextColor(getColor(R.color.text_secondary))
            }
            else -> {
                badge.setText(R.string.proposal_status_pending)
                badge.setBackgroundResource(R.drawable.bg_badge_swap)
                badge.setTextColor(getColor(R.color.swap_text))
            }
        }

        val titleView = card.findViewById<TextView>(R.id.proposalTitle)
        val diffView = card.findViewById<TextView>(R.id.proposalDiff)
        if (isMeetup) {
            titleView.text = "${proposal.proposerName} proposed a new meetup"
            val oldWhen = proposal.oldMeetMillis?.let { Rentals.whenLabel(this, it) } ?: "Current schedule"
            val newWhen = proposal.newMeetMillis?.let { Rentals.whenLabel(this, it) } ?: ""
            val oldSpot = proposal.oldMeetup.orEmpty()
            val newSpot = proposal.newMeetup.orEmpty() +
                if (proposal.newMeetupNote.isNotBlank()) " (${proposal.newMeetupNote})" else ""
            diffView.text = "From: $oldSpot \u2022 $oldWhen\nTo: $newSpot \u2022 $newWhen"
        } else {
            titleView.text = "${proposal.proposerName} requested +${Listings.daysLabel(this, proposal.extraDays)}"
            val newDue = proposal.newDueMillis?.let { Rentals.dateLabel(it) } ?: ""
            val feeText = if (proposal.extraCostPesos > 0) {
                getString(R.string.extension_preview_format, newDue, Listings.price(proposal.extraCostPesos))
            } else {
                getString(R.string.extension_preview_free_format, newDue)
            }
            diffView.text = feeText
        }

        val noteView = card.findViewById<TextView>(R.id.proposalNote)
        if (proposal.note.isNotBlank()) {
            noteView.isVisible = true
            noteView.text = "\u201C${proposal.note}\u201D"
        }

        val isPending = proposal.proposalStatus == ProposalStatus.PENDING
        val isProposer = proposal.proposerUid == UserState.currentUid
        card.findViewById<View>(R.id.proposalRecipientActions).isVisible = isPending && !isProposer
        card.findViewById<View>(R.id.proposalProposerActions).isVisible = isPending && isProposer

        card.findViewById<View>(R.id.btnProposalConfirm).setOnClickListener { btn ->
            Motion.confirm(btn)
            if (UserState.respondToProposal(threadId, proposal.proposalId, ProposalStatus.ACCEPTED)) {
                Toast.makeText(this, R.string.toast_proposal_accepted, Toast.LENGTH_SHORT).show()
                renderMessages(scrollToBottom = true)
            }
        }
        card.findViewById<View>(R.id.btnProposalDecline).setOnClickListener { btn ->
            Motion.tick(btn)
            if (UserState.respondToProposal(threadId, proposal.proposalId, ProposalStatus.DECLINED)) {
                Toast.makeText(this, R.string.toast_proposal_declined, Toast.LENGTH_SHORT).show()
                renderMessages(scrollToBottom = false)
            }
        }
        card.findViewById<View>(R.id.btnProposalWithdraw).setOnClickListener { btn ->
            Motion.tick(btn)
            if (UserState.respondToProposal(threadId, proposal.proposalId, ProposalStatus.WITHDRAWN)) {
                Toast.makeText(this, R.string.toast_proposal_withdrawn, Toast.LENGTH_SHORT).show()
                renderMessages(scrollToBottom = false)
            }
        }

        messages.addView(card)
    }

    private fun ingestAndSendPhoto(uri: Uri) {
        lifecycleScope.launch {
            val ref = ImagePipeline.ingestUri(this@ChatThreadActivity, uri) ?: uri.toString()
            UserState.appendChatMessage(
                threadId,
                UserState.ChatMessage(
                    senderUid = UserState.currentUid,
                    photoUri = ref,
                    outgoing = true,
                    timeLabel = getString(R.string.msg_sent_now),
                ),
            )
            renderMessages(scrollToBottom = true)
            Toast.makeText(this@ChatThreadActivity, R.string.toast_photo_sent, Toast.LENGTH_SHORT).show()
        }
    }

    private fun addPhotoBubble(ref: String, animate: Boolean) {
        val photo = LayoutInflater.from(this).inflate(R.layout.item_bubble_photo, messages, false)
        val image = photo.findViewById<ImageView>(R.id.bubblePhotoImage)
        val placeholder = photo.findViewById<View>(R.id.bubblePhotoPlaceholder)
        val frame = photo.findViewById<View>(R.id.bubblePhotoFrame)
        AppIntents.bindImageUri(image, placeholder, ref)
        frame.setOnClickListener { AppIntents.viewMediaRef(this, ref) }
        messages.addView(photo)
        if (animate) {
            Motion.insert(photo, fromEnd = true)
            Motion.confirm(photo)
        }
    }

    internal fun launchChatGallery() {
        try {
            photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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

    /**
     * Context-aware `+` menu:
     * - Always shows **Photo** and **View listing**.
     * - Shows **Propose meetup change** ONLY when this listing has an active ACCEPTED deal.
     * - Shows **Propose return extension** ONLY when this listing has an active ON_LOAN rental.
     */
    fun openChatActions(view: View) {
        Motion.tick(view)
        val plus = findViewById<View>(R.id.attachIcon)
        plus?.let { Motion.rotateTo(it, 45f) }

        val activeAccepted = UserState.accepted(listing.id)
        val activeRental = UserState.rental(listing.id)
        val myUid = UserState.currentUid
        val canProposeMeetup = activeAccepted != null &&
            (activeAccepted.ownerUid == myUid || activeAccepted.requesterUid == myUid) &&
            activeRental == null
        val canProposeExtension = activeRental != null &&
            (activeRental.lenderUid == myUid || activeRental.borrowerUid == myUid) &&
            !activeRental.returnPending

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

            val meetupRow = sheet.findViewById<View>(R.id.actionProposeMeetup)
            meetupRow.isVisible = canProposeMeetup
            meetupRow.setOnClickListener {
                dialog.dismiss()
                if (activeAccepted != null) openProposeMeetupSheet(activeAccepted)
            }

            val extRow = sheet.findViewById<View>(R.id.actionProposeExtension)
            extRow.isVisible = canProposeExtension
            extRow.setOnClickListener {
                dialog.dismiss()
                if (activeRental != null) openProposeExtensionSheet(activeRental)
            }

            sheet.findViewById<View>(R.id.actionListing).setOnClickListener {
                dialog.dismiss()
                openListingById(listing.id)
            }
        }
    }

    private fun openProposeMeetupSheet(deal: Request) {
        var placeIndex = Listings.MEETUP_POINTS.indexOf(deal.meetup).coerceAtLeast(0)
        val cal = Calendar.getInstance().apply { timeInMillis = deal.meetMillis }
        var pickedYear = cal.get(Calendar.YEAR)
        var pickedMonth = cal.get(Calendar.MONTH)
        var pickedDay = cal.get(Calendar.DAY_OF_MONTH)
        var pickedHour = cal.get(Calendar.HOUR_OF_DAY)
        var pickedMinute = cal.get(Calendar.MINUTE)

        fun currentMillis(): Long = Calendar.getInstance().apply {
            set(pickedYear, pickedMonth, pickedDay, pickedHour, pickedMinute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        Sheets.show(this, R.layout.sheet_propose_meetup) { sheet, dialog ->
            val placeText = sheet.findViewById<TextView>(R.id.propPlaceText)
            val noteInput = sheet.findViewById<EditText>(R.id.propMeetupNote)
            val dateBtn = sheet.findViewById<TextView>(R.id.propPickDate)
            val timeBtn = sheet.findViewById<TextView>(R.id.propPickTime)
            val reasonInput = sheet.findViewById<EditText>(R.id.propReason)

            placeText.text = Listings.MEETUP_POINTS[placeIndex]
            noteInput.setText(deal.meetupNote)
            dateBtn.text = Rentals.dateLabel(currentMillis())
            timeBtn.text = Availability.time(pickedHour * 60 + pickedMinute)

            sheet.findViewById<View>(R.id.propPlaceRow).setOnClickListener { row ->
                Dropdown.show(row, Listings.MEETUP_POINTS, placeIndex, icons = listOf(R.drawable.ic_location)) {
                    placeIndex = it
                    placeText.text = Listings.MEETUP_POINTS[it]
                }
            }

            dateBtn.setOnClickListener {
                val picker = MaterialDatePicker.Builder.datePicker()
                    .setTitleText(R.string.pick_date_title)
                    .setCalendarConstraints(CalendarConstraints.Builder().setValidator(DateValidatorPointForward.now()).build())
                    .build()
                picker.addOnPositiveButtonClickListener { millis ->
                    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
                    pickedYear = utc.get(Calendar.YEAR)
                    pickedMonth = utc.get(Calendar.MONTH)
                    pickedDay = utc.get(Calendar.DAY_OF_MONTH)
                    dateBtn.text = SimpleDateFormat("EEE, MMM d", Locale.US).format(currentMillis())
                }
                picker.show(supportFragmentManager, "prop_date")
            }

            timeBtn.setOnClickListener {
                val picker = MaterialTimePicker.Builder()
                    .setTimeFormat(TimeFormat.CLOCK_12H)
                    .setHour(pickedHour)
                    .setMinute(pickedMinute)
                    .setTitleText(R.string.pick_time_title)
                    .build()
                picker.addOnPositiveButtonClickListener {
                    pickedHour = picker.hour
                    pickedMinute = picker.minute
                    timeBtn.text = Availability.time(pickedHour * 60 + pickedMinute)
                }
                picker.show(supportFragmentManager, "prop_time")
            }

            sheet.findViewById<View>(R.id.propSend).setOnClickListener { btn ->
                Motion.confirm(btn)
                UserState.proposeMeetupChange(
                    threadId = threadId,
                    listing = listing,
                    request = deal,
                    newMeetup = Listings.MEETUP_POINTS[placeIndex],
                    newMeetupNote = noteInput.text.toString().trim(),
                    newMeetMillis = currentMillis(),
                    note = reasonInput.text.toString().trim(),
                )
                dialog.dismiss()
                renderMessages(scrollToBottom = true)
                Toast.makeText(this, R.string.toast_proposal_sent, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openProposeExtensionSheet(rental: UserState.Rental) {
        var extraDays = Listings.EXTENSION_DAYS.first()
        Sheets.show(this, R.layout.sheet_propose_extension) { sheet, dialog ->
            val chipContainer = sheet.findViewById<LinearLayout>(R.id.extDaysChips)
            val customInput = sheet.findViewById<EditText>(R.id.extCustomDays)
            val preview = sheet.findViewById<TextView>(R.id.extPreview)
            val reasonInput = sheet.findViewById<EditText>(R.id.extReason)

            fun updatePreview() {
                val newDue = Rentals.dateLabel(rental.dueMillis + extraDays * Rentals.DAY_MS)
                val cost = listing.price * extraDays
                preview.text = if (cost > 0) {
                    getString(R.string.extension_preview_format, newDue, Listings.price(cost))
                } else {
                    getString(R.string.extension_preview_free_format, newDue)
                }
            }

            val chips = Listings.EXTENSION_DAYS.map { d ->
                (LayoutInflater.from(this).inflate(R.layout.item_chip, chipContainer, false) as TextView).also {
                    it.text = "+${Listings.daysLabel(this, d)}"
                    chipContainer.addView(it)
                }
            }
            Choice.chips(chips, 0) { idx ->
                extraDays = Listings.EXTENSION_DAYS[idx]
                if (!customInput.text.isNullOrEmpty()) customInput.setText("")
                updatePreview()
            }
            customInput.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val typed = s?.toString()?.toIntOrNull()
                    if (typed != null && typed in 1..60) {
                        extraDays = typed
                        updatePreview()
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            updatePreview()

            sheet.findViewById<View>(R.id.extSend).setOnClickListener { btn ->
                Motion.confirm(btn)
                UserState.proposeReturnExtension(
                    threadId = threadId,
                    listing = listing,
                    rental = rental,
                    extraDays = extraDays,
                    note = reasonInput.text.toString().trim(),
                )
                dialog.dismiss()
                renderMessages(scrollToBottom = true)
                Toast.makeText(this, R.string.toast_proposal_sent, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val EXTRA_THREAD_ID = "thread_id"
        const val EXTRA_LISTING_ID = EXTRA_LISTING
        const val EXTRA_RESERVED_DETAIL = "reserved_detail"
        private const val STATE_CAMERA_URI = "state_chat_camera_uri"
    }
}
