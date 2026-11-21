package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle

/**
 * Compatibility entry point forwarding directly to the Campus Market tab inside
 * [MainActivity], plus the [Filters] model shared with [MarketTab].
 */
class MarketActivity : BaseActivity() {

    /** What the Filters sheet can narrow by. Empty sets mean "any". */
    data class Filters(
        val conditions: Set<Int> = emptySet(),
        val types: Set<Int> = emptySet(),
        val price: Int = 0,
    ) {
        val isActive get() = conditions.isNotEmpty() || types.isNotEmpty() || price != 0

        fun matches(listing: Listing): Boolean =
            (conditions.isEmpty() || listing.condition in conditions) &&
                (types.isEmpty() || listing.type in types) &&
                when (price) {
                    1 -> listing.price < 500
                    2 -> listing.price in 500..1000
                    3 -> listing.price > 1000
                    else -> true
                }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forward = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_NAV_TAB, MainActivity.TAB_MARKET)
        intent.getStringExtra(EXTRA_PROGRAM)?.let { forward.putExtra(EXTRA_PROGRAM, it) }
        startActivity(forward)
        finish()
    }
}
