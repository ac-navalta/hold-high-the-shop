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
        email.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val valid = !s.isNullOrBlank() && s.contains("@")
                Motion.setVisiblePop(check, valid)
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

    /**
     * Signs the student in and opens the dashboard. finish() drops this screen
     * so back from the dashboard leaves the app instead of returning to login.
     */
    fun signIn(view: View) {
        hideKeyboard(view)
        Motion.confirm(view)
        findViewById<View>(R.id.signInArrow)?.let { arrow ->
            val dp = resources.displayMetrics.density
            Motion.spring(arrow, DynamicAnimation.TRANSLATION_X, 6f * dp, Motion.Spatial.SNAPPY)
        }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        const val EXTRA_FROM_SPLASH = "from_splash"
    }
}
