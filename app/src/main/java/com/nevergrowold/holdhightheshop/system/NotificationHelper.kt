package com.nevergrowold.holdhightheshop.system

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.nevergrowold.holdhightheshop.ChatThreadActivity
import com.nevergrowold.holdhightheshop.ExchangeDetailActivity
import com.nevergrowold.holdhightheshop.ListingDetailActivity
import com.nevergrowold.holdhightheshop.MainActivity
import com.nevergrowold.holdhightheshop.NotificationsActivity
import com.nevergrowold.holdhightheshop.R
import com.nevergrowold.holdhightheshop.UserState
import com.nevergrowold.holdhightheshop.data.NotificationTarget

/**
 * Central Android system notification manager for Hold High The Shop.
 *
 * Security & Permissions (`android-intent-security` & `android-permissions-security`):
 * - Creates 4 scoped Android 8+ NotificationChannels (`hhts_messages`, `hhts_deals`,
 *   `hhts_reminders`, `hhts_announcements`).
 * - Never caches `POST_NOTIFICATIONS`; always queries `ContextCompat.checkSelfPermission`
 *   at post time and catches `SecurityException`.
 * - Uses `FLAG_IMMUTABLE` on all standard PendingIntents.
 * - Uses `FLAG_MUTABLE` ONLY for the inline Chat Direct Reply (`RemoteInput`) action,
 *   paired with an explicit `ComponentName(context, DealAlertReceiver::class.java)`.
 */
object NotificationHelper {

    const val CHANNEL_MESSAGES = "hhts_messages"
    const val CHANNEL_DEALS = "hhts_deals"
    const val CHANNEL_REMINDERS = "hhts_reminders"
    const val CHANNEL_ANNOUNCEMENTS = "hhts_announcements"

