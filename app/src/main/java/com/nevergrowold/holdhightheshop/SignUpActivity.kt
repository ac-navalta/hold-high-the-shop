package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.nevergrowold.holdhightheshop.data.AppRepository
import com.nevergrowold.holdhightheshop.data.AuthResult
import com.nevergrowold.holdhightheshop.security.UcEmailValidator
import kotlinx.coroutines.launch

/**
 * Verified UC Student Sign-Up screen.
 * Enforces strictly `@students.uc-bcf.edu.ph`, strong password policy,
 * UC College & Year Level selection, and hands off to [VerifyEmailActivity].
 */
class SignUpActivity : BaseActivity() {

    override val requiresVerifiedAuth: Boolean = false

    private var selectedProgram: String = Listings.PROGRAMS.first()
    private var selectedYear: String = YEARS.first()
    private var passwordVisible = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_sign_up)
        BarInsets.apply(this)

        val nameInput = findViewById<EditText>(R.id.inputFullName)
        val emailInput = findViewById<EditText>(R.id.inputEmail)
        val emailCheck = findViewById<View>(R.id.emailCheck)
        val programBtn = findViewById<TextView>(R.id.pickProgram)
        val yearBtn = findViewById<TextView>(R.id.pickYear)
        val passwordInput = findViewById<EditText>(R.id.inputPassword)
        val confirmInput = findViewById<EditText>(R.id.inputConfirmPassword)
        val togglePw = findViewById<TextView>(R.id.btnTogglePassword)
        val strengthView = findViewById<TextView>(R.id.passwordStrength)
        val errorView = findViewById<TextView>(R.id.signUpError)

        programBtn.text = selectedProgram
        yearBtn.text = selectedYear

        emailInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                errorView.isVisible = false
                Motion.setVisiblePop(emailCheck, UcEmailValidator.isValidUcStudentEmail(s?.toString()))
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        passwordInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                errorView.isVisible = false
                val text = s?.toString().orEmpty()
                if (text.isEmpty()) {
                    strengthView.isVisible = false
                } else {
                    val check = UcEmailValidator.checkPassword(text)
                    strengthView.text = check.message
                    strengthView.setTextColor(getColor(if (check.valid) R.color.uc_green else R.color.danger))
                    strengthView.isVisible = true
                }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        togglePw.setOnClickListener {
            passwordVisible = !passwordVisible
            val pos = passwordInput.selectionEnd
            val type = if (passwordVisible) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            passwordInput.inputType = type
            confirmInput.inputType = type
            togglePw.text = if (passwordVisible) "Hide" else "Show"
            passwordInput.setSelection(pos.coerceAtLeast(0))
        }

        programBtn.setOnClickListener {
            pickFromSheet(getString(R.string.signup_program_label), Listings.PROGRAMS, selectedProgram) {
                selectedProgram = it
                programBtn.text = it
            }
        }

        yearBtn.setOnClickListener {
            pickFromSheet(getString(R.string.signup_year_label), YEARS, selectedYear) {
                selectedYear = it
                yearBtn.text = it
            }
        }

        findViewById<View>(R.id.btnGoSignIn).setOnClickListener { finish() }

        findViewById<View>(R.id.btnCreateAccount).setOnClickListener { btn ->
            if (busy) return@setOnClickListener
            val fullName = UcEmailValidator.sanitizeSingleLine(nameInput.text?.toString(), 60)
            val email = UcEmailValidator.normalize(emailInput.text?.toString())
            val password = passwordInput.text?.toString().orEmpty()
            val confirm = confirmInput.text?.toString().orEmpty()

            if (fullName.length < 2) {
                showError(errorView, nameInput, getString(R.string.toast_need_name))
                return@setOnClickListener
            }
            if (!UcEmailValidator.isValidUcStudentEmail(email)) {
                showError(errorView, emailInput, getString(R.string.signup_domain_error))
                return@setOnClickListener
            }
            val pwCheck = UcEmailValidator.checkPassword(password)
            if (!pwCheck.valid) {
                showError(errorView, passwordInput, pwCheck.message)
                return@setOnClickListener
            }
            if (password != confirm) {
                showError(errorView, confirmInput, getString(R.string.signup_password_mismatch))
                return@setOnClickListener
            }

            hideKeyboard(btn)
            setBusy(true)
            lifecycleScope.launch {
                val result = AppRepository.signUp(
                    context = this@SignUpActivity,
                    rawEmail = email,
                    password = password,
                    rawName = fullName,
                    program = selectedProgram,
                    year = selectedYear,
                )
                setBusy(false)
                when (result) {
                    is AuthResult.NeedsVerification -> {
                        Motion.confirm(btn)
                        Toast.makeText(this@SignUpActivity, R.string.toast_verification_sent, Toast.LENGTH_LONG).show()
                        startActivity(
                            Intent(this@SignUpActivity, VerifyEmailActivity::class.java)
                                .putExtra(EXTRA_PENDING_DEEP_LINK, intent.getStringExtra(EXTRA_PENDING_DEEP_LINK)),
                        )
                        finish()
                    }
                    is AuthResult.VerifiedSuccess -> {
                        startActivity(Intent(this@SignUpActivity, MainActivity::class.java))
                        finish()
                    }
                    is AuthResult.Suspended -> {
                        startActivity(Intent(this@SignUpActivity, AccountSuspendedActivity::class.java))
                        finish()
                    }
                    is AuthResult.Error -> {
                        errorView.text = result.message
                        errorView.isVisible = true
                    }
                }
            }
        }
    }

    private fun showError(errorView: TextView, field: View, message: String) {
        Motion.shake(field)
        errorView.text = message
        errorView.isVisible = true
    }

    private fun setBusy(loading: Boolean) {
        busy = loading
        findViewById<View>(R.id.signUpProgress).isVisible = loading
        findViewById<View>(R.id.btnCreateAccount).isEnabled = !loading
    }

    private fun pickFromSheet(title: String, options: List<String>, current: String, onPicked: (String) -> Unit) {
        var selectedIdx = options.indexOf(current).coerceAtLeast(0)
        Sheets.show(this, R.layout.sheet_choose) { sheet, dialog ->
            sheet.findViewById<TextView>(R.id.chooseKicker).text = getString(R.string.signup_kicker)
            sheet.findViewById<TextView>(R.id.chooseTitle).text = title
            val box = sheet.findViewById<LinearLayout>(R.id.chooseRows)
            val rows = options.map { item ->
                LayoutInflater.from(this).inflate(R.layout.item_swap_option, box, false).also {
                    it.findViewById<TextView>(R.id.optionTitle).text = item
                    it.findViewById<View>(R.id.optionMeta).visibility = View.GONE
                    box.addView(it)
                }
            }
            Choice.rows(rows, rows.map { it.findViewById(R.id.optionRadio) }, selectedIdx) { selectedIdx = it }
            sheet.findViewById<TextView>(R.id.choosePrimary).apply {
                setText(R.string.btn_done)
                setOnClickListener {
                    onPicked(options[selectedIdx])
                    dialog.dismiss()
                }
            }
        }
    }

    companion object {
        val YEARS = listOf("1st Year", "2nd Year", "3rd Year", "4th Year", "5th Year / Grad")
    }
}
