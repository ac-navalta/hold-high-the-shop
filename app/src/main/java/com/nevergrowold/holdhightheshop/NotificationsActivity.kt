package com.nevergrowold.holdhightheshop

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.DynamicAnimation

class NotificationsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_notifications)
        BarInsets.apply(this)

        syncReadState(animate = false)
    }

    override fun onResume() {
        super.onResume()
        syncReadState(animate = false)
    }

    private fun syncReadState(animate: Boolean) {
        val pairs = listOf(
            Triple(1, R.id.notifRow1, R.id.notifDot1),
            Triple(2, R.id.notifRow2, R.id.notifDot2),
        )
        pairs.forEachIndexed { index, (id, rowId, dotId) ->
            if (UserState.isNotificationRead(id)) {
                val row = findViewById<View>(rowId)
                val dot = findViewById<View>(dotId)
                if (animate) {
                    Motion.backgroundColor(row, Color.TRANSPARENT, animate = true)
                    if (!Motion.enabled(this)) {
                        dot.isVisible = false
                    } else if (dot.isVisible) {
                        dot.postDelayed({
                            if (!dot.isAttachedToWindow) return@postDelayed
                            Motion.springScale(dot, 0f, Motion.Effects.FAST)
                            val alpha = Motion.spring(dot, DynamicAnimation.ALPHA, 0f, Motion.Effects.FAST)
                            alpha?.addEndListener { _, canceled, _, _ ->
                                if (!canceled) dot.isVisible = false
                            }
                        }, index * 55L)
                    }
                } else {
                    row.setBackgroundColor(Color.TRANSPARENT)
                    dot.isVisible = false
                }
            }
        }
        val markBtn = findViewById<View>(R.id.btnMarkAllRead)
        if (UserState.notificationsRead) {
            markBtn.isEnabled = false
            if (animate) {
                Motion.spring(markBtn, DynamicAnimation.ALPHA, 0.45f, Motion.Effects.DEFAULT)
            } else {
                markBtn.alpha = 0.45f
            }
        }
    }

    /** Clears all unread highlights, smoothly collapses the dots, and shows a Toast. */
    fun markAllRead(view: View) {
        if (UserState.notificationsRead) return
        UserState.notificationsRead = true
        syncReadState(animate = true)
        Motion.confirm(view)
        Toast.makeText(this, R.string.toast_notif_all_read, Toast.LENGTH_SHORT).show()
    }

    /** "Reservation confirmed": marks read and opens the reserved Casio listing. */
    fun openReservationNotification(view: View) {
        UserState.markNotificationRead(1)
        syncReadState(animate = false)
        openListing(view)
    }

    /** "New message from Miguel Santos": marks read and opens that conversation. */
    fun openMessageNotification(view: View) {
        UserState.markNotificationRead(2)
        syncReadState(animate = false)
        openChatWith(Listings.sellerByName("Miguel Santos"))
    }

    /** "You received a new rating": opens Profile > Completed. */
    fun openRatings(view: View) {
        switchTab(MainActivity.TAB_PROFILE) { putExtra(EXTRA_TAB, ProfileActivity.TAB_COMPLETED) }
    }

    /** "Report reviewed": opens a summary sheet with a shortcut to view the listing. */
    fun openReportNotification(view: View) {
        Sheets.show(this, R.layout.sheet_info) { sheet, dialog ->
            sheet.findViewById<TextView>(R.id.infoTitle).setText(R.string.notif_report_sheet_title)
            sheet.findViewById<TextView>(R.id.infoBody).setText(R.string.notif_report_sheet_body)
            sheet.findViewById<TextView>(R.id.infoAction).apply {
                isVisible = true
                setText(R.string.btn_view_reported_item)
                setOnClickListener {
                    dialog.dismiss()
                    openListingById("item4")
                }
            }
            sheet.findViewById<View>(R.id.infoDone).setOnClickListener { dialog.dismiss() }
        }
    }
}
