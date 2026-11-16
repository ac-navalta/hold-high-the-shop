package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible

class CreateListingActivity : BaseActivity() {

    private var condition = -1
    private var listingType = 0
    private var program = -1
    private var meetup = -1

    private val programs by lazy { Listings.PROGRAMS }

    /** Which photo tiles show the "added" state. UI only for now. */
    private val photos = BooleanArray(MAX_PHOTOS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_create_listing)
        BarInsets.apply(this)

        Choice.pills(
            listOf(findViewById(R.id.condition1), findViewById(R.id.condition2), findViewById(R.id.condition3)),
            condition
        ) { condition = it }

        Choice.segments(
            listOf(findViewById(R.id.type1), findViewById(R.id.type2), findViewById(R.id.type3)),
            listingType
        ) { listingType = it }

        countCharacters(findViewById(R.id.inputDescription), findViewById(R.id.counterDescription))
    }

    /**
     * Photo tiles. Tapping one marks it as added (or removes it again) so the
     * flow can be tested. Picking real photos comes with the backend.
     */
    fun pickPhotos(view: View) {
        val tiles = listOf(R.id.photoTile1, R.id.photoTile2, R.id.photoTile3, R.id.photoTile4)
        val index = tiles.indexOf(view.id).takeIf { it >= 0 } ?: return
        photos[index] = !photos[index]
        val tile = view as ViewGroup
        tile.findViewWithTag<View>(PHOTO_TAG)?.let { tile.removeView(it) }
        for (i in 0 until tile.childCount) tile.getChildAt(i).isVisible = !photos[index]
        if (photos[index]) {
            val added = LayoutInflater.from(this).inflate(R.layout.item_photo_added, tile, false)
            added.tag = PHOTO_TAG
            tile.addView(added)
        }
        findViewById<TextView>(R.id.photoCounter).text =
            getString(R.string.photo_counter_format, photos.count { it })
    }

    /** Lets the seller pick which college the item belongs to. */
    fun pickProgram(view: View) {
        Dropdown.show(view, programs, program) {
            program = it
            setPicked(R.id.rowProgramText, programs[it])
        }
    }

    /** Lets the seller pick where the handover happens. */
    fun pickMeetup(view: View) {
        Dropdown.show(view, Listings.MEETUP_POINTS, meetup, icons = listOf(R.drawable.ic_location)) {
            meetup = it
            setPicked(R.id.rowMeetupText, Listings.MEETUP_POINTS[it])
        }
    }

    private fun setPicked(targetId: Int, value: String) {
        val target = findViewById<TextView>(targetId)
        target.text = value
        target.setTextColor(getColor(R.color.text_primary))
    }

    /** Keeps the 0 / 300 counter in step with what is typed. */
    private fun countCharacters(input: EditText, counter: TextView) {
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                counter.text = getString(R.string.counter_format, s?.length ?: 0)
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    /** Saves the listing as a draft and closes the form. */
    fun saveDraft(view: View) {
        Toast.makeText(this, R.string.toast_draft_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    /** Posts the listing once every required field is filled in. */
    fun postItem(view: View) {
        val title = findViewById<EditText>(R.id.inputTitle)
        val price = findViewById<EditText>(R.id.inputPrice)
        when {
            title.text.isBlank() -> missing(R.string.toast_need_title, title)
            program < 0 -> missing(R.string.toast_need_program, findViewById(R.id.rowProgram))
            condition < 0 -> missing(R.string.toast_need_condition, findViewById(R.id.condition1))
            listingType != 2 && price.text.isBlank() -> missing(R.string.toast_need_price, price)
            meetup < 0 -> missing(R.string.toast_need_meetup, findViewById(R.id.rowMeetup))
            else -> {
                hideKeyboard(view)
                startActivity(Intent(this, ListingPostedActivity::class.java))
                finish()
            }
        }
    }

    /** Says what is missing and brings that field into view. */
    private fun missing(message: Int, field: View) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        val scroll = findViewById<ScrollView>(R.id.formScroll)
        val y = IntArray(2).also { field.getLocationInWindow(it) }[1] -
            IntArray(2).also { scroll.getLocationInWindow(it) }[1]
        scroll.smoothScrollBy(0, y - dp(this, 60))
        if (field is EditText) showKeyboard(field)
    }

    companion object {
        private const val MAX_PHOTOS = 4
        private const val PHOTO_TAG = "photo"
    }
}
