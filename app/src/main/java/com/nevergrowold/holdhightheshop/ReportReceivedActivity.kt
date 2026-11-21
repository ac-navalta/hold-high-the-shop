package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge

class ReportReceivedActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_report_received)
        BarInsets.apply(this)
        // Same stamp as "Listing posted": the report is in.
        Celebration.play(this)
    }

    /** Returns to the listing that was reported. */
    fun backToListing(view: View) {
        finish()
    }

    /** Returns to the Campus Market. */
    fun returnToMarket(view: View) = switchTab(MainActivity.TAB_MARKET)
}
