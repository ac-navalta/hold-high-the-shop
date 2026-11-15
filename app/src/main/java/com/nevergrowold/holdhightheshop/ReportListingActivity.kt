package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge

class ReportListingActivity : BaseActivity() {

    private var reason = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_report_listing)
        BarInsets.apply(this)

        Choice.rows(
            listOf(
                findViewById(R.id.reason1), findViewById(R.id.reason2), findViewById(R.id.reason3),
                findViewById(R.id.reason4), findViewById(R.id.reason5), findViewById(R.id.reason6)
            ),
            listOf(
                findViewById(R.id.reason1Icon), findViewById(R.id.reason2Icon), findViewById(R.id.reason3Icon),
                findViewById(R.id.reason4Icon), findViewById(R.id.reason5Icon), findViewById(R.id.reason6Icon)
            ),
            reason
        ) { reason = it }

        val input = findViewById<EditText>(R.id.inputReportDetails)
        val counter = findViewById<TextView>(R.id.counterReport)
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                counter.text = getString(R.string.counter_format, s?.length ?: 0)
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    /** Sends the report, but only once a reason has been chosen. */
    fun submitReport(view: View) {
        if (reason < 0) {
            Toast.makeText(this, R.string.toast_select_reason, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, ReportReceivedActivity::class.java))
        finish()
    }
}
