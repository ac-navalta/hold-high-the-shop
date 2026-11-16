package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.transition.ChangeBounds
import android.transition.Fade
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ScrollView
import androidx.activity.enableEdgeToEdge

class LoginActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_login)
        BarInsets.apply(this)
        playEntrance()

        val email = findViewById<EditText>(R.id.inputEmail)
        email.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                signIn(v)
                true
            } else {
                false
            }
        }
        // When the keyboard opens the scroll area shrinks; keep the whole card,
        // including the Sign in button, in view rather than just the field.
        val scroll = findViewById<ScrollView>(R.id.loginScroll)
        scroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top < oldBottom - oldTop) {
                scroll.post { scroll.smoothScrollTo(0, scroll.getChildAt(0).height) }
            }
        }
    }

    /**
     * Arriving from the splash, the brand row glides up into place (shared
     * element) while the sign-in card rises from below, its contents a beat
     * behind it. Opened any other way, only the card rises.
     */
    private fun playEntrance() {
        if (!Motion.enabled(this)) return
        window.sharedElementEnterTransition = ChangeBounds().apply {
            duration = 560
            interpolator = Motion.EMPHASIZED
        }
        window.enterTransition = Fade().setDuration(200).excludeTarget(R.id.loginCard, true)

        val card = findViewById<ViewGroup>(R.id.loginCard)
        val dp = resources.displayMetrics.density
        card.alpha = 0f
        card.translationY = 56 * dp
        card.animate().alpha(1f).translationY(0f)
            .setStartDelay(260).setDuration(520).setInterpolator(Motion.EMPHASIZED).start()

        val content = card.getChildAt(0) as ViewGroup
        Motion.enter((0 until content.childCount).map { content.getChildAt(it) }, delay = 340, rise = 10)
    }

    /**
     * Signs the student in and opens the dashboard. finish() drops this screen
     * so back from the dashboard leaves the app instead of returning to login.
     */
    fun signIn(view: View) {
        hideKeyboard(view)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
