package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog

class CreateListingActivity : BaseActivity() {

    private var condition = -1
    private var listingType = 0

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

    /** Lets the seller pick which college the item belongs to. */
    fun pickProgram(view: View) {
        val programs = arrayOf(
            getString(R.string.prog_citcs), getString(R.string.prog_conahs),
            getString(R.string.prog_cba), getString(R.string.prog_cea)
        )
        showPicker(R.string.pick_program, programs, R.id.rowProgramText)
    }

    /** Lets the seller pick where the handover happens. */
    fun pickMeetup(view: View) {
        val places = arrayOf(
            getString(R.string.meetup1), getString(R.string.meetup2),
            getString(R.string.meetup3), getString(R.string.meetup4)
        )
        showPicker(R.string.pick_meetup, places, R.id.rowMeetupText)
    }

    private fun showPicker(titleRes: Int, options: Array<String>, targetId: Int) {
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setItems(options) { _, which ->
                val target = findViewById<TextView>(targetId)
                target.text = options[which]
                target.setTextColor(getColor(R.color.text_primary))
            }
            .show()
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

    /** Posts the listing and shows the confirmation screen. */
    fun postItem(view: View) {
        startActivity(Intent(this, ListingPostedActivity::class.java))
        finish()
    }
}
