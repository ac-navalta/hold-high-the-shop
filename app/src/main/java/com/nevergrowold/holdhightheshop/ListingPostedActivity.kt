package com.nevergrowold.holdhightheshop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge

class ListingPostedActivity : BaseActivity() {

    private var listingId: String? = null
    private var referenceCode: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_listing_posted)
        BarInsets.apply(this)

        listingId = intent.getStringExtra(EXTRA_LISTING)
        referenceCode = intent.getStringExtra(EXTRA_REFERENCE)
            ?: listingId?.let { Listings.referenceCode(it) }
            ?: getString(R.string.posted_ref)
        findViewById<TextView>(R.id.postedRef).text = referenceCode

        Celebration.play(this)
    }

    /** Copies the listing reference code to the clipboard with a Toast confirmation. */
    fun copyReference(view: View) {
        Motion.confirm(view)
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.posted_ref_label), referenceCode))
        Toast.makeText(this, getString(R.string.toast_ref_copied, referenceCode), Toast.LENGTH_SHORT).show()
    }

    /** Opens the exact listing that was just posted via an explicit Intent. */
    fun viewListing(view: View) {
        startActivity(
            Intent(this, ListingDetailActivity::class.java)
                .putExtra(EXTRA_LISTING, listingId ?: Listings.DEFAULT_ID)
        )
        finish()
    }

    /** Returns to the Campus Market. */
    fun backToMarket(view: View) = switchTab(MainActivity.TAB_MARKET)

    companion object {
        const val EXTRA_REFERENCE = "reference"
    }
}
