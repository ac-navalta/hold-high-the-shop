package com.nevergrowold.holdhightheshop.system

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.nevergrowold.holdhightheshop.Listings
import com.nevergrowold.holdhightheshop.UserState
import com.nevergrowold.holdhightheshop.data.ActiveMeetupCountdown
import com.nevergrowold.holdhightheshop.data.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Bound local Service (`android:exported="false"`) powering live second-by-second
 * campus meetup & loan-due countdowns on [com.nevergrowold.holdhightheshop.MainActivity]
 * (Home tab) and [com.nevergrowold.holdhightheshop.ListingDetailActivity].
 *
 * Lifecycle & battery discipline:
 * - Only ticks while at least one Activity is bound (`onBind`).
 * - Immediately cancels its ticker coroutine in `onUnbind` and `onDestroy` so it
 *   consumes zero CPU/battery in the background!
 */
class MeetupCountdownService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null

    private val _soonestCountdown = MutableStateFlow<ActiveMeetupCountdown?>(null)
    val soonestCountdown: StateFlow<ActiveMeetupCountdown?> = _soonestCountdown.asStateFlow()

    inner class LocalBinder : Binder() {
        fun service(): MeetupCountdownService = this@MeetupCountdownService
    }

    override fun onBind(intent: Intent?): IBinder {
        AppRepository.ensureInitialized(applicationContext)
        refreshNow()
        startTicker()
        return binder
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        refreshNow()
        startTicker()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopTicker()
        return true
    }

    override fun onDestroy() {
        stopTicker()
        serviceScope.cancel()
        super.onDestroy()
    }

    fun refreshNow() {
        _soonestCountdown.value = computeSoonestCountdown()
    }

    /** Computes the live countdown specifically for [listingId] (if it has an accepted meetup or active loan). */
    fun countdownForListing(listingId: String): ActiveMeetupCountdown? {
        val now = System.currentTimeMillis()
        val listing = Listings.byIdOrNull(listingId) ?: return null
        val uid = UserState.currentUid
        if (uid.isEmpty()) return null

        UserState.rental(listingId)?.let { rental ->
            if (rental.lenderUid == uid || rental.borrowerUid == uid) {
                val remaining = rental.dueMillis - now
                return ActiveMeetupCountdown(
                    listingId = listingId,
                    listingTitle = listing.title,
                    partnerFirstName = rental.partner.first,
                    meetupPlace = listing.meetup,
                    targetMillis = rental.dueMillis,
                    remainingMillis = remaining,
                    isLoanDue = true,
                    isOverdue = remaining < 0L,
                )
            }
        }

        val accepted = UserState.accepted(listingId)
        if (accepted != null && (accepted.ownerUid == uid || accepted.requesterUid == uid)) {
            val partnerFirst = if (uid == accepted.ownerUid) {
                accepted.from.first
            } else {
                Listings.sellerOf(listing).first
            }
            val remaining = accepted.meetMillis - now
            return ActiveMeetupCountdown(
                listingId = listingId,
                listingTitle = listing.title,
                partnerFirstName = partnerFirst,
                meetupPlace = accepted.meetup,
                targetMillis = accepted.meetMillis,
                remainingMillis = remaining,
                isLoanDue = false,
                isOverdue = remaining < 0L,
            )
        }
        return null
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = serviceScope.launch {
            while (isActive) {
                _soonestCountdown.value = computeSoonestCountdown()
                delay(1_000L)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    internal fun computeSoonestCountdown(now: Long = System.currentTimeMillis()): ActiveMeetupCountdown? {
        val uid = UserState.currentUid
        if (uid.isEmpty()) return null
        val candidates = mutableListOf<ActiveMeetupCountdown>()

        // 1. All accepted meetups involving the signed-in student
        Listings.ALL.forEach { listing ->
            val accepted = UserState.accepted(listing.id)
            if (accepted != null && (accepted.ownerUid == uid || accepted.requesterUid == uid)) {
                val partnerFirst = if (uid == accepted.ownerUid) accepted.from.first else Listings.sellerOf(listing).first
                val remaining = accepted.meetMillis - now
                candidates += ActiveMeetupCountdown(
                    listingId = listing.id,
                    listingTitle = listing.title,
                    partnerFirstName = partnerFirst,
                    meetupPlace = accepted.meetup,
                    targetMillis = accepted.meetMillis,
                    remainingMillis = remaining,
                    isLoanDue = false,
                    isOverdue = remaining < 0L,
                )
            }
        }

        // 2. All active loans (borrowing & lending)
        (UserState.borrowing() + UserState.lending()).forEach { (listing, rental) ->
            val remaining = rental.dueMillis - now
            candidates += ActiveMeetupCountdown(
                listingId = listing.id,
                listingTitle = listing.title,
                partnerFirstName = rental.partner.first,
                meetupPlace = listing.meetup,
                targetMillis = rental.dueMillis,
                remainingMillis = remaining,
                isLoanDue = true,
                isOverdue = remaining < 0L,
            )
        }

        return candidates.minByOrNull { it.targetMillis }
    }

    companion object {
        /** Formats remaining time cleanly as `2d 04h`, `01h 24m 08s`, or `Overdue`. */
        fun formatRemaining(remainingMillis: Long): String {
            if (remainingMillis <= 0L) return "Now / Overdue"
            val totalSeconds = remainingMillis / 1_000L
            val days = totalSeconds / 86_400L
            val hours = (totalSeconds % 86_400L) / 3_600L
            val minutes = (totalSeconds % 3_600L) / 60L
            val seconds = totalSeconds % 60L
            return when {
                days >= 1L -> String.format(Locale.US, "%dd %02dh %02dm", days, hours, minutes)
                hours >= 1L -> String.format(Locale.US, "%dh %02dm %02ds", hours, minutes, seconds)
                else -> String.format(Locale.US, "%02dm %02ds", minutes, seconds)
            }
        }
    }
}
