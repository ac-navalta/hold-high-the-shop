package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.enableEdgeToEdge

/**
 * Splash screen. Shows the brand for a moment, then moves on to sign in.
 */
class LandingActivity : BaseActivity() {

    private val handler = Handler(Looper.getMainLooper())

    private val goToLogin = Runnable {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_landing)
        BarInsets.apply(this)
        handler.postDelayed(goToLogin, SPLASH_MILLIS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(goToLogin)
        super.onDestroy()
    }

    companion object {
        private const val SPLASH_MILLIS = 1600L
    }
}
