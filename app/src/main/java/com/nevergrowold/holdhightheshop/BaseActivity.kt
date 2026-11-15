package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.view.View
import androidx.appcompat.app.AppCompatActivity

/**
 * Shared behaviour for every screen in the app.
 *
 * The bottom navigation appears on several layouts, so the handlers for it
 * live here instead of being copied into each Activity. Tab switches use
 * CLEAR_TOP so moving between Home, Market and Chats reuses the screen that
 * is already open rather than stacking another copy of it.
 */
open class BaseActivity : AppCompatActivity() {

    fun openHome(view: View) = switchTab(MainActivity::class.java)

    fun openMarket(view: View) = switchTab(MarketActivity::class.java)

    fun openChats(view: View) = switchTab(ChatsActivity::class.java)

    /** Opens the Create listing form. */
    fun openCreateListing(view: View) {
        startActivity(Intent(this, CreateListingActivity::class.java))
    }

    /** Opens the listing detail screen. */
    fun openListing(view: View) {
        startActivity(Intent(this, ListingDetailActivity::class.java))
    }

    /** Closes the current screen. */
    fun goBack(view: View) {
        finish()
    }

    private fun switchTab(target: Class<*>) {
        if (javaClass == target) return
        val intent = Intent(this, target)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
    }
}
