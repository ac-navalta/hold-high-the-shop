package com.nevergrowold.holdhightheshop

import android.app.Activity
import android.app.Dialog
import android.app.SearchManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
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
import com.nevergrowold.holdhightheshop.BaseActivity.Companion.EXTRA_EMAIL
import com.nevergrowold.holdhightheshop.BaseActivity.Companion.EXTRA_LISTING
import com.nevergrowold.holdhightheshop.BaseActivity.Companion.EXTRA_PROGRAM
import com.nevergrowold.holdhightheshop.BaseActivity.Companion.EXTRA_SELLER
import com.nevergrowold.holdhightheshop.BaseActivity.Companion.EXTRA_TAB
import com.nevergrowold.holdhightheshop.ExchangeDetailActivity.Companion.EXTRA_EXCHANGE
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
 * End-to-end Robolectric verification of:
 * 1. AndroidManifest.xml Activity/Provider declarations & intent-filter resolution
 * 2. Every incoming Manifest intent on [MainActivity] (ACTION_VIEW deep links, ACTION_SEARCH, ACTION_SEND)
 * 3. Every explicit navigation Intent across all Activities
 * 4. Every outgoing implicit Intent (Share, Maps geo:, Calendar, Gallery, Camera + FileProvider, Photo Viewer, Email, Dialer, Browser, System Notification Settings)
 * 5. All completed interactive widgets, persistence flows, and Toast messages
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntentsAndFeaturesTest {

    private lateinit var app: android.app.Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("hold_high_the_shop_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        clearFileProviderCache()
        Listings.reset()
        UserState.reset()
        ShadowToast.reset()
    }

    private fun clearFileProviderCache() {
        val field = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        field.isAccessible = true
        (field.get(null) as? MutableMap<*, *>)?.clear()
    }

    // ---------------------------------------------------------------- 1. Manifest verification

    @Test
    fun manifest_declaresAllActivitiesFileProviderAndIntentFilters() {
        val pm = app.packageManager
        val pkgInfo = pm.getPackageInfo(
            app.packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_PROVIDERS,
        )
        val activityNames = pkgInfo.activities.orEmpty().map { it.name }.toSet()
        val expectedActivities = listOf(
            LandingActivity::class.java,
            LoginActivity::class.java,
            MainActivity::class.java,
            MarketActivity::class.java,
            ChatsActivity::class.java,
            ProfileActivity::class.java,
            ListingDetailActivity::class.java,
            CreateListingActivity::class.java,
            ListingPostedActivity::class.java,
            ChatThreadActivity::class.java,
            SavedActivity::class.java,
            SellerProfileActivity::class.java,
            ExchangeDetailActivity::class.java,
            EditProfileActivity::class.java,
            SettingsActivity::class.java,
            NotificationsActivity::class.java,
            ReportListingActivity::class.java,
            ReportReceivedActivity::class.java,
            MapActivity::class.java,
        ).map { it.name }
        expectedActivities.forEach { name ->
            assertTrue("Missing Activity in manifest: $name", name in activityNames)
        }

        // FileProvider registered
        val providers = pkgInfo.providers.orEmpty()
        assertTrue(
            "FileProvider missing from manifest",
            providers.any { it.authority == "${app.packageName}.fileprovider" },
        )

        // MainActivity is exported + singleTop
        val mainInfo = pkgInfo.activities.orEmpty().first { it.name == MainActivity::class.java.name }
        assertTrue(mainInfo.exported)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, mainInfo.launchMode)

        // Launcher resolves to LandingActivity
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launcherMatches = pm.queryIntentActivities(launcherIntent, 0).map { it.activityInfo.name }
        assertTrue(LandingActivity::class.java.name in launcherMatches)

        // Deep link, search, and share filters resolve to MainActivity
        val manifestTestIntents = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://holdhightheshop.app/listing/item2")),
            Intent(Intent.ACTION_VIEW, Uri.parse("http://holdhightheshop.app/listing/item2")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://holdhightheshop.app/market/CITCS")),
            Intent(Intent.ACTION_VIEW, Uri.parse("holdhightheshop://listing/item2")),
            Intent(Intent.ACTION_VIEW, Uri.parse("holdhightheshop://chat/Paolo%20Mendoza")),
            Intent(Intent.ACTION_VIEW, Uri.parse("holdhightheshop://settings")),
            Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, "Casio"),
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Test"),
            Intent(Intent.ACTION_SEND).setType("image/jpeg"),
        )
        manifestTestIntents.forEach { intent ->
            val resolved = pm.queryIntentActivities(intent, 0).map { it.activityInfo.name }
            assertTrue(
                "MainActivity did not resolve manifest intent: action=${intent.action} data=${intent.data} type=${intent.type}",
                MainActivity::class.java.name in resolved,
            )
        }
    }

    // ---------------------------------------------------------------- 2. Onboarding & Login flow + Toasts

    @Test
    fun landingAndLogin_validateEmailShowToastAndLaunchMainActivity() {
        val landing = Robolectric.buildActivity(LandingActivity::class.java).setup().get()
        landing.findViewById<View>(R.id.main).performClick()
        val toLogin = shadowOf(landing).nextStartedActivity
        assertNotNull(toLogin)
        assertEquals(LoginActivity::class.java.name, toLogin.component?.className)
        assertTrue(toLogin.getBooleanExtra(LoginActivity.EXTRA_FROM_SPLASH, false))

        val login = Robolectric.buildActivity(LoginActivity::class.java, toLogin).setup().get()
        val emailInput = login.findViewById<EditText>(R.id.inputEmail)

        // Invalid email -> validation Toast, no MainActivity launched
        emailInput.setText("not-an-email")
        login.signIn(login.findViewById(R.id.main))
        assertEquals(login.getString(R.string.toast_invalid_email), ShadowToast.getTextOfLatestToast())
        assertNull(shadowOf(login).nextStartedActivity)

        // Valid email -> welcome Toast + explicit Intent to MainActivity with EXTRA_EMAIL
        emailInput.setText("mika.delacruz@uc-bcf.edu.ph")
        login.signIn(login.findViewById(R.id.main))
        assertEquals(
            login.getString(R.string.toast_signed_in, UserState.profile.first),
            ShadowToast.getTextOfLatestToast(),
        )
        val toMain = shadowOf(login).nextStartedActivity
        assertNotNull(toMain)
        assertEquals(MainActivity::class.java.name, toMain.component?.className)
        assertEquals("mika.delacruz@uc-bcf.edu.ph", toMain.getStringExtra(EXTRA_EMAIL))
        assertTrue(login.isFinishing)
    }

    // ---------------------------------------------------------------- 3. Manifest incoming intents on MainActivity

    @Test
    fun mainActivity_handlesDeepLinksSearchAndIncomingShareIntents() {
        // 1. Listing web deep link via onCreate
        val listingIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://holdhightheshop.app/listing/item6"))
        val mainController = Robolectric.buildActivity(MainActivity::class.java, listingIntent).setup()
        val main = mainController.get()
        val openedListing = shadowOf(main).nextStartedActivity
        assertEquals(ListingDetailActivity::class.java.name, openedListing.component?.className)
        assertEquals("item6", openedListing.getStringExtra(EXTRA_LISTING))

        // 2. Remaining deep links via onNewIntent (singleTop)
        mainController.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("holdhightheshop://seller/Janelle%20Garcia")))
        val openedSeller = shadowOf(main).nextStartedActivity
        assertEquals(SellerProfileActivity::class.java.name, openedSeller.component?.className)
        assertEquals("Janelle Garcia", openedSeller.getStringExtra(EXTRA_SELLER))

        mainController.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("holdhightheshop://map/UC%20Library%20Lobby")))
        val openedMap = shadowOf(main).nextStartedActivity
        assertEquals(MapActivity::class.java.name, openedMap.component?.className)
        assertEquals("UC Library Lobby", openedMap.getStringExtra(MapActivity.EXTRA_PLACE))

        mainController.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("holdhightheshop://chat/Paolo%20Mendoza?listing=item2")))
        val openedChat = shadowOf(main).nextStartedActivity
        assertEquals(ChatThreadActivity::class.java.name, openedChat.component?.className)
        assertEquals("Paolo Mendoza", openedChat.getStringExtra(EXTRA_SELLER))
        assertEquals("item2", openedChat.getStringExtra(EXTRA_LISTING))

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
        val marketSearchInput = main.findViewById<EditText>(R.id.inputSearch)
        assertNotNull(marketSearchInput)
        assertEquals("Casio", marketSearchInput.text.toString())

        // 4. ACTION_SEND forwards shared title, description, and image URI to CreateListingActivity
        val sharedUri = Uri.parse("content://external.app/shared/item.jpg")
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_SUBJECT, "Graphing Calculator")
            putExtra(Intent.EXTRA_TEXT, "Slightly used TI-84 for COE majors.")
            putExtra(Intent.EXTRA_STREAM, sharedUri)
        }
        mainController.newIntent(shareIntent)
        val forwardedCreate = shadowOf(main).nextStartedActivity
        assertEquals(CreateListingActivity::class.java.name, forwardedCreate.component?.className)
        assertEquals("Graphing Calculator", forwardedCreate.getStringExtra(AppIntents.EXTRA_SHARED_SUBJECT))
        assertEquals("Slightly used TI-84 for COE majors.", forwardedCreate.getStringExtra(AppIntents.EXTRA_SHARED_TEXT))
        assertEquals(sharedUri.toString(), forwardedCreate.getStringExtra(AppIntents.EXTRA_SHARED_IMAGE_URI))

        // Verify CreateListingActivity prefills from the forwarded share intent and shows Toast
        ShadowToast.reset()
        val create = Robolectric.buildActivity(CreateListingActivity::class.java, forwardedCreate).setup().get()
        assertEquals("Graphing Calculator", create.findViewById<EditText>(R.id.inputTitle).text.toString())
        assertEquals("Slightly used TI-84 for COE majors.", create.findViewById<EditText>(R.id.inputDescription).text.toString())
        assertEquals(create.getString(R.string.toast_shared_prefilled), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun legacyTabActivities_forwardToMainActivity() {
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
        val cForward = shadowOf(chats).nextStartedActivity
        assertEquals(MainActivity::class.java.name, cForward.component?.className)
        assertEquals(MainActivity.TAB_CHATS, cForward.getIntExtra(MainActivity.EXTRA_NAV_TAB, -1))
        assertTrue(chats.isFinishing)

        val profile = Robolectric.buildActivity(
            ProfileActivity::class.java,
            Intent(app, ProfileActivity::class.java).putExtra(EXTRA_TAB, ProfileActivity.TAB_COMPLETED),
        ).setup().get()
        val pForward = shadowOf(profile).nextStartedActivity
        assertEquals(MainActivity::class.java.name, pForward.component?.className)
        assertEquals(MainActivity.TAB_PROFILE, pForward.getIntExtra(MainActivity.EXTRA_NAV_TAB, -1))
        assertEquals(ProfileActivity.TAB_COMPLETED, pForward.getIntExtra(EXTRA_TAB, -1))
        assertTrue(profile.isFinishing)
    }

    // ---------------------------------------------------------------- 4. Create / Draft / Edit Listing + Photo Intents

    @Test
    fun createListing_draftSaveRestorePostEditAndPhotoIntents() {
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
        assertEquals("180", create2.findViewById<EditText>(R.id.inputPrice).text.toString())
        assertEquals(create2.getString(R.string.toast_draft_restored), ShadowToast.getTextOfLatestToast())

        // Implicit gallery & camera intents
        create2.launchGalleryPicker()
        val galleryIntent = shadowOf(create2).nextStartedActivityForResult?.intent
        assertNotNull(galleryIntent)
        assertEquals(Intent.ACTION_GET_CONTENT, galleryIntent!!.action)
        assertEquals("image/*", galleryIntent.type)

        create2.launchCameraCapture()
        val cameraIntent = shadowOf(create2).nextStartedActivityForResult?.intent
        assertNotNull(cameraIntent)
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, cameraIntent!!.action)
        val cameraUri = cameraIntent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)
        assertNotNull(cameraUri)
        assertEquals("${app.packageName}.fileprovider", cameraUri!!.authority)

        // Validation Toast when program not yet picked
        create2.postItem(create2.findViewById(R.id.btnPostItem))
        assertEquals(create2.getString(R.string.toast_need_program), ShadowToast.getTextOfLatestToast())

        // Complete required fields (program + condition)
        val programField = CreateListingActivity::class.java.getDeclaredField("program").apply { isAccessible = true }
        programField.setInt(create2, 0)
        create2.findViewById<View>(R.id.condition1).performClick()

        ShadowToast.reset()
        create2.postItem(create2.findViewById(R.id.btnPostItem))
        assertEquals(create2.getString(R.string.toast_listing_posted), ShadowToast.getTextOfLatestToast())
        assertNull(UserState.draft)

        val postedIntent = shadowOf(create2).nextStartedActivity
        assertEquals(ListingPostedActivity::class.java.name, postedIntent.component?.className)
        val newId = postedIntent.getStringExtra(EXTRA_LISTING)
        assertNotNull(newId)
        assertEquals("UC Lab Notebook", Listings.byId(newId).title)

        // ListingPostedActivity: copy reference Toast + View Listing opens the exact new listing
        val posted = Robolectric.buildActivity(ListingPostedActivity::class.java, postedIntent).setup().get()
        posted.copyReference(posted.findViewById(R.id.postedRefCard))
        val expectedRef = Listings.referenceCode(newId!!)
        assertEquals(posted.getString(R.string.toast_ref_copied, expectedRef), ShadowToast.getTextOfLatestToast())

        posted.viewListing(posted.findViewById(R.id.main))
        val toDetail = shadowOf(posted).nextStartedActivity
        assertEquals(ListingDetailActivity::class.java.name, toDetail.component?.className)
        assertEquals(newId, toDetail.getStringExtra(EXTRA_LISTING))

        // Edit the newly posted listing and confirm changes persist in Listings & ListingDetailActivity
        val editIntent = Intent(app, CreateListingActivity::class.java).putExtra(EXTRA_LISTING, newId)
        val editActivity = Robolectric.buildActivity(CreateListingActivity::class.java, editIntent).setup().get()
        editActivity.findViewById<EditText>(R.id.inputTitle).setText("UC Lab Notebook (2-Pack)")
        editActivity.findViewById<EditText>(R.id.inputPrice).setText("320")
        ShadowToast.reset()
        editActivity.postItem(editActivity.findViewById(R.id.btnPostItem))
        assertEquals(editActivity.getString(R.string.toast_changes_saved), ShadowToast.getTextOfLatestToast())
        assertEquals(Activity.RESULT_OK, shadowOf(editActivity).resultCode)
        assertEquals("UC Lab Notebook (2-Pack)", Listings.byId(newId).title)
        assertEquals(320, Listings.byId(newId).price)

        val detail = Robolectric.buildActivity(ListingDetailActivity::class.java, toDetail).setup().get()
        assertEquals("UC Lab Notebook (2-Pack)", detail.findViewById<TextView>(R.id.detailTitle).text.toString())
    }

    // ---------------------------------------------------------------- 5. ListingDetail, Share, Calendar, Map, Seller, Report

    @Test
    fun listingDetail_shareCalendarPhotoViewerMapSellerAndReportIntents() {
        // Add a photo to item2 so photo-viewer implicit intent can be verified too
        Listings.updateListing(Listings.byId("item2").copy(photoUris = listOf("content://test/casio.jpg")))

        val detail = Robolectric.buildActivity(
            ListingDetailActivity::class.java,
            Intent(app, ListingDetailActivity::class.java).putExtra(EXTRA_LISTING, "item2"),
        ).setup().get()

        // Implicit ACTION_VIEW image/* on hero tap
        detail.cycleHeroPhoto(detail.findViewById(R.id.detailHero))
        val viewPhoto = shadowOf(detail).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, viewPhoto.action)
        assertEquals("image/*", viewPhoto.type)

        // Implicit ACTION_INSERT Calendar event for reserved listing item2 + Toast
        ShadowToast.reset()
        detail.addToCalendar(detail.findViewById(R.id.btnAddCalendar))
        val calIntent = shadowOf(detail).nextStartedActivity
        assertEquals(Intent.ACTION_INSERT, calIntent.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, calIntent.data)
        assertEquals(detail.getString(R.string.toast_calendar_opened), ShadowToast.getTextOfLatestToast())

        // Also verify Calendar implicit intent on an active loan (item5)
        val loanDetail = Robolectric.buildActivity(
            ListingDetailActivity::class.java,
            Intent(app, ListingDetailActivity::class.java).putExtra(EXTRA_LISTING, "item5"),
        ).setup().get()
        loanDetail.addToCalendar(loanDetail.findViewById(R.id.btnAddCalendar))
        val loanCal = shadowOf(loanDetail).nextStartedActivity
        assertEquals(Intent.ACTION_INSERT, loanCal.action)
        assertTrue(loanCal.getStringExtra(CalendarContract.Events.TITLE)!!.contains("White Lab Gown"))

        // Share sheet -> Copy link Toast
        detail.shareListing(detail.findViewById(R.id.btnShare))
        ShadowLooper.idleMainLooper()
        val shareDialog1: Dialog? = ShadowDialog.getLatestDialog()
        assertNotNull(shareDialog1)
        shareDialog1!!.findViewById<View>(R.id.shareCopy).performClick()
        assertEquals(detail.getString(R.string.toast_link_copied), ShadowToast.getTextOfLatestToast())
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("https://holdhightheshop.app/listing/item2", clipboard.primaryClip?.getItemAt(0)?.text.toString())

        // Share sheet -> Share via... implicit ACTION_SEND chooser
        detail.shareListing(detail.findViewById(R.id.btnShare))
        ShadowLooper.idleMainLooper()
        val shareDialog2 = ShadowDialog.getLatestDialog()!!
        shareDialog2.findViewById<View>(R.id.shareExternal).performClick()
        val shareChooser = shadowOf(detail).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, shareChooser.action)
        val innerShare = shareChooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertEquals(Intent.ACTION_SEND, innerShare?.action)

        // Meetup sheet -> Directions opens MapActivity
        detail.openMeetup(detail.findViewById(R.id.meetupPlace))
        ShadowLooper.idleMainLooper()
        ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.meetupDirections).performClick()
        val toMap = shadowOf(detail).nextStartedActivity
        assertEquals(MapActivity::class.java.name, toMap.component?.className)

        // MapActivity -> Open in Maps implicit geo: intent + recenter Toast
        val mapActivity = Robolectric.buildActivity(MapActivity::class.java, toMap).setup().get()
        mapActivity.openInExternalMaps(mapActivity.findViewById(R.id.btnOpenMaps))
        val geoIntent = shadowOf(mapActivity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, geoIntent.action)
        assertEquals("geo", geoIntent.data?.scheme)

        ShadowToast.reset()
        mapActivity.recenter(mapActivity.findViewById(R.id.campusMap))
        assertEquals(mapActivity.getString(R.string.toast_map_recentered), ShadowToast.getTextOfLatestToast())

        // SellerProfileActivity -> share seller implicit ACTION_SEND + message seller explicit intent
        detail.openSellerProfile(detail.findViewById(R.id.sellerCard))
        val toSeller = shadowOf(detail).nextStartedActivity
        assertEquals(SellerProfileActivity::class.java.name, toSeller.component?.className)
        val sellerActivity = Robolectric.buildActivity(SellerProfileActivity::class.java, toSeller).setup().get()
        sellerActivity.shareSeller(sellerActivity.findViewById(R.id.btnShareSeller))
        assertEquals(Intent.ACTION_CHOOSER, shadowOf(sellerActivity).nextStartedActivity.action)
        sellerActivity.messageSeller(sellerActivity.findViewById(R.id.btnMessageSeller))
        assertEquals(ChatThreadActivity::class.java.name, shadowOf(sellerActivity).nextStartedActivity.component?.className)

        // Report flow: ListingDetail -> ReportListing -> ReportReceived + Toasts + real reference
        detail.openReport(detail.findViewById(R.id.btnReport))
        val toReport = shadowOf(detail).nextStartedActivity
        assertEquals(ReportListingActivity::class.java.name, toReport.component?.className)
        val reportActivity = Robolectric.buildActivity(ReportListingActivity::class.java, toReport).setup().get()

        // Missing reason -> validation Toast
        reportActivity.submitReport(reportActivity.findViewById(R.id.main))
        assertEquals(reportActivity.getString(R.string.toast_select_reason), ShadowToast.getTextOfLatestToast())

        // Select reason + submit -> real Issue created + Toast + ReportReceivedActivity
        reportActivity.findViewById<View>(R.id.reason1).performClick()
        reportActivity.findViewById<EditText>(R.id.inputReportDetails).setText("Duplicate spam listing.")
        reportActivity.submitReport(reportActivity.findViewById(R.id.main))
        val issue = UserState.issue("item2")
        assertNotNull(issue)
        assertEquals(reportActivity.getString(R.string.toast_report_submitted, issue!!.ref), ShadowToast.getTextOfLatestToast())

        val toReceived = shadowOf(reportActivity).nextStartedActivity
        assertEquals(ReportReceivedActivity::class.java.name, toReceived.component?.className)
        assertEquals(issue.ref, toReceived.getStringExtra(ReportReceivedActivity.EXTRA_REFERENCE))

        val receivedActivity = Robolectric.buildActivity(ReportReceivedActivity::class.java, toReceived).setup().get()
        assertEquals(issue.ref, receivedActivity.findViewById<TextView>(R.id.reportRef).text.toString())
        receivedActivity.copyReference(receivedActivity.findViewById(R.id.reportRefCard))
        assertEquals(receivedActivity.getString(R.string.toast_ref_copied, issue.ref), ShadowToast.getTextOfLatestToast())
    }

    // ---------------------------------------------------------------- 6. ChatThread persistence, header, meetup, photo intents

    @Test
    fun chatThread_persistsMessagesUpdatesChatsTabAndLaunchesIntents() {
        val chatIntent = Intent(app, ChatThreadActivity::class.java)
            .putExtra(EXTRA_SELLER, "Paolo Mendoza")
            .putExtra(EXTRA_LISTING, "item2")
        val chat = Robolectric.buildActivity(ChatThreadActivity::class.java, chatIntent).setup().get()

        // Opening thread marks Paolo's chat read
        assertFalse(UserState.isChatUnread(0, "Paolo Mendoza"))

        // Send a message and verify persistence + input cleared
        val input = chat.findViewById<EditText>(R.id.inputMessage)
        input.setText("I'll be at UC Main Gate at 1:30!")
        chat.sendMessage(chat.findViewById(R.id.btnSend))
        assertEquals("", input.text.toString())
        assertEquals(1, UserState.chatMessages("Paolo Mendoza").size)

        // Header tap -> SellerProfileActivity; Item strip tap -> ListingDetailActivity
        chat.openThreadSeller(chat.findViewById(R.id.threadProfileGroup))
        assertEquals(SellerProfileActivity::class.java.name, shadowOf(chat).nextStartedActivity.component?.className)
        chat.openThreadListing(chat.findViewById(R.id.stripTitle))
        assertEquals(ListingDetailActivity::class.java.name, shadowOf(chat).nextStartedActivity.component?.className)

        // Tapping meetup confirmation card -> Directions (MapActivity) and Calendar (ACTION_INSERT)
        chat.openSampleMeetupOptions(chat.findViewById(R.id.sampleSystemCard))
        ShadowLooper.idleMainLooper()
        val meetupDialog1 = ShadowDialog.getLatestDialog() as AlertDialog
        meetupDialog1.listView.performItemClick(meetupDialog1.listView.getChildAt(0), 0, 0)
        assertEquals(MapActivity::class.java.name, shadowOf(chat).nextStartedActivity.component?.className)

        ShadowToast.reset()
        chat.openSampleMeetupOptions(chat.findViewById(R.id.sampleSystemCard))
        ShadowLooper.idleMainLooper()
        val meetupDialog2 = ShadowDialog.getLatestDialog() as AlertDialog
        meetupDialog2.listView.performItemClick(meetupDialog2.listView.getChildAt(1), 1, 1)
        val chatCal = shadowOf(chat).nextStartedActivity
        assertEquals(Intent.ACTION_INSERT, chatCal.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, chatCal.data)
        assertEquals(chat.getString(R.string.toast_calendar_opened), ShadowToast.getTextOfLatestToast())

        // Gallery & Camera implicit intents in chat
        chat.launchChatGallery()
        assertEquals(Intent.ACTION_GET_CONTENT, shadowOf(chat).nextStartedActivityForResult?.intent?.action)
        chat.launchChatCamera()
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, shadowOf(chat).nextStartedActivityForResult?.intent?.action)

        // Chats tab reflects the new message preview and hides Paolo's unread dot
        val main = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_NAV_TAB, MainActivity.TAB_CHATS),
        ).setup().get()
        assertEquals("I'll be at UC Main Gate at 1:30!", main.findViewById<TextView>(R.id.chatLast1).text.toString())
        assertEquals(View.GONE, main.findViewById<View>(R.id.chatUnread1).visibility)
    }

    // ---------------------------------------------------------------- 7. EditProfile, Settings, ExchangeDetail, Notifications

    @Test
    fun editProfileAndSettings_persistChangesAndLaunchAllSystemIntents() {
        val edit = Robolectric.buildActivity(EditProfileActivity::class.java).setup().get()

        // Avatar tap launches implicit gallery intent
        edit.pickAvatar(edit.findViewById(R.id.editAvatarContainer))
        val pickAvatar = shadowOf(edit).nextStartedActivityForResult?.intent
        assertEquals(Intent.ACTION_GET_CONTENT, pickAvatar?.action)
        assertEquals("image/*", pickAvatar?.type)

        // Empty name -> validation Toast
        edit.findViewById<EditText>(R.id.inputName).setText("   ")
        edit.saveProfile(edit.findViewById(R.id.main))
        assertEquals(edit.getString(R.string.toast_need_name), ShadowToast.getTextOfLatestToast())
        assertFalse(edit.isFinishing)

        // Save updated profile -> RESULT_OK + Toast + updates Home & Profile tabs
        edit.findViewById<EditText>(R.id.inputName).setText("Marco Navalta")
        edit.findViewById<EditText>(R.id.inputBio).setText("CITCS senior trading books.")
        ShadowToast.reset()
        edit.saveProfile(edit.findViewById(R.id.main))
        assertEquals(edit.getString(R.string.toast_profile_saved), ShadowToast.getTextOfLatestToast())
        assertEquals(Activity.RESULT_OK, shadowOf(edit).resultCode)
        assertEquals("Marco Navalta", UserState.profile.name)

        // SettingsActivity: switches persist + system notification settings + 4 info sheet implicit intents
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertEquals(UserState.profile.email, settings.findViewById<TextView>(R.id.rowEmailValue).text.toString())

        val swShowProgram = settings.findViewById<MaterialSwitch>(R.id.swShowProgram)
        swShowProgram.isChecked = false
        assertFalse(UserState.settings.showProgram)

        // System notification settings implicit intent
        settings.findViewById<View>(R.id.rowSystemNotifications).performClick()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, shadowOf(settings).nextStartedActivity.action)

        // Community Guidelines -> Open UC website (ACTION_VIEW https:)
        settings.findViewById<View>(R.id.rowGuidelines).performClick()
        ShadowLooper.idleMainLooper()
        ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.infoAction).performClick()
        val webIntent = shadowOf(settings).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, webIntent.action)
        assertEquals(AppIntents.UC_WEBSITE_URL, webIntent.data.toString())

        // Meetup Safety Tips -> Dial UC Security (ACTION_DIAL tel:)
        settings.findViewById<View>(R.id.rowSafety).performClick()
        ShadowLooper.idleMainLooper()
        ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.infoAction).performClick()
        val dialIntent = shadowOf(settings).nextStartedActivity
        assertEquals(Intent.ACTION_DIAL, dialIntent.action)
        assertEquals("tel:${AppIntents.UC_SECURITY_PHONE}", dialIntent.data.toString())

        // Help Center -> Email student support (ACTION_SENDTO mailto:)
        settings.findViewById<View>(R.id.rowHelp).performClick()
        ShadowLooper.idleMainLooper()
        ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.infoAction).performClick()
        val emailIntent = shadowOf(settings).nextStartedActivity
        assertEquals(Intent.ACTION_SENDTO, emailIntent.action)
        assertEquals("mailto:", emailIntent.data.toString())

        // About -> Share Hold High The Shop (ACTION_SEND chooser)
        settings.findViewById<View>(R.id.rowAbout).performClick()
        ShadowLooper.idleMainLooper()
        ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.infoAction).performClick()
        assertEquals(Intent.ACTION_CHOOSER, shadowOf(settings).nextStartedActivity.action)

        // HomeTab reflects edited first name & ProfileTab reflects edited name + showProgram = false
        val mainHome = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(
            mainHome.getString(R.string.greeting_format, "Marco"),
            mainHome.findViewById<TextView>(R.id.homeGreetingTitle).text.toString(),
        )

        val mainProfile = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_NAV_TAB, MainActivity.TAB_PROFILE),
        ).setup().get()
        assertEquals("Marco Navalta", mainProfile.findViewById<TextView>(R.id.profileName).text.toString())
        assertEquals("3rd Year", mainProfile.findViewById<TextView>(R.id.profileMeta).text.toString())

        // Logout confirmation -> Toast + LoginActivity
        settings.findViewById<View>(R.id.btnLogout).performClick()
        ShadowLooper.idleMainLooper()
        val logoutDialog = ShadowDialog.getLatestDialog() as AlertDialog
        logoutDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(settings.getString(R.string.toast_logged_out), ShadowToast.getTextOfLatestToast())
        assertEquals(LoginActivity::class.java.name, shadowOf(settings).nextStartedActivity.component?.className)
    }

    @Test
    fun exchangeDetailAndNotifications_handleAllIntentsAndToasts() {
        val sample = Listings.completed().first { it.partner != null }
        val ex = Robolectric.buildActivity(
            ExchangeDetailActivity::class.java,
            Intent(app, ExchangeDetailActivity::class.java).putExtra(EXTRA_EXCHANGE, sample.id),
        ).setup().get()

        // Share receipt implicit ACTION_SEND chooser
        ex.shareReceipt(ex.findViewById(R.id.btnShareReceipt))
        assertEquals(Intent.ACTION_CHOOSER, shadowOf(ex).nextStartedActivity.action)

        // Copy reference -> Clipboard + Toast
        ex.copyReference(ex.findViewById(R.id.exReferenceRow))
        val ref = ex.findViewById<TextView>(R.id.exReference).text.toString()
        assertEquals(ex.getString(R.string.toast_ref_copied, ref), ShadowToast.getTextOfLatestToast())

        // Meetup row -> MapActivity; Partner card -> SellerProfileActivity; Message -> ChatThreadActivity
        ex.openMeetupMap(ex.findViewById(R.id.exMeetupRow))
        assertEquals(MapActivity::class.java.name, shadowOf(ex).nextStartedActivity.component?.className)
        ex.openPartner(ex.findViewById(R.id.exPartnerCard))
        assertEquals(SellerProfileActivity::class.java.name, shadowOf(ex).nextStartedActivity.component?.className)
        ex.messagePartner(ex.findViewById(R.id.exMessage))
        assertEquals(ChatThreadActivity::class.java.name, shadowOf(ex).nextStartedActivity.component?.className)

        // NotificationsActivity: row 1, row 2, row 5 sheet shortcut, and markAllRead Toast
        val notif = Robolectric.buildActivity(NotificationsActivity::class.java).setup().get()
        notif.openReservationNotification(notif.findViewById(R.id.notifRow1))
        assertTrue(UserState.isNotificationRead(1))
        assertEquals(ListingDetailActivity::class.java.name, shadowOf(notif).nextStartedActivity.component?.className)

        notif.openReportNotification(notif.findViewById(R.id.notifRow5))
        ShadowLooper.idleMainLooper()
        ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.infoAction).performClick()
        val reportedItem = shadowOf(notif).nextStartedActivity
        assertEquals(ListingDetailActivity::class.java.name, reportedItem.component?.className)
        assertEquals("item4", reportedItem.getStringExtra(EXTRA_LISTING))

        ShadowToast.reset()
        notif.markAllRead(notif.findViewById(R.id.btnMarkAllRead))
        assertTrue(UserState.notificationsRead)
        assertEquals(notif.getString(R.string.toast_notif_all_read), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun homeSavedAndAcceptedDeal_coverRemainingExplicitAndImplicitIntents() {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        // Home tab explicit intents: Notifications, Create Listing, Saved
        main.openNotifications(main.findViewById(R.id.main))
        assertEquals(NotificationsActivity::class.java.name, shadowOf(main).nextStartedActivity.component?.className)

        main.openCreateListing(main.findViewById(R.id.main))
        assertEquals(CreateListingActivity::class.java.name, shadowOf(main).nextStartedActivity.component?.className)

        main.openSaved(main.findViewById(R.id.main))
        assertEquals(SavedActivity::class.java.name, shadowOf(main).nextStartedActivity.component?.className)

        // SavedActivity lists bookmarked items and clicking one opens ListingDetailActivity
        val saved = Robolectric.buildActivity(SavedActivity::class.java).setup().get()
        val savedList = saved.findViewById<android.widget.LinearLayout>(R.id.savedList)
        assertTrue(savedList.childCount > 0)
        savedList.getChildAt(0).findViewById<View>(R.id.rowBody).performClick()
        assertEquals(ListingDetailActivity::class.java.name, shadowOf(saved).nextStartedActivity.component?.className)

        // Accepted deal on student's own listing (mine3, request rq4): Calendar implicit intent + Toast
        ShadowToast.reset()
        val dealDetail = Robolectric.buildActivity(
            ListingDetailActivity::class.java,
            Intent(app, ListingDetailActivity::class.java).putExtra(EXTRA_LISTING, "mine3"),
        ).setup().get()
        dealDetail.addToCalendar(dealDetail.findViewById(R.id.btnAddCalendar))
        val dealCal = shadowOf(dealDetail).nextStartedActivity
        assertEquals(Intent.ACTION_INSERT, dealCal.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, dealCal.data)
        assertEquals(dealDetail.getString(R.string.toast_calendar_opened), ShadowToast.getTextOfLatestToast())

        // Own open listing (mine5): Edit button opens CreateListingActivity with EXTRA_LISTING
        val ownDetail = Robolectric.buildActivity(
            ListingDetailActivity::class.java,
            Intent(app, ListingDetailActivity::class.java).putExtra(EXTRA_LISTING, "mine5"),
        ).setup().get()
        ownDetail.editListing(ownDetail.findViewById(R.id.btnEdit))
        val editIntent = shadowOf(ownDetail).nextStartedActivity
        assertEquals(CreateListingActivity::class.java.name, editIntent.component?.className)
        assertEquals("mine5", editIntent.getStringExtra(EXTRA_LISTING))
    }
}
