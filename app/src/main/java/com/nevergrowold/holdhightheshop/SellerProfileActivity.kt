package com.nevergrowold.holdhightheshop

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Public profile of a verified UC student seller, with Share and Block/Unblock safety controls. */
class SellerProfileActivity : BaseActivity() {

    private lateinit var seller: Seller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_seller_profile)
        BarInsets.apply(this)

        seller = Listings.sellerByUid(intent.getStringExtra(EXTRA_SELLER_UID))
            ?: Listings.sellerByName(intent.getStringExtra(EXTRA_SELLER))
        val firstName = seller.first

        findViewById<TextView>(R.id.sellerInitials).text = seller.initials
        findViewById<TextView>(R.id.sellerName).text = seller.name
        findViewById<TextView>(R.id.sellerMeta).text =
            if (seller.showProgram) getString(R.string.seller_meta_format, seller.program, seller.year) else seller.year
        val keyBase = seller.uid.hashCode() * 31
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

    fun shareSeller(view: View) {
        Motion.tick(view)
        AppIntents.launch(this, AppIntents.shareSellerChooser(this, seller))
    }

    fun toggleBlockSeller(view: View) {
        Motion.tick(view)
        val blocked = UserState.isBlocked(seller.uid)
        val label = getString(if (blocked) R.string.menu_unblock_user else R.string.menu_block_user)
        MaterialAlertDialogBuilder(this)
            .setItems(arrayOf(label)) { _, _ ->
                if (blocked) {
                    UserState.unblockUser(seller.uid)
                    Toast.makeText(this, getString(R.string.toast_user_unblocked, seller.first), Toast.LENGTH_SHORT).show()
                } else {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.block_user_title, seller.first))
                        .setMessage(R.string.block_user_message)
                        .setNegativeButton(R.string.action_cancel, null)
                        .setPositiveButton(R.string.btn_block) { _, _ ->
                            UserState.blockUser(seller.uid)
                            Toast.makeText(this, getString(R.string.toast_user_blocked, seller.first), Toast.LENGTH_SHORT).show()
                            finish()
                        }
                        .show()
                }
            }
            .show()
    }

    fun messageSeller(view: View) {
        val firstListingId = Listings.bySeller(seller).firstOrNull()?.id
        openChatWith(seller, firstListingId)
    }
}
