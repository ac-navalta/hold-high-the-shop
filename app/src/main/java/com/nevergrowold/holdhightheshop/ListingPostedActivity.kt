package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

class ListingPostedActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_listing_posted)
        BarInsets.apply(this)
    }

    /** Opens the listing that was just posted. */
    fun viewListing(view: View) {
        startActivity(Intent(this, ListingDetailActivity::class.java))
        finish()
    }

    /**
     * Returns to the Campus Market. CLEAR_TOP drops the create form and this
     * screen off the stack, so back from the market goes to the dashboard
     * instead of walking back through the form.
     */
    fun backToMarket(view: View) {
        val intent = Intent(this, MarketActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        finish()
    }
}
