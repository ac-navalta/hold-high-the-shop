package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge

class LoginActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_login)
        BarInsets.apply(this)
    }

    /**
     * Signs the student in and opens the dashboard. finish() drops this screen
     * so back from the dashboard leaves the app instead of returning to login.
     */
    fun signIn(view: View) {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