    const val FOREGROUND_SYNC_NOTIFICATION_ID = 9001

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.applicationContext.getSystemService(NotificationManager::class.java) ?: return
        val channels = listOf(
            NotificationChannel(
                CHANNEL_MESSAGES,
                context.getString(R.string.notif_channel_messages),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notif_channel_messages_desc)
            },
            NotificationChannel(
                CHANNEL_DEALS,
                context.getString(R.string.notif_channel_deals),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notif_channel_deals_desc)
            },
            NotificationChannel(
                CHANNEL_REMINDERS,
                context.getString(R.string.notif_channel_reminders),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notif_channel_reminders_desc)
            },
            NotificationChannel(
                CHANNEL_ANNOUNCEMENTS,
                context.getString(R.string.notif_channel_announcements),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notif_channel_announcements_desc)
            },
        )
        nm.createNotificationChannels(channels)
    }

    /** Always queries the OS directly — never caches permission state. */
    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun buildSyncForegroundNotification(context: Context): Notification {
        ensureChannels(context)
        val openPending = PendingIntent.getActivity(
            context,
            FOREGROUND_SYNC_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(context.getString(R.string.sync_notif_title))
            .setContentText(context.getString(R.string.sync_notif_body))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPending)
            .build()
    }

    fun postChatMessageNotification(
        context: Context,
        threadId: String,
        listingId: String,
        senderName: String,
        messageBody: String,
        appNotificationId: String? = null,
    ) {
        if (!UserState.settings.notifyMessages) return
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)

        val notifId = 2000 + (threadId.hashCode() and 0x7fff) % 1000
        val contentIntent = Intent(context, ChatThreadActivity::class.java).apply {
            putExtra(ChatThreadActivity.EXTRA_THREAD_ID, threadId)
            putExtra(ChatThreadActivity.EXTRA_LISTING_ID, listingId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPending = PendingIntent.getActivity(
            context,
            notifId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Inline Direct Reply requires FLAG_MUTABLE on Android 12+ and MUST target an explicit component.
        val remoteInput = RemoteInput.Builder(MarketplaceBroadcasts.KEY_QUICK_REPLY_TEXT)
            .setLabel(context.getString(R.string.notif_action_reply_hint))
            .build()
        val replyIntent = Intent(MarketplaceBroadcasts.ACTION_QUICK_REPLY).apply {
            component = ComponentName(context.applicationContext, DealAlertReceiver::class.java)
            putExtra(MarketplaceBroadcasts.EXTRA_THREAD_ID, threadId)
            putExtra(MarketplaceBroadcasts.EXTRA_LISTING_ID, listingId)
            putExtra(MarketplaceBroadcasts.EXTRA_NOTIFICATION_ID, notifId)
            if (appNotificationId != null) putExtra(MarketplaceBroadcasts.EXTRA_NOTIF_APP_ID, appNotificationId)
        }
        val replyFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val replyPending = PendingIntent.getBroadcast(context, notifId + 10_000, replyIntent, replyFlags)

        val markReadPending = buildMarkReadPendingIntent(
            context = context,
            requestCode = notifId + 20_000,
            notifId = notifId,
            threadId = threadId,
            appNotificationId = appNotificationId,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle(senderName)
            .setContentText(messageBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(messageBody))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentPending)
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_send,
                    context.getString(R.string.notif_action_reply),
                    replyPending,
                ).addRemoteInput(remoteInput).build(),
            )
            .addAction(
                R.drawable.ic_check_small,
                context.getString(R.string.notif_action_mark_read),
                markReadPending,
            )
            .build()

        safeNotify(context, notifId, notification)
    }

    fun postDealNotification(
        context: Context,
        title: String,
        body: String,
        targetType: String,
        targetId: String,
        appNotificationId: String? = null,
        bypassToggle: Boolean = false,
    ) {
        if (!bypassToggle && !UserState.settings.notifyReservations) return
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)

        val notifId = 4000 + ((appNotificationId ?: "$targetType:$targetId:$title").hashCode() and 0x7fff) % 1000
        val contentPending = buildTargetPendingIntent(context, notifId, targetType, targetId)
        val markReadPending = buildMarkReadPendingIntent(
            context = context,
            requestCode = notifId + 30_000,
            notifId = notifId,
            threadId = null,
            appNotificationId = appNotificationId,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_DEALS)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentPending)
            .addAction(
                R.drawable.ic_check_small,
                context.getString(R.string.notif_action_mark_read),
                markReadPending,
            )
            .build()

        safeNotify(context, notifId, notification)
    }

    fun postReminderNotification(
        context: Context,
        alarmKey: String,
        title: String,
        body: String,
        targetType: String,
        targetId: String,
        appNotificationId: String? = null,
    ) {
        if (!UserState.settings.notifyReminders) return
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)

        val notifId = 6000 + (alarmKey.hashCode() and 0x7fff) % 1000
        val contentPending = buildTargetPendingIntent(context, notifId, targetType, targetId)

        val snoozeIntent = Intent(MarketplaceBroadcasts.ACTION_SNOOZE_REMINDER).apply {
            component = ComponentName(context.applicationContext, DealAlertReceiver::class.java)
            putExtra(MarketplaceBroadcasts.EXTRA_NOTIFICATION_ID, notifId)
            putExtra(MarketplaceBroadcasts.EXTRA_ALARM_KEY, alarmKey)
            putExtra(MarketplaceBroadcasts.EXTRA_TITLE, title)
            putExtra(MarketplaceBroadcasts.EXTRA_BODY, body)
            putExtra(MarketplaceBroadcasts.EXTRA_TARGET_TYPE, targetType)
            putExtra(MarketplaceBroadcasts.EXTRA_TARGET_ID, targetId)
        }
        val snoozePending = PendingIntent.getBroadcast(
            context,
            notifId + 40_000,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_clock)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentPending)
            .addAction(
                R.drawable.ic_clock,
                context.getString(R.string.notif_action_snooze),
                snoozePending,
            )
            .build()

        safeNotify(context, notifId, notification)
    }

    fun postAnnouncementNotification(
        context: Context,
        id: String,
        title: String,
        body: String,
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)

        val notifId = 8000 + (id.hashCode() and 0x7fff) % 500
        val contentPending = buildTargetPendingIntent(context, notifId, NotificationTarget.ANNOUNCEMENT, id)
        val notification = NotificationCompat.Builder(context, CHANNEL_ANNOUNCEMENTS)
            .setSmallIcon(R.drawable.ic_verified)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentPending)
            .build()

        safeNotify(context, notifId, notification)
    }

    fun cancel(context: Context, notificationId: Int) {
        if (notificationId <= 0) return
        try {
            NotificationManagerCompat.from(context.applicationContext).cancel(notificationId)
        } catch (_: SecurityException) {
            // Safe ignore if notification access revoked.
        }
    }

    fun cancelAll(context: Context) {
        try {
            NotificationManagerCompat.from(context.applicationContext).cancelAll()
        } catch (_: SecurityException) {
            // Safe ignore.
        }
    }

    private fun buildTargetPendingIntent(
        context: Context,
        requestCode: Int,
        targetType: String,
        targetId: String,
    ): PendingIntent {
        val intent = when (targetType) {
            NotificationTarget.LISTING -> Intent(context, ListingDetailActivity::class.java).apply {
                putExtra(ListingDetailActivity.EXTRA_ID, targetId)
            }
            NotificationTarget.CHAT -> Intent(context, ChatThreadActivity::class.java).apply {
                putExtra(ChatThreadActivity.EXTRA_THREAD_ID, targetId)
            }
            NotificationTarget.EXCHANGE -> Intent(context, ExchangeDetailActivity::class.java).apply {
                putExtra(ExchangeDetailActivity.EXTRA_ID, targetId)
            }
            NotificationTarget.ANNOUNCEMENT -> Intent(context, NotificationsActivity::class.java)
            else -> Intent(context, MainActivity::class.java)
        }.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildMarkReadPendingIntent(
        context: Context,
        requestCode: Int,
        notifId: Int,
        threadId: String?,
        appNotificationId: String?,
    ): PendingIntent {
        val intent = Intent(MarketplaceBroadcasts.ACTION_MARK_READ).apply {
            component = ComponentName(context.applicationContext, DealAlertReceiver::class.java)
            putExtra(MarketplaceBroadcasts.EXTRA_NOTIFICATION_ID, notifId)
            if (threadId != null) putExtra(MarketplaceBroadcasts.EXTRA_THREAD_ID, threadId)
            if (appNotificationId != null) putExtra(MarketplaceBroadcasts.EXTRA_NOTIF_APP_ID, appNotificationId)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun safeNotify(context: Context, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context.applicationContext).notify(id, notification)
        } catch (_: SecurityException) {
            // Guarded per `android-permissions-security`: never crash if user revoked notifications.
        }
    }
}
