package com.nevergrowold.holdhightheshop.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Modern Android connectivity observer using `ConnectivityManager.NetworkCallback`
 * (replaces deprecated `CONNECTIVITY_ACTION` static broadcasts).
 *
 * Drives the subtle offline status pill in [com.nevergrowold.holdhightheshop.MainActivity]
 * and [com.nevergrowold.holdhightheshop.ChatThreadActivity], and automatically triggers
 * a sync sweep when the device regains Wi-Fi / cellular connectivity.
 */
class NetworkStateMonitor(context: Context) {

    private val appCtx = context.applicationContext
    private val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val _isOnline = MutableStateFlow(checkCurrentlyOnline())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private var registered = false
    private var wasOffline = !_isOnline.value

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val nowOnline = true
            _isOnline.value = nowOnline
            if (wasOffline) {
                wasOffline = false
                MarketplaceSyncService.startIfAllowed(appCtx)
            }
        }

        override fun onLost(network: Network) {
            val stillOnline = checkCurrentlyOnline()
            _isOnline.value = stillOnline
            if (!stillOnline) wasOffline = true
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            _isOnline.value = hasInternet
            if (!hasInternet) wasOffline = true
        }
    }

    fun start() {
        if (registered || cm == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(callback)
                registered = true
            }
            _isOnline.value = checkCurrentlyOnline()
        } catch (_: Exception) {
            _isOnline.value = true
        }
    }

    fun stop() {
        if (!registered || cm == null) return
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        } finally {
            registered = false
        }
    }

    private fun checkCurrentlyOnline(): Boolean {
        val manager = cm ?: return true
        return try {
            val active = manager.activeNetwork ?: return false
            val caps = manager.getNetworkCapabilities(active) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }
}
