package com.nevergrowold.holdhightheshop

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.nevergrowold.holdhightheshop.data.AiCheckStatus
import com.nevergrowold.holdhightheshop.data.AppRepository
import com.nevergrowold.holdhightheshop.media.ImagePipeline
import com.nevergrowold.holdhightheshop.security.CampusSafetyApi
import com.nevergrowold.holdhightheshop.security.UcEmailValidator
import kotlinx.coroutines.launch

/**
 * Create & Edit Listing screen with:
 * - Dynamic 1–10 Photo Rail (`PickMultipleVisualMedia` zero-permission photo picker + Camera)
 * - Multi-select UC college tags (empty = `All colleges`)
 * - Day-unit lending (`₱X / day`, 1 day .. Semester) & `FREE` giveaways (`₱0`..`₱50,000`)
 * - Optional indoor floor/room `meetupNote`
 * - Price/Type lock when editing a listing that has active requests/loans
 * - Unsaved changes guard on Back press
 * - Hybrid Multimodal Prohibited-Item Safety Gate (local rules + live Gemini 2.5 Flash)
 */
class CreateListingActivity : BaseActivity() {

    private var condition = -1
    private var listingType = 0
    private var meetup = -1
    private var maxDays = 14
    private val selectedProgramIndices = LinkedHashSet<Int>()

    private class Block(var days: Set<Int>, var from: Int, var to: Int)
    private val blocks = Availability.OFFICE_HOURS.times.map { Block(it.days, it.from, it.to) }.toMutableList()

    private var editing: Listing? = null
    private var priceTypeLocked = false
    private var submitting = false
    private var formDirty = false

    /** 1..10 durable `media://` or `content://` photo refs. Index 0 is Cover. */
    private val photoRefs = mutableListOf<String>()
    private var replacePhotoIndex: Int? = null
    private var pendingCameraUri: Uri? = null

