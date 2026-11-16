package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge

class MainActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        BarInsets.apply(this, hideBottomOnKeyboard = true)

        val row = findViewById<LinearLayout>(R.id.programRow)
        Listings.COLLEGES.forEach { program ->
            val tile = LayoutInflater.from(this).inflate(R.layout.item_program_tile, row, false)
            tile.findViewById<ImageView>(R.id.programIcon).setImageResource(program.icon)
            tile.findViewById<TextView>(R.id.programCode).text = program.code
            tile.tag = program.code
            tile.setOnClickListener { openProgram(it) }
            row.addView(tile)
        }
    }

    /** Opens the notifications screen. */
    fun openNotifications(view: View) {
        startActivity(Intent(this, NotificationsActivity::class.java))
    }

    /** "Items exchanged" card: the Completed tab on the profile lists them. */
    fun openExchanged(view: View) {
        switchTab(ProfileActivity::class.java) { putExtra(EXTRA_TAB, ProfileActivity.TAB_COMPLETED) }
    }

    /** "Active listings" card: the Active tab on the profile lists them. */
    fun openActiveListings(view: View) {
        switchTab(ProfileActivity::class.java) { putExtra(EXTRA_TAB, ProfileActivity.TAB_ACTIVE) }
    }
}
