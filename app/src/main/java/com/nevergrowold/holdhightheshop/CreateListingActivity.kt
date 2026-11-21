package com.nevergrowold.holdhightheshop

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

class CreateListingActivity : BaseActivity() {

    private var condition = -1
    private var listingType = 0
    private var program = -1
    private var meetup = -1

    private val programs by lazy { Listings.PROGRAMS }

    /** Rent: the longest rental the seller allows, in weeks. */
    private var maxWeeks = 4

    /** Blocks of free time, edited in place. Starts with one: Mon–Fri, 9:00 AM–4:00 PM. */
    private class Block(var days: Set<Int>, var from: Int, var to: Int)

    private val blocks = Availability.OFFICE_HOURS.times.map { Block(it.days, it.from, it.to) }.toMutableList()

    /** Set when opened from "Edit listing" on one of the student's listings. */
    private var editing: Listing? = null

    /** Selected or captured photo URIs for each of the four tiles (null = empty). */
    private val photoUris = arrayOfNulls<String>(MAX_PHOTOS)
    private var pendingPhotoIndex = 0
    private var pendingCameraUri: Uri? = null

    /** Implicit intent launcher: choose an item photo from gallery (ACTION_GET_CONTENT). */
    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            setPhotoSlot(pendingPhotoIndex, uri.toString(), animate = true)
            Toast.makeText(this, R.string.toast_photo_added, Toast.LENGTH_SHORT).show()
        }
    }

    /** Implicit intent launcher: capture an item photo with the camera (ACTION_IMAGE_CAPTURE). */
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = pendingCameraUri ?: return@registerForActivityResult
            setPhotoSlot(pendingPhotoIndex, uri.toString(), animate = true)
            Toast.makeText(this, R.string.toast_photo_added, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_create_listing)
        BarInsets.apply(this)

        editing = intent.getStringExtra(EXTRA_LISTING)?.let { Listings.byId(it) }
        bindFreeTimes(animateLayout = false)

        val sharedSubject = intent.getStringExtra(AppIntents.EXTRA_SHARED_SUBJECT)
        val sharedText = intent.getStringExtra(AppIntents.EXTRA_SHARED_TEXT)
        val sharedImage = intent.getStringExtra(AppIntents.EXTRA_SHARED_IMAGE_URI)
        val hasShared = !sharedSubject.isNullOrBlank() || !sharedText.isNullOrBlank() || !sharedImage.isNullOrBlank()

        when {
            editing != null -> prefill(editing!!)
            hasShared -> {
                meetup = UserState.defaultMeetup
                setPicked(R.id.rowMeetupText, Listings.MEETUP_POINTS[meetup])
                prefillFromShare(sharedSubject, sharedText, sharedImage)
            }
            UserState.draft != null -> restoreDraft(UserState.draft!!)
            else -> {
                meetup = UserState.defaultMeetup
                setPicked(R.id.rowMeetupText, Listings.MEETUP_POINTS[meetup])
            }
        }

        Choice.pills(
            listOf(findViewById(R.id.condition1), findViewById(R.id.condition2), findViewById(R.id.condition3)),
            condition,
        ) { condition = it }

        Choice.segments(
            listOf(findViewById(R.id.type1), findViewById(R.id.type2), findViewById(R.id.type3)),
            listingType,
        ) { listingType = it; bindType(animate = true) }

        countCharacters(findViewById(R.id.inputDescription), findViewById(R.id.counterDescription))
        updatePhotoCounter(roll = false)
    }

    private fun prefillFromShare(subject: String?, text: String?, imageUri: String?) {
        if (!subject.isNullOrBlank()) {
            findViewById<EditText>(R.id.inputTitle).setText(subject.take(60))
        }
        if (!text.isNullOrBlank()) {
            findViewById<EditText>(R.id.inputDescription).setText(text.take(300))
        }
        if (!imageUri.isNullOrBlank()) {
            setPhotoSlot(0, imageUri, animate = false)
        }
        Toast.makeText(this, R.string.toast_shared_prefilled, Toast.LENGTH_SHORT).show()
    }

    private fun restoreDraft(draft: UserState.ListingDraft) {
        findViewById<EditText>(R.id.inputTitle).setText(draft.title)
        findViewById<EditText>(R.id.inputPrice).setText(draft.price)
        findViewById<EditText>(R.id.inputDescription).setText(draft.description)
        findViewById<EditText>(R.id.inputWants).setText(draft.wants)
        program = draft.program
        if (program in programs.indices) setPicked(R.id.rowProgramText, programs[program])
        condition = draft.condition
        listingType = draft.listingType
        maxWeeks = draft.maxWeeks
        meetup = draft.meetup
        if (meetup in Listings.MEETUP_POINTS.indices) setPicked(R.id.rowMeetupText, Listings.MEETUP_POINTS[meetup])
        blocks.clear()
        draft.availability.times.forEach { blocks += Block(it.days, it.from, it.to) }
        if (blocks.isEmpty()) blocks += Block(setOf(0, 1, 2, 3, 4), 9 * 60, 16 * 60)
        bindFreeTimes(animateLayout = false)
        bindType(animate = false)
        draft.photoUris.forEachIndexed { idx, uri ->
            if (idx < MAX_PHOTOS && !uri.isNullOrBlank()) setPhotoSlot(idx, uri, animate = false)
        }
        Toast.makeText(this, R.string.toast_draft_restored, Toast.LENGTH_SHORT).show()
    }

    /**
     * Photo tiles. Empty tiles offer Gallery (ACTION_GET_CONTENT) or Camera
     * (ACTION_IMAGE_CAPTURE). Filled tiles let the student view, replace, or remove the photo.
     */
    fun pickPhotos(view: View) {
        val index = PHOTO_TILE_IDS.indexOf(view.id).takeIf { it >= 0 } ?: return
        pendingPhotoIndex = index
        val existing = photoUris[index]
        if (existing == null) {
            val options = arrayOf(
                getString(R.string.photo_option_gallery),
                getString(R.string.photo_option_camera),
            )
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.photo_picker_title)
                .setItems(options) { _, which ->
                    if (which == 0) launchGalleryPicker() else launchCameraCapture()
                }
                .show()
        } else {
            val options = arrayOf(
                getString(R.string.photo_option_view),
                getString(R.string.photo_option_gallery),
                getString(R.string.photo_option_camera),
                getString(R.string.photo_option_remove),
            )
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.photo_picker_title)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> AppIntents.launch(this, AppIntents.viewImageIntent(Uri.parse(existing)))
                        1 -> launchGalleryPicker()
                        2 -> launchCameraCapture()
                        3 -> {
                            setPhotoSlot(index, null, animate = true)
                            Toast.makeText(this, R.string.toast_photo_removed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .show()
        }
    }

    internal fun launchGalleryPicker() {
        try {
            galleryLauncher.launch(AppIntents.pickImageIntent())
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_no_app_available, Toast.LENGTH_SHORT).show()
        }
    }

    internal fun launchCameraCapture() {
        try {
            val (captureIntent, uri) = AppIntents.capturePhotoIntent(this)
            pendingCameraUri = uri
            cameraLauncher.launch(captureIntent)
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_no_camera, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setPhotoSlot(index: Int, uriString: String?, animate: Boolean) {
        if (index !in 0 until MAX_PHOTOS) return
        photoUris[index] = uriString
        val tile = findViewById<ViewGroup>(PHOTO_TILE_IDS[index]) ?: return
        tile.findViewWithTag<View>(PHOTO_TAG)?.let { tile.removeView(it) }
        val hasPhoto = !uriString.isNullOrBlank()
        for (i in 0 until tile.childCount) tile.getChildAt(i).isVisible = !hasPhoto

        if (hasPhoto) {
            val added = LayoutInflater.from(this).inflate(R.layout.item_photo_added, tile, false)
            added.tag = PHOTO_TAG
            val preview = added.findViewById<ImageView>(R.id.photoAddedPreview)
            val placeholder = added.findViewById<View>(R.id.photoAddedPlaceholder)
            AppIntents.bindImageUri(preview, placeholder, uriString)
            tile.addView(added)
            if (animate && Motion.enabled(this)) {
                added.alpha = 0f
                added.scaleX = 0.86f
                added.scaleY = 0.86f
                Motion.spring(added, DynamicAnimation.ALPHA, 1f, Motion.Effects.FAST)
                Motion.springScale(added, 1f, Motion.Spatial.SNAPPY)

                added.findViewById<View>(R.id.photoAddedBadge)?.let { badge ->
                    badge.scaleX = 0.65f
                    badge.scaleY = 0.65f
                    badge.alpha = 0f
                    badge.postDelayed({
                        if (!badge.isAttachedToWindow) return@postDelayed
                        Motion.spring(badge, DynamicAnimation.ALPHA, 1f, Motion.Effects.FAST)
                        Motion.springScale(badge, 1f, Motion.Spatial.EXPRESSIVE)
                    }, 55)
                }
            }
            if (animate) Motion.confirm(tile)
        } else {
            if (animate && Motion.enabled(this)) {
                for (i in 0 until tile.childCount) {
                    val child = tile.getChildAt(i)
                    child.alpha = 0f
                    child.scaleX = 0.88f
                    child.scaleY = 0.88f
                    Motion.spring(child, DynamicAnimation.ALPHA, 1f, Motion.Effects.FAST)
                    Motion.springScale(child, 1f, Motion.Spatial.SNAPPY)
                }
            }
            if (animate) Motion.tick(tile)
        }
        updatePhotoCounter(roll = animate)
    }

    private fun updatePhotoCounter(roll: Boolean) {
        val text = getString(R.string.photo_counter_format, photoUris.count { !it.isNullOrBlank() })
        val counter = findViewById<TextView>(R.id.photoCounter)
        if (roll) Motion.rollText(counter, text) else counter.text = text
    }

    /** Lets the seller pick which college the item belongs to. */
    fun pickProgram(view: View) {
        Dropdown.show(view, programs, program, rotateChevron = true) {
            program = it
            setPicked(R.id.rowProgramText, programs[it])
        }
    }

    /** Lets the seller pick where the handover happens. */
    fun pickMeetup(view: View) {
        Dropdown.show(
            view,
            Listings.MEETUP_POINTS,
            meetup,
            icons = listOf(R.drawable.ic_location),
            rotateChevron = true,
        ) {
            meetup = it
            setPicked(R.id.rowMeetupText, Listings.MEETUP_POINTS[it])
        }
    }

    private fun setPicked(targetId: Int, value: String) {
        val target = findViewById<TextView>(targetId)
        target.text = value
        Motion.textColor(target, getColor(R.color.text_primary), animate = true)
    }

    /** Keeps the 0 / 300 counter in step with what is typed. */
    private fun countCharacters(input: EditText, counter: TextView) {
        counter.text = getString(R.string.counter_format, input.text?.length ?: 0)
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                counter.text = getString(R.string.counter_format, s?.length ?: 0)
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    /** Redraws free-time cards; day chips toggle in-place so their transitions play cleanly. */
    private fun bindFreeTimes(animateLayout: Boolean = false, animateLastIn: Boolean = false) {
        val form = findViewById<ViewGroup>(R.id.formContent)
        if (animateLayout) Motion.layoutTransition(form, durationMs = 220L)
        val box = findViewById<LinearLayout>(R.id.freeTimes)
        box.removeAllViews()
        blocks.forEachIndexed { index, block ->
            val card = LayoutInflater.from(this).inflate(R.layout.item_free_time, box, false)
            card.findViewById<TextView>(R.id.freeTitle).text = getString(R.string.free_time_title, index + 1)
            card.findViewById<View>(R.id.freeRemove).apply {
                isVisible = blocks.size > 1
                setOnClickListener {
                    Motion.tick(it)
                    blocks.removeAt(index)
                    bindFreeTimes(animateLayout = true)
                }
            }
            val dayChips = DAY_IDS.map { card.findViewById<TextView>(it) }
            fun bindDays() {
                Choice.toggles(dayChips, block.days, inactiveBg = R.drawable.bg_input) { updated ->
                    block.days = updated
                    bindDays()
                }
            }
            bindDays()
            val fromText = card.findViewById<TextView>(R.id.freeFromText)
            val toText = card.findViewById<TextView>(R.id.freeToText)
            fromText.text = Availability.time(block.from)
            toText.text = Availability.time(block.to)
            card.findViewById<View>(R.id.freeFrom).setOnClickListener {
                pickBlockTime(block, isFrom = true) { fromText.text = Availability.time(block.from) }
            }
            card.findViewById<View>(R.id.freeTo).setOnClickListener {
                pickBlockTime(block, isFrom = false) { toText.text = Availability.time(block.to) }
            }
            box.addView(card)
            if (animateLastIn && index == blocks.lastIndex && Motion.enabled(this)) {
                card.alpha = 0f
                card.translationY = 10f * resources.displayMetrics.density
                Motion.spring(card, DynamicAnimation.ALPHA, 1f, Motion.Effects.DEFAULT)
                Motion.spring(card, DynamicAnimation.TRANSLATION_Y, 0f, Motion.Spatial.RESPONSIVE)
            }
        }
    }

    /** "Add available time": smoothly appends a new Sat–Sun morning block. */
    fun addFreeTime(view: View) {
        Motion.tick(view)
        blocks += Block(setOf(5, 6), 9 * 60, 12 * 60)
        bindFreeTimes(animateLayout = true, animateLastIn = true)
    }

    /** From / To: any time of day; only the end has to come after the start. */
    private fun pickBlockTime(block: Block, isFrom: Boolean, onUpdated: () -> Unit) {
        val current = if (isFrom) block.from else block.to
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_12H)
            .setHour(current / 60)
            .setMinute(current % 60)
            .setTitleText(if (isFrom) R.string.lbl_from else R.string.lbl_to)
            .build()
        picker.addOnPositiveButtonClickListener {
            val minutes = picker.hour * 60 + picker.minute
            when {
                (isFrom && minutes >= block.to) || (!isFrom && minutes <= block.from) -> {
                    Motion.reject(window.decorView)
                    Toast.makeText(this, R.string.toast_time_order, Toast.LENGTH_SHORT).show()
                }
                isFrom -> {
                    block.from = minutes
                    onUpdated()
                }
                else -> {
                    block.to = minutes
                    onUpdated()
                }
            }
        }
        picker.show(supportFragmentManager, "free")
    }

    /** Edit mode: same form, filled in, with Cancel / Save changes. */
    private fun prefill(listing: Listing) {
        findViewById<TextView>(R.id.createTitle).setText(R.string.edit_title)
        findViewById<TextView>(R.id.btnSaveDraft).setText(R.string.action_cancel)
        findViewById<TextView>(R.id.btnPostItem).setText(R.string.btn_save_changes)
        findViewById<EditText>(R.id.inputTitle).setText(listing.title)
        findViewById<EditText>(R.id.inputPrice).setText(listing.price.toString())
        findViewById<EditText>(R.id.inputDescription).setText(listing.description)
        program = programs.indexOf(listing.program)
        if (program >= 0) setPicked(R.id.rowProgramText, listing.program)
        meetup = Listings.MEETUP_POINTS.indexOf(listing.meetup)
        if (meetup >= 0) setPicked(R.id.rowMeetupText, listing.meetup)
        condition = listing.condition
        blocks.clear()
        listing.availability.times.forEach { blocks += Block(it.days, it.from, it.to) }
        bindFreeTimes(animateLayout = false)
        listingType = listing.type
        findViewById<EditText>(R.id.inputWants).setText(listing.wants)
        maxWeeks = listing.maxWeeks
        bindType(animate = false)
        listing.photoUris.forEachIndexed { idx, uri ->
            if (idx < MAX_PHOTOS) setPhotoSlot(idx, uri, animate = false)
        }
    }

    /** Smoothly reveals/hides Price, Max Rent, and Swap Wants as listing type changes. */
    private fun bindType(animate: Boolean) {
        if (animate) Motion.layoutTransition(findViewById(R.id.formContent), durationMs = 220L)
        val swap = listingType == 2
        val rent = listingType == 1
        listOf(R.id.labelPrice, R.id.rowPrice).forEach { findViewById<View>(it).isVisible = !swap }
        listOf(R.id.labelWants, R.id.inputWants).forEach { findViewById<View>(it).isVisible = swap }
        findViewById<TextView>(R.id.labelPrice).setText(if (rent) R.string.lbl_price_per_week else R.string.lbl_price)
        listOf(R.id.labelMaxRent, R.id.hintMaxRent, R.id.rowMaxRent).forEach { findViewById<View>(it).isVisible = rent }
        if (rent) bindMaxRent()
    }

    /** Rent: chips for the longest rental (1 week ... a semester). */
    private fun bindMaxRent() {
        val box = findViewById<LinearLayout>(R.id.maxRentChips)
        if (box.childCount == 0) {
            Listings.RENT_WEEKS.forEach { weeks ->
                val chip = LayoutInflater.from(this).inflate(R.layout.item_chip, box, false) as TextView
                chip.text = Listings.weeksLabel(this, weeks)
                box.addView(chip)
            }
        }
        val chips = (0 until box.childCount).map { box.getChildAt(it) as TextView }
        Choice.chips(chips, Listings.RENT_WEEKS.indexOf(maxWeeks)) { maxWeeks = Listings.RENT_WEEKS[it] }
    }

    /** Saves the listing as a real draft in [UserState] and closes the form (Cancel in edit mode). */
    fun saveDraft(view: View) {
        if (editing != null) {
            finish()
            return
        }
        UserState.draft = UserState.ListingDraft(
            title = findViewById<EditText>(R.id.inputTitle).text.toString().trim(),
            program = program,
            condition = condition,
            listingType = listingType,
            price = findViewById<EditText>(R.id.inputPrice).text.toString().trim(),
            wants = findViewById<EditText>(R.id.inputWants).text.toString().trim(),
            maxWeeks = maxWeeks,
            description = findViewById<EditText>(R.id.inputDescription).text.toString().trim(),
            meetup = meetup,
            availability = Availability(blocks.map { FreeTime(it.days.toSet(), it.from, it.to) }),
            photoUris = photoUris.toList(),
        )
        Motion.confirm(view)
        Toast.makeText(this, R.string.toast_draft_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    /** Posts or updates the listing in [Listings] once every required field is filled in. */
    fun postItem(view: View) {
        val title = findViewById<EditText>(R.id.inputTitle)
        val price = findViewById<EditText>(R.id.inputPrice)
        val wants = findViewById<EditText>(R.id.inputWants)
        val description = findViewById<EditText>(R.id.inputDescription)
        when {
            title.text.isBlank() -> missing(R.string.toast_need_title, title)
            program < 0 -> missing(R.string.toast_need_program, findViewById(R.id.rowProgram))
            condition < 0 -> missing(R.string.toast_need_condition, findViewById(R.id.condition1))
            listingType != 2 && price.text.isBlank() -> missing(R.string.toast_need_price, findViewById(R.id.rowPrice))
            listingType == 2 && wants.text.isBlank() -> missing(R.string.toast_need_wants, wants)
            meetup < 0 -> missing(R.string.toast_need_meetup, findViewById(R.id.rowMeetup))
            blocks.any { it.days.isEmpty() } -> missing(R.string.toast_need_days, findViewById(R.id.freeTimes))
            else -> {
                hideKeyboard(view)
                Motion.confirm(view)
                val parsedPrice = if (listingType == 2) 0 else price.text.toString().trim().toIntOrNull() ?: 0
                val availability = Availability(blocks.map { FreeTime(it.days.toSet(), it.from, it.to) })
                val attachedPhotos = photoUris.filterNotNull().filter { it.isNotBlank() }
                val existing = editing
                if (existing != null) {
                    val updated = existing.copy(
                        title = title.text.toString().trim(),
                        price = parsedPrice,
                        program = programs[program],
                        condition = condition,
                        type = listingType,
                        description = description.text.toString().trim(),
                        meetup = Listings.MEETUP_POINTS[meetup],
                        availability = availability,
                        wants = if (listingType == 2) wants.text.toString().trim() else null,
                        maxWeeks = maxWeeks,
                        photoUris = attachedPhotos,
                    )
                    Listings.updateListing(updated)
                    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_LISTING, updated.id))
                    Toast.makeText(this, R.string.toast_changes_saved, Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    val newId = Listings.nextListingId()
                    val created = Listings.addListing(
                        Listing(
                            id = newId,
                            title = title.text.toString().trim(),
                            price = parsedPrice,
                            program = programs[program],
                            condition = condition,
                            type = listingType,
                            description = description.text.toString().trim(),
                            seller = Listings.ME,
                            meetup = Listings.MEETUP_POINTS[meetup],
                            postedDaysAgo = 0,
                            availability = availability,
                            wants = if (listingType == 2) wants.text.toString().trim() else null,
                            maxWeeks = maxWeeks,
                            photoUris = attachedPhotos,
                        )
                    )
                    UserState.draft = null
                    val ref = Listings.referenceCode(created.id)
                    Toast.makeText(this, R.string.toast_listing_posted, Toast.LENGTH_SHORT).show()
                    startActivity(
                        Intent(this, ListingPostedActivity::class.java)
                            .putExtra(EXTRA_LISTING, created.id)
                            .putExtra(ListingPostedActivity.EXTRA_REFERENCE, ref)
                    )
                    finish()
                }
            }
        }
    }

    /** Scrolls the missing field into view and plays a tactile validation shake. */
    private fun missing(message: Int, field: View) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        val scroll = findViewById<ScrollView>(R.id.formScroll)
        val y = IntArray(2).also { field.getLocationInWindow(it) }[1] -
            IntArray(2).also { scroll.getLocationInWindow(it) }[1]
        scroll.smoothScrollBy(0, y - dp(this, 60))
        Motion.shake(field)
        val focusTarget = if (field.id == R.id.rowPrice) findViewById<EditText>(R.id.inputPrice) else field
        if (focusTarget is EditText) showKeyboard(focusTarget)
    }

    companion object {
        private val DAY_IDS = listOf(R.id.day0, R.id.day1, R.id.day2, R.id.day3, R.id.day4, R.id.day5, R.id.day6)
        private val PHOTO_TILE_IDS = listOf(R.id.photoTile1, R.id.photoTile2, R.id.photoTile3, R.id.photoTile4)
        private const val MAX_PHOTOS = 4
        private const val PHOTO_TAG = "photo"
    }
}
