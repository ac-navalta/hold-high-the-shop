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
 * Splash & smart session router.
 *
 * Routes cleanly based on the verified UC student session state:
 * - Signed in & suspended -> [AccountSuspendedActivity]
 * - Signed in & unverified -> [VerifyEmailActivity]
 * - Signed in & verified -> [MainActivity]
 * - Signed out -> [LoginActivity] (with shared-element brand glide)
 */
class LandingActivity : BaseActivity() {

    override val requiresVerifiedAuth: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private var handedOff = false

    private val routeNext = Runnable {
        if (handedOff || isFinishing || isDestroyed) return@Runnable
        handedOff = true
        when {
            UserState.isSignedIn && UserState.isSuspended -> {
                startActivity(Intent(this, AccountSuspendedActivity::class.java))
                finish()
            }
            UserState.isSignedIn && !UserState.isVerifiedSignedIn -> {
                startActivity(Intent(this, VerifyEmailActivity::class.java))
                finish()
            }
            UserState.isVerifiedSignedIn -> {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            else -> {
                val brand = findViewById<View>(R.id.brandRow)
                val next = Intent(this, LoginActivity::class.java)
                    .putExtra(LoginActivity.EXTRA_FROM_SPLASH, true)
                if (Motion.enabled(this)) {
                    window.exitTransition = Fade().setDuration(140)
                    val options = ActivityOptions.makeSceneTransitionAnimation(
                        this,
                        brand,
                        getString(R.string.transition_brand),
                    )
                    startActivity(next, options.toBundle())
                } else {
                    startActivity(next)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_landing)
        BarInsets.apply(this)
        playEntrance()
        findViewById<View>(R.id.main).setOnClickListener {
            handler.removeCallbacks(routeNext)
            routeNext.run()
        }
        handler.postDelayed(routeNext, SPLASH_MILLIS)
    }

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

    override fun onStop() {
        super.onStop()
        if (handedOff) finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(routeNext)
        super.onDestroy()
    }

    companion object {
        private const val SPLASH_MILLIS = 1100L
    }
}
