package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Settings, opened from the "..." menu on the profile.
 * Persists all toggles and default meetup preferences to SharedPreferences via [UserState],
 * and wires external implicit intents for system notification settings, UC Security dialer,
 * student support email, UC website, and sharing the app.
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
                UserState.updateDefaultMeetup(this, it)
                meetupValue.text = Listings.MEETUP_POINTS[it]
                Toast.makeText(this, R.string.toast_setting_saved, Toast.LENGTH_SHORT).show()
            }
        }

        bindSwitches()

        findViewById<View>(R.id.rowSystemNotifications).setOnClickListener {
            Motion.tick(it)
            AppIntents.launch(this, AppIntents.appNotificationSettingsIntent(this))
        }

        info(
            rowId = R.id.rowGuidelines,
            title = R.string.settings_guidelines,
            body = R.string.info_guidelines_body,
            actionLabel = R.string.btn_visit_uc,
            onAction = { AppIntents.launch(this, AppIntents.openWebsiteIntent()) },
        )
        info(
            rowId = R.id.rowSafety,
            title = R.string.settings_safety,
            body = R.string.info_safety_body,
            actionLabel = R.string.btn_call_security,
            onAction = { AppIntents.launch(this, AppIntents.dialSecurityIntent()) },
        )
        info(
            rowId = R.id.rowHelp,
            title = R.string.settings_help,
            body = R.string.info_help_body,
            actionLabel = R.string.btn_email_support,
            onAction = {
                AppIntents.launch(
                    this,
                    AppIntents.emailSupportIntent(
                        context = this,
                        subject = getString(R.string.support_email_subject),
                        body = getString(R.string.support_email_body),
                    ),
                )
            },
        )
        info(
            rowId = R.id.rowAbout,
            title = R.string.settings_about,
            body = R.string.info_about_body,
            actionLabel = R.string.btn_share_app,
            onAction = { AppIntents.launch(this, AppIntents.shareAppChooser(this)) },
        )

        findViewById<View>(R.id.btnLogout).setOnClickListener { confirmLogout() }
    }

    override fun onResume() {
        super.onResume()
        bindAccountSummary()
    }

    private fun bindAccountSummary() {
        val profile = UserState.profile
        findViewById<TextView>(R.id.rowEmailValue)?.text = profile.email
        findViewById<TextView>(R.id.rowProgramValue)?.text =
            getString(R.string.seller_meta_format, profile.program, profile.year)
        val iconRes = Listings.COLLEGES.firstOrNull { it.code == profile.program }?.icon ?: R.drawable.ic_prog_citcs
        findViewById<ImageView>(R.id.rowProgramIcon)?.setImageResource(iconRes)
    }

    private fun bindSwitches() {
        val state = UserState.settings
        val swMessages = findViewById<MaterialSwitch>(R.id.swMessages).apply { isChecked = state.notifyMessages }
        val swReservations = findViewById<MaterialSwitch>(R.id.swReservations).apply { isChecked = state.notifyReservations }
        val swReminders = findViewById<MaterialSwitch>(R.id.swReminders).apply { isChecked = state.notifyReminders }
        val swPriceDrops = findViewById<MaterialSwitch>(R.id.swPriceDrops).apply { isChecked = state.notifyPriceDrops }
        val swShowProgram = findViewById<MaterialSwitch>(R.id.swShowProgram).apply { isChecked = state.showProgram }
        val swActiveStatus = findViewById<MaterialSwitch>(R.id.swActiveStatus).apply { isChecked = state.showActiveStatus }

        fun saveAll(button: View) {
            Motion.tick(button)
            Motion.nudge(button, velocity = 1.3f)
            UserState.updateSettings(
                this,
                UserState.SettingsState(
                    notifyMessages = swMessages.isChecked,
                    notifyReservations = swReservations.isChecked,
                    notifyReminders = swReminders.isChecked,
                    notifyPriceDrops = swPriceDrops.isChecked,
                    showProgram = swShowProgram.isChecked,
                    showActiveStatus = swActiveStatus.isChecked,
                ),
            )
        }

        listOf(swMessages, swReservations, swReminders, swPriceDrops, swShowProgram, swActiveStatus).forEach { sw ->
            sw.setOnCheckedChangeListener { button, _ -> saveAll(button) }
        }
    }

    /** Opens an info sheet with an optional contextual implicit-intent action button. */
    private fun info(
        rowId: Int,
        title: Int,
        body: Int,
        actionLabel: Int? = null,
        onAction: (() -> Unit)? = null,
    ) {
        findViewById<View>(rowId).setOnClickListener {
            Sheets.show(this, R.layout.sheet_info) { sheet, dialog ->
                sheet.findViewById<TextView>(R.id.infoTitle).setText(title)
                sheet.findViewById<TextView>(R.id.infoBody).setText(body)
                val actionBtn = sheet.findViewById<TextView>(R.id.infoAction)
                if (actionLabel != null && onAction != null) {
                    actionBtn.isVisible = true
                    actionBtn.setText(actionLabel)
                    actionBtn.setOnClickListener {
                        dialog.dismiss()
                        onAction()
                    }
                } else {
                    actionBtn.isVisible = false
                }
                sheet.findViewById<View>(R.id.infoDone).setOnClickListener { dialog.dismiss() }
            }
        }
    }
}
