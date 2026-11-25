package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.nevergrowold.holdhightheshop.data.AppRepository
import com.nevergrowold.holdhightheshop.data.AuthResult
import com.nevergrowold.holdhightheshop.security.CampusEmailService
import kotlinx.coroutines.launch

/**
 * 6-Digit UC Outlook Verification Code Gate.
 *
 * Validates the 6-digit one-time verification code sent inside a branded HTML email
 * to the student's `@students.uc-bcf.edu.ph` Microsoft Outlook inbox.
 * Supports single-digit auto-advance, Backspace navigation, 6-digit clipboard paste,
 * 60-second resend cooldown, and one-tap Mail app launcher.
 */
class VerifyEmailActivity : BaseActivity() {

    override val requiresVerifiedAuth: Boolean = false

    private var cooldownTimer: CountDownTimer? = null
    private var canResend = true
    private var verifying = false
    private var suppressOtpWatcher = false
    private lateinit var otpBoxes: List<EditText>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_verify_email)
        BarInsets.apply(this)

        val email = UserState.profile.email.ifBlank { getString(R.string.signup_email_hint) }
        findViewById<TextView>(R.id.verifyBody).text = getString(R.string.verify_body, email)

        otpBoxes = listOf(
            findViewById(R.id.otpDigit1),
            findViewById(R.id.otpDigit2),
            findViewById(R.id.otpDigit3),
            findViewById(R.id.otpDigit4),
            findViewById(R.id.otpDigit5),
            findViewById(R.id.otpDigit6),
        )
        setupOtpInputs()
        refreshLocalPreviewBanner()

        val firebaseActive = AppRepository.isFirebaseConfigured(this)
        val localBtn = findViewById<View>(R.id.btnLocalPreviewContinue)
        localBtn.isVisible = !firebaseActive && !CampusEmailService.isWebhookConfigured()
        localBtn.setOnClickListener {
            lifecycleScope.launch {
                val res = AppRepository.markEmailVerifiedLocalOnly(this@VerifyEmailActivity)
                if (res is AuthResult.VerifiedSuccess) {
                    enterApp()
                }
            }
        }

        findViewById<View>(R.id.btnCheckVerified).setOnClickListener {
            Motion.tick(it)
            submitVerificationCode()
        }

        findViewById<View>(R.id.btnOpenMailApp).setOnClickListener {
            Motion.tick(it)
            AppIntents.launch(this, AppIntents.openEmailAppIntent(), unavailableToastRes = R.string.toast_no_email_app)
        }

        val resendBtn = findViewById<TextView>(R.id.btnResendVerification)
        resendBtn.setOnClickListener {
            if (!canResend || verifying) return@setOnClickListener
            Motion.tick(it)
            lifecycleScope.launch {
                val res = AppRepository.resendVerificationEmail(this@VerifyEmailActivity)
                if (res.isSuccess) {
                    clearOtpBoxes()
                    refreshLocalPreviewBanner()
                    Toast.makeText(this@VerifyEmailActivity, R.string.toast_verification_sent, Toast.LENGTH_SHORT).show()
                    startCooldown(resendBtn)
                } else {
                    Toast.makeText(
                        this@VerifyEmailActivity,
                        res.exceptionOrNull()?.localizedMessage ?: "Please wait before resending.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

        findViewById<View>(R.id.btnUseDifferentEmail).setOnClickListener {
            lifecycleScope.launch {
                AppRepository.signOut(this@VerifyEmailActivity)
                startActivity(Intent(this@VerifyEmailActivity, SignUpActivity::class.java))
                finish()
            }
        }
    }

    private fun setupOtpInputs() {
        val errorView = findViewById<TextView>(R.id.verifyError)

        otpBoxes.forEachIndexed { index, box ->
            box.setOnFocusChangeListener { _, _ -> updateOtpBoxBackgrounds() }

            box.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DEL) {
                    if (box.text.isNullOrEmpty() && index > 0) {
                        val prev = otpBoxes[index - 1]
                        suppressOtpWatcher = true
                        prev.setText("")
                        suppressOtpWatcher = false
                        prev.requestFocus()
                        updateOtpBoxBackgrounds()
                        return@setOnKeyListener true
                    }
                }
                false
            }

            if (index == otpBoxes.lastIndex) {
                box.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE) {
                        submitVerificationCode()
                        true
                    } else {
                        false
                    }
                }
            }

            box.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if (suppressOtpWatcher) return
                    errorView.isVisible = false
                    val digits = s?.toString().orEmpty().filter { it.isDigit() }

                    // Support pasting a multi-digit or full 6-digit code into any box
                    if (digits.length > 1) {
                        fillCodeAcrossBoxes(digits, startAt = if (digits.length >= 6) 0 else index)
                        if (enteredCode().length == 6) {
                            submitVerificationCode()
                        }
                        return
                    }

                    if (s?.toString() != digits) {
                        suppressOtpWatcher = true
                        box.setText(digits)
                        box.setSelection(digits.length)
                        suppressOtpWatcher = false
                    }

                    updateOtpBoxBackgrounds()

                    if (digits.length == 1) {
                        if (index < otpBoxes.lastIndex) {
                            otpBoxes[index + 1].requestFocus()
                        } else if (enteredCode().length == 6) {
                            hideKeyboard(box)
                            submitVerificationCode()
                        }
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        updateOtpBoxBackgrounds()
    }

    internal fun fillCodeAcrossBoxes(rawDigits: String, startAt: Int = 0) {
        val clean = rawDigits.filter { it.isDigit() }
        suppressOtpWatcher = true
        clean.forEachIndexed { offset, ch ->
            val targetIdx = startAt + offset
            if (targetIdx in otpBoxes.indices) {
                otpBoxes[targetIdx].setText(ch.toString())
                otpBoxes[targetIdx].setSelection(1)
            }
        }
        suppressOtpWatcher = false
        val focusIdx = (startAt + clean.length).coerceAtMost(otpBoxes.lastIndex)
        otpBoxes[focusIdx].requestFocus()
        updateOtpBoxBackgrounds()
    }

    private fun clearOtpBoxes() {
        suppressOtpWatcher = true
        otpBoxes.forEach { it.setText("") }
        suppressOtpWatcher = false
        otpBoxes.firstOrNull()?.requestFocus()
        updateOtpBoxBackgrounds()
    }

    private fun updateOtpBoxBackgrounds() {
        otpBoxes.forEach { box ->
            val active = box.hasFocus() || !box.text.isNullOrEmpty()
            box.setBackgroundResource(if (active) R.drawable.bg_input_selected else R.drawable.bg_input)
        }
    }

    private fun enteredCode(): String =
        otpBoxes.joinToString("") { it.text?.toString().orEmpty().trim() }

    private fun refreshLocalPreviewBanner() {
        val banner = findViewById<TextView>(R.id.verifyLocalBanner)
        val previewCode = AppRepository.localPreviewVerificationCode()
        if (previewCode.length == 6 && !CampusEmailService.isWebhookConfigured()) {
            banner.text = getString(R.string.verify_local_note, previewCode)
            banner.isVisible = true
            banner.setOnClickListener {
                Motion.tick(it)
                fillCodeAcrossBoxes(previewCode)
                submitVerificationCode()
            }
        } else {
            banner.isVisible = false
        }
    }

    override fun onDestroy() {
        cooldownTimer?.cancel()
        super.onDestroy()
    }

    private fun submitVerificationCode() {
        if (verifying) return
        val code = enteredCode()
        val errorView = findViewById<TextView>(R.id.verifyError)
        val otpRow = findViewById<View>(R.id.otpContainer)

        if (code.length != 6) {
            Motion.shake(otpRow)
            errorView.text = getString(R.string.toast_not_verified_yet)
            errorView.isVisible = true
            return
        }

        hideKeyboard(otpRow)
        setVerifying(true)
        lifecycleScope.launch {
            val res = AppRepository.verifyEmailCode(this@VerifyEmailActivity, code)
            setVerifying(false)
            when (res) {
                is AuthResult.VerifiedSuccess -> enterApp()
                is AuthResult.NeedsVerification -> {
                    Motion.shake(otpRow)
                    errorView.text = getString(R.string.toast_not_verified_yet)
                    errorView.isVisible = true
                }
                is AuthResult.Error -> {
                    Motion.shake(otpRow)
                    errorView.text = res.message
                    errorView.isVisible = true
                }
                is AuthResult.Suspended -> {
                    startActivity(Intent(this@VerifyEmailActivity, AccountSuspendedActivity::class.java))
                    finish()
                }
            }
        }
    }

    private fun setVerifying(busy: Boolean) {
        verifying = busy
        findViewById<View>(R.id.verifyProgress).isVisible = busy
        findViewById<View>(R.id.btnCheckVerified).isEnabled = !busy
    }

    private fun enterApp() {
        Toast.makeText(this, getString(R.string.toast_signed_in, UserState.profile.first), Toast.LENGTH_SHORT).show()
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            intent.getStringExtra(EXTRA_PENDING_DEEP_LINK)?.takeIf { it.isNotBlank() }?.let { dl ->
                action = Intent.ACTION_VIEW
                data = Uri.parse(dl)
            }
        }
        startActivity(mainIntent)
        finish()
    }

    private fun startCooldown(button: TextView) {
        canResend = false
        button.alpha = 0.5f
        cooldownTimer?.cancel()
        cooldownTimer = object : CountDownTimer(60_000L, 1_000L) {
            override fun onTick(ms: Long) {
                button.text = getString(R.string.btn_resend_countdown, (ms / 1000L).toInt().coerceAtLeast(1))
            }
            override fun onFinish() {
                canResend = true
                button.alpha = 1f
                button.setText(R.string.btn_resend_verification)
            }
        }.start()
    }
}
