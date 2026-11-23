package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.nevergrowold.holdhightheshop.data.AppRepository
import com.nevergrowold.holdhightheshop.data.AuthResult
import kotlinx.coroutines.launch

/**
 * Real UC Outlook Email Verification Gate.
 *
 * Requires the student to tap the verification link sent to their
 * `@students.uc-bcf.edu.ph` Microsoft Outlook inbox before accessing Campus Market.
 * Includes one-tap Mail app launcher, 60-second resend cooldown, and automatic
 * re-check on `onResume()`.
 */
class VerifyEmailActivity : BaseActivity() {

    override val requiresVerifiedAuth: Boolean = false

    private var cooldownTimer: CountDownTimer? = null
    private var canResend = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_verify_email)
        BarInsets.apply(this)

        val email = UserState.profile.email.ifBlank { getString(R.string.signup_email_hint) }
        findViewById<TextView>(R.id.verifyBody).text = getString(R.string.verify_body, email)

        val firebaseActive = AppRepository.isFirebaseConfigured(this)
        findViewById<View>(R.id.verifyLocalBanner).isVisible = !firebaseActive
        val localBtn = findViewById<View>(R.id.btnLocalPreviewContinue)
        localBtn.isVisible = !firebaseActive
        localBtn.setOnClickListener {
            lifecycleScope.launch {
                val res = AppRepository.markEmailVerifiedLocalOnly(this@VerifyEmailActivity)
                if (res is AuthResult.VerifiedSuccess) {
                    enterApp()
                }
            }
        }

        findViewById<View>(R.id.btnOpenMailApp).setOnClickListener {
            Motion.tick(it)
            AppIntents.launch(this, AppIntents.openEmailAppIntent(), unavailableToastRes = R.string.toast_no_email_app)
        }

        findViewById<View>(R.id.btnCheckVerified).setOnClickListener {
            Motion.tick(it)
            checkVerification(showNotYetToast = true)
        }

        val resendBtn = findViewById<TextView>(R.id.btnResendVerification)
        resendBtn.setOnClickListener {
            if (!canResend) return@setOnClickListener
            Motion.tick(it)
            lifecycleScope.launch {
                val res = AppRepository.resendVerificationEmail(this@VerifyEmailActivity)
                if (res.isSuccess) {
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

    override fun onResume() {
        super.onResume()
        // Automatically re-check when student returns from UC Outlook app!
        if (AppRepository.isFirebaseConfigured(this)) {
            checkVerification(showNotYetToast = false)
        }
    }

    override fun onDestroy() {
        cooldownTimer?.cancel()
        super.onDestroy()
    }

    private fun checkVerification(showNotYetToast: Boolean) {
        lifecycleScope.launch {
            when (val res = AppRepository.checkEmailVerified(this@VerifyEmailActivity)) {
                is AuthResult.VerifiedSuccess -> enterApp()
                is AuthResult.NeedsVerification -> {
                    if (showNotYetToast) {
                        Toast.makeText(this@VerifyEmailActivity, R.string.toast_not_verified_yet, Toast.LENGTH_SHORT).show()
                    }
                }
                is AuthResult.Error -> {
                    if (showNotYetToast) {
                        Toast.makeText(this@VerifyEmailActivity, res.message, Toast.LENGTH_SHORT).show()
                    }
                }
                is AuthResult.Suspended -> {
                    startActivity(Intent(this@VerifyEmailActivity, AccountSuspendedActivity::class.java))
                    finish()
                }
            }
        }
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
