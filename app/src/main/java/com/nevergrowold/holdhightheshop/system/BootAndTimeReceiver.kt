package com.nevergrowold.holdhightheshop.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Static BroadcastReceiver for OS-protected system events:
 * - `ACTION_BOOT_COMPLETED`
 * - `ACTION_MY_PACKAGE_REPLACED`
 * - `ACTION_TIME_CHANGED` (`TIME_SET`)
 * - `ACTION_TIMEZONE_CHANGED`
 *
 * Security & ANR compliance:
 * - Strictly allowlists only the 4 expected system actions.
 * - Uses `goAsync()` + `Dispatchers.IO` so Room database queries and
 *   `AlarmManager` re-registration never touch the main thread!
 */
class BootAndTimeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in ALLOWED_ACTIONS) {
            Log.w(TAG, "Ignoring unexpected broadcast action: $action")
            return
        }
        val pending = goAsync()
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val restored = ReminderScheduler.rescheduleAllFromDb(appCtx)
                MarketplaceSyncWorker.enqueuePeriodic(appCtx)
                Log.d(TAG, "Handled $action: restored $restored scheduled campus reminders")
            } catch (e: Exception) {
                Log.w(TAG, "Failed handling $action", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootAndTimeReceiver"
        internal val ALLOWED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
