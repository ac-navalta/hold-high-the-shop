package com.nevergrowold.holdhightheshop

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.app.SearchManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.materialswitch.MaterialSwitch
import com.nevergrowold.holdhightheshop.BaseActivity.Companion.EXTRA_LISTING
import com.nevergrowold.holdhightheshop.BaseActivity.Companion.EXTRA_PROGRAM
import com.nevergrowold.holdhightheshop.BaseActivity.Companion.EXTRA_SELLER
import com.nevergrowold.holdhightheshop.BaseActivity.Companion.EXTRA_TAB
import com.nevergrowold.holdhightheshop.ExchangeDetailActivity.Companion.EXTRA_EXCHANGE
import com.nevergrowold.holdhightheshop.data.AppRepository
import com.nevergrowold.holdhightheshop.data.NotificationTarget
import com.nevergrowold.holdhightheshop.data.NotificationType
import com.nevergrowold.holdhightheshop.security.CampusSafetyApi
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

/**
 * End-to-end Robolectric UI & Navigation verification covering:
 * 1. Verified UC Student Sign-Up / Log-In / Auth Gate flow
 * 2. Deep Links (HTTPS + custom scheme), ACTION_SEARCH, and sanitized ACTION_SEND on [MainActivity]
 * 3. Create / Draft / Edit Listing with 1–10 photos, multi-select college tags, and Prohibited-Item Safety Gate dialog
 * 4. ListingDetail: Direct Message, Share, Safe Meetup Bottom Sheet (no MapActivity), Calendar, SellerProfile, Report -> Celebration
 * 5. ChatThread: Per-listing messages, Context-Aware `+` Menu (Meetup Proposal / Extension Proposal visibility), Camera & Photo Picker
 * 6. EditProfile, Settings (zero pre-seed button, Blocked Users, Logout, System Intents), ExchangeDetail, Notifications, Saved
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntentsAndFeaturesTest {

    private lateinit var app: Application
    private lateinit var peerSeller: Seller
    private lateinit var peerListing: Listing

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        clearFileProviderCache()
        Listings.reset()
        ShadowToast.reset()
        CampusSafetyApi.testOverride = null
    }

    @After
    fun tearDown() {
        CampusSafetyApi.testOverride = null
        Listings.reset()
    }

    private fun clearFileProviderCache() {
        val field = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        field.isAccessible = true
        (field.get(null) as? MutableMap<*, *>)?.clear()
    }

    private fun signInAndSeedCampusFixtures() {
        AppRepository.seedVerifiedTestSession(
            context = app,
            email = "juan.delacruz@students.uc-bcf.edu.ph",
            name = "Juan Dela Cruz",
            program = "CITCS",
            year = "3rd Year",
        )
        peerSeller = Seller(
            name = "Paolo Mendoza",
            initials = "PM",
            program = "CITCS",
            year = "3rd Year",
            rating = "4.9",
            onTime = "98%",
            completed = 4,
            lastMessage = "",
            online = true,
            uid = "uid_paolo",
            email = "paolo.mendoza@students.uc-bcf.edu.ph",
        )
        AppRepository.registerTestStudent(peerSeller)
        peerListing = Listing(
            id = "lst_casio",
            title = "Casio fx-991EX",
            price = 850,
            program = "CITCS",
            condition = 0,
            type = 0,
            description = "Clean scientific calculator for CITCS & COE.",
            seller = peerSeller,
            meetup = "UC Library Lobby",
            meetupNote = "2nd floor reading entrance",
            photoUris = listOf("content://test/casio.jpg"),
            targetPrograms = listOf("CITCS", "COE"),
            ownerUid = peerSeller.uid,
        )
        AppRepository.addTestListing(peerListing)
    }

    // ---------------------------------------------------------------- 1. Onboarding & strict UC Auth Gate

    @Test
    fun landingAndLogin_enforceUcStudentDomainOnly() {
        val landing = Robolectric.buildActivity(LandingActivity::class.java).setup().get()
        landing.findViewById<View>(R.id.main).performClick()
        val toLogin = shadowOf(landing).nextStartedActivity
        assertNotNull(toLogin)
        assertEquals(LoginActivity::class.java.name, toLogin.component?.className)
        assertTrue(toLogin.getBooleanExtra(LoginActivity.EXTRA_FROM_SPLASH, false))

        val login = Robolectric.buildActivity(LoginActivity::class.java, toLogin).setup().get()
        val emailInput = login.findViewById<EditText>(R.id.inputEmail)
        val pwInput = login.findViewById<EditText>(R.id.inputPassword)

        // Non-UC email -> domain error Toast, no MainActivity launched
        emailInput.setText("student@gmail.com")
        pwInput.setText("Campus2026!")
        login.signIn(login.findViewById(R.id.btnSignIn))
        assertEquals(login.getString(R.string.signup_domain_error), ShadowToast.getTextOfLatestToast())
        assertNull(shadowOf(login).nextStartedActivity)

        // Legacy @uc-bcf.edu.ph (missing students.) -> also rejected!
        ShadowToast.reset()
        emailInput.setText("faculty@uc-bcf.edu.ph")
        login.signIn(login.findViewById(R.id.btnSignIn))
        assertEquals(login.getString(R.string.signup_domain_error), ShadowToast.getTextOfLatestToast())
        assertNull(shadowOf(login).nextStartedActivity)
    }

    // ---------------------------------------------------------------- 2. Incoming Intents on MainActivity

    @Test
    fun mainActivity_whenAuthenticated_handlesDeepLinksSearchAndSanitizedShare() {
        signInAndSeedCampusFixtures()

        // 1. HTTPS listing deep link via onCreate
        val listingIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://holdhightheshop.app/listing/lst_casio"))
        val mainController = Robolectric.buildActivity(MainActivity::class.java, listingIntent).setup()
        val main = mainController.get()
        val openedListing = shadowOf(main).nextStartedActivity
        assertEquals(ListingDetailActivity::class.java.name, openedListing.component?.className)
        assertEquals("lst_casio", openedListing.getStringExtra(EXTRA_LISTING))

        // 2. Remaining custom-scheme deep links via onNewIntent
        mainController.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("holdhightheshop://seller/Paolo%20Mendoza")))
        assertEquals(SellerProfileActivity::class.java.name, shadowOf(main).nextStartedActivity.component?.className)

        mainController.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("holdhightheshop://chat/Paolo%20Mendoza?listing=lst_casio")))
        val openedChat = shadowOf(main).nextStartedActivity
        assertEquals(ChatThreadActivity::class.java.name, openedChat.component?.className)
        assertEquals("lst_casio", openedChat.getStringExtra(EXTRA_LISTING))

        mainController.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("holdhightheshop://create")))
        assertEquals(CreateListingActivity::class.java.name, shadowOf(main).nextStartedActivity.component?.className)

        mainController.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("holdhightheshop://saved")))
        assertEquals(SavedActivity::class.java.name, shadowOf(main).nextStartedActivity.component?.className)

        mainController.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("holdhightheshop://notifications")))
        assertEquals(NotificationsActivity::class.java.name, shadowOf(main).nextStartedActivity.component?.className)

        mainController.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("holdhightheshop://settings")))
        assertEquals(SettingsActivity::class.java.name, shadowOf(main).nextStartedActivity.component?.className)

        // 3. ACTION_SEARCH filters Market tab and shows Toast
        ShadowToast.reset()
        mainController.newIntent(Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, "Casio"))
        assertEquals(main.getString(R.string.toast_searching_format, "Casio"), ShadowToast.getTextOfLatestToast())
        assertEquals("Casio", main.findViewById<EditText>(R.id.inputSearch).text.toString())

        // 4. ACTION_SEND forwards sanitized subject, text, and external content:// URI to CreateListingActivity
        val sharedUri = Uri.parse("content://external.gallery/shared/item.jpg")
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_SUBJECT, "Graphing Calculator")
            putExtra(Intent.EXTRA_TEXT, "Slightly used TI-84 for COE majors.")
            putExtra(Intent.EXTRA_STREAM, sharedUri)
        }
        mainController.newIntent(shareIntent)
        waitUntil { shadowOf(main).peekNextStartedActivity() != null }
        val forwardedCreate = shadowOf(main).nextStartedActivity
        assertEquals(CreateListingActivity::class.java.name, forwardedCreate.component?.className)
        assertEquals("Graphing Calculator", forwardedCreate.getStringExtra(AppIntents.EXTRA_SHARED_SUBJECT))

        ShadowToast.reset()
        val create = Robolectric.buildActivity(CreateListingActivity::class.java, forwardedCreate).setup().get()
        assertEquals("Graphing Calculator", create.findViewById<EditText>(R.id.inputTitle).text.toString())
        assertEquals(create.getString(R.string.toast_shared_prefilled), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun legacyTabActivities_forwardToMainActivity() {
        signInAndSeedCampusFixtures()

        val market = Robolectric.buildActivity(
            MarketActivity::class.java,
            Intent(app, MarketActivity::class.java).putExtra(EXTRA_PROGRAM, "CITCS"),
        ).setup().get()
        val mForward = shadowOf(market).nextStartedActivity
        assertEquals(MainActivity::class.java.name, mForward.component?.className)
        assertEquals(MainActivity.TAB_MARKET, mForward.getIntExtra(MainActivity.EXTRA_NAV_TAB, -1))
        assertEquals("CITCS", mForward.getStringExtra(EXTRA_PROGRAM))
        assertTrue(market.isFinishing)

        val chats = Robolectric.buildActivity(ChatsActivity::class.java).setup().get()
        assertEquals(MainActivity.TAB_CHATS, shadowOf(chats).nextStartedActivity.getIntExtra(MainActivity.EXTRA_NAV_TAB, -1))

        val profile = Robolectric.buildActivity(
            ProfileActivity::class.java,
            Intent(app, ProfileActivity::class.java).putExtra(EXTRA_TAB, ProfileActivity.TAB_COMPLETED),
        ).setup().get()
        assertEquals(ProfileActivity.TAB_COMPLETED, shadowOf(profile).nextStartedActivity.getIntExtra(EXTRA_TAB, -1))
    }

    // ---------------------------------------------------------------- 3. Create / Safety Gate / Draft / Edit Listing

    @Test
    fun createListing_draftRestoreSafetyGateBlockAndApprovedPostEdit() {
        signInAndSeedCampusFixtures()

        // Save draft
        val create1 = Robolectric.buildActivity(CreateListingActivity::class.java).setup().get()
        create1.findViewById<EditText>(R.id.inputTitle).setText("UC Lab Notebook")
        create1.findViewById<EditText>(R.id.inputPrice).setText("180")
        create1.findViewById<EditText>(R.id.inputDescription).setText("Unused grid notebook.")
        create1.saveDraft(create1.findViewById(R.id.btnSaveDraft))
        assertEquals(create1.getString(R.string.toast_draft_saved), ShadowToast.getTextOfLatestToast())
        assertNotNull(UserState.draft)
        assertTrue(create1.isFinishing)

        // Restore draft on reopen
        ShadowToast.reset()
        val create2 = Robolectric.buildActivity(CreateListingActivity::class.java).setup().get()
        assertEquals("UC Lab Notebook", create2.findViewById<EditText>(R.id.inputTitle).text.toString())
        assertEquals(create2.getString(R.string.toast_draft_restored), ShadowToast.getTextOfLatestToast())

        // Require at least 1 photo
        create2.findViewById<View>(R.id.condition1).performClick()
        create2.postItem(create2.findViewById(R.id.btnPostItem))
        assertEquals(create2.getString(R.string.toast_need_photo), ShadowToast.getTextOfLatestToast())

        // Add a photo, then try posting a prohibited item -> Safety Gate blocks with Prohibited Item Notice dialog!
        create2.addPhotoRefForTest("content://test/item.jpg")
        create2.findViewById<EditText>(R.id.inputTitle).setText("Relx Vape Starter Pod")
        create2.postItem(create2.findViewById(R.id.btnPostItem))
        waitUntil { ShadowDialog.getLatestDialog() is AlertDialog && (ShadowDialog.getLatestDialog() as AlertDialog).isShowing }
        val safetyDialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertNotNull(safetyDialog)
        assertNull("Prohibited item must not launch ListingPostedActivity", shadowOf(create2).nextStartedActivity)
        safetyDialog.dismiss()

        // Fix title to safe campus item + override Gemini to Approved -> posts cleanly!
        CampusSafetyApi.testOverride = { _, _, _, _ -> CampusSafetyApi.Verdict.Approved }
        create2.findViewById<EditText>(R.id.inputTitle).setText("UC Lab Notebook")
        ShadowToast.reset()
        create2.postItem(create2.findViewById(R.id.btnPostItem))
        waitUntil { shadowOf(create2).peekNextStartedActivity() != null }

        val postedIntent = shadowOf(create2).nextStartedActivity
        assertEquals(ListingPostedActivity::class.java.name, postedIntent.component?.className)
        val newId = postedIntent.getStringExtra(EXTRA_LISTING)!!
        assertEquals("UC Lab Notebook", Listings.byId(newId).title)
        assertTrue("No college tag selected defaults to All colleges", Listings.byId(newId).isForAllColleges)

        // ListingPostedActivity -> copy ref + open detail
        val posted = Robolectric.buildActivity(ListingPostedActivity::class.java, postedIntent).setup().get()
        posted.copyReference(posted.findViewById(R.id.postedRefCard))
        assertEquals(posted.getString(R.string.toast_ref_copied, Listings.referenceCode(newId)), ShadowToast.getTextOfLatestToast())
        posted.viewListing(posted.findViewById(R.id.main))
        assertEquals(ListingDetailActivity::class.java.name, shadowOf(posted).nextStartedActivity.component?.className)

        // Edit listing -> updates price to 0 (FREE) cleanly!
        val editIntent = Intent(app, CreateListingActivity::class.java).putExtra(EXTRA_LISTING, newId)
        val editActivity = Robolectric.buildActivity(CreateListingActivity::class.java, editIntent).setup().get()
        editActivity.findViewById<EditText>(R.id.inputTitle).setText("UC Lab Notebook (Free)")
        editActivity.findViewById<EditText>(R.id.inputPrice).setText("0")
        editActivity.postItem(editActivity.findViewById(R.id.btnPostItem))
        waitUntil { editActivity.isFinishing }
        assertEquals(Activity.RESULT_OK, shadowOf(editActivity).resultCode)
        assertEquals("UC Lab Notebook (Free)", Listings.byId(newId).title)
        assertTrue(Listings.byId(newId).isFree)
    }

    // ---------------------------------------------------------------- 4. ListingDetail, Safe Meetup Sheet, Direct Chat, Report

    @Test
    fun listingDetail_directMessageSafeMeetupSheetShareSellerAndReportFlow() {
        signInAndSeedCampusFixtures()

        val detail = Robolectric.buildActivity(
            ListingDetailActivity::class.java,
            Intent(app, ListingDetailActivity::class.java).putExtra(EXTRA_LISTING, peerListing.id),
        ).setup().get()

        // Direct Message button visible BEFORE reserving -> opens ChatThreadActivity!
        val quickChatBtn = detail.findViewById<View>(R.id.btnQuickChat)
        assertEquals(View.VISIBLE, quickChatBtn.visibility)
        quickChatBtn.performClick()
        val toChat = shadowOf(detail).nextStartedActivity
        assertEquals(ChatThreadActivity::class.java.name, toChat.component?.className)

        // Safe Meetup Point Bottom Sheet replaces removed MapActivity (Copy spot + Call UC Security!)
        detail.openMeetup(detail.findViewById(R.id.meetupPlace))
        ShadowLooper.idleMainLooper()
        val meetupSheet = ShadowDialog.getLatestDialog()!!
        meetupSheet.findViewById<View>(R.id.meetupCopySpot).performClick()
        assertEquals(detail.getString(R.string.toast_meetup_copied), ShadowToast.getTextOfLatestToast())

        detail.openMeetup(detail.findViewById(R.id.meetupPlace))
        ShadowLooper.idleMainLooper()
        ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.meetupCallSecurity).performClick()
        val dialIntent = shadowOf(detail).nextStartedActivity
        assertEquals(Intent.ACTION_DIAL, dialIntent.action)
        assertEquals("tel:${AppIntents.UC_SECURITY_PHONE}", dialIntent.data.toString())

        // Share sheet -> Copy link + Share via chooser
        detail.shareListing(detail.findViewById(R.id.btnShare))
        ShadowLooper.idleMainLooper()
        ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.shareCopy).performClick()
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("https://holdhightheshop.app/listing/${peerListing.id}", clipboard.primaryClip?.getItemAt(0)?.text.toString())

        detail.shareListing(detail.findViewById(R.id.btnShare))
        ShadowLooper.idleMainLooper()
        ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.shareExternal).performClick()
        assertEquals(Intent.ACTION_CHOOSER, shadowOf(detail).nextStartedActivity.action)

        // SellerProfile -> Share seller + Message seller
        detail.openSellerProfile(detail.findViewById(R.id.sellerCard))
        val toSeller = shadowOf(detail).nextStartedActivity
        assertEquals(SellerProfileActivity::class.java.name, toSeller.component?.className)
        val sellerActivity = Robolectric.buildActivity(SellerProfileActivity::class.java, toSeller).setup().get()
        sellerActivity.shareSeller(sellerActivity.findViewById(R.id.btnShareSeller))
        assertEquals(Intent.ACTION_CHOOSER, shadowOf(sellerActivity).nextStartedActivity.action)

        // Report Listing -> validation -> ReportReceivedActivity with celebratory reference
        detail.openReport(detail.findViewById(R.id.btnReport))
        val toReport = shadowOf(detail).nextStartedActivity
        assertEquals(ReportListingActivity::class.java.name, toReport.component?.className)
        val reportActivity = Robolectric.buildActivity(ReportListingActivity::class.java, toReport).setup().get()

        reportActivity.submitReport(reportActivity.findViewById(R.id.main))
        assertEquals(reportActivity.getString(R.string.toast_select_reason), ShadowToast.getTextOfLatestToast())

        reportActivity.findViewById<View>(R.id.reason1).performClick()
        reportActivity.findViewById<EditText>(R.id.inputReportDetails).setText("Duplicate spam listing.")
        reportActivity.submitReport(reportActivity.findViewById(R.id.main))

        val issue = UserState.issue(peerListing.id)
        assertNotNull(issue)
        val toReceived = shadowOf(reportActivity).nextStartedActivity
        assertEquals(ReportReceivedActivity::class.java.name, toReceived.component?.className)
        val received = Robolectric.buildActivity(ReportReceivedActivity::class.java, toReceived).setup().get()
        assertEquals(issue!!.ref, received.findViewById<TextView>(R.id.reportRef).text.toString())
    }

    // ---------------------------------------------------------------- 5. Context-Aware Chat '+' Menu & Per-Listing Threads

    @Test
    fun chatThread_contextAwarePlusMenu_hidesMeetupChangeUntilAcceptedAndShowsItOnceAccepted() {
        signInAndSeedCampusFixtures()

        val chatIntent = Intent(app, ChatThreadActivity::class.java)
            .putExtra(EXTRA_SELLER, peerSeller.name)
            .putExtra(EXTRA_LISTING, peerListing.id)
        val chat = Robolectric.buildActivity(ChatThreadActivity::class.java, chatIntent).setup().get()

        // 1. Before any accepted deal: '+' menu shows Photo & View Listing, HIDES Propose Meetup & Return Extension!
        chat.openChatActions(chat.findViewById(R.id.btnAttach))
        ShadowLooper.idleMainLooper()
        val actionsBefore = ShadowDialog.getLatestDialog()!!
        assertEquals(View.VISIBLE, actionsBefore.findViewById<View>(R.id.actionPhoto).visibility)
        assertEquals(View.VISIBLE, actionsBefore.findViewById<View>(R.id.actionListing).visibility)
        assertEquals(View.GONE, actionsBefore.findViewById<View>(R.id.actionProposeMeetup).visibility)
        assertEquals(View.GONE, actionsBefore.findViewById<View>(R.id.actionProposeExtension).visibility)
        actionsBefore.dismiss()

        // 2. After buyer reserves and seller accepts: '+' menu UNLOCKS Propose Meetup Change!
        val req = UserState.submitRequest(
            listing = peerListing,
            meetup = peerListing.meetup,
            meetupNote = "",
            meetMillis = System.currentTimeMillis() + 6 * 60 * 60 * 1000L,
        )
        UserState.accept(req)
        chat.openChatActions(chat.findViewById(R.id.btnAttach))
        ShadowLooper.idleMainLooper()
        val actionsAfter = ShadowDialog.getLatestDialog()!!
        assertEquals(View.VISIBLE, actionsAfter.findViewById<View>(R.id.actionProposeMeetup).visibility)
        assertEquals(View.GONE, actionsAfter.findViewById<View>(R.id.actionProposeExtension).visibility)
        actionsAfter.dismiss()

        // 3. Send a message & verify camera implicit intent uses scoped FileProvider
        val input = chat.findViewById<EditText>(R.id.inputMessage)
        input.setText("See you at UC Library Lobby!")
        chat.sendMessage(chat.findViewById(R.id.btnSend))
        assertEquals("", input.text.toString())
        val thread = UserState.threadFor(peerListing.id, peerSeller.uid)
        assertTrue(UserState.chatMessages(thread.threadId).any { it.text == "See you at UC Library Lobby!" })

        chat.launchChatCamera()
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, shadowOf(chat).nextStartedActivityForResult?.intent?.action)
    }

    // ---------------------------------------------------------------- 6. EditProfile, Settings, Notifications, Saved

    @Test
    fun editProfileSettingsNotificationsAndSaved_workEndToEndWithoutPreSeedButton() {
        signInAndSeedCampusFixtures()

        // EditProfile
        val edit = Robolectric.buildActivity(EditProfileActivity::class.java).setup().get()
        edit.findViewById<EditText>(R.id.inputName).setText("   ")
        edit.saveProfile(edit.findViewById(R.id.main))
        assertEquals(edit.getString(R.string.toast_need_name), ShadowToast.getTextOfLatestToast())
        assertFalse(edit.isFinishing)

        edit.findViewById<EditText>(R.id.inputName).setText("Marco Navalta")
        edit.findViewById<EditText>(R.id.inputBio).setText("CITCS senior trading books.")
        edit.saveProfile(edit.findViewById(R.id.main))
        assertEquals(Activity.RESULT_OK, shadowOf(edit).resultCode)
        assertEquals("Marco Navalta", UserState.profile.name)

        // Settings: switches, OS notification settings, guidelines/safety/help/about intents, Blocked Users, Logout
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertEquals(UserState.profile.email, settings.findViewById<TextView>(R.id.rowEmailValue).text.toString())
        // Verify Delete Account button exists and NO pre-seed view exists
        assertNotNull(settings.findViewById<View>(R.id.btnDeleteAccount))
        assertEquals(0, settings.resources.getIdentifier("btnPreSeed", "id", app.packageName))

        val swShowProgram = settings.findViewById<MaterialSwitch>(R.id.swShowProgram)
        swShowProgram.isChecked = false
        assertFalse(UserState.settings.showProgram)

        settings.findViewById<View>(R.id.rowSystemNotifications).performClick()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, shadowOf(settings).nextStartedActivity.action)

        settings.findViewById<View>(R.id.rowGuidelines).performClick()
        ShadowLooper.idleMainLooper()
        ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.infoAction).performClick()
        assertEquals(AppIntents.UC_WEBSITE_URL, shadowOf(settings).nextStartedActivity.data.toString())

        settings.findViewById<View>(R.id.rowSafety).performClick()
        ShadowLooper.idleMainLooper()
        ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.infoAction).performClick()
        assertEquals(Intent.ACTION_DIAL, shadowOf(settings).nextStartedActivity.action)

        settings.findViewById<View>(R.id.rowHelp).performClick()
        ShadowLooper.idleMainLooper()
        ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.infoAction).performClick()
        assertEquals(Intent.ACTION_SENDTO, shadowOf(settings).nextStartedActivity.action)

        settings.findViewById<View>(R.id.rowAbout).performClick()
        ShadowLooper.idleMainLooper()
        ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.infoAction).performClick()
        assertEquals(Intent.ACTION_CHOOSER, shadowOf(settings).nextStartedActivity.action)

        // NotificationsActivity: dynamic notification + markAllRead
        AppRepository.addLocalNotification(
            type = NotificationType.RESERVATION,
            title = "Meetup confirmed",
            body = "Meet Paolo at UC Library Lobby",
            targetType = NotificationTarget.LISTING,
            targetId = peerListing.id,
            postOsNotification = false,
        )
        assertTrue(UserState.hasUnreadNotifications)
        val notif = Robolectric.buildActivity(NotificationsActivity::class.java).setup().get()
        ShadowToast.reset()
        notif.markAllRead(notif.findViewById(R.id.btnMarkAllRead))
        assertFalse(UserState.hasUnreadNotifications)
        assertEquals(notif.getString(R.string.toast_notif_all_read), ShadowToast.getTextOfLatestToast())

        // SavedActivity
        UserState.setSaved(peerListing.id, true)
        val saved = Robolectric.buildActivity(SavedActivity::class.java).setup().get()
        val savedList = saved.findViewById<android.widget.LinearLayout>(R.id.savedList)
        assertEquals(1, savedList.childCount)
        savedList.getChildAt(0).findViewById<View>(R.id.rowBody).performClick()
        assertEquals(ListingDetailActivity::class.java.name, shadowOf(saved).nextStartedActivity.component?.className)

        // Logout -> clears session & returns to LoginActivity
        settings.findViewById<View>(R.id.btnLogout).performClick()
        ShadowLooper.idleMainLooper()
        val logoutDialog = ShadowDialog.getLatestDialog() as AlertDialog
        logoutDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        waitUntil { !UserState.isSignedIn && shadowOf(settings).peekNextStartedActivity() != null }
        assertFalse(UserState.isSignedIn)
        assertEquals(LoginActivity::class.java.name, shadowOf(settings).nextStartedActivity.component?.className)
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
