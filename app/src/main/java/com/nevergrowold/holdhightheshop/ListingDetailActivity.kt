package com.nevergrowold.holdhightheshop

import android.os.Bundle
import androidx.activity.enableEdgeToEdge

class ListingDetailActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_listing_detail)
        BarInsets.apply(this)
    }
}
