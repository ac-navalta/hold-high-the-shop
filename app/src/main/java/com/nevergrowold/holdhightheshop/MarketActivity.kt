package com.nevergrowold.holdhightheshop

import android.os.Bundle
import androidx.activity.enableEdgeToEdge

class MarketActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_market)
        BarInsets.apply(this)

        Choice.chips(
            listOf(
                findViewById(R.id.chip1), findViewById(R.id.chip2), findViewById(R.id.chip3),
                findViewById(R.id.chip4), findViewById(R.id.chip5)
            ),
            selected = 0
        )
    }
}
