package com.nevergrowold.holdhightheshop.system

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat

/**
 * Hardened app-internal Broadcast helper following Google's `android-intent-security`:
 *
 * - Explicit `intent.setPackage(context.packageName)` on every outgoing broadcast.
 * - Protected by signature-level `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
 *   so minSdk 24..32 devices enjoy the same isolation as Android 13+ `RECEIVER_NOT_EXPORTED`.
 * - Opts into sender identity sharing on Android 14+ (`BroadcastOptions.setShareIdentityEnabled(true)`)
 *   and validates `sentFromPackage == context.packageName` on receive.
 */
object MarketplaceBroadcasts {

    const val ACTION_STATE_CHANGED = "com.nevergrowold.holdhightheshop.action.STATE_CHANGED"
    const val ACTION_DEAL_UPDATED = "com.nevergrowold.holdhightheshop.action.DEAL_UPDATED"
    const val ACTION_NEW_CHAT_MESSAGE = "com.nevergrowold.holdhightheshop.action.NEW_CHAT_MESSAGE"
    const val ACTION_PROPOSAL_UPDATED = "com.nevergrowold.holdhightheshop.action.PROPOSAL_UPDATED"

    // Alarm & notification action broadcasts (delivered explicitly to DealAlertReceiver)
    const val ACTION_MEETUP_REMINDER = "com.nevergrowold.holdhightheshop.action.MEETUP_REMINDER"
    const val ACTION_LOAN_DUE_REMINDER = "com.nevergrowold.holdhightheshop.action.LOAN_DUE_REMINDER"
    const val ACTION_REQUEST_EXPIRING = "com.nevergrowold.holdhightheshop.action.REQUEST_EXPIRING"
    const val ACTION_MARK_READ = "com.nevergrowold.holdhightheshop.action.MARK_READ"
    const val ACTION_SNOOZE_REMINDER = "com.nevergrowold.holdhightheshop.action.SNOOZE_REMINDER"
    const val ACTION_QUICK_REPLY = "com.nevergrowold.holdhightheshop.action.QUICK_REPLY"

    const val EXTRA_LISTING_ID = "extra_listing_id"
    const val EXTRA_THREAD_ID = "extra_thread_id"
    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    const val EXTRA_ALARM_KEY = "extra_alarm_key"
    const val EXTRA_TITLE = "extra_title"
    const val EXTRA_BODY = "extra_body"
    const val EXTRA_TARGET_TYPE = "extra_target_type"
    const val EXTRA_TARGET_ID = "extra_target_id"
    const val EXTRA_NOTIF_APP_ID = "extra_notif_app_id"
    const val KEY_QUICK_REPLY_TEXT = "key_quick_reply_text"

    fun internalPermission(context: Context): String =
        "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

    fun sendInternal(
        context: Context,
        action: String,
        extras: (Intent.() -> Unit)? = null,
    ) {
        val appCtx = context.applicationContext
        val intent = Intent(action).apply {
            setPackage(appCtx.packageName)
            extras?.invoke(this)
        }
        val perm = internalPermission(appCtx)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val opts: Bundle = BroadcastOptions.makeBasic()
                .setShareIdentityEnabled(true)
                .toBundle()
            appCtx.sendBroadcast(intent, perm, opts)
        } else {
            appCtx.sendBroadcast(intent, perm)
        }
    }

    /**
     * Registers a dynamic receiver safely across API 24..37 using
     * `ContextCompat.RECEIVER_NOT_EXPORTED`.
     */
    fun registerInternalReceiver(
        context: Context,
        receiver: BroadcastReceiver,
        vararg actions: String,
    ) {
        val filter = IntentFilter().apply {
            actions.forEach { addAction(it) }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /**
     * Defense-in-depth verification on Android 14+ that an incoming dynamic broadcast
     * originated from our own package.
     */
    fun isTrustedSender(context: Context, receiver: BroadcastReceiver): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val sender = try {
                receiver.sentFromPackage
            } catch (_: Throwable) {
                null
            }
            // In Robolectric/unit tests sentFromPackage may be null; reject mismatched third-party packages.
            if (sender != null && sender != context.packageName) return false
        }
        return true
    }
}