    /** Zero-permission Android Photo Picker supporting up to 10 photos at once. */
    private val multiPhotoPicker =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(Listings.MAX_PHOTOS)) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            ingestPickedUris(uris)
        }

    /** Single-photo replacement picker. */
    private val singlePhotoPicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val targetIdx = replacePhotoIndex
            replacePhotoIndex = null
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val ref = ImagePipeline.ingestUri(this@CreateListingActivity, uri) ?: uri.toString()
                if (targetIdx != null && targetIdx in photoRefs.indices) {
                    photoRefs[targetIdx] = ref
                } else if (photoRefs.size < Listings.MAX_PHOTOS) {
                    photoRefs.add(ref)
                }
                formDirty = true
                renderPhotoRail(animate = true)
                Toast.makeText(this@CreateListingActivity, R.string.toast_photo_added, Toast.LENGTH_SHORT).show()
            }
        }

    /** Camera capture via scoped FileProvider (`onSaveInstanceState` safe). */
    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = pendingCameraUri ?: return@registerForActivityResult
                val targetIdx = replacePhotoIndex
                replacePhotoIndex = null
                lifecycleScope.launch {
                    val ref = ImagePipeline.ingestUri(this@CreateListingActivity, uri) ?: uri.toString()
                    if (targetIdx != null && targetIdx in photoRefs.indices) {
                        photoRefs[targetIdx] = ref
                    } else if (photoRefs.size < Listings.MAX_PHOTOS) {
                        photoRefs.add(ref)
                    }
                    formDirty = true
                    renderPhotoRail(animate = true)
                    Toast.makeText(this@CreateListingActivity, R.string.toast_photo_added, Toast.LENGTH_SHORT).show()
                }
            } else {
                replacePhotoIndex = null
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_create_listing)
        BarInsets.apply(this)

        savedInstanceState?.let { state ->
            state.getString(STATE_CAMERA_URI)?.let { pendingCameraUri = Uri.parse(it) }
            state.getStringArrayList(STATE_PHOTO_REFS)?.let {
                photoRefs.clear()
                photoRefs.addAll(it)
            }
        }

        editing = intent.getStringExtra(EXTRA_LISTING)?.let { Listings.byIdOrNull(it) }
        bindFreeTimes(animateLayout = false)
        buildCollegeTagChips()

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
            UserState.draft != null && savedInstanceState == null -> restoreDraft(UserState.draft!!)
            else -> {
                meetup = UserState.defaultMeetup
                setPicked(R.id.rowMeetupText, Listings.MEETUP_POINTS[meetup])
            }
        }

        Choice.pills(
            listOf(findViewById(R.id.condition1), findViewById(R.id.condition2), findViewById(R.id.condition3)),
            condition,
        ) {
            condition = it
            formDirty = true
        }

        val typeViews = listOf<TextView>(findViewById(R.id.type1), findViewById(R.id.type2), findViewById(R.id.type3))
        if (priceTypeLocked) {
            Choice.segments(typeViews, listingType) { /* locked */ }
            typeViews.forEach { it.isEnabled = false; it.alpha = 0.55f }
            findViewById<EditText>(R.id.inputPrice).isEnabled = false
            findViewById<View>(R.id.rowPrice).alpha = 0.6f
            findViewById<View>(R.id.editLockedBanner).isVisible = true
        } else {
            Choice.segments(typeViews, listingType) {
                listingType = it
                formDirty = true
                bindType(animate = true)
            }
        }

        bindType(animate = false)
        renderCollegeTags()
        renderPhotoRail(animate = false)
        countCharacters(findViewById(R.id.inputDescription), findViewById(R.id.counterDescription))
        watchDirtyFields()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmLeaveIfDirty()
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingCameraUri?.let { outState.putString(STATE_CAMERA_URI, it.toString()) }
        outState.putStringArrayList(STATE_PHOTO_REFS, ArrayList(photoRefs))
    }

    override fun goBack(view: View) {
        Motion.tick(view)
        confirmLeaveIfDirty()
    }

    private fun confirmLeaveIfDirty() {
        if (!formDirty) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.discard_changes_title)
            .setMessage(R.string.discard_changes_message)
            .setNegativeButton(R.string.btn_keep_editing, null)
            .setPositiveButton(R.string.btn_discard) { _, _ -> finish() }
            .show()
    }

    private fun watchDirtyFields() {
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { formDirty = true }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        listOf(R.id.inputTitle, R.id.inputPrice, R.id.inputWants, R.id.inputDescription, R.id.inputMeetupNote)
            .forEach { findViewById<EditText>(it).addTextChangedListener(watcher) }
        formDirty = false
    }

    // ---------------------------------------------------------------- 1..10 Photo Rail

    private fun ingestPickedUris(uris: List<Uri>) {
        val remaining = (Listings.MAX_PHOTOS - photoRefs.size).coerceAtLeast(0)
        if (remaining == 0) {
            Toast.makeText(this, R.string.toast_photo_limit_reached, Toast.LENGTH_SHORT).show()
            return
        }
        val toAdd = uris.take(remaining)
        lifecycleScope.launch {
            var added = 0
            for (uri in toAdd) {
                val ref = ImagePipeline.ingestUri(this@CreateListingActivity, uri) ?: uri.toString()
                if (photoRefs.size < Listings.MAX_PHOTOS) {
                    photoRefs.add(ref)
                    added++
                }
            }
            if (added > 0) {
                formDirty = true
                renderPhotoRail(animate = true)
                Toast.makeText(this@CreateListingActivity, R.string.toast_photo_added, Toast.LENGTH_SHORT).show()
            }
            if (uris.size > remaining) {
                Toast.makeText(this@CreateListingActivity, R.string.toast_photo_limit_reached, Toast.LENGTH_SHORT).show()
            }
        }
    }

    internal fun addPhotoRefForTest(ref: String) {
        if (photoRefs.size < Listings.MAX_PHOTOS) {
            photoRefs.add(ref)
            formDirty = true
            renderPhotoRail(animate = false)
        }
    }

    private fun renderPhotoRail(animate: Boolean) {
        val rail = findViewById<LinearLayout>(R.id.photoRail)
        rail.removeAllViews()

        photoRefs.forEachIndexed { index, ref ->
            val tile = LayoutInflater.from(this).inflate(R.layout.item_photo_tile, rail, false)
            val img = tile.findViewById<ImageView>(R.id.tileImage)
            val placeholder = tile.findViewById<View>(R.id.tilePlaceholder)
            AppIntents.bindImageUri(img, placeholder, ref)

            tile.findViewById<View>(R.id.tileCoverBadge).isVisible = (index == 0)
            tile.findViewById<View>(R.id.tileRemove).setOnClickListener {
                Motion.tick(it)
                photoRefs.removeAt(index)
                formDirty = true
                renderPhotoRail(animate = true)
                Toast.makeText(this, R.string.toast_photo_removed, Toast.LENGTH_SHORT).show()
            }
            tile.setOnClickListener { showPhotoOptions(index) }
            rail.addView(tile)
        }

        if (photoRefs.size < Listings.MAX_PHOTOS) {
            val addTile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(this@CreateListingActivity, 86), dp(this@CreateListingActivity, 86))
                setBackgroundResource(
                    if (photoRefs.isEmpty()) R.drawable.bg_tile_dashed_cover else R.drawable.bg_tile_dashed,
                )
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                isClickable = true
                isFocusable = true
                addView(
                    ImageView(this@CreateListingActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(this@CreateListingActivity, 22), dp(this@CreateListingActivity, 22))
                        setImageResource(if (photoRefs.isEmpty()) R.drawable.ic_camera else R.drawable.ic_plus)
                        setColorFilter(getColor(R.color.uc_green))
                    },
                )
                addView(
                    TextView(this@CreateListingActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(this@CreateListingActivity, 4)
                        }
                        text = getString(if (photoRefs.isEmpty()) R.string.add_cover else R.string.cd_add_photo)
                        textSize = 10f
                        setTextColor(getColor(R.color.uc_green))
                    },
                )
                setOnClickListener { openAddPhotoDialog() }
            }
            rail.addView(addTile)
        }

        val text = getString(R.string.photo_counter_10_format, photoRefs.size)
        val counter = findViewById<TextView>(R.id.photoCounter)
        if (animate) Motion.rollText(counter, text) else counter.text = text
    }

    private fun openAddPhotoDialog() {
        val options = arrayOf(
            getString(R.string.photo_option_gallery),
            getString(R.string.photo_option_camera),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.btn_add_photos_hint)
            .setItems(options) { _, which ->
                replacePhotoIndex = null
                if (which == 0) launchGalleryPicker(multi = true) else launchCameraCapture()
            }
            .show()
    }

    private fun showPhotoOptions(index: Int) {
        val options = mutableListOf(
            getString(R.string.photo_option_view),
            getString(R.string.photo_option_gallery),
            getString(R.string.photo_option_camera),
        )
        if (index > 0) options.add("Set as Cover photo")
        options.add(getString(R.string.photo_option_remove))

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.photo_picker_title)
            .setItems(options.toTypedArray()) { _, which ->
                val chosen = options[which]
                when (chosen) {
                    getString(R.string.photo_option_view) -> AppIntents.viewMediaRef(this, photoRefs[index])
                    getString(R.string.photo_option_gallery) -> {
                        replacePhotoIndex = index
                        launchGalleryPicker(multi = false)
                    }
                    getString(R.string.photo_option_camera) -> {
                        replacePhotoIndex = index
                        launchCameraCapture()
                    }
                    "Set as Cover photo" -> {
                        val moved = photoRefs.removeAt(index)
                        photoRefs.add(0, moved)
                        formDirty = true
                        renderPhotoRail(animate = true)
                    }
                    getString(R.string.photo_option_remove) -> {
                        photoRefs.removeAt(index)
                        formDirty = true
                        renderPhotoRail(animate = true)
                        Toast.makeText(this, R.string.toast_photo_removed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    internal fun launchGalleryPicker(multi: Boolean = true) {
        try {
            val req = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            if (multi) multiPhotoPicker.launch(req) else singlePhotoPicker.launch(req)
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

    // ---------------------------------------------------------------- Multi-Select College Tags

    private fun buildCollegeTagChips() {
        val box = findViewById<LinearLayout>(R.id.collegeTagChips)
        box.removeAllViews()
        Listings.PROGRAMS.forEach { code ->
            val chip = LayoutInflater.from(this).inflate(R.layout.item_chip, box, false) as TextView
            chip.text = code
            box.addView(chip)
        }
    }

    private fun renderCollegeTags() {
        val box = findViewById<LinearLayout>(R.id.collegeTagChips)
        val chips = (0 until box.childCount).map { box.getChildAt(it) as TextView }
        Choice.toggles(chips, selectedProgramIndices) { updated ->
            selectedProgramIndices.clear()
            selectedProgramIndices.addAll(updated)
            formDirty = true
            renderCollegeTags()
        }
        val selectedCodes = selectedProgramIndices.sorted().map { Listings.PROGRAMS[it] }
        findViewById<TextView>(R.id.selectedCollegesSummary).text = when {
            selectedCodes.isEmpty() -> getString(R.string.tag_all_colleges)
            selectedCodes.size <= 2 -> selectedCodes.joinToString(" \u00B7 ")
            else -> "${selectedCodes[0]} \u00B7 ${selectedCodes[1]} +${selectedCodes.size - 2}"
        }
    }

    // ---------------------------------------------------------------- Prefill, Draft & Share

    private fun prefillFromShare(subject: String?, text: String?, imageRef: String?) {
        if (!subject.isNullOrBlank()) {
            findViewById<EditText>(R.id.inputTitle).setText(subject.take(60))
        }
        if (!text.isNullOrBlank()) {
            findViewById<EditText>(R.id.inputDescription).setText(text.take(300))
        }
        if (!imageRef.isNullOrBlank() && photoRefs.isEmpty()) {
            photoRefs.add(imageRef)
        }
        Toast.makeText(this, R.string.toast_shared_prefilled, Toast.LENGTH_SHORT).show()
    }

    private fun restoreDraft(draft: UserState.ListingDraft) {
        findViewById<EditText>(R.id.inputTitle).setText(draft.title)
        findViewById<EditText>(R.id.inputPrice).setText(draft.price)
        findViewById<EditText>(R.id.inputDescription).setText(draft.description)
        findViewById<EditText>(R.id.inputWants).setText(draft.wants)
        findViewById<EditText>(R.id.inputMeetupNote).setText(draft.meetupNote)
        selectedProgramIndices.clear()
        draft.targetPrograms.forEach { code ->
            Listings.PROGRAMS.indexOf(code).takeIf { it >= 0 }?.let { selectedProgramIndices.add(it) }
        }
        condition = draft.condition
        listingType = draft.listingType
        maxDays = draft.maxDays.coerceIn(1, 120)
        meetup = draft.meetup
        if (meetup in Listings.MEETUP_POINTS.indices) setPicked(R.id.rowMeetupText, Listings.MEETUP_POINTS[meetup])
        blocks.clear()
        draft.availability.times.forEach { blocks += Block(it.days, it.from, it.to) }
        if (blocks.isEmpty()) blocks += Block(setOf(0, 1, 2, 3, 4), 9 * 60, 16 * 60)
        bindFreeTimes(animateLayout = false)
        photoRefs.clear()
        photoRefs.addAll(draft.photoUris.filter { it.isNotBlank() }.take(Listings.MAX_PHOTOS))
        Toast.makeText(this, R.string.toast_draft_restored, Toast.LENGTH_SHORT).show()
    }

    private fun prefill(listing: Listing) {
        priceTypeLocked = !AppRepository.canEditPriceOrType(listing.id)
        findViewById<TextView>(R.id.createTitle).setText(R.string.edit_title)
        findViewById<TextView>(R.id.btnSaveDraft).setText(R.string.action_cancel)
        findViewById<TextView>(R.id.btnPostItem).setText(R.string.btn_save_changes)
        findViewById<EditText>(R.id.inputTitle).setText(listing.title)
        findViewById<EditText>(R.id.inputPrice).setText(listing.price.toString())
        findViewById<EditText>(R.id.inputDescription).setText(listing.description)
        findViewById<EditText>(R.id.inputMeetupNote).setText(listing.meetupNote)
        selectedProgramIndices.clear()
        listing.targetPrograms.forEach { code ->
            Listings.PROGRAMS.indexOf(code).takeIf { it >= 0 }?.let { selectedProgramIndices.add(it) }
        }
        meetup = Listings.MEETUP_POINTS.indexOf(listing.meetup)
        if (meetup >= 0) setPicked(R.id.rowMeetupText, listing.meetup)
        condition = listing.condition
        blocks.clear()
        listing.availability.times.forEach { blocks += Block(it.days, it.from, it.to) }
        bindFreeTimes(animateLayout = false)
        listingType = listing.type
        findViewById<EditText>(R.id.inputWants).setText(listing.wants)
        maxDays = listing.maxDays.coerceIn(1, 120)
        if (photoRefs.isEmpty()) photoRefs.addAll(listing.photoUris.take(Listings.MAX_PHOTOS))
    }

    // ---------------------------------------------------------------- Meetup, Type & Day-Unit Loan

    fun pickMeetup(view: View) {
        Dropdown.show(
            view,
            Listings.MEETUP_POINTS,
            meetup,
            icons = listOf(R.drawable.ic_location),
            rotateChevron = true,
        ) {
            meetup = it
            formDirty = true
            setPicked(R.id.rowMeetupText, Listings.MEETUP_POINTS[it])
        }
    }

    private fun setPicked(targetId: Int, value: String) {
        val target = findViewById<TextView>(targetId)
        target.text = value
        Motion.textColor(target, getColor(R.color.text_primary), animate = true)
    }

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
                    formDirty = true
                    bindFreeTimes(animateLayout = true)
                }
            }
            val dayChips = DAY_IDS.map { card.findViewById<TextView>(it) }
            fun bindDays() {
                Choice.toggles(dayChips, block.days, inactiveBg = R.drawable.bg_input) { updated ->
                    block.days = updated
                    formDirty = true
                    bindDays()
                }
            }
            bindDays()
            val fromText = card.findViewById<TextView>(R.id.freeFromText)
            val toText = card.findViewById<TextView>(R.id.freeToText)
            fromText.text = Availability.time(block.from)
            toText.text = Availability.time(block.to)
            card.findViewById<View>(R.id.freeFrom).setOnClickListener {
                pickBlockTime(block, isFrom = true) { fromText.text = Availability.time(block.from); formDirty = true }
            }
            card.findViewById<View>(R.id.freeTo).setOnClickListener {
                pickBlockTime(block, isFrom = false) { toText.text = Availability.time(block.to); formDirty = true }
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

    fun addFreeTime(view: View) {
        Motion.tick(view)
        blocks += Block(setOf(5, 6), 9 * 60, 12 * 60)
        formDirty = true
        bindFreeTimes(animateLayout = true, animateLastIn = true)
    }

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
                isFrom -> { block.from = minutes; onUpdated() }
                else -> { block.to = minutes; onUpdated() }
            }
        }
        picker.show(supportFragmentManager, "free")
    }

    private fun bindType(animate: Boolean) {
        if (animate) Motion.layoutTransition(findViewById(R.id.formContent), durationMs = 220L)
        val swap = listingType == 2
        val rent = listingType == 1
        listOf(R.id.labelPrice, R.id.rowPrice).forEach { findViewById<View>(it).isVisible = !swap }
        listOf(R.id.labelWants, R.id.inputWants).forEach { findViewById<View>(it).isVisible = swap }
        findViewById<TextView>(R.id.labelPrice).setText(
            if (rent) R.string.lbl_price_per_day else R.string.lbl_price_or_free,
        )
        listOf(R.id.labelMaxRent, R.id.hintMaxRent, R.id.rowMaxRent).forEach { findViewById<View>(it).isVisible = rent }
        if (rent) bindMaxRent()
    }

    /** Day-unit loan limit chips: 1 day, 2 days, 3 days, 5 days, 1 week, 2 weeks, 1 month, 2 months, Semester. */
    private fun bindMaxRent() {
        val box = findViewById<LinearLayout>(R.id.maxRentChips)
        if (box.childCount == 0) {
            Listings.RENT_DAYS.forEach { days ->
                val chip = LayoutInflater.from(this).inflate(R.layout.item_chip, box, false) as TextView
                chip.text = Listings.daysLabel(this, days)
                box.addView(chip)
            }
        }
        val chips = (0 until box.childCount).map { box.getChildAt(it) as TextView }
        val activeIdx = Listings.RENT_DAYS.indexOf(maxDays).takeIf { it >= 0 } ?: 4
        Choice.chips(chips, activeIdx) {
            maxDays = Listings.RENT_DAYS[it]
            formDirty = true
        }
    }

    fun saveDraft(view: View) {
        if (editing != null) {
            confirmLeaveIfDirty()
            return
        }
        val targetPrograms = selectedProgramIndices.sorted().map { Listings.PROGRAMS[it] }
        UserState.draft = UserState.ListingDraft(
            title = findViewById<EditText>(R.id.inputTitle).text.toString().trim(),
            targetPrograms = targetPrograms,
            condition = condition,
            listingType = listingType,
            price = findViewById<EditText>(R.id.inputPrice).text.toString().trim(),
            wants = findViewById<EditText>(R.id.inputWants).text.toString().trim(),
            maxDays = maxDays,
            description = findViewById<EditText>(R.id.inputDescription).text.toString().trim(),
            meetup = meetup,
            meetupNote = findViewById<EditText>(R.id.inputMeetupNote).text.toString().trim(),
            availability = Availability(blocks.map { FreeTime(it.days.toSet(), it.from, it.to) }),
            photoUris = photoRefs.toList(),
        )
        formDirty = false
        Motion.confirm(view)
        Toast.makeText(this, R.string.toast_draft_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    /**
     * Validates every required field (including >=1 photo and ₱50,000 cap),
     * then runs the Hybrid Multimodal Prohibited-Item Safety Gate (Local Filter +
     * Google Gemini 2.5 Flash REST API) before publishing or saving edits!
     */
    fun postItem(view: View) {
        if (submitting) return
        val title = findViewById<EditText>(R.id.inputTitle)
        val price = findViewById<EditText>(R.id.inputPrice)
        val wants = findViewById<EditText>(R.id.inputWants)
        val description = findViewById<EditText>(R.id.inputDescription)
        val meetupNote = findViewById<EditText>(R.id.inputMeetupNote)

        val titleText = UcEmailValidator.sanitizeSingleLine(title.text?.toString(), 60)
        val descText = UcEmailValidator.sanitizeMultiLine(description.text?.toString(), 300)
        val wantsText = UcEmailValidator.sanitizeSingleLine(wants.text?.toString(), 80)
        val noteText = UcEmailValidator.sanitizeSingleLine(meetupNote.text?.toString(), 100)
        val parsedPrice = if (listingType == 2) 0 else price.text.toString().trim().toIntOrNull()

        when {
            photoRefs.isEmpty() -> missing(R.string.toast_need_photo, findViewById(R.id.photoRailScroll))
            titleText.isBlank() -> missing(R.string.toast_need_title, title)
            condition < 0 -> missing(R.string.toast_need_condition, findViewById(R.id.condition1))
            listingType != 2 && (price.text.isBlank() || parsedPrice == null) ->
                missing(R.string.toast_need_price, findViewById(R.id.rowPrice))
            listingType != 2 && parsedPrice != null && parsedPrice > Listings.MAX_PRICE ->
                missing(R.string.toast_price_too_high, findViewById(R.id.rowPrice))
            listingType == 2 && wantsText.isBlank() -> missing(R.string.toast_need_wants, wants)
            meetup < 0 -> missing(R.string.toast_need_meetup, findViewById(R.id.rowMeetup))
            blocks.any { it.days.isEmpty() } -> missing(R.string.toast_need_days, findViewById(R.id.freeTimes))
            else -> runSafetyGateAndPublish(
                button = view,
                titleText = titleText,
                parsedPrice = parsedPrice ?: 0,
                descText = descText,
                wantsText = if (listingType == 2) wantsText else null,
                noteText = noteText,
            )
        }
    }

    private fun runSafetyGateAndPublish(
        button: View,
        titleText: String,
        parsedPrice: Int,
        descText: String,
        wantsText: String?,
        noteText: String,
    ) {
        hideKeyboard(button)
        submitting = true
        val postBtn = findViewById<TextView>(R.id.btnPostItem)
        val originalBtnText = postBtn.text
        postBtn.isEnabled = false
        postBtn.text = getString(R.string.safety_checking_title)

        lifecycleScope.launch {
            val verdict = CampusSafetyApi.evaluateListing(
                context = this@CreateListingActivity,
                title = titleText,
                description = descText,
                wants = wantsText,
                meetupNote = noteText,
                photoRefs = photoRefs.toList(),
            )

            submitting = false
            postBtn.isEnabled = true
            postBtn.text = originalBtnText

            when (verdict) {
                is CampusSafetyApi.Verdict.Blocked -> {
                    Motion.reject(button)
                    AppRepository.logModerationBlock(
                        title = titleText,
                        description = descText,
                        category = verdict.category,
                        policySection = verdict.policySection,
                        reason = verdict.reason,
                        source = verdict.source,
                    )
                    MaterialAlertDialogBuilder(this@CreateListingActivity)
                        .setTitle(R.string.safety_blocked_title)
                        .setMessage(getString(R.string.safety_blocked_message, verdict.reason, verdict.policySection))
                        .setPositiveButton(R.string.btn_edit_listing_short, null)
                        .show()
                }
                is CampusSafetyApi.Verdict.Approved,
                is CampusSafetyApi.Verdict.PendingAiReview -> {
                    val aiStatus = if (verdict is CampusSafetyApi.Verdict.Approved) {
                        AiCheckStatus.APPROVED
                    } else {
                        AiCheckStatus.PENDING_REVIEW
                    }
                    commitListing(
                        button = button,
                        titleText = titleText,
                        parsedPrice = parsedPrice,
                        descText = descText,
                        wantsText = wantsText,
                        noteText = noteText,
                        aiCheckStatus = aiStatus,
                    )
                }
            }
        }
    }

    private fun commitListing(
        button: View,
        titleText: String,
        parsedPrice: Int,
        descText: String,
        wantsText: String?,
        noteText: String,
        aiCheckStatus: String,
    ) {
        Motion.confirm(button)
        val targetPrograms = selectedProgramIndices.sorted().map { Listings.PROGRAMS[it] }
        val primaryProgram = targetPrograms.firstOrNull() ?: getString(R.string.tag_all_colleges)
        val availability = Availability(blocks.map { FreeTime(it.days.toSet(), it.from, it.to) })
        val attachedPhotos = photoRefs.toList()
        val existing = editing
        formDirty = false

        if (existing != null) {
            val updated = existing.copy(
                title = titleText,
                price = if (priceTypeLocked) existing.price else parsedPrice,
                program = primaryProgram,
                targetPrograms = targetPrograms,
                condition = condition,
                type = if (priceTypeLocked) existing.type else listingType,
                description = descText,
                meetup = Listings.MEETUP_POINTS[meetup],
                meetupNote = noteText,
                availability = availability,
                wants = wantsText,
                maxDays = maxDays,
                photoUris = attachedPhotos,
                aiCheckStatus = aiCheckStatus,
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
                    title = titleText,
                    price = parsedPrice,
                    program = primaryProgram,
                    condition = condition,
                    type = listingType,
                    description = descText,
                    seller = Listings.ME,
                    meetup = Listings.MEETUP_POINTS[meetup],
                    postedDaysAgo = 0,
                    availability = availability,
                    wants = wantsText,
                    maxDays = maxDays,
                    photoUris = attachedPhotos,
                    targetPrograms = targetPrograms,
                    meetupNote = noteText,
                    ownerUid = UserState.currentUid,
                    aiCheckStatus = aiCheckStatus,
                ),
            )
            UserState.draft = null
            val ref = Listings.referenceCode(created.id)
            val toastRes = if (aiCheckStatus == AiCheckStatus.PENDING_REVIEW) {
                R.string.toast_posted_pending_ai
            } else {
                R.string.toast_listing_posted
            }
            Toast.makeText(this, toastRes, Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(this, ListingPostedActivity::class.java)
                    .putExtra(EXTRA_LISTING, created.id)
                    .putExtra(ListingPostedActivity.EXTRA_REFERENCE, ref),
            )
            finish()
        }
    }

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
        private const val STATE_CAMERA_URI = "state_camera_uri"
        private const val STATE_PHOTO_REFS = "state_photo_refs"
    }
}
