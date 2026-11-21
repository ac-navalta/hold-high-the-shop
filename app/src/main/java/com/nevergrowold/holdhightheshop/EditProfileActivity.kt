package com.nevergrowold.holdhightheshop

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge

/** Edit profile, opened from the profile "..." menu or Settings. UI only for now. */
class EditProfileActivity : BaseActivity() {

    private val years = listOf("1st Year", "2nd Year", "3rd Year", "4th Year", "5th Year")
    private var program = Listings.PROGRAMS.indexOf(Listings.ME.program)
    private var year = years.indexOf(Listings.ME.year)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_edit_profile)
        BarInsets.apply(this)

        val programText = findViewById<TextView>(R.id.rowEditProgramText)
        findViewById<View>(R.id.rowEditProgram).setOnClickListener { row ->
            Dropdown.show(row, Listings.PROGRAMS, program, rotateChevron = true) {
                program = it
                programText.text = Listings.PROGRAMS[it]
            }
        }
        val yearText = findViewById<TextView>(R.id.rowEditYearText)
        findViewById<View>(R.id.rowEditYear).setOnClickListener { row ->
            Dropdown.show(row, years, year, rotateChevron = true) {
                year = it
                yearText.text = years[it]
            }
        }
    }

    /** "Save changes": needs a name, then closes with a confirmation. */
    fun saveProfile(view: View) {
        val name = findViewById<EditText>(R.id.inputName)
        if (name.text.isBlank()) {
            Motion.shake(name)
            Toast.makeText(this, R.string.toast_need_name, Toast.LENGTH_SHORT).show()
            showKeyboard(name)
            return
        }
        hideKeyboard(view)
        Motion.confirm(view)
        Toast.makeText(this, R.string.toast_profile_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}
