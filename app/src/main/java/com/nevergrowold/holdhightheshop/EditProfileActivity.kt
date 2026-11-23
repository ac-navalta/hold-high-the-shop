package com.nevergrowold.holdhightheshop

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nevergrowold.holdhightheshop.media.ImagePipeline
import kotlinx.coroutines.launch

/** Edit profile using zero-permission PickVisualMedia + off-main-thread WebP compression + unsaved changes guard. */
class EditProfileActivity : BaseActivity() {

    private val years = SignUpActivity.YEARS
    private var program = 0
    private var year = 0
    private var avatarUri: String? = null
    private var dirty = false

    private val avatarPicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val ref = ImagePipeline.ingestUri(this@EditProfileActivity, uri) ?: uri.toString()
                avatarUri = ref
                dirty = true
                bindAvatar()
                Toast.makeText(this@EditProfileActivity, R.string.toast_avatar_updated, Toast.LENGTH_SHORT).show()
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
            override fun afterTextChanged(s: Editable?) {
                dirty = true
                bindAvatar(s?.toString().orEmpty())
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        bioInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { dirty = true }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        findViewById<View>(R.id.rowEditProgram).setOnClickListener { row ->
            Dropdown.show(row, Listings.PROGRAMS, program, rotateChevron = true) {
                program = it
                dirty = true
                programText.text = Listings.PROGRAMS[it]
            }
        }
        findViewById<View>(R.id.rowEditYear).setOnClickListener { row ->
            Dropdown.show(row, years, year, rotateChevron = true) {
                year = it
                dirty = true
                yearText.text = years[it]
            }
        }
        dirty = false

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmLeaveIfDirty()
        })
    }

    override fun goBack(view: View) {
        Motion.tick(view)
        confirmLeaveIfDirty()
    }

    private fun confirmLeaveIfDirty() {
        if (!dirty) {
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

    private fun bindAvatar(nameOverride: String? = null) {
        val initialsView = findViewById<TextView>(R.id.editInitials)
        val photoView = findViewById<ImageView>(R.id.editAvatarPhoto)
        val displayName = nameOverride ?: findViewById<EditText>(R.id.inputName).text.toString()
        initialsView.text = UserState.UserProfile(name = displayName).initials
        AppIntents.bindImageUri(photoView, initialsView, avatarUri)
    }

    fun pickAvatar(view: View) {
        Motion.tick(view)
        try {
            avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_no_app_available, Toast.LENGTH_SHORT).show()
        }
    }

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
        dirty = false
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
