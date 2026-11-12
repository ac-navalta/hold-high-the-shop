package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

class MarketActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_market)
        BarInsets.apply(this)
    }

    /** Goes back to the dashboard. */
    fun openHome(view: View) {
        finish()
    }

    /** Opens the listing detail screen. */
    fun openListing(view: View) {
        startActivity(Intent(this, ListingDetailActivity::class.java))
    }
}
