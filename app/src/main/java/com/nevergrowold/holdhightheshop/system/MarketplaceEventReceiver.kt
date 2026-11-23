package com.nevergrowold.holdhightheshop.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Dynamically registered receiver (via `ContextCompat.RECEIVER_NOT_EXPORTED`)
 * that notifies active UI screens whenever deals, chats, proposals, or background
 * sync updates change marketplace state.
 *
 * Verifies `sentFromPackage == context.packageName` on Android 14+ before invoking
 * the UI callback.
 */
class MarketplaceEventReceiver(
    private val onEvent: (action: String, intent: Intent) -> Unit,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (!MarketplaceBroadcasts.isTrustedSender(context, this)) return
        onEvent(action, intent)
    }
}
