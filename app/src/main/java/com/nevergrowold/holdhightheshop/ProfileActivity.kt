package com.nevergrowold.holdhightheshop

import android.os.Bundle
import androidx.activity.enableEdgeToEdge

class ProfileActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_profile)
        BarInsets.apply(this)
    }
}
