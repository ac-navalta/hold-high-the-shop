package com.nevergrowold.holdhightheshop.system

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nevergrowold.holdhightheshop.data.AppRepository
import java.util.concurrent.TimeUnit

/**
 * Battery-friendly periodic background maintenance using AndroidX WorkManager.
 * Runs every 6 hours when connected to a network to expire stale requests,
 * check overdue campus loans, and keep persisted reminders aligned.
 */
class MarketplaceSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            AppRepository.runBackgroundMaintenance(applicationContext)
            ReminderScheduler.rescheduleAllFromDb(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Periodic marketplace sync retry needed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "MarketplaceSyncWorker"
        private const val PERIODIC_WORK_NAME = "hhts_periodic_marketplace_sync"
        private const val ONETIME_WORK_NAME = "hhts_onetime_marketplace_sync"

        fun enqueuePeriodic(context: Context) {
            runCatching {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                val request = PeriodicWorkRequestBuilder<MarketplaceSyncWorker>(6, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build()
                WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }
        }

        fun enqueueOneTime(context: Context) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<MarketplaceSyncWorker>().build()
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    ONETIME_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
            }
        }
    }
}
