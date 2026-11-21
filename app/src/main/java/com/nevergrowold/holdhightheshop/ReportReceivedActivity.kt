package com.nevergrowold.holdhightheshop

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge

class ReportReceivedActivity : BaseActivity() {

    private var referenceCode: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_report_received)
        BarInsets.apply(this)

        referenceCode = intent.getStringExtra(EXTRA_REFERENCE) ?: getString(R.string.received_ref)
        findViewById<TextView>(R.id.reportRef).text = referenceCode

        // Same stamp as "Listing posted": the report is in.
        Celebration.play(this)
    }

    /** Copies the report reference code to clipboard with a Toast confirmation. */
    fun copyReference(view: View) {
        Motion.confirm(view)
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.received_ref_label), referenceCode))
        Toast.makeText(this, getString(R.string.toast_ref_copied, referenceCode), Toast.LENGTH_SHORT).show()
    }

    /** Returns to the listing that was reported. */
    fun backToListing(view: View) {
        finish()
    }

    /** Returns to the Campus Market. */
    fun returnToMarket(view: View) = switchTab(MainActivity.TAB_MARKET)

    companion object {
        const val EXTRA_REFERENCE = "reference"
    }
}
