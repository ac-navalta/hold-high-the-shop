package com.nevergrowold.holdhightheshop

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [AppIntents] (deep-link parsing and every implicit intent builder),
 * [Listings] CRUD operations, and [UserState] persistence & session tracking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppIntentsUnitTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("hold_high_the_shop_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        clearFileProviderCache()
        Listings.reset()
        UserState.reset()
    }

    private fun clearFileProviderCache() {
        val field = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        field.isAccessible = true
        (field.get(null) as? MutableMap<*, *>)?.clear()
    }

    // ---------------------------------------------------------------- deep-link parser

    @Test
    fun parseDeepLink_webUrls_coverAllRoutes() {
        assertEquals(
            AppIntents.DeepLinkRoute.ListingDetail("item2"),
            AppIntents.parseDeepLink(Uri.parse("https://holdhightheshop.app/listing/item2")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.ListingDetail("item5"),
            AppIntents.parseDeepLink(Uri.parse("http://holdhightheshop.app/listing/item5")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.ListingDetail(Listings.DEFAULT_ID),
            AppIntents.parseDeepLink(Uri.parse("https://holdhightheshop.app/listing")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.Market(program = "CITCS", query = "casio"),
            AppIntents.parseDeepLink(Uri.parse("https://holdhightheshop.app/market/citcs?q=casio")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.Market(program = "COE", query = "arduino"),
            AppIntents.parseDeepLink(Uri.parse("https://holdhightheshop.app/market?program=COE&q=arduino")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.Chats,
            AppIntents.parseDeepLink(Uri.parse("https://holdhightheshop.app/chats")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.ChatThread("Paolo Mendoza", "item2"),
            AppIntents.parseDeepLink(Uri.parse("https://holdhightheshop.app/chat/Paolo%20Mendoza?listing=item2")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.Profile(ProfileActivity.TAB_ACTIVE),
            AppIntents.parseDeepLink(Uri.parse("https://holdhightheshop.app/profile")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.Profile(ProfileActivity.TAB_COMPLETED),
            AppIntents.parseDeepLink(Uri.parse("https://holdhightheshop.app/profile/completed")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.Profile(ProfileActivity.TAB_SAVED),
            AppIntents.parseDeepLink(Uri.parse("https://holdhightheshop.app/profile/saved")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.SellerProfile("Janelle Garcia"),
            AppIntents.parseDeepLink(Uri.parse("https://holdhightheshop.app/seller/Janelle%20Garcia")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.MapDirections("UC Library Lobby"),
            AppIntents.parseDeepLink(Uri.parse("https://holdhightheshop.app/map/UC%20Library%20Lobby")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.CreateListing,
            AppIntents.parseDeepLink(Uri.parse("https://holdhightheshop.app/create")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.Saved,
            AppIntents.parseDeepLink(Uri.parse("https://holdhightheshop.app/saved")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.Notifications,
            AppIntents.parseDeepLink(Uri.parse("https://holdhightheshop.app/notifications")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.Settings,
            AppIntents.parseDeepLink(Uri.parse("https://holdhightheshop.app/settings")),
        )
    }

    @Test
    fun parseDeepLink_customScheme_coversAllRoutes() {
        assertEquals(
            AppIntents.DeepLinkRoute.ListingDetail("item6"),
            AppIntents.parseDeepLink(Uri.parse("holdhightheshop://listing/item6")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.Market(program = "CONAHS", query = "gown"),
            AppIntents.parseDeepLink(Uri.parse("holdhightheshop://market/CONAHS?q=gown")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.Chats,
            AppIntents.parseDeepLink(Uri.parse("holdhightheshop://chats")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.ChatThread("Miguel Santos", "item1"),
            AppIntents.parseDeepLink(Uri.parse("holdhightheshop://chat/Miguel%20Santos?listing=item1")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.ChatThread("Aira Villanueva", null),
            AppIntents.parseDeepLink(Uri.parse("holdhightheshop://chat?seller=Aira%20Villanueva")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.Chats,
            AppIntents.parseDeepLink(Uri.parse("holdhightheshop://chat")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.Profile(ProfileActivity.TAB_COMPLETED),
            AppIntents.parseDeepLink(Uri.parse("holdhightheshop://profile/completed")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.SellerProfile("Carlo Reyes"),
            AppIntents.parseDeepLink(Uri.parse("holdhightheshop://seller/Carlo%20Reyes")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.MapDirections("CITCS Building Entrance"),
            AppIntents.parseDeepLink(Uri.parse("holdhightheshop://map/CITCS%20Building%20Entrance")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.CreateListing,
            AppIntents.parseDeepLink(Uri.parse("holdhightheshop://create")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.Saved,
            AppIntents.parseDeepLink(Uri.parse("holdhightheshop://saved")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.Notifications,
            AppIntents.parseDeepLink(Uri.parse("holdhightheshop://notifications")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.Settings,
            AppIntents.parseDeepLink(Uri.parse("holdhightheshop://settings")),
        )
    }

    @Test
    fun parseDeepLink_invalidOrForeignUris_returnNull() {
        assertNull(AppIntents.parseDeepLink(null))
        assertNull(AppIntents.parseDeepLink(Uri.parse("https://example.com/listing/item2")))
        assertNull(AppIntents.parseDeepLink(Uri.parse("mailto:support@holdhightheshop.app")))
        assertNull(AppIntents.parseDeepLink(Uri.parse("https://holdhightheshop.app/unknown")))
        assertNull(AppIntents.parseDeepLink(Uri.parse("holdhightheshop://unknown")))
    }

    // ---------------------------------------------------------------- implicit intent builders

    @Test
    fun implicitIntentBuilders_populateActionsDataAndExtras() {
        val listing = Listings.byId("item2")
        val shareListing = AppIntents.shareListingChooser(context, listing)
        assertEquals(Intent.ACTION_CHOOSER, shareListing.action)
        val innerShare = shareListing.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(innerShare)
        assertEquals(Intent.ACTION_SEND, innerShare!!.action)
        assertEquals("text/plain", innerShare.type)
        assertEquals("Casio fx-991EX", innerShare.getStringExtra(Intent.EXTRA_SUBJECT))
        assertTrue(innerShare.getStringExtra(Intent.EXTRA_TEXT)!!.contains("https://holdhightheshop.app/listing/item2"))

        val shareSeller = AppIntents.shareSellerChooser(context, listing.seller)
        assertEquals(Intent.ACTION_CHOOSER, shareSeller.action)
        val innerSeller = shareSeller.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertTrue(innerSeller.getStringExtra(Intent.EXTRA_TEXT)!!.contains("Paolo%20Mendoza"))

        val exchange = Listings.completed().first()
        val shareReceipt = AppIntents.shareReceiptChooser(context, exchange, "HTS-23001")
        val innerReceipt = shareReceipt.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertTrue(innerReceipt.getStringExtra(Intent.EXTRA_TEXT)!!.contains("HTS-23001"))

        val shareApp = AppIntents.shareAppChooser(context)
        val innerApp = shareApp.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertTrue(innerApp.getStringExtra(Intent.EXTRA_TEXT)!!.contains("https://holdhightheshop.app/market"))

        val geo = AppIntents.mapGeoIntent("UC Library Lobby")
        assertEquals(Intent.ACTION_VIEW, geo.action)
        assertEquals("geo", geo.data?.scheme)
        assertTrue(geo.data.toString().contains("UC%20Library%20Lobby"))

        val webMap = AppIntents.mapWebIntent("UC Library Lobby")
        assertEquals(Intent.ACTION_VIEW, webMap.action)
        assertEquals("https", webMap.data?.scheme)
        assertTrue(webMap.data.toString().contains("maps.google.com"))

        val begin = 1_800_000_000_000L
        val cal = AppIntents.calendarEventIntent("Meetup", "UC Library Lobby", "Bring ID", begin)
        assertEquals(Intent.ACTION_INSERT, cal.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, cal.data)
        assertEquals(begin, cal.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1L))
        assertEquals(begin + 30 * 60 * 1000L, cal.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1L))
        assertEquals("Meetup", cal.getStringExtra(CalendarContract.Events.TITLE))
        assertTrue(cal.getStringExtra(CalendarContract.Events.EVENT_LOCATION)!!.contains("UC Library Lobby"))

        val gallery = AppIntents.pickImageIntent()
        assertEquals(Intent.ACTION_GET_CONTENT, gallery.action)
        assertEquals("image/*", gallery.type)
        assertTrue(gallery.hasCategory(Intent.CATEGORY_OPENABLE))

        val (camera, outputUri) = AppIntents.capturePhotoIntent(context)
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, camera.action)
        assertEquals(outputUri, camera.getParcelableExtra(MediaStore.EXTRA_OUTPUT))
        assertEquals("com.nevergrowold.holdhightheshop.fileprovider", outputUri.authority)
        assertTrue((camera.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)
        assertTrue((camera.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)

        val viewPhoto = AppIntents.viewImageIntent(outputUri)
        assertEquals(Intent.ACTION_VIEW, viewPhoto.action)
        assertEquals(outputUri, viewPhoto.data)
        assertEquals("image/*", viewPhoto.type)
        assertTrue((viewPhoto.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)

        val email = AppIntents.emailSupportIntent(context, "Help", "Details")
        assertEquals(Intent.ACTION_SENDTO, email.action)
        assertEquals("mailto:", email.data.toString())
        assertEquals(AppIntents.SUPPORT_EMAIL, email.getStringArrayExtra(Intent.EXTRA_EMAIL)?.firstOrNull())

        val dial = AppIntents.dialSecurityIntent()
        assertEquals(Intent.ACTION_DIAL, dial.action)
        assertEquals("tel:${AppIntents.UC_SECURITY_PHONE}", dial.data.toString())

        val web = AppIntents.openWebsiteIntent()
        assertEquals(Intent.ACTION_VIEW, web.action)
        assertEquals(AppIntents.UC_WEBSITE_URL, web.data.toString())

        val notifSettings = AppIntents.appNotificationSettingsIntent(context)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, notifSettings.action)
        assertEquals(context.packageName, notifSettings.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun launch_fallsBackAndShowsToastWhenNoHandlerExists() {
        var startedFallback: Intent? = null
        val fallbackOnlyContext = object : android.content.ContextWrapper(context) {
            override fun startActivity(intent: Intent?) {
                if (intent?.data?.scheme == "geo") {
                    throw android.content.ActivityNotFoundException("No maps app")
                }
                startedFallback = intent
            }
        }
        val launchedFallback = AppIntents.launch(
            context = fallbackOnlyContext,
            intent = AppIntents.mapGeoIntent("UC Library Lobby"),
            fallback = AppIntents.mapWebIntent("UC Library Lobby"),
        )
        assertTrue(launchedFallback)
        assertNotNull(startedFallback)
        assertEquals("https", startedFallback!!.data?.scheme)

        val noAppsContext = object : android.content.ContextWrapper(context) {
            override fun startActivity(intent: Intent?) {
                throw android.content.ActivityNotFoundException("No handler")
            }
        }
        org.robolectric.shadows.ShadowToast.reset()
        val launchedNone = AppIntents.launch(noAppsContext, AppIntents.dialSecurityIntent())
        assertFalse(launchedNone)
        assertEquals(
            context.getString(R.string.toast_no_app_available),
            org.robolectric.shadows.ShadowToast.getTextOfLatestToast(),
        )
    }

    // ---------------------------------------------------------------- Listings & UserState backend

    @Test
    fun listingsCrud_andSellerLookup_workEndToEnd() {
        val id = Listings.nextListingId()
        assertEquals("mine7", id)
        assertTrue(Listings.referenceCode(id).startsWith("HTS-"))

        val added = Listings.addListing(
            Listing(
                id = id,
                title = "TI-84 Plus Calculator",
                price = 2500,
                program = "CITCS",
                condition = 0,
                type = 0,
                description = "Clean screen and fresh batteries.",
                seller = Listings.ME,
                meetup = "UC Library Lobby",
                postedDaysAgo = 0,
                photoUris = listOf("content://test/calc.jpg"),
            ),
        )
        assertTrue(Listings.isMine(added))
        assertEquals("TI-84 Plus Calculator", Listings.byId(id).title)
        assertEquals(Listings.ME.name, Listings.sellerOf(added).name)

        Listings.updateListing(added.copy(title = "TI-84 Plus CE", price = 2800))
        assertEquals("TI-84 Plus CE", Listings.byId(id).title)
        assertEquals(2800, Listings.byId(id).price)

        assertEquals("Paolo Mendoza", Listings.sellerByName("paolo").name)
        assertEquals("Janelle Garcia", Listings.sellerByName("JANELLE GARCIA").name)
    }

    @Test
    fun userState_persistsProfileSettingsAndChatState() {
        UserState.updateEmail(context, "alex.reyes@uc-bcf.edu.ph")
        UserState.updateProfile(
            context = context,
            name = "Alex Reyes",
            program = "COE",
            year = "4th Year",
            bio = "BSCE student selling reviewers.",
            avatarUri = "content://test/avatar.png",
        )
        UserState.updateSettings(
            context = context,
            next = UserState.SettingsState(
                notifyMessages = false,
                notifyReservations = true,
                notifyReminders = false,
                notifyPriceDrops = true,
                showProgram = false,
                showActiveStatus = false,
            ),
        )
        UserState.updateDefaultMeetup(context, 2)

        // Simulate fresh app restart reloading from SharedPreferences.
        UserState.reset()
        UserState.ensureLoaded(context)

        assertEquals("alex.reyes@uc-bcf.edu.ph", UserState.profile.email)
        assertEquals("Alex Reyes", UserState.profile.name)
        assertEquals("AR", UserState.profile.initials)
        assertEquals("COE", UserState.profile.program)
        assertEquals("4th Year", UserState.profile.year)
        assertEquals("BSCE student selling reviewers.", UserState.profile.bio)
        assertEquals("content://test/avatar.png", UserState.profile.avatarUri)
        assertFalse(UserState.settings.notifyMessages)
        assertTrue(UserState.settings.notifyPriceDrops)
        assertFalse(UserState.settings.showProgram)
        assertFalse(UserState.settings.showActiveStatus)
        assertEquals(2, UserState.defaultMeetup)

        // Per-notification read tracking.
        assertFalse(UserState.notificationsRead)
        UserState.markNotificationRead(1)
        assertTrue(UserState.isNotificationRead(1))
        assertFalse(UserState.notificationsRead)
        UserState.markNotificationRead(2)
        assertTrue(UserState.notificationsRead)

        // Chat log + unread state.
        val paolo = Listings.sellerByName("Paolo Mendoza")
        assertTrue(UserState.isChatUnread(0, paolo.name))
        UserState.appendChatMessage(paolo.name, UserState.ChatMessage(text = "See you at 2 PM!", timeLabel = "Now"))
        assertFalse(UserState.isChatUnread(0, paolo.name))
        assertEquals("See you at 2 PM!" to "Now", UserState.lastChatPreview(paolo))
    }
}
