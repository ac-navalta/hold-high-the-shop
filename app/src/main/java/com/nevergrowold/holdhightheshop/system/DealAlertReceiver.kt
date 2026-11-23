package com.nevergrowold.holdhightheshop.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.nevergrowold.holdhightheshop.UserState
import com.nevergrowold.holdhightheshop.data.AppDatabase
import com.nevergrowold.holdhightheshop.data.AppRepository
import com.nevergrowold.holdhightheshop.data.NotificationTarget
import com.nevergrowold.holdhightheshop.data.NotificationType
import com.nevergrowold.holdhightheshop.security.UcEmailValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Static non-exported BroadcastReceiver (`android:exported="false"`) handling:
 * 1. Scheduled AlarmManager triggers (meetup 1h/15m alerts, loan due reminders, request expiry alerts)
 * 2. Notification quick actions:
 *    - Mark Read (`ACTION_MARK_READ`)
 *    - Snooze 15 min (`ACTION_SNOOZE_REMINDER`)
 *    - Inline Chat Direct Reply via `RemoteInput` (`ACTION_QUICK_REPLY`)
 *
 * Uses `goAsync()` + `Dispatchers.IO` so Room/Firestore operations never cause ANRs.
 */
class DealAlertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appCtx = context.applicationContext
        val pending = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppRepository.ensureInitialized(appCtx)
                when (action) {
                    MarketplaceBroadcasts.ACTION_MEETUP_REMINDER,
                    MarketplaceBroadcasts.ACTION_LOAN_DUE_REMINDER,
                    MarketplaceBroadcasts.ACTION_REQUEST_EXPIRING -> handleAlarmTrigger(appCtx, action, intent)

                    MarketplaceBroadcasts.ACTION_MARK_READ -> handleMarkRead(appCtx, intent)
                    MarketplaceBroadcasts.ACTION_SNOOZE_REMINDER -> handleSnooze(appCtx, intent)
                    MarketplaceBroadcasts.ACTION_QUICK_REPLY -> handleQuickReply(appCtx, intent)
                    else -> Log.w(TAG, "Ignoring unknown action: $action")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error handling broadcast $action", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handleAlarmTrigger(context: Context, action: String, intent: Intent) {
        val alarmKey = intent.getStringExtra(MarketplaceBroadcasts.EXTRA_ALARM_KEY).orEmpty()
        val title = intent.getStringExtra(MarketplaceBroadcasts.EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(MarketplaceBroadcasts.EXTRA_BODY).orEmpty()
        val targetType = intent.getStringExtra(MarketplaceBroadcasts.EXTRA_TARGET_TYPE) ?: NotificationTarget.LISTING
        val targetId = intent.getStringExtra(MarketplaceBroadcasts.EXTRA_TARGET_ID).orEmpty()

        if (alarmKey.isNotEmpty()) {
            runCatching { AppDatabase.get(context).alarmDao().delete(alarmKey) }
        }
        if (title.isBlank() || !AppRepository.isSignedIn()) return

        val type = when (action) {
            MarketplaceBroadcasts.ACTION_LOAN_DUE_REMINDER -> NotificationType.LOAN
            MarketplaceBroadcasts.ACTION_REQUEST_EXPIRING -> NotificationType.REQUEST
            else -> NotificationType.RESERVATION
        }
        val appNotif = AppRepository.addLocalNotification(
            type = type,
            title = title,
            body = body,
            targetType = targetType,
            targetId = targetId,
            postOsNotification = false,
        )
        NotificationHelper.postReminderNotification(
            context = context,
            alarmKey = alarmKey.ifBlank { appNotif.id },
            title = title,
            body = body,
            targetType = targetType,
            targetId = targetId,
            appNotificationId = appNotif.id,
        )
        MarketplaceBroadcasts.sendInternal(context, MarketplaceBroadcasts.ACTION_STATE_CHANGED)
    }

    private fun handleMarkRead(context: Context, intent: Intent) {
        val notifId = intent.getIntExtra(MarketplaceBroadcasts.EXTRA_NOTIFICATION_ID, 0)
        val threadId = intent.getStringExtra(MarketplaceBroadcasts.EXTRA_THREAD_ID)
        val appNotifId = intent.getStringExtra(MarketplaceBroadcasts.EXTRA_NOTIF_APP_ID)

        if (!threadId.isNullOrBlank()) AppRepository.markThreadRead(threadId)
        if (!appNotifId.isNullOrBlank()) AppRepository.markNotificationRead(appNotifId)
        NotificationHelper.cancel(context, notifId)
    }

    private fun handleSnooze(context: Context, intent: Intent) {
        val notifId = intent.getIntExtra(MarketplaceBroadcasts.EXTRA_NOTIFICATION_ID, 0)
        val alarmKey = intent.getStringExtra(MarketplaceBroadcasts.EXTRA_ALARM_KEY).orEmpty()
        val title = intent.getStringExtra(MarketplaceBroadcasts.EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(MarketplaceBroadcasts.EXTRA_BODY).orEmpty()
        val targetType = intent.getStringExtra(MarketplaceBroadcasts.EXTRA_TARGET_TYPE) ?: NotificationTarget.LISTING
        val targetId = intent.getStringExtra(MarketplaceBroadcasts.EXTRA_TARGET_ID).orEmpty()

        if (title.isNotBlank()) {
            ReminderScheduler.scheduleSnooze(
                context = context,
                alarmKey = alarmKey.ifBlank { "snooze_${System.currentTimeMillis()}" },
                title = title,
                body = body,
                targetType = targetType,
                targetId = targetId,
                delayMinutes = 15,
            )
        }
        NotificationHelper.cancel(context, notifId)
    }

    private fun handleQuickReply(context: Context, intent: Intent) {
        val replyBundle = RemoteInput.getResultsFromIntent(intent)
        val rawReply = replyBundle?.getCharSequence(MarketplaceBroadcasts.KEY_QUICK_REPLY_TEXT)?.toString()
        val replyText = UcEmailValidator.sanitizeMultiLine(rawReply, 600)
        val threadId = intent.getStringExtra(MarketplaceBroadcasts.EXTRA_THREAD_ID).orEmpty()
        val notifId = intent.getIntExtra(MarketplaceBroadcasts.EXTRA_NOTIFICATION_ID, 0)
        val appNotifId = intent.getStringExtra(MarketplaceBroadcasts.EXTRA_NOTIF_APP_ID)

        if (replyText.isNotEmpty() && threadId.isNotEmpty() && AppRepository.isSignedIn()) {
            AppRepository.sendChatMessage(
                threadId = threadId,
                rawMessage = UserState.ChatMessage(
                    senderUid = AppRepository.currentUid(),
                    text = replyText,
                    outgoing = true,
                ),
            )
            AppRepository.markThreadRead(threadId)
            if (!appNotifId.isNullOrBlank()) AppRepository.markNotificationRead(appNotifId)
        }
        NotificationHelper.cancel(context, notifId)
    }

    companion object {
        private const val TAG = "DealAlertReceiver"
    }
}
