package com.nevergrowold.holdhightheshop.system

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nevergrowold.holdhightheshop.data.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground Service (`android:foregroundServiceType="dataSync"`, `android:exported="false"`)
 * performing short-lived campus marketplace sync & deal maintenance off the main thread:
 * - Expires stale (>24h) pending reservations
 * - Checks overdue loans and schedules/posts reminders
 * - Refreshes account moderation/suspension status
 * - Broadcasts `ACTION_STATE_CHANGED` to live screens and cleanly stops itself (`stopSelf()`).
 *
 * On Android 12+, if triggered while the app is in the background where foreground
 * service starts aren't allowed, [startIfAllowed] catches `ForegroundServiceStartNotAllowedException`
 * and seamlessly delegates to [MarketplaceSyncWorker].
 */
class MarketplaceSyncService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationHelper.buildSyncForegroundNotification(this)
        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.FOREGROUND_SYNC_NOTIFICATION_ID,
                notification,
                fgType,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Unable to promote sync service to foreground", e)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        serviceScope.launch {
            try {
                AppRepository.runBackgroundMaintenance(applicationContext)
                ReminderScheduler.rescheduleAllFromDb(applicationContext)
                MarketplaceBroadcasts.sendInternal(applicationContext, MarketplaceBroadcasts.ACTION_STATE_CHANGED)
            } catch (e: Exception) {
                Log.w(TAG, "Background sync sweep failed", e)
            } finally {
                ServiceCompat.stopForeground(this@MarketplaceSyncService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MarketplaceSyncService"

        fun startIfAllowed(context: Context) {
            val appCtx = context.applicationContext
            if (!AppRepository.isVerifiedSignedIn()) return
            val intent = Intent(appCtx, MarketplaceSyncService::class.java)
            try {
                ContextCompat.startForegroundService(appCtx, intent)
            } catch (e: Exception) {
                Log.d(TAG, "FGS start deferred to WorkManager: ${e.javaClass.simpleName}")
                MarketplaceSyncWorker.enqueueOneTime(appCtx)
            }
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            runCatching { appCtx.stopService(Intent(appCtx, MarketplaceSyncService::class.java)) }
        }
    }
}
