package com.nevergrowold.holdhightheshop

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible

/** Edit profile, opened from the profile "..." menu or Settings. Persists edits to [UserState]. */
class EditProfileActivity : BaseActivity() {

    private val years = listOf("1st Year", "2nd Year", "3rd Year", "4th Year", "5th Year")
    private var program = 0
    private var year = 0
    private var avatarUri: String? = null

    /** Implicit intent launcher: choose a profile photo from gallery (ACTION_GET_CONTENT). */
    private val avatarPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            avatarUri = uri.toString()
            bindAvatar()
            Toast.makeText(this, R.string.toast_avatar_updated, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_edit_profile)
        BarInsets.apply(this)

        val current = UserState.profile
        program = Listings.PROGRAMS.indexOf(current.program).coerceAtLeast(0)
        year = years.indexOf(current.year).coerceAtLeast(0)
        avatarUri = current.avatarUri

        val nameInput = findViewById<EditText>(R.id.inputName)
        val bioInput = findViewById<EditText>(R.id.inputBio)
        val emailText = findViewById<TextView>(R.id.editEmailValue)
        val programText = findViewById<TextView>(R.id.rowEditProgramText)
        val yearText = findViewById<TextView>(R.id.rowEditYearText)

        nameInput.setText(current.name)
        bioInput.setText(current.bio)
        emailText.text = current.email
        programText.text = Listings.PROGRAMS[program]
        yearText.text = years[year]
        bindAvatar(current.name)

        nameInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = bindAvatar(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        findViewById<View>(R.id.rowEditProgram).setOnClickListener { row ->
            Dropdown.show(row, Listings.PROGRAMS, program, rotateChevron = true) {
                program = it
                programText.text = Listings.PROGRAMS[it]
            }
        }
        findViewById<View>(R.id.rowEditYear).setOnClickListener { row ->
            Dropdown.show(row, years, year, rotateChevron = true) {
                year = it
                yearText.text = years[it]
            }
        }
    }

    private fun bindAvatar(nameOverride: String? = null) {
        val initialsView = findViewById<TextView>(R.id.editInitials)
        val photoView = findViewById<ImageView>(R.id.editAvatarPhoto)
        val displayName = nameOverride ?: findViewById<EditText>(R.id.inputName).text.toString()
        initialsView.text = UserState.UserProfile(name = displayName).initials
        AppIntents.bindImageUri(photoView, initialsView, avatarUri)
    }

    /** Tapping the avatar launches an implicit gallery picker intent (ACTION_GET_CONTENT). */
    fun pickAvatar(view: View) {
        Motion.tick(view)
        try {
            avatarPicker.launch(AppIntents.pickImageIntent())
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_no_app_available, Toast.LENGTH_SHORT).show()
        }
    }

    /** "Save changes": validates name, persists profile to [UserState], returns RESULT_OK + Toast. */
    fun saveProfile(view: View) {
        val name = findViewById<EditText>(R.id.inputName)
        val trimmedName = name.text.toString().trim()
        if (trimmedName.isBlank()) {
            Motion.shake(name)
            Toast.makeText(this, R.string.toast_need_name, Toast.LENGTH_SHORT).show()
            showKeyboard(name)
            return
        }
        val bio = findViewById<EditText>(R.id.inputBio).text.toString().trim()
        val selectedProgram = Listings.PROGRAMS[program]
        val selectedYear = years[year]
        UserState.updateProfile(
            context = this,
            name = trimmedName,
            program = selectedProgram,
            year = selectedYear,
            bio = bio,
            avatarUri = avatarUri,
        )
        hideKeyboard(view)
        Motion.confirm(view)
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(EXTRA_PROFILE_NAME, trimmedName)
                .putExtra(EXTRA_PROFILE_PROGRAM, selectedProgram)
                .putExtra(EXTRA_PROFILE_YEAR, selectedYear),
        )
        Toast.makeText(this, R.string.toast_profile_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val EXTRA_PROFILE_NAME = "profile_name"
        const val EXTRA_PROFILE_PROGRAM = "profile_program"
        const val EXTRA_PROFILE_YEAR = "profile_year"
    }
}
