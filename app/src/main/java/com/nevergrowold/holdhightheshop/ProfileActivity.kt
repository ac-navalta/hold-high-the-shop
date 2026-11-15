package com.nevergrowold.holdhightheshop

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge

class ProfileActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_profile)
        BarInsets.apply(this)
        selectTab(0)
    }

    /** Moves the green underline and highlights the chosen tab. */
    private fun selectTab(selected: Int) {
        val rows = listOf<View>(findViewById(R.id.tab1), findViewById(R.id.tab2), findViewById(R.id.tab3))
        val labels = listOf<TextView>(
            findViewById(R.id.tab1Label), findViewById(R.id.tab2Label), findViewById(R.id.tab3Label)
        )
        val bars = listOf<View>(findViewById(R.id.tab1Bar), findViewById(R.id.tab2Bar), findViewById(R.id.tab3Bar))

        rows.forEachIndexed { index, row ->
            row.setOnClickListener { selectTab(index) }
            val isOn = index == selected
            labels[index].setTextColor(getColor(if (isOn) R.color.uc_green else R.color.text_secondary))
            if (isOn) {
                bars[index].setBackgroundResource(R.drawable.bg_tab_active)
            } else {
                bars[index].setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        }
    }
}
