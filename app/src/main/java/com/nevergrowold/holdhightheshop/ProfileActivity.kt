package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle

/**
 * Compatibility entry point forwarding directly to the Profile tab inside [MainActivity],
 * while keeping the sub-tab index constants shared with [ProfileTab].
 */
class ProfileActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forward = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_NAV_TAB, MainActivity.TAB_PROFILE)
        if (intent.hasExtra(EXTRA_TAB)) {
            forward.putExtra(EXTRA_TAB, intent.getIntExtra(EXTRA_TAB, TAB_ACTIVE))
        }
        startActivity(forward)
        finish()
    }

    companion object {
        const val TAB_ACTIVE = 0
        const val TAB_COMPLETED = 1
        const val TAB_SAVED = 2
    }
}
