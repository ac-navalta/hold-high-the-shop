package com.nevergrowold.holdhightheshop

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.nevergrowold.holdhightheshop.data.AppRepository
import com.nevergrowold.holdhightheshop.data.ListingStatus
import com.nevergrowold.holdhightheshop.data.ProposalStatus
import com.nevergrowold.holdhightheshop.data.ProposalType
import org.junit.After
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
 * Unit tests for [AppIntents] (hardened HTTPS-only deep links + implicit intent builders),
 * [Listings] multi-select college tags & day-unit lending, [UserState] per-listing chats,
 * interactive schedule/extension proposals, 3-report auto-moderation, and blocking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppIntentsUnitTest {

    private lateinit var context: Context
    private lateinit var peerSeller: Seller

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearFileProviderCache()
        Listings.reset()
        AppRepository.seedVerifiedTestSession(
            context = context,
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
            completed = 5,
            lastMessage = "",
            online = true,
            uid = "uid_paolo",
            email = "paolo.mendoza@students.uc-bcf.edu.ph",
        )
        AppRepository.registerTestStudent(peerSeller)
    }

    @After
    fun tearDown() {
        Listings.reset()
    }

    private fun clearFileProviderCache() {
        val field = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        field.isAccessible = true
        (field.get(null) as? MutableMap<*, *>)?.clear()
    }

    // ---------------------------------------------------------------- deep-link parser

    @Test
    fun parseDeepLink_httpsAndCustomScheme_coverAllValidRoutesAndRejectRemovedOnes() {
        assertEquals(
            AppIntents.DeepLinkRoute.ListingDetail("item2"),
            AppIntents.parseDeepLink(Uri.parse("https://holdhightheshop.app/listing/item2")),
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
            AppIntents.DeepLinkRoute.SellerProfile("Paolo Mendoza"),
            AppIntents.parseDeepLink(Uri.parse("https://holdhightheshop.app/seller/Paolo%20Mendoza")),
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

        // Custom scheme routes
        assertEquals(
            AppIntents.DeepLinkRoute.ListingDetail("item6"),
            AppIntents.parseDeepLink(Uri.parse("holdhightheshop://listing/item6")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.Market(program = "CONAHS", query = "gown"),
            AppIntents.parseDeepLink(Uri.parse("holdhightheshop://market/CONAHS?q=gown")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.ChatThread("Paolo Mendoza", null),
            AppIntents.parseDeepLink(Uri.parse("holdhightheshop://chat?seller=Paolo%20Mendoza")),
        )
        assertEquals(
            AppIntents.DeepLinkRoute.Chats,
            AppIntents.parseDeepLink(Uri.parse("holdhightheshop://chat")),
        )

        // Hardened rejections: cleartext http://, removed /map, foreign hosts, unknown routes
        assertNull(AppIntents.parseDeepLink(null))
        assertNull(AppIntents.parseDeepLink(Uri.parse("http://holdhightheshop.app/listing/item5")))
        assertNull(AppIntents.parseDeepLink(Uri.parse("https://holdhightheshop.app/map/UC%20Library%20Lobby")))
        assertNull(AppIntents.parseDeepLink(Uri.parse("holdhightheshop://map/UC%20Library%20Lobby")))
        assertNull(AppIntents.parseDeepLink(Uri.parse("https://example.com/listing/item2")))
        assertNull(AppIntents.parseDeepLink(Uri.parse("https://holdhightheshop.app/unknown")))
    }

    // ---------------------------------------------------------------- implicit intent builders

    @Test
    fun implicitIntentBuilders_populateActionsDataAndExtras() {
        val listing = Listing(
            id = "lst_casio",
            title = "Casio fx-991EX",
            price = 850,
            program = "CITCS",
            condition = 0,
            type = 0,
            description = "Clean calculator",
            seller = peerSeller,
            meetup = "UC Library Lobby",
            ownerUid = peerSeller.uid,
        )
        AppRepository.addTestListing(listing)

        val shareListing = AppIntents.shareListingChooser(context, listing)
        assertEquals(Intent.ACTION_CHOOSER, shareListing.action)
        val innerShare = shareListing.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, innerShare.action)
        assertEquals("text/plain", innerShare.type)
        assertEquals("Casio fx-991EX", innerShare.getStringExtra(Intent.EXTRA_SUBJECT))
        assertTrue(innerShare.getStringExtra(Intent.EXTRA_TEXT)!!.contains("https://holdhightheshop.app/listing/lst_casio"))

        val shareSeller = AppIntents.shareSellerChooser(context, peerSeller)
        val innerSeller = shareSeller.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertTrue(innerSeller.getStringExtra(Intent.EXTRA_TEXT)!!.contains("Paolo%20Mendoza"))

        val exchange = Exchange(
            id = "ex_1",
            title = "Casio fx-991EX",
            price = 850,
            kind = Exchange.BOUGHT,
            partner = peerSeller,
            finishedMillis = System.currentTimeMillis(),
            meetup = "UC Library Lobby",
            theirRating = 5,
            yourRating = 0,
        )
        val shareReceipt = AppIntents.shareReceiptChooser(context, exchange, "HTS-24001")
        val innerReceipt = shareReceipt.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertTrue(innerReceipt.getStringExtra(Intent.EXTRA_TEXT)!!.contains("HTS-24001"))

        val shareApp = AppIntents.shareAppChooser(context)
        val innerApp = shareApp.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertTrue(innerApp.getStringExtra(Intent.EXTRA_TEXT)!!.contains("https://holdhightheshop.app/market"))

        val begin = 1_800_000_000_000L
        val cal = AppIntents.calendarEventIntent("Meetup", "UC Library Lobby", "Bring UC ID", begin)
        assertEquals(Intent.ACTION_INSERT, cal.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, cal.data)
        assertEquals(begin, cal.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1L))
        assertEquals(begin + 30 * 60 * 1000L, cal.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1L))

        val (camera, outputUri) = AppIntents.capturePhotoIntent(context)
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, camera.action)
        assertEquals(outputUri, camera.getParcelableExtra(MediaStore.EXTRA_OUTPUT))
        assertEquals("${context.packageName}.fileprovider", outputUri.authority)
        assertTrue((camera.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)

        val viewPhoto = AppIntents.viewImageIntent(outputUri)
        assertEquals(Intent.ACTION_VIEW, viewPhoto.action)
        assertEquals(outputUri, viewPhoto.data)
        assertEquals("image/*", viewPhoto.type)

        val openMail = AppIntents.openEmailAppIntent()
        assertEquals(Intent.ACTION_MAIN, openMail.action)
        assertTrue(openMail.hasCategory(Intent.CATEGORY_APP_EMAIL))

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
    fun launch_showsToastWhenNoHandlerExists() {
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

    // ---------------------------------------------------------------- Multi-Select College Tags & Day-Unit Lending

    @Test
    fun collegeTags_freePricing_andDayUnitLending_behaveCorrectly() {
        val universal = Listing(
            id = "lst_univ",
            title = "Anker USB-C Charger",
            price = 0,
            program = "All colleges",
            condition = 0,
            type = 0,
            description = "Free giveaway",
            seller = peerSeller,
            meetup = "UC Canteen Area",
            targetPrograms = emptyList(),
            ownerUid = peerSeller.uid,
        )
        val citcsAndCoe = Listing(
            id = "lst_multi",
            title = "Breadboard & Jumper Kit",
            price = 45,
            program = "CITCS",
            condition = 1,
            type = 1,
            description = "Per-day campus loan",
            seller = peerSeller,
            meetup = "CITCS Building Entrance",
            maxDays = 14,
            targetPrograms = listOf("CITCS", "COE"),
            ownerUid = peerSeller.uid,
        )

        // Universal listing matches All, All colleges, AND every individual college
        assertTrue(universal.isForAllColleges)
        assertTrue(universal.isFree)
        assertEquals("FREE", Listings.priceLabel(context, universal).toString())
        assertEquals("All colleges", Listings.targetProgramsLabel(context, universal))
        assertTrue(universal.matchesProgramFilter(null))
        assertTrue(universal.matchesProgramFilter(Listings.FILTER_ALL_COLLEGES))
        assertTrue(universal.matchesProgramFilter("CONAHS"))
        assertFalse(universal.isExplicitlyTaggedFor("CITCS"))

        // Multi-college listing matches CITCS and COE, NOT strictly All colleges or CONAHS
        assertFalse(citcsAndCoe.isForAllColleges)
        assertEquals("CITCS \u00B7 COE", Listings.targetProgramsLabel(context, citcsAndCoe))
        assertTrue(citcsAndCoe.matchesProgramFilter("CITCS"))
        assertTrue(citcsAndCoe.matchesProgramFilter("COE"))
        assertTrue(citcsAndCoe.isExplicitlyTaggedFor("CITCS"))
        assertFalse(citcsAndCoe.matchesProgramFilter("CONAHS"))
        assertFalse(citcsAndCoe.matchesProgramFilter(Listings.FILTER_ALL_COLLEGES))

        // Day-unit lending labels & per-day formatting
        assertEquals("₱45 / day", Listings.priceLabel(context, citcsAndCoe).toString())
        assertEquals("1 day", Listings.daysLabel(context, 1))
        assertEquals("3 days", Listings.daysLabel(context, 3))
        assertEquals(context.getString(R.string.rent_1_week), Listings.daysLabel(context, 7))
        assertEquals(context.getString(R.string.rent_2_weeks), Listings.daysLabel(context, 14))
        assertEquals(context.getString(R.string.rent_semester), Listings.daysLabel(context, 120))
    }

    // ---------------------------------------------------------------- Proposals, Delete Guard, 3-Report Escalation & Block

    @Test
    fun interactiveMeetupAndExtensionProposals_deleteGuard_andThreeReportEscalation() {
        val borrowListing = Listing(
            id = "lst_loan_item",
            title = "CONAHS Lab Coat",
            price = 30,
            program = "CONAHS",
            condition = 0,
            type = 1,
            description = "Clean medium gown",
            seller = peerSeller,
            meetup = "UC Library Lobby",
            maxDays = 14,
            ownerUid = peerSeller.uid,
        )
        AppRepository.addTestListing(borrowListing)

        // Buyer submits 3-day borrow request -> seller accepts -> Meetup Change Proposal -> confirmed!
        val initialMeet = System.currentTimeMillis() + 5 * 60 * 60 * 1000L
        val req = UserState.submitRequest(borrowListing, "UC Library Lobby", "Ground floor", initialMeet, days = 3)
        UserState.accept(req)
        val acceptedBefore = UserState.accepted(borrowListing.id)!!

        val thread = UserState.threadFor(borrowListing.id, peerSeller.uid)
        val newMeet = initialMeet + 2 * 60 * 60 * 1000L
        val proposal = UserState.proposeMeetupChange(
            threadId = thread.threadId,
            listing = borrowListing,
            request = acceptedBefore,
            newMeetup = "UC Main Gate Security Desk",
            newMeetupNote = "Near gate 1",
            newMeetMillis = newMeet,
            note = "Lab ends at 3 PM",
        )
        assertEquals(ProposalType.MEETUP_CHANGE, proposal.proposalType)
        assertEquals(ProposalStatus.PENDING, proposal.proposalStatus)

        // Security check: proposer CANNOT self-confirm their own proposal!
        assertFalse(UserState.respondToProposal(thread.threadId, proposal.proposalId, ProposalStatus.ACCEPTED))

        // Counterparty (Paolo) confirms -> updates the accepted request's meetup spot & timestamp atomically!
        AppRepository.seedVerifiedTestSession(context, peerSeller.email, peerSeller.name, peerSeller.program, peerSeller.year)
        val paoloUid = UserState.currentUid
        // Re-align ownerUid or switch directly to Paolo's session uid; let's confirm as counterparty!
        assertTrue(UserState.respondToProposal(thread.threadId, proposal.proposalId, ProposalStatus.ACCEPTED))
        val acceptedAfter = UserState.accepted(borrowListing.id)!!
        assertEquals("UC Main Gate Security Desk", acceptedAfter.meetup)
        assertEquals(newMeet, acceptedAfter.meetMillis)

        // Complete handover -> active loan -> Juan proposes Return Extension (+2 days) -> Paolo confirms!
        UserState.handOver(borrowListing, Exchange.PROOF_CODE)
        val rentalBefore = UserState.rental(borrowListing.id)!!
        AppRepository.seedVerifiedTestSession(context, "juan.delacruz@students.uc-bcf.edu.ph", "Juan Dela Cruz", "CITCS", "3rd Year")
        val extProposal = UserState.proposeReturnExtension(
            threadId = thread.threadId,
            listing = borrowListing,
            rental = rentalBefore,
            extraDays = 2,
            note = "Need it for Friday lab",
        )
        assertEquals(ProposalType.RETURN_EXTENSION, extProposal.proposalType)
        assertEquals(60, extProposal.extraCostPesos) // 2 days * ₱30/day
        assertFalse(UserState.respondToProposal(thread.threadId, extProposal.proposalId, ProposalStatus.ACCEPTED))

        AppRepository.seedVerifiedTestSession(context, peerSeller.email, peerSeller.name, peerSeller.program, peerSeller.year)
        assertTrue(UserState.respondToProposal(thread.threadId, extProposal.proposalId, ProposalStatus.ACCEPTED))
        val rentalAfter = UserState.rental(borrowListing.id)!!
        assertEquals(rentalBefore.dueMillis + 2 * Rentals.DAY_MS, rentalAfter.dueMillis)
        assertEquals(rentalBefore.days + 2, rentalAfter.days)

        // Confirm return with keepOpen=false pauses listing!
        UserState.markReturned(borrowListing, keepOpen = false)
        assertNull(UserState.rental(borrowListing.id))
        assertEquals(ListingStatus.PAUSED, Listings.byId(borrowListing.id).status)

        // Switch back to Juan for own-listing delete, block, and 3-report checks
        AppRepository.seedVerifiedTestSession(context, "juan.delacruz@students.uc-bcf.edu.ph", "Juan Dela Cruz", "CITCS", "3rd Year")
        assertTrue(paoloUid.isNotBlank())

        // Delete listing guard: open own listing can be deleted; active deal listing cannot!
        val myOpen = Listings.addListing(
            Listing(
                id = "lst_my_open",
                title = "Physics Notes",
                price = 100,
                program = "COE",
                condition = 1,
                type = 0,
                description = "Handwritten",
                seller = UserState.me(),
                meetup = "UC Canteen Area",
                ownerUid = UserState.currentUid,
            ),
        )
        assertTrue(AppRepository.canDeleteListing(myOpen.id))
        assertTrue(Listings.deleteListing(myOpen.id))
        assertNull(Listings.byIdOrNull(myOpen.id))

        // Block / Unblock user hides & restores peer listings
        val peerItem = Listing(
            id = "lst_peer_open",
            title = "Drafting Tube",
            price = 200,
            program = "CAFA",
            condition = 1,
            type = 0,
            description = "Waterproof",
            seller = peerSeller,
            meetup = "UC Library Lobby",
            ownerUid = peerSeller.uid,
        )
        AppRepository.addTestListing(peerItem)
        assertTrue(Listings.open().any { it.id == peerItem.id })
        UserState.blockUser(peerSeller.uid)
        assertFalse(Listings.open().any { it.id == peerItem.id })
        UserState.unblockUser(peerSeller.uid)
        assertTrue(Listings.open().any { it.id == peerItem.id })

        // 3 distinct student reports auto-escalate listing to UNDER_REVIEW!
        UserState.report(peerItem.id, 1, peerSeller, "Suspicious", isListingReport = true)
        assertEquals(1, Listings.byId(peerItem.id).reportCount)
        assertEquals(ListingStatus.OPEN, Listings.byId(peerItem.id).status)

        // Duplicate report by same student is ignored (count stays 1)
        UserState.report(peerItem.id, 1, peerSeller, "Duplicate", isListingReport = true)
        assertEquals(1, Listings.byId(peerItem.id).reportCount)

        // 2nd & 3rd distinct students report -> auto-escalates to UNDER_REVIEW!
        AppRepository.seedVerifiedTestSession(context, "student2@students.uc-bcf.edu.ph", "Student Two", "CBA", "2nd Year")
        UserState.report(peerItem.id, 2, peerSeller, "Fake", isListingReport = true)
        assertEquals(2, Listings.byId(peerItem.id).reportCount)
        assertEquals(ListingStatus.OPEN, Listings.byId(peerItem.id).status)

        AppRepository.seedVerifiedTestSession(context, "student3@students.uc-bcf.edu.ph", "Student Three", "CTE", "4th Year")
        UserState.report(peerItem.id, 3, peerSeller, "Scam", isListingReport = true)
        assertEquals(3, Listings.byId(peerItem.id).reportCount)
        assertEquals(ListingStatus.UNDER_REVIEW, Listings.byId(peerItem.id).status)
    }
}
