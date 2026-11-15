package com.nevergrowold.holdhightheshop

import android.os.Bundle
import androidx.activity.enableEdgeToEdge

class ChatsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_chats)
        BarInsets.apply(this)
    }

    /** Opens a conversation. */
    fun openThread(view: android.view.View) {
        startActivity(android.content.Intent(this, ChatThreadActivity::class.java))
    }
}
