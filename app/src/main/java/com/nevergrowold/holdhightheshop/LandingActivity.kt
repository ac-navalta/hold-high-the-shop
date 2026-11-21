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
import androidx.dynamicanimation.animation.DynamicAnimation

/**
 * Splash screen. The brand emblem springs into place with restrained physics,
 * then hands off directly to sign-in via a shared-element glide.
 */
class LandingActivity : BaseActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var handedOff = false

    private val goToLogin = Runnable {
        val brand = findViewById<View>(R.id.brandRow)
        val intent = Intent(this, LoginActivity::class.java)
            .putExtra(LoginActivity.EXTRA_FROM_SPLASH, true)
        if (Motion.enabled(this)) {
            window.exitTransition = Fade().setDuration(140)
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

    /** Mark settles on a spring, wordmark glides beside it, tagline fades in softly. */
    private fun playEntrance() {
        if (!Motion.enabled(this)) return
        val row = findViewById<ViewGroup>(R.id.brandRow)
        val mark = row.getChildAt(0)
        val name = row.getChildAt(1)
        val tagline = findViewById<View>(R.id.brandTagline)
        val dp = resources.displayMetrics.density

        mark.alpha = 0f
        mark.scaleX = 0.88f
        mark.scaleY = 0.88f
        name.alpha = 0f
        name.translationX = -10f * dp
        tagline.alpha = 0f
        tagline.translationY = 6f * dp

        mark.postDelayed({
            if (!mark.isAttachedToWindow) return@postDelayed
            Motion.spring(mark, DynamicAnimation.ALPHA, 1f, Motion.Effects.DEFAULT)
            Motion.springScale(mark, 1f, Motion.Spatial.RESPONSIVE)
        }, 40)

        name.postDelayed({
            if (!name.isAttachedToWindow) return@postDelayed
            Motion.spring(name, DynamicAnimation.ALPHA, 1f, Motion.Effects.DEFAULT)
            Motion.spring(name, DynamicAnimation.TRANSLATION_X, 0f, Motion.Spatial.RESPONSIVE)
        }, 120)

        tagline.postDelayed({
            if (!tagline.isAttachedToWindow) return@postDelayed
            Motion.spring(tagline, DynamicAnimation.ALPHA, 1f, Motion.Effects.DEFAULT)
            Motion.spring(tagline, DynamicAnimation.TRANSLATION_Y, 0f, Motion.Spatial.STANDARD)
        }, 220)
    }

    /** Finish only after sign-in takes over so the shared-element handoff completes. */
    override fun onStop() {
        super.onStop()
        if (handedOff) finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(goToLogin)
        super.onDestroy()
    }

    companion object {
        private const val SPLASH_MILLIS = 1150L
    }
}
