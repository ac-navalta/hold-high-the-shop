package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge

class ListingPostedActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_listing_posted)
        BarInsets.apply(this)
        Celebration.play(this)
    }

    /** Opens the listing that was just posted. */
    fun viewListing(view: View) {
        startActivity(Intent(this, ListingDetailActivity::class.java))
        finish()
    }

    /** Returns to the Campus Market. */
    fun backToMarket(view: View) = switchTab(MainActivity.TAB_MARKET)
}
