package com.nevergrowold.holdhightheshop

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Pushes the status bar height into the green header and the navigation bar
 * height into the bottom bar, so the brand colour still reaches both edges of
 * the screen but nothing is hidden underneath the system bars.
 *
 * Every screen in the app calls this, which is what keeps the top and bottom
 * looking identical from one Activity to the next.
 */
object BarInsets {

    fun apply(activity: Activity) {
        val root = activity.findViewById<View>(R.id.main)
        val header = activity.findViewById<View>(R.id.headerArea)
        val nav = activity.findViewById<View>(R.id.bottomNav)

        val headerTop = header.paddingTop
        val navBottom = nav.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            header.updatePadding(top = headerTop + bars.top)
            nav.updatePadding(bottom = navBottom + bars.bottom)
            insets
        }
    }
}
