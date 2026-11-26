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
import com.google.firebase.firestore.ListenerRegistration
import com.nevergrowold.holdhightheshop.data.AppRepository
import com.nevergrowold.holdhightheshop.data.AuthResult
import com.nevergrowold.holdhightheshop.security.CampusEmailService
import kotlinx.coroutines.launch

/**
 * Branded UC Outlook Email Verification Gate (Button Link Flow).
 *
 * Guides the student to tap the "Verify My UC Email" button inside the HTML email
 * sent to their `@students.uc-bcf.edu.ph` Microsoft Outlook inbox, which opens our
 * custom Hold High The Shop `/auth/action` verification page.
 *
 * Includes a real-time Firestore listener on `users/{uid}` (auto-unlocks the moment
 * the student confirms in their browser or on another device), automatic `onResume()`
 * re-check, 60-second resend cooldown, and one-tap Mail app launcher.
 */
class VerifyEmailActivity : BaseActivity() {

    override val requiresVerifiedAuth: Boolean = false

    private var cooldownTimer: CountDownTimer? = null
    private var verificationListener: ListenerRegistration? = null
    private var canResend = true
    private var checking = false
    private var entered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_verify_email)
        BarInsets.apply(this)

        val email = UserState.profile.email.ifBlank { getString(R.string.signup_email_hint) }
        findViewById<TextView>(R.id.verifyBody).text = getString(R.string.verify_body, email)

        val firebaseActive = AppRepository.isFirebaseConfigured(this)
        val localMode = !firebaseActive && !CampusEmailService.isWebhookConfigured()
        findViewById<View>(R.id.verifyLocalBanner).isVisible = localMode
        val localBtn = findViewById<View>(R.id.btnLocalPreviewContinue)
        localBtn.isVisible = localMode
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
            if (!canResend || checking) return@setOnClickListener
            Motion.tick(it)
            lifecycleScope.launch {
                val res = AppRepository.resendVerificationEmail(this@VerifyEmailActivity)
                if (res.isSuccess) {
                    findViewById<View>(R.id.verifyError).isVisible = false
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

    override fun onStart() {
        super.onStart()
        verificationListener?.remove()
        verificationListener = AppRepository.observeCurrentUserVerification(this) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed && !entered) {
                    checkVerification(showNotYetToast = false)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Automatically re-check when student returns from UC Outlook / browser!
        if (AppRepository.isFirebaseConfigured(this) && !entered) {
            checkVerification(showNotYetToast = false)
        }
    }

    override fun onStop() {
        verificationListener?.remove()
        verificationListener = null
        super.onStop()
    }

    override fun onDestroy() {
        cooldownTimer?.cancel()
        verificationListener?.remove()
        verificationListener = null
        super.onDestroy()
    }

    private fun checkVerification(showNotYetToast: Boolean) {
        if (checking || entered) return
        val errorView = findViewById<TextView>(R.id.verifyError)
        errorView.isVisible = false
        setChecking(true)
        lifecycleScope.launch {
            val res = AppRepository.checkEmailVerified(this@VerifyEmailActivity)
            setChecking(false)
            when (res) {
                is AuthResult.VerifiedSuccess -> enterApp()
                is AuthResult.NeedsVerification -> {
                    if (showNotYetToast) {
                        errorView.text = getString(R.string.toast_not_verified_yet)
                        errorView.isVisible = true
                        Toast.makeText(this@VerifyEmailActivity, R.string.toast_not_verified_yet, Toast.LENGTH_SHORT).show()
                    }
                }
                is AuthResult.Error -> {
                    if (showNotYetToast) {
                        errorView.text = res.message
                        errorView.isVisible = true
                        Toast.makeText(this@VerifyEmailActivity, res.message, Toast.LENGTH_SHORT).show()
                    }
                }
                is AuthResult.Suspended -> {
                    entered = true
                    startActivity(Intent(this@VerifyEmailActivity, AccountSuspendedActivity::class.java))
                    finish()
                }
            }
        }
    }

    private fun setChecking(busy: Boolean) {
        checking = busy
        findViewById<View>(R.id.verifyProgress).isVisible = busy
        findViewById<View>(R.id.btnCheckVerified).isEnabled = !busy
    }

    private fun enterApp() {
        if (entered) return
        entered = true
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
