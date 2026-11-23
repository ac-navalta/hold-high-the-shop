package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.nevergrowold.holdhightheshop.data.AppRepository
import kotlinx.coroutines.launch

/**
 * Displayed whenever a student account is suspended via the Admin Web Portal.
 * Blocks all marketplace access while offering UC Student Support contact and Logout.
 */
class AccountSuspendedActivity : BaseActivity() {

    override val requiresVerifiedAuth: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_account_suspended)
        BarInsets.apply(this)

        val reason = UserState.profile.suspendedReason.ifBlank {
            getString(R.string.suspended_default_reason)
        }
        findViewById<TextView>(R.id.suspendedReason).text = reason

        findViewById<View>(R.id.btnContactSupport).setOnClickListener {
            Motion.tick(it)
            AppIntents.launch(
                this,
                AppIntents.emailSupportIntent(
                    this,
                    "Account Appeal — ${UserState.profile.email}",
                    "Hi UC Student Support,\n\nI would like to appeal my account suspension.\nAccount: ${UserState.profile.email}\n",
                ),
            )
        }

        findViewById<View>(R.id.btnSignOut).setOnClickListener {
            lifecycleScope.launch {
                AppRepository.signOut(this@AccountSuspendedActivity)
                val intent = Intent(this@AccountSuspendedActivity, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }
        }
    }
}
