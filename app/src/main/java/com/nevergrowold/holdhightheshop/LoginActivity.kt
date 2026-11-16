package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.view.View
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
     * Signs the student in and opens the dashboard. finish() drops this screen
     * so back from the dashboard leaves the app instead of returning to login.
     */
    fun signIn(view: View) {
        hideKeyboard(view)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
