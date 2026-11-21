package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle

/**
 * Compatibility entry point forwarding directly to the Chats tab inside [MainActivity].
 */
class ChatsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_NAV_TAB, MainActivity.TAB_CHATS)
        )
        finish()
    }
}
