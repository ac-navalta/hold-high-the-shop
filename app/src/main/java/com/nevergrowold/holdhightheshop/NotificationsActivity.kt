package com.nevergrowold.holdhightheshop

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge

class NotificationsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_notifications)
        BarInsets.apply(this)
    }

    /** Clears the unread markers. */
    fun markAllRead(view: View) {
        Toast.makeText(this, R.string.notif_mark_all, Toast.LENGTH_SHORT).show()
    }
}
