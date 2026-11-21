package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.transition.ChangeBounds
import android.transition.Fade
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.dynamicanimation.animation.DynamicAnimation

class LoginActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_login)
        BarInsets.apply(this)
        playEntrance(fromSplash = intent.getBooleanExtra(EXTRA_FROM_SPLASH, false))

        val email = findViewById<EditText>(R.id.inputEmail)
        val check = findViewById<View>(R.id.emailCheck)
        email.setText(UserState.profile.email)
        Motion.setVisiblePop(check, isValidEmail(email.text?.toString().orEmpty()))
        email.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                Motion.setVisiblePop(check, isValidEmail(s?.toString().orEmpty()))
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        email.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                signIn(v)
                true
            } else {
                false
            }
        }
        // Keep the whole sign-in card, including the button, visible when the keyboard opens.
        val scroll = findViewById<ScrollView>(R.id.loginScroll)
        scroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top < oldBottom - oldTop) {
                scroll.post { scroll.smoothScrollTo(0, scroll.getChildAt(0).height) }
            }
        }
    }

    /**
     * Arriving from splash, the logo glides cleanly up while the sign-in card
     * rises right in sync. Arriving from logout, the card arrives immediately
     * with no artificial delay.
     */
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

        val content = card.getChildAt(0) as ViewGroup
        val children = (0 until content.childCount).map { content.getChildAt(it) }
        Motion.staggerIn(children, delay = cardDelay + 50L, riseDp = 8f, gapMs = 20L, subtleScale = false)
    }

    private fun isValidEmail(raw: String): Boolean {
        val trimmed = raw.trim()
        val at = trimmed.indexOf('@')
        if (at <= 0 || at != trimmed.lastIndexOf('@')) return false
        val domain = trimmed.substring(at + 1)
        val dot = domain.lastIndexOf('.')
        return dot > 0 && dot < domain.length - 1 && !trimmed.any { it.isWhitespace() }
    }

    /**
     * Validates the UC email, signs the student in with a welcome Toast, and
     * opens [MainActivity] via an explicit Intent carrying the email extra.
     * finish() drops this screen so Back from the dashboard leaves the app.
     */
    fun signIn(view: View) {
        val emailInput = findViewById<EditText>(R.id.inputEmail)
        val emailText = emailInput.text.toString().trim()
        if (!isValidEmail(emailText)) {
            Motion.shake(emailInput)
            Toast.makeText(this, R.string.toast_invalid_email, Toast.LENGTH_SHORT).show()
            showKeyboard(emailInput)
            return
        }
        hideKeyboard(view)
        Motion.confirm(view)
        UserState.updateEmail(this, emailText)
        findViewById<View>(R.id.signInArrow)?.let { arrow ->
            val dp = resources.displayMetrics.density
            Motion.spring(arrow, DynamicAnimation.TRANSLATION_X, 6f * dp, Motion.Spatial.SNAPPY)
        }
        // User Communication (Toast): greets the student upon verified sign-in.
        Toast.makeText(this, getString(R.string.toast_signed_in, UserState.profile.first), Toast.LENGTH_SHORT).show()
        startActivity(
            Intent(this, MainActivity::class.java).putExtra(EXTRA_EMAIL, emailText)
        )
        finish()
    }

    companion object {
        const val EXTRA_FROM_SPLASH = "from_splash"
    }
}
