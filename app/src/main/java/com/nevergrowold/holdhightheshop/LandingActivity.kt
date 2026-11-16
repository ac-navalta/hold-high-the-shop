package com.nevergrowold.holdhightheshop

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.transition.Fade
import android.view.View
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge

/**
 * Splash screen. The brand settles in, then hands itself to sign in: the
 * logo row travels up to its place above the sign-in card (a shared element),
 * so the two screens read as one moment rather than a cut.
 */
class LandingActivity : BaseActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var handedOff = false

    private val goToLogin = Runnable {
        val brand = findViewById<View>(R.id.brandRow)
        val intent = Intent(this, LoginActivity::class.java)
        if (Motion.enabled(this)) {
            window.exitTransition = Fade().setDuration(150)
            val options = ActivityOptions.makeSceneTransitionAnimation(this, brand, getString(R.string.transition_brand))
            startActivity(intent, options.toBundle())
        } else {
            startActivity(intent)
        }
        handedOff = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_landing)
        BarInsets.apply(this)
        playEntrance()
        handler.postDelayed(goToLogin, SPLASH_MILLIS)
    }

    /** The mark settles, the name slides in beside it, the tagline follows. */
    private fun playEntrance() {
        if (!Motion.enabled(this)) return
        val row = findViewById<ViewGroup>(R.id.brandRow)
        val mark = row.getChildAt(0)
        val name = row.getChildAt(1)
        val tagline = findViewById<View>(R.id.brandTagline)
        val dp = resources.displayMetrics.density

        mark.alpha = 0f
        mark.scaleX = 0.86f
        mark.scaleY = 0.86f
        mark.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(80).setDuration(520).setInterpolator(Motion.EMPHASIZED).start()

        name.alpha = 0f
        name.translationX = -12 * dp
        name.animate().alpha(1f).translationX(0f)
            .setStartDelay(220).setDuration(480).setInterpolator(Motion.EMPHASIZED).start()

        tagline.alpha = 0f
        tagline.animate().alpha(1f)
            .setStartDelay(420).setDuration(360).setInterpolator(Motion.EASE_OUT).start()
    }

    /** Leave once sign in has fully taken over, so the hand-off is not cut short. */
    override fun onStop() {
        super.onStop()
        if (handedOff) finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(goToLogin)
        super.onDestroy()
    }

    companion object {
        private const val SPLASH_MILLIS = 1600L
    }
}
