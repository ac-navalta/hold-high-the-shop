package com.nevergrowold.holdhightheshop

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

class ListingDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_listing_detail)
        BarInsets.apply(this)
    }

    /** Returns to the previous screen. */
    fun goBack(view: View) {
        finish()
    }
}
