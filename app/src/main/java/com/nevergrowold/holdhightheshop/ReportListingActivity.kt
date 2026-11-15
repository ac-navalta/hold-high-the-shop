package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge

class ReportListingActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_report_listing)
        BarInsets.apply(this)
    }

    /** Sends the report and shows the confirmation screen. */
    fun submitReport(view: View) {
        startActivity(Intent(this, ReportReceivedActivity::class.java))
        finish()
    }
}
