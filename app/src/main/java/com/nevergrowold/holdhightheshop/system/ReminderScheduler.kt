package com.nevergrowold.holdhightheshop.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.nevergrowold.holdhightheshop.R
import com.nevergrowold.holdhightheshop.Rentals
import com.nevergrowold.holdhightheshop.UserState
import com.nevergrowold.holdhightheshop.data.AppDatabase
import com.nevergrowold.holdhightheshop.data.NotificationTarget
import com.nevergrowold.holdhightheshop.data.ScheduledAlarmEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Reliable, battery-friendly, reboot-surviving reminder scheduler using
 * `AlarmManager.setAndAllowWhileIdle(...)` + Room `scheduled_alarms` table.
 *
 * Why `setAndAllowWhileIdle` instead of `setExactAndAllowWhileIdle`?
 * Play Store policy restricts `SCHEDULE_EXACT_ALARM` to alarm-clock & calendar apps.
 * `setAndAllowWhileIdle` requires ZERO special permission while still firing reliably
 * for campus meetup reminders, request expiries, and loan return due alerts — and
 * [BootAndTimeReceiver] restores every persisted alarm after reboot or clock change!
 */
object ReminderScheduler {

    private const val TAG = "ReminderScheduler"
    private const val HOUR_MS = 60L * 60L * 1000L
    private const val MIN_MS = 60L * 1000L

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun scheduleMeetupReminders(
        context: Context,
        listingId: String,
        listingTitle: String,
        meetupPlace: String,
        meetMillis: Long,
        recipientUid: String = UserState.currentUid,
    ) {
        val now = System.currentTimeMillis()
        val appCtx = context.applicationContext
        val oneHourKey = "meetup_${listingId}_1h"
        val fifteenMinKey = "meetup_${listingId}_15m"

        // Cancel any previous meetup alarms for this listing (e.g. after a reschedule proposal!)
        cancelAlarmKey(appCtx, oneHourKey)
        cancelAlarmKey(appCtx, fifteenMinKey)

        if (meetMillis - HOUR_MS > now) {
            scheduleEntity(
                appCtx,
                ScheduledAlarmEntity(
                    alarmKey = oneHourKey,
                    requestCode = requestCodeFor(oneHourKey),
                    action = MarketplaceBroadcasts.ACTION_MEETUP_REMINDER,
                    triggerAtMillis = meetMillis - HOUR_MS,
                    title = appCtx.getString(R.string.alarm_meetup_1h_title, listingTitle),
                    body = appCtx.getString(R.string.alarm_meetup_body, meetupPlace),
                    targetType = NotificationTarget.LISTING,
                    targetId = listingId,
                    recipientUid = recipientUid,
                ),
            )
        }
        if (meetMillis - 15 * MIN_MS > now) {
            scheduleEntity(
                appCtx,
                ScheduledAlarmEntity(
                    alarmKey = fifteenMinKey,
                    requestCode = requestCodeFor(fifteenMinKey),
                    action = MarketplaceBroadcasts.ACTION_MEETUP_REMINDER,
                    triggerAtMillis = meetMillis - 15 * MIN_MS,
                    title = appCtx.getString(R.string.alarm_meetup_15m_title, listingTitle),
                    body = appCtx.getString(R.string.alarm_meetup_body, meetupPlace),
                    targetType = NotificationTarget.LISTING,
                    targetId = listingId,
                    recipientUid = recipientUid,
                ),
            )
        }
    }

    fun scheduleLoanReminders(
        context: Context,
        listingId: String,
        listingTitle: String,
        dueMillis: Long,
        recipientUid: String = UserState.currentUid,
    ) {
        val now = System.currentTimeMillis()
        val appCtx = context.applicationContext
        val dayBeforeKey = "loan_${listingId}_1d"
        val dueDayKey = "loan_${listingId}_due"

        cancelAlarmKey(appCtx, dayBeforeKey)
        cancelAlarmKey(appCtx, dueDayKey)

        if (dueMillis - Rentals.DAY_MS > now) {
            scheduleEntity(
                appCtx,
                ScheduledAlarmEntity(
                    alarmKey = dayBeforeKey,
                    requestCode = requestCodeFor(dayBeforeKey),
                    action = MarketplaceBroadcasts.ACTION_LOAN_DUE_REMINDER,
                    triggerAtMillis = dueMillis - Rentals.DAY_MS,
                    title = appCtx.getString(R.string.alarm_loan_tomorrow_title, listingTitle),
                    body = appCtx.getString(R.string.alarm_loan_tomorrow_body),
                    targetType = NotificationTarget.LISTING,
                    targetId = listingId,
                    recipientUid = recipientUid,
                ),
            )
        }
        if (dueMillis > now) {
            scheduleEntity(
                appCtx,
                ScheduledAlarmEntity(
                    alarmKey = dueDayKey,
                    requestCode = requestCodeFor(dueDayKey),
                    action = MarketplaceBroadcasts.ACTION_LOAN_DUE_REMINDER,
                    triggerAtMillis = dueMillis,
                    title = appCtx.getString(R.string.alarm_loan_due_title, listingTitle),
                    body = appCtx.getString(R.string.alarm_loan_due_body),
                    targetType = NotificationTarget.LISTING,
                    targetId = listingId,
                    recipientUid = recipientUid,
                ),
            )
        }
    }

