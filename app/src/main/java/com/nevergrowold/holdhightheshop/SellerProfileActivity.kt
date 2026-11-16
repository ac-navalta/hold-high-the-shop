package com.nevergrowold.holdhightheshop

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge

/** Public profile of a seller, opened from the seller card on a listing. */
class SellerProfileActivity : BaseActivity() {

    private lateinit var seller: Seller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_seller_profile)
        BarInsets.apply(this)

        seller = Listings.sellerByName(intent.getStringExtra(EXTRA_SELLER))
        val firstName = seller.name.substringBefore(' ')

        findViewById<TextView>(R.id.sellerInitials).text = seller.initials
        findViewById<TextView>(R.id.sellerName).text = seller.name
        findViewById<TextView>(R.id.sellerMeta).text = getString(R.string.seller_meta_format, seller.program, seller.year)
        findViewById<TextView>(R.id.statRating).text = seller.rating
        findViewById<TextView>(R.id.statOnTime).text = seller.onTime
        findViewById<TextView>(R.id.statCompleted).text = seller.completed.toString()
        findViewById<TextView>(R.id.sellerListingsTitle).text = getString(R.string.seller_listings_format, firstName)
        findViewById<TextView>(R.id.btnMessageSeller).text = getString(R.string.btn_message_seller, firstName)

        val list = findViewById<LinearLayout>(R.id.sellerListings)
        val items = Listings.bySeller(seller)
        items.forEachIndexed { index, item ->
            val meta = when (item.postedDaysAgo) {
                0 -> getString(R.string.seller_listing_meta_today)
                1 -> getString(R.string.seller_listing_meta_yesterday)
                else -> getString(R.string.seller_listing_meta, item.postedDaysAgo)
            }
            addRow(list, item.title, Listings.priceLabel(this, item), meta, index < items.size - 1) {
                openListingById(item.id)
            }
        }
    }

    /** "Message ..." button. */
    fun messageSeller(view: View) = openChatWith(seller)
}
