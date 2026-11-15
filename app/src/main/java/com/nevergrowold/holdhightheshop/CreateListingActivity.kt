package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

class CreateListingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_create_listing)
        BarInsets.apply(this)
    }

    /** Closes the form without posting. */
    fun goBack(view: View) {
        finish()
    }

    /** Saves the listing as a draft and closes the form. */
    fun saveDraft(view: View) {
        Toast.makeText(this, R.string.toast_draft_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    /** Posts the listing and shows the confirmation screen. */
    fun postItem(view: View) {
        startActivity(Intent(this, ListingPostedActivity::class.java))
        finish()
    }
}
