package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge

class ListingDetailActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_listing_detail)
        BarInsets.apply(this)
    }

    /** Opens the report form for this listing. */
    fun openReport(view: View) {
        startActivity(Intent(this, ReportListingActivity::class.java))
    }
}
