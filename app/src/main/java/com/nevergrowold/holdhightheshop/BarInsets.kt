package com.nevergrowold.holdhightheshop

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Pushes the status bar height into the screen's top area and the navigation
 * bar height into the bottom bar, so colour still reaches both edges of the
 * screen but nothing is hidden underneath the system bars.
 *
 * Every Activity calls this, which is what keeps the top and bottom looking
 * the same from one screen to the next.
 */
object BarInsets {

    fun apply(activity: Activity) {
        val root: View = activity.findViewById(R.id.main) ?: return
        val header: View? = activity.findViewById(R.id.headerArea)
        val bottom: View? = activity.findViewById(R.id.bottomNav)

        val headerTop = header?.paddingTop ?: 0
        val bottomPad = bottom?.paddingBottom ?: 0

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            header?.updatePadding(top = headerTop + bars.top)
            bottom?.updatePadding(bottom = bottomPad + bars.bottom)
            insets
        }
    }
}
