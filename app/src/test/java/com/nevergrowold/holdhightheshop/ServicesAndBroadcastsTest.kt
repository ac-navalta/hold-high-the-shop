package com.nevergrowold.holdhightheshop

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import com.nevergrowold.holdhightheshop.data.AppDatabase
import com.nevergrowold.holdhightheshop.data.AppRepository
import com.nevergrowold.holdhightheshop.data.NotificationTarget
import com.nevergrowold.holdhightheshop.data.NotificationType
import com.nevergrowold.holdhightheshop.data.ScheduledAlarmEntity
import com.nevergrowold.holdhightheshop.system.BootAndTimeReceiver
import com.nevergrowold.holdhightheshop.system.DealAlertReceiver
import com.nevergrowold.holdhightheshop.system.MarketplaceBroadcasts
import com.nevergrowold.holdhightheshop.system.MarketplaceEventReceiver
import com.nevergrowold.holdhightheshop.system.MarketplaceSyncService
import com.nevergrowold.holdhightheshop.system.MeetupCountdownService
import com.nevergrowold.holdhightheshop.system.NotificationHelper
import com.nevergrowold.holdhightheshop.system.ReminderScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * End-to-end Robolectric verification of Android Services, BroadcastReceivers,
 * AlarmManager/Room persistence, and OS Notification channels:
 * - [BootAndTimeReceiver] action allowlist & Room alarm rescheduling
 * - [DealAlertReceiver] alarm trigger, Mark Read, Snooze 15m, and RemoteInput Quick Reply
 * - [MarketplaceBroadcasts] + [MarketplaceEventReceiver] internal scoped broadcasts
 * - [MeetupCountdownService] bound Service lifecycle & live countdown computation
 * - [MarketplaceSyncService] foreground sync & stale request expiration
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServicesAndBroadcastsTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        AppRepository.resetAllForTests()
        AppRepository.seedVerifiedTestSession(
            context = app,
            email = "juan.delacruz@students.uc-bcf.edu.ph",
            name = "Juan Dela Cruz",
            program = "CITCS",
            year = "3rd Year",
        )
    }

    @After
    fun tearDown() {
        AppRepository.resetAllForTests()
    }

    @Test
    fun reminderScheduler_persistsReschedulesFutureAndPrunesExpiredAlarms() = runBlocking {
        val now = System.currentTimeMillis()
        val dao = AppDatabase.get(app).alarmDao()
        dao.upsert(
            ScheduledAlarmEntity(
                alarmKey = "future_meetup",
                requestCode = 12345,
                action = MarketplaceBroadcasts.ACTION_MEETUP_REMINDER,
                triggerAtMillis = now + 2 * 60 * 60 * 1000L,
                title = "Meetup in 1 hour: Casio",
                body = "UC Library Lobby",
                targetType = NotificationTarget.LISTING,
                targetId = "lst_1",
                recipientUid = UserState.currentUid,
            ),
        )
        dao.upsert(
            ScheduledAlarmEntity(
                alarmKey = "expired_meetup",
                requestCode = 12346,
                action = MarketplaceBroadcasts.ACTION_MEETUP_REMINDER,
                triggerAtMillis = now - 60 * 60 * 1000L,
                title = "Old meetup",
                body = "Old spot",
                targetType = NotificationTarget.LISTING,
                targetId = "lst_old",
                recipientUid = UserState.currentUid,
            ),
        )

        val restored = ReminderScheduler.rescheduleAllFromDb(app)
        assertEquals(1, restored)
        val remaining = dao.all()
        assertEquals(1, remaining.size)
        assertEquals("future_meetup", remaining.first().alarmKey)

        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        assertTrue(shadowOf(am).scheduledAlarms.isNotEmpty())
    }

    @Test
    fun bootAndTimeReceiver_enforcesStrictActionAllowlist() {
        assertEquals(
            setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
            ),
            BootAndTimeReceiver.ALLOWED_ACTIONS,
        )
        val receiver = BootAndTimeReceiver()
        // Spoofed/unexpected action is safely ignored without throwing or calling goAsync()
        receiver.onReceive(app, Intent("com.evil.SPOOF_BOOT"))
        receiver.onReceive(app, null)
    }

    @Test
    fun dealAlertReceiver_handlesAlarmTriggerMarkReadSnoozeAndRemoteInputQuickReply() {
        val partner = Seller(
            name = "Maria Santos",
            initials = "MS",
            program = "COE",
            year = "2nd Year",
            rating = "New",
            onTime = "–",
            completed = 0,
            lastMessage = "",
            online = true,
            uid = "uid_maria",
        )
        AppRepository.registerTestStudent(partner)
        val listing = Listing(
            id = "lst_calc",
            title = "Casio fx-991EX",
            price = 850,
            program = "CITCS",
            condition = 0,
            type = 0,
            description = "Like new",
            seller = partner,
            meetup = "UC Library Lobby",
            ownerUid = partner.uid,
        )
        AppRepository.addTestListing(listing)
        val thread = UserState.threadFor(listing.id, partner.uid)

        val receiver = DealAlertReceiver()

        // 1. Alarm trigger -> inserts in-app notification
        val beforeCount = UserState.notifications().size
        receiver.onReceive(
            app,
            Intent(MarketplaceBroadcasts.ACTION_MEETUP_REMINDER).apply {
                putExtra(MarketplaceBroadcasts.EXTRA_ALARM_KEY, "meetup_lst_calc_15m")
                putExtra(MarketplaceBroadcasts.EXTRA_TITLE, "Meetup in 15 min: Casio fx-991EX")
                putExtra(MarketplaceBroadcasts.EXTRA_BODY, "Safe Meetup Point: UC Library Lobby")
                putExtra(MarketplaceBroadcasts.EXTRA_TARGET_TYPE, NotificationTarget.LISTING)
                putExtra(MarketplaceBroadcasts.EXTRA_TARGET_ID, listing.id)
            },
        )
        waitUntil { UserState.notifications().size > beforeCount }
        val inserted = UserState.notifications().first { it.title.contains("Meetup in 15 min") }
        assertFalse(inserted.read)

        // 2. Mark Read action -> marks app notification read
        receiver.onReceive(
            app,
            Intent(MarketplaceBroadcasts.ACTION_MARK_READ).apply {
                putExtra(MarketplaceBroadcasts.EXTRA_NOTIFICATION_ID, 6001)
                putExtra(MarketplaceBroadcasts.EXTRA_NOTIF_APP_ID, inserted.id)
                putExtra(MarketplaceBroadcasts.EXTRA_THREAD_ID, thread.threadId)
            },
        )
        waitUntil { UserState.notifications().first { it.id == inserted.id }.read }
        assertTrue(UserState.notifications().first { it.id == inserted.id }.read)

        // 3. Inline RemoteInput Quick Reply -> appends chat message directly into the per-listing thread
        val replyIntent = Intent(MarketplaceBroadcasts.ACTION_QUICK_REPLY).apply {
            putExtra(MarketplaceBroadcasts.EXTRA_THREAD_ID, thread.threadId)
            putExtra(MarketplaceBroadcasts.EXTRA_NOTIFICATION_ID, 2001)
        }
        val remoteInput = RemoteInput.Builder(MarketplaceBroadcasts.KEY_QUICK_REPLY_TEXT).build()
        val replyBundle = Bundle().apply {
            putCharSequence(MarketplaceBroadcasts.KEY_QUICK_REPLY_TEXT, "Heading to UC Library Lobby now!")
        }
        RemoteInput.addResultsToIntent(arrayOf(remoteInput), replyIntent, replyBundle)
        receiver.onReceive(app, replyIntent)
        waitUntil { UserState.chatMessages(thread.threadId).any { it.text == "Heading to UC Library Lobby now!" } }
        assertTrue(UserState.chatMessages(thread.threadId).any { it.text == "Heading to UC Library Lobby now!" })

        // 4. Snooze 15m -> registers a snoozed AlarmManager entry
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val alarmsBefore = shadowOf(am).scheduledAlarms.size
        receiver.onReceive(
            app,
            Intent(MarketplaceBroadcasts.ACTION_SNOOZE_REMINDER).apply {
                putExtra(MarketplaceBroadcasts.EXTRA_NOTIFICATION_ID, 6001)
                putExtra(MarketplaceBroadcasts.EXTRA_ALARM_KEY, "meetup_lst_calc_15m")
                putExtra(MarketplaceBroadcasts.EXTRA_TITLE, "Meetup in 15 min: Casio fx-991EX")
                putExtra(MarketplaceBroadcasts.EXTRA_BODY, "UC Library Lobby")
                putExtra(MarketplaceBroadcasts.EXTRA_TARGET_TYPE, NotificationTarget.LISTING)
                putExtra(MarketplaceBroadcasts.EXTRA_TARGET_ID, listing.id)
            },
        )
        waitUntil { shadowOf(am).scheduledAlarms.size > alarmsBefore }
        assertTrue(shadowOf(am).scheduledAlarms.size > alarmsBefore)
    }

    @Test
    fun marketplaceBroadcasts_deliversScopedInternalBroadcastToDynamicReceiver() {
        var receivedAction: String? = null
        val receiver = MarketplaceEventReceiver { action, _ -> receivedAction = action }
        MarketplaceBroadcasts.registerInternalReceiver(
            app,
            receiver,
            MarketplaceBroadcasts.ACTION_DEAL_UPDATED,
        )
        MarketplaceBroadcasts.sendInternal(app, MarketplaceBroadcasts.ACTION_DEAL_UPDATED)
        ShadowLooper.idleMainLooper()

        assertEquals(MarketplaceBroadcasts.ACTION_DEAL_UPDATED, receivedAction)
        app.unregisterReceiver(receiver)
    }

    @Test
    fun meetupCountdownService_formatsRemainingAndComputesLiveAcceptedMeetupCountdown() {
        assertEquals("Now / Overdue", MeetupCountdownService.formatRemaining(0L))
        assertEquals("Now / Overdue", MeetupCountdownService.formatRemaining(-5_000L))
        assertEquals("05m 30s", MeetupCountdownService.formatRemaining((5 * 60 + 30) * 1000L))
        assertEquals("2h 04m 08s", MeetupCountdownService.formatRemaining((2 * 3600 + 4 * 60 + 8) * 1000L))
        assertEquals("3d 02h 15m", MeetupCountdownService.formatRemaining((3 * 86400 + 2 * 3600 + 15 * 60) * 1000L))

        val serviceController = Robolectric.buildService(MeetupCountdownService::class.java).create().bind()
        val service = serviceController.get()
        // Fresh student with no active meetups/loans -> null countdown
        assertNull(service.computeSoonestCountdown())

        // Add peer listing + accepted meetup 3 hours from now -> service surfaces live countdown!
        val seller = Seller("Carlo Reyes", "CR", "COE", "2nd Year", "New", "–", 0, "", true, uid = "uid_carlo")
        AppRepository.registerTestStudent(seller)
        val peerListing = Listing(
            id = "lst_peer_cd",
            title = "Arduino Uno Kit",
            price = 650,
            program = "COE",
            condition = 0,
            type = 0,
            description = "Complete kit",
            seller = seller,
            meetup = "CITCS Building Entrance",
            ownerUid = seller.uid,
        )
        AppRepository.addTestListing(peerListing)
        val meetAt = System.currentTimeMillis() + 3 * 60 * 60 * 1000L
        val pending = UserState.submitRequest(
            listing = peerListing,
            meetup = peerListing.meetup,
            meetupNote = "",
            meetMillis = meetAt,
            message = "See you!",
        )
        UserState.accept(pending)

        service.refreshNow()
        val countdown = service.soonestCountdown.value
        assertNotNull(countdown)
        assertEquals(peerListing.id, countdown!!.listingId)
        assertEquals("CITCS Building Entrance", countdown.meetupPlace)
        assertFalse(countdown.isLoanDue)
        assertFalse(countdown.isOverdue)

        serviceController.unbind().destroy()
    }

    @Test
    fun marketplaceSyncService_runsForegroundMaintenanceAndStopsCleanly() {
        NotificationHelper.ensureChannels(app)
        val controller = Robolectric.buildService(MarketplaceSyncService::class.java).create().startCommand(0, 1)
        val service = controller.get()
        assertNotNull(service)
        // Verify foreground notification was posted using FOREGROUND_SYNC_NOTIFICATION_ID
        val fgNotif = shadowOf(service).lastForegroundNotification
        assertNotNull(fgNotif)
        assertEquals(NotificationHelper.FOREGROUND_SYNC_NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        controller.destroy()
    }

    private fun waitUntil(timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(25L)
        }
    }
}
