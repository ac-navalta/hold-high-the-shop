package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nevergrowold.holdhightheshop.data.AppNotification
import com.nevergrowold.holdhightheshop.data.AppRepository
import com.nevergrowold.holdhightheshop.data.NotificationTarget
import com.nevergrowold.holdhightheshop.data.NotificationType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class NotificationsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_notifications)
        BarInsets.apply(this)

        render()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppRepository.stateVersion.collect { render() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val list = findViewById<LinearLayout>(R.id.notificationsList)
        list.removeAllViews()

        val announcements = UserState.announcements()
        val notifs = UserState.notifications()

        if (announcements.isNotEmpty()) {
            addGroupHeader(list, getString(R.string.notif_announcements_section))
            announcements.forEachIndexed { idx, ann ->
                val row = LayoutInflater.from(this).inflate(R.layout.item_notification_row, list, false)
                row.findViewById<ImageView>(R.id.notifIcon).setImageResource(R.drawable.ic_verified)
                row.findViewById<TextView>(R.id.notifTitle).text = ann.title
                row.findViewById<TextView>(R.id.notifBody).text = ann.body
                row.findViewById<TextView>(R.id.notifTime).text = formatTime(ann.createdAt)
                row.findViewById<View>(R.id.notifDivider).isVisible = idx < announcements.lastIndex
                row.findViewById<View>(R.id.notifRowBody).setOnClickListener {
                    Sheets.show(this, R.layout.sheet_info) { sheet, dialog ->
                        sheet.findViewById<TextView>(R.id.infoTitle).text = ann.title
                        sheet.findViewById<TextView>(R.id.infoBody).text = ann.body
                        sheet.findViewById<View>(R.id.infoAction).isVisible = false
                        sheet.findViewById<View>(R.id.infoDone).setOnClickListener { dialog.dismiss() }
                    }
                }
                list.addView(row)
            }
        }

        val today = notifs.filter { Rentals.daysUntil(it.createdAt) == 0 }
        val earlier = notifs.filter { Rentals.daysUntil(it.createdAt) != 0 }

        if (today.isNotEmpty()) {
            addGroupHeader(list, getString(R.string.notif_today))
            today.forEachIndexed { idx, n -> addNotifRow(list, n, showDivider = idx < today.lastIndex) }
        }
        if (earlier.isNotEmpty()) {
            addGroupHeader(list, getString(R.string.notif_earlier))
            earlier.forEachIndexed { idx, n -> addNotifRow(list, n, showDivider = idx < earlier.lastIndex) }
        }

        findViewById<View>(R.id.notificationsEmpty).isVisible = announcements.isEmpty() && notifs.isEmpty()

        val hasUnread = UserState.hasUnreadNotifications
        findViewById<View>(R.id.btnMarkAllRead).apply {
            isEnabled = hasUnread
            alpha = if (hasUnread) 1f else 0.45f
        }
    }

    private fun addGroupHeader(container: LinearLayout, label: String) {
        val header = TextView(this).apply {
            text = label
            setTextColor(getColor(R.color.text_secondary))
            textSize = 10f
            letterSpacing = 0.1f
            setPadding(dp(this@NotificationsActivity, 20), dp(this@NotificationsActivity, 18), dp(this@NotificationsActivity, 20), dp(this@NotificationsActivity, 6))
        }
        container.addView(header)
    }

    private fun addNotifRow(container: LinearLayout, n: AppNotification, showDivider: Boolean) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_notification_row, container, false)
        val body = row.findViewById<View>(R.id.notifRowBody)
        if (!n.read) body.setBackgroundResource(R.drawable.bg_row_unread)
        else body.setBackgroundColor(Color.TRANSPARENT)

        val iconRes = when (n.type) {
            NotificationType.MESSAGE -> R.drawable.ic_chat
            NotificationType.RATING -> R.drawable.ic_star
            NotificationType.REPORT, NotificationType.MODERATION -> R.drawable.ic_flag
            NotificationType.LOAN -> R.drawable.ic_clock
            else -> R.drawable.ic_bell
        }
        row.findViewById<ImageView>(R.id.notifIcon).setImageResource(iconRes)
        row.findViewById<TextView>(R.id.notifTitle).text = n.title
        row.findViewById<TextView>(R.id.notifBody).text = n.body
        row.findViewById<TextView>(R.id.notifTime).text = formatTime(n.createdAt)
        row.findViewById<View>(R.id.notifUnreadDot).isVisible = !n.read
        row.findViewById<View>(R.id.notifDivider).isVisible = showDivider

        body.setOnClickListener {
            UserState.markNotificationRead(n.id)
            when (n.targetType) {
                NotificationTarget.LISTING -> if (n.targetId.isNotBlank()) openListingById(n.targetId)
                NotificationTarget.CHAT -> if (n.targetId.isNotBlank()) openChatThread(n.targetId)
                NotificationTarget.EXCHANGE -> if (n.targetId.isNotBlank()) {
                    startActivity(
                        Intent(this, ExchangeDetailActivity::class.java)
                            .putExtra(ExchangeDetailActivity.EXTRA_EXCHANGE, n.targetId),
                    )
                }
                NotificationTarget.PROFILE_COMPLETED -> switchTab(MainActivity.TAB_PROFILE) {
                    putExtra(EXTRA_TAB, ProfileActivity.TAB_COMPLETED)
                }
                else -> {
                    Sheets.show(this, R.layout.sheet_info) { sheet, dialog ->
                        sheet.findViewById<TextView>(R.id.infoTitle).text = n.title
                        sheet.findViewById<TextView>(R.id.infoBody).text = n.body
                        sheet.findViewById<View>(R.id.infoAction).isVisible = false
                        sheet.findViewById<View>(R.id.infoDone).setOnClickListener { dialog.dismiss() }
                    }
                }
            }
        }
        container.addView(row)
    }

    private fun formatTime(millis: Long): String {
        val diff = System.currentTimeMillis() - millis
        return when {
            diff < 60_000L -> getString(R.string.msg_sent_now)
            Rentals.daysUntil(millis) == 0 -> SimpleDateFormat("h:mm a", Locale.US).format(millis)
            Rentals.daysUntil(millis) == -1 -> getString(R.string.day_yesterday_label)
            else -> SimpleDateFormat("MMM d", Locale.US).format(millis)
        }
    }

    fun markAllRead(view: View) {
        if (!UserState.hasUnreadNotifications) return
        UserState.markAllNotificationsRead()
        Motion.confirm(view)
        Toast.makeText(this, R.string.toast_notif_all_read, Toast.LENGTH_SHORT).show()
        render()
    }
}
