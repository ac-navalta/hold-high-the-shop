package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.transition.ChangeBounds
import android.transition.Fade
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.lifecycle.lifecycleScope
import com.nevergrowold.holdhightheshop.data.AppRepository
import com.nevergrowold.holdhightheshop.data.AuthResult
import com.nevergrowold.holdhightheshop.security.UcEmailValidator
import kotlinx.coroutines.launch

/**
 * Verified UC Student Log-In screen.
 * Enforces strictly `@students.uc-bcf.edu.ph` student emails paired with password
 * authentication and UC Outlook email verification.
 */
class LoginActivity : BaseActivity() {

    override val requiresVerifiedAuth: Boolean = false

    private var passwordVisible = false
    private var busy = false
    private var pendingDeepLink: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_login)
        BarInsets.apply(this)
        playEntrance(fromSplash = intent.getBooleanExtra(EXTRA_FROM_SPLASH, false))

        pendingDeepLink = intent.getStringExtra(EXTRA_PENDING_DEEP_LINK)

        val email = findViewById<EditText>(R.id.inputEmail)
        val password = findViewById<EditText>(R.id.inputPassword)
        val check = findViewById<View>(R.id.emailCheck)
        val togglePw = findViewById<TextView>(R.id.btnTogglePassword)
        val errorView = findViewById<TextView>(R.id.loginError)

        val initialEmail = intent.getStringExtra(EXTRA_EMAIL).orEmpty().ifBlank { UserState.profile.email }
        if (initialEmail.isNotBlank()) email.setText(initialEmail)

        Motion.setVisiblePop(check, UcEmailValidator.isValidUcStudentEmail(email.text?.toString()))
        email.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                errorView.isVisible = false
                Motion.setVisiblePop(check, UcEmailValidator.isValidUcStudentEmail(s?.toString()))
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        password.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                errorView.isVisible = false
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        password.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                signIn(v)
                true
            } else {
                false
            }
        }

        togglePw.setOnClickListener {
            passwordVisible = !passwordVisible
            val pos = password.selectionEnd
            password.inputType = if (passwordVisible) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            togglePw.text = if (passwordVisible) "Hide" else "Show"
            password.setSelection(pos.coerceAtLeast(0))
        }

        findViewById<View>(R.id.btnForgotPassword).setOnClickListener {
            Motion.tick(it)
            startActivity(
                Intent(this, ForgotPasswordActivity::class.java)
                    .putExtra(EXTRA_EMAIL, email.text?.toString()?.trim().orEmpty()),
            )
        }

        findViewById<View>(R.id.btnGoSignUp).setOnClickListener {
            Motion.tick(it)
            startActivity(
                Intent(this, SignUpActivity::class.java)
                    .putExtra(EXTRA_PENDING_DEEP_LINK, pendingDeepLink),
            )
        }

        val scroll = findViewById<ScrollView>(R.id.loginScroll)
        scroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top < oldBottom - oldTop) {
                scroll.post { scroll.smoothScrollTo(0, scroll.getChildAt(0).height) }
            }
        }
    }

    private fun playEntrance(fromSplash: Boolean) {
        if (!Motion.enabled(this)) return
        window.sharedElementEnterTransition = ChangeBounds().apply {
            duration = 400
            interpolator = Motion.EMPHASIZED
        }
        window.enterTransition = Fade().setDuration(180).excludeTarget(R.id.loginCard, true)

        val card = findViewById<ViewGroup>(R.id.loginCard)
        val dp = resources.displayMetrics.density
        val cardDelay = if (fromSplash) 90L else 0L
        val cardRise = if (fromSplash) 32f * dp else 20f * dp

        card.alpha = 0f
        card.translationY = cardRise
        card.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(cardDelay)
            .setDuration(if (fromSplash) 340 else 260)
            .setInterpolator(Motion.EMPHASIZED)
            .withLayer()
            .start()
    }

    fun signIn(view: View) {
        if (busy) return
        val emailInput = findViewById<EditText>(R.id.inputEmail)
        val passwordInput = findViewById<EditText>(R.id.inputPassword)
        val errorView = findViewById<TextView>(R.id.loginError)
        val emailText = UcEmailValidator.normalize(emailInput.text?.toString())
        val passwordText = passwordInput.text?.toString().orEmpty()

        if (!UcEmailValidator.isValidUcStudentEmail(emailText)) {
            Motion.shake(emailInput)
            errorView.text = getString(R.string.signup_domain_error)
            errorView.isVisible = true
            Toast.makeText(this, R.string.signup_domain_error, Toast.LENGTH_SHORT).show()
            showKeyboard(emailInput)
            return
        }
        if (passwordText.isEmpty()) {
            Motion.shake(passwordInput)
            errorView.text = getString(R.string.login_password_hint)
            errorView.isVisible = true
            showKeyboard(passwordInput)
            return
        }

        hideKeyboard(view)
        setBusy(true)
        lifecycleScope.launch {
            val result = AppRepository.signIn(this@LoginActivity, emailText, passwordText)
            setBusy(false)
            when (result) {
                is AuthResult.VerifiedSuccess -> {
                    Motion.confirm(view)
                    findViewById<View>(R.id.signInArrow)?.let { arrow ->
                        val dp = resources.displayMetrics.density
                        Motion.spring(arrow, DynamicAnimation.TRANSLATION_X, 6f * dp, Motion.Spatial.SNAPPY)
                    }
                    Toast.makeText(
                        this@LoginActivity,
                        getString(R.string.toast_signed_in, UserState.profile.first),
                        Toast.LENGTH_SHORT,
                    ).show()
                    val mainIntent = Intent(this@LoginActivity, MainActivity::class.java).apply {
                        pendingDeepLink?.takeIf { it.isNotBlank() }?.let { dl ->
                            action = Intent.ACTION_VIEW
                            data = Uri.parse(dl)
                        }
                    }
                    startActivity(mainIntent)
                    finish()
                }
                is AuthResult.NeedsVerification -> {
                    startActivity(
                        Intent(this@LoginActivity, VerifyEmailActivity::class.java)
                            .putExtra(EXTRA_PENDING_DEEP_LINK, pendingDeepLink),
                    )
                    finish()
                }
                is AuthResult.Suspended -> {
                    startActivity(Intent(this@LoginActivity, AccountSuspendedActivity::class.java))
                    finish()
                }
                is AuthResult.Error -> {
                    Motion.shake(findViewById(R.id.loginCard))
                    errorView.text = result.message
                    errorView.isVisible = true
                }
            }
        }
    }

    private fun setBusy(loading: Boolean) {
        busy = loading
        findViewById<View>(R.id.signInProgress).isVisible = loading
        findViewById<View>(R.id.signInArrow).isVisible = !loading
        findViewById<View>(R.id.btnSignIn).isEnabled = !loading
    }

    companion object {
        const val EXTRA_FROM_SPLASH = "from_splash"
    }
}
