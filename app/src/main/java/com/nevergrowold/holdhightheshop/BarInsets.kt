package com.nevergrowold.holdhightheshop

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding

/**
 * Pushes the status bar height into the screen's top area and the navigation
 * bar height into the bottom bar, so colour still reaches both edges of the
 * screen but nothing is hidden underneath the system bars.
 *
 * It also makes room for the on-screen keyboard. The app draws edge to edge,
 * and in that mode Android no longer shrinks the window when the keyboard
 * opens, so without this the keyboard would sit on top of inputs and buttons
 * (for example the Sign in card on the login screen).
 *
 * Every Activity calls this, which is what keeps the top and bottom looking
 * the same from one screen to the next.
 *
 * @param hideBottomOnKeyboard true on screens with the tab bar. While typing
 * (a search, for instance) the tab bar is hidden instead of riding up on top
 * of the keyboard, which is how most Android apps behave.
 */
object BarInsets {

    fun apply(activity: Activity, hideBottomOnKeyboard: Boolean = false) {
        val root: View = activity.findViewById(R.id.main) ?: return
        val header: View? = activity.findViewById(R.id.headerArea)
        val bottom: View? = activity.findViewById(R.id.bottomNav)
        val fab: View? = activity.findViewById(R.id.fabPost)

        val headerTop = header?.paddingTop ?: 0
        val bottomPad = bottom?.paddingBottom ?: 0
        val rootBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val typing = insets.isVisible(WindowInsetsCompat.Type.ime()) && ime.bottom > 0

            header?.updatePadding(top = headerTop + bars.top)

            when {
                // Tab screens: drop the tab bar and lift the content above the keyboard.
                hideBottomOnKeyboard && bottom != null -> {
                    bottom.isVisible = !typing
                    fab?.isVisible = !typing
                    bottom.updatePadding(bottom = bottomPad + bars.bottom)
                    root.updatePadding(bottom = rootBottom + if (typing) ime.bottom else 0)
                }
                // Screens with an action bar or message box: keep it, sitting on the keyboard.
                bottom != null -> {
                    bottom.updatePadding(bottom = bottomPad + bars.bottom)
                    root.updatePadding(bottom = rootBottom + maxOf(0, ime.bottom - bars.bottom))
                }
                // Screens with no bottom bar, such as login.
                else -> root.updatePadding(bottom = rootBottom + ime.bottom)
            }
            insets
        }
    }
}
