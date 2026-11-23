package com.nevergrowold.holdhightheshop

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.nevergrowold.holdhightheshop.data.AppRepository
import com.nevergrowold.holdhightheshop.security.UcEmailValidator
import kotlinx.coroutines.launch

/**
 * Password Reset screen with strict `@students.uc-bcf.edu.ph` validation
 * and neutral anti-enumeration messaging.
 */
class ForgotPasswordActivity : BaseActivity() {

    override val requiresVerifiedAuth: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_forgot_password)
        BarInsets.apply(this)

        val emailInput = findViewById<EditText>(R.id.inputEmail)
        val statusView = findViewById<TextView>(R.id.forgotStatus)

        intent.getStringExtra(EXTRA_EMAIL)?.takeIf { it.isNotBlank() }?.let {
            emailInput.setText(it)
        }

        findViewById<View>(R.id.btnSendReset).setOnClickListener { btn ->
            val email = UcEmailValidator.normalize(emailInput.text?.toString())
            if (!UcEmailValidator.isValidUcStudentEmail(email)) {
                Motion.shake(emailInput)
                Toast.makeText(this, R.string.signup_domain_error, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            hideKeyboard(btn)
            lifecycleScope.launch {
                AppRepository.sendPasswordReset(this@ForgotPasswordActivity, email)
                statusView.text = getString(R.string.forgot_sent_message, email)
                statusView.isVisible = true
                Motion.confirm(btn)
            }
        }
    }
}
