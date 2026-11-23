package com.nevergrowold.holdhightheshop

import android.app.Application
import com.nevergrowold.holdhightheshop.data.AppRepository
import com.nevergrowold.holdhightheshop.system.MarketplaceSyncWorker
import com.nevergrowold.holdhightheshop.system.NotificationHelper

/**
 * Application entry point initializing Android 8+ NotificationChannels,
 * the unified [AppRepository] (Firebase Spark + Room offline store), and
 * battery-friendly periodic [MarketplaceSyncWorker].
 */
class HoldHighApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        AppRepository.ensureInitialized(this)
        MarketplaceSyncWorker.enqueuePeriodic(this)
    }
}
