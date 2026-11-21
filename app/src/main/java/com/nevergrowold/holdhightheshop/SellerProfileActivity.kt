package com.nevergrowold.holdhightheshop

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge

/** Public profile of a seller, opened from the seller card on a listing, chat header, or deep link. */
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
        val keyBase = seller.name.hashCode() * 31
        Motion.countUp(findViewById(R.id.statRating), seller.rating, delay = 120, sessionKey = keyBase + 1)
        Motion.countUp(findViewById(R.id.statOnTime), seller.onTime, delay = 170, sessionKey = keyBase + 2)
        Motion.countUp(findViewById(R.id.statCompleted), seller.completed.toString(), delay = 220, sessionKey = keyBase + 3)
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

    /** Shares the seller's profile via implicit ACTION_SEND intent. */
    fun shareSeller(view: View) {
        Motion.tick(view)
        AppIntents.launch(this, AppIntents.shareSellerChooser(this, seller))
    }

    /** "Message ..." button. */
    fun messageSeller(view: View) = openChatWith(seller)
}
