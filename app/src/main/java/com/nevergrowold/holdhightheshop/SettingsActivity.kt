package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge

/**
 * Settings, opened from the "..." menu on the profile. Switches and the
 * default meetup point change on screen only; saving them comes with the
 * backend.
 */
class SettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)
        BarInsets.apply(this)

        findViewById<View>(R.id.rowEditProfile).setOnClickListener {
            startActivity(Intent(this, EditProfileActivity::class.java))
        }

        val meetupValue = findViewById<TextView>(R.id.rowDefaultMeetupValue)
        meetupValue.text = Listings.MEETUP_POINTS[UserState.defaultMeetup]
        findViewById<View>(R.id.rowDefaultMeetup).setOnClickListener { row ->
            Dropdown.show(row, Listings.MEETUP_POINTS, UserState.defaultMeetup, icons = listOf(R.drawable.ic_location)) {
                UserState.defaultMeetup = it
                meetupValue.text = Listings.MEETUP_POINTS[it]
            }
        }

        info(R.id.rowGuidelines, R.string.settings_guidelines, R.string.info_guidelines_body)
        info(R.id.rowSafety, R.string.settings_safety, R.string.info_safety_body)
        info(R.id.rowHelp, R.string.settings_help, R.string.info_help_body)
        info(R.id.rowAbout, R.string.settings_about, R.string.info_about_body)

        findViewById<View>(R.id.btnLogout).setOnClickListener { confirmLogout() }
    }

    /** Opens a read-only sheet when the row is tapped. */
    private fun info(rowId: Int, title: Int, body: Int) {
        findViewById<View>(rowId).setOnClickListener {
            Sheets.show(this, R.layout.sheet_info) { sheet, dialog ->
                sheet.findViewById<TextView>(R.id.infoTitle).setText(title)
                sheet.findViewById<TextView>(R.id.infoBody).setText(body)
                sheet.findViewById<View>(R.id.infoDone).setOnClickListener { dialog.dismiss() }
            }
        }
    }
}