    fun scheduleRequestExpiryReminder(
        context: Context,
        requestId: String,
        listingId: String,
        listingTitle: String,
        expiresMillis: Long,
        recipientUid: String = UserState.currentUid,
    ) {
        val triggerAt = expiresMillis - 2 * HOUR_MS
        if (triggerAt <= System.currentTimeMillis()) return
        val appCtx = context.applicationContext
        val key = "req_exp_$requestId"
        scheduleEntity(
            appCtx,
            ScheduledAlarmEntity(
                alarmKey = key,
                requestCode = requestCodeFor(key),
                action = MarketplaceBroadcasts.ACTION_REQUEST_EXPIRING,
                triggerAtMillis = triggerAt,
                title = appCtx.getString(R.string.alarm_request_expiring_title, listingTitle),
                body = appCtx.getString(R.string.alarm_request_expiring_body),
                targetType = NotificationTarget.LISTING,
                targetId = listingId,
                recipientUid = recipientUid,
            ),
        )
    }

    fun scheduleSnooze(
        context: Context,
        alarmKey: String,
        title: String,
        body: String,
        targetType: String,
        targetId: String,
        delayMinutes: Int = 15,
        recipientUid: String = UserState.currentUid,
    ) {
        val appCtx = context.applicationContext
        val snoozeKey = "${alarmKey}_snooze"
        scheduleEntity(
            appCtx,
            ScheduledAlarmEntity(
                alarmKey = snoozeKey,
                requestCode = requestCodeFor(snoozeKey),
                action = MarketplaceBroadcasts.ACTION_MEETUP_REMINDER,
                triggerAtMillis = System.currentTimeMillis() + delayMinutes * MIN_MS,
                title = title,
                body = body,
                targetType = targetType,
                targetId = targetId,
                recipientUid = recipientUid,
            ),
        )
    }

    fun cancelForListing(context: Context, listingId: String) {
        val appCtx = context.applicationContext
        listOf(
            "meetup_${listingId}_1h",
            "meetup_${listingId}_15m",
            "loan_${listingId}_1d",
            "loan_${listingId}_due",
        ).forEach { cancelAlarmKey(appCtx, it) }
    }

    fun cancelForRequest(context: Context, requestId: String) {
        cancelAlarmKey(context.applicationContext, "req_exp_$requestId")
    }

    /** Called on Logout or Account Deletion to stop all alarms for the departing student. */
    fun cancelAllForUser(context: Context, uid: String) {
        val appCtx = context.applicationContext
        ioScope.launch {
            try {
                val dao = AppDatabase.get(appCtx).alarmDao()
                val existing = dao.forUser(uid)
                existing.forEach { cancelPlatformAlarm(appCtx, it.requestCode, it.action) }
                dao.deleteForUser(uid)
            } catch (e: Exception) {
                Log.w(TAG, "Failed clearing alarms for $uid", e)
            }
        }
    }

    /** Called on `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED` (off main thread). */
    suspend fun rescheduleAllFromDb(context: Context): Int {
        val appCtx = context.applicationContext
        val dao = AppDatabase.get(appCtx).alarmDao()
        val now = System.currentTimeMillis()
        val stored = dao.all()
        var restored = 0
        stored.forEach { alarm ->
            if (alarm.triggerAtMillis <= now - 5 * MIN_MS) {
                dao.delete(alarm.alarmKey)
            } else {
                registerPlatformAlarm(appCtx, alarm)
                restored++
            }
        }
        return restored
    }

    private fun scheduleEntity(context: Context, entity: ScheduledAlarmEntity) {
        registerPlatformAlarm(context, entity)
        ioScope.launch {
            try {
                AppDatabase.get(context).alarmDao().upsert(entity)
            } catch (e: Exception) {
                Log.w(TAG, "Failed persisting alarm ${entity.alarmKey}", e)
            }
        }
    }

    private fun cancelAlarmKey(context: Context, alarmKey: String) {
        val code = requestCodeFor(alarmKey)
        cancelPlatformAlarm(context, code, MarketplaceBroadcasts.ACTION_MEETUP_REMINDER)
        cancelPlatformAlarm(context, code, MarketplaceBroadcasts.ACTION_LOAN_DUE_REMINDER)
        cancelPlatformAlarm(context, code, MarketplaceBroadcasts.ACTION_REQUEST_EXPIRING)
        ioScope.launch {
            try {
                AppDatabase.get(context).alarmDao().delete(alarmKey)
            } catch (_: Exception) {
            }
        }
    }

    private fun registerPlatformAlarm(context: Context, entity: ScheduledAlarmEntity) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = buildAlarmPendingIntent(context, entity)
        val trigger = entity.triggerAtMillis.coerceAtLeast(System.currentTimeMillis() + 5_000L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        } else {
            am.set(AlarmManager.RTC_WAKEUP, trigger, pending)
        }
    }

    private fun cancelPlatformAlarm(context: Context, requestCode: Int, action: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(action).apply {
            component = ComponentName(context.applicationContext, DealAlertReceiver::class.java)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pending != null) {
            am.cancel(pending)
            pending.cancel()
        }
    }

    private fun buildAlarmPendingIntent(context: Context, entity: ScheduledAlarmEntity): PendingIntent {
        val intent = Intent(entity.action).apply {
            component = ComponentName(context.applicationContext, DealAlertReceiver::class.java)
            putExtra(MarketplaceBroadcasts.EXTRA_ALARM_KEY, entity.alarmKey)
            putExtra(MarketplaceBroadcasts.EXTRA_TITLE, entity.title)
            putExtra(MarketplaceBroadcasts.EXTRA_BODY, entity.body)
            putExtra(MarketplaceBroadcasts.EXTRA_TARGET_TYPE, entity.targetType)
            putExtra(MarketplaceBroadcasts.EXTRA_TARGET_ID, entity.targetId)
        }
        return PendingIntent.getBroadcast(
            context,
            entity.requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    internal fun requestCodeFor(alarmKey: String): Int =
        10_000 + (alarmKey.hashCode() and 0x7fffffff) % 40_000
}
