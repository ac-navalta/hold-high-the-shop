package com.nevergrowold.holdhightheshop

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.nevergrowold.holdhightheshop.data.AppRepository
import com.nevergrowold.holdhightheshop.security.IntentSecurity
import com.nevergrowold.holdhightheshop.system.BootAndTimeReceiver
import com.nevergrowold.holdhightheshop.system.DealAlertReceiver
import com.nevergrowold.holdhightheshop.system.MarketplaceBroadcasts
import com.nevergrowold.holdhightheshop.system.MarketplaceSyncService
import com.nevergrowold.holdhightheshop.system.MeetupCountdownService
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

/**
 * Automated security regression suite validating every control grounded in
 * Google's `android-intent-security` and `android-permissions-security`:
 * 1. Manifest hardening (`allowBackup=false`, non-exported Services & DealAlertReceiver,
 *    signature-level broadcast permission, removed `MapActivity` & cleartext `http://`)
 * 2. Strict Auth Gate on `MainActivity` (blocks unauthenticated deep links & ignores spoofed `EXTRA_EMAIL`)
 * 3. `IntentSanitizer` defense against `file://` and self-FileProvider exfiltration in `ACTION_SEND`
 * 4. Strict 2-step `SecureRandom` handover code secrecy (empty while pending, generated only on accept)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecurityAuditTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        AppRepository.resetAllForTests()
    }

    @After
    fun tearDown() {
        AppRepository.resetAllForTests()
    }

    @Test
    fun manifest_enforcesHardenedExportBackupPermissionAndDeepLinkRules() {
        val pm = app.packageManager
        val pkgInfo = pm.getPackageInfo(
            app.packageName,
            PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS or
                PackageManager.GET_PERMISSIONS,
        )

        // 1. allowBackup="false" protects auth & local marketplace data from ADB/cloud extraction
        assertEquals(0, pkgInfo.applicationInfo!!.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)

        // 2. All Auth screens declared; removed MapActivity absent
        val activities = pkgInfo.activities.orEmpty().associateBy { it.name }
        assertTrue(SignUpActivity::class.java.name in activities)
        assertTrue(VerifyEmailActivity::class.java.name in activities)
        assertTrue(ForgotPasswordActivity::class.java.name in activities)
        assertTrue(AccountSuspendedActivity::class.java.name in activities)
        assertFalse(
            "Removed MapActivity must not exist in manifest",
            activities.keys.any { it.endsWith(".MapActivity") },
        )

        // 3. Both Services declared and strictly non-exported
        val services = pkgInfo.services.orEmpty().associateBy { it.name }
        val syncSvc = services[MarketplaceSyncService::class.java.name]
        val countdownSvc = services[MeetupCountdownService::class.java.name]
        assertNotNull("MarketplaceSyncService missing", syncSvc)
        assertNotNull("MeetupCountdownService missing", countdownSvc)
        assertFalse("MarketplaceSyncService must be exported=false", syncSvc!!.exported)
        assertFalse("MeetupCountdownService must be exported=false", countdownSvc!!.exported)

        // 4. DealAlertReceiver strictly non-exported; BootAndTimeReceiver declared
        val receivers = pkgInfo.receivers.orEmpty().associateBy { it.name }
        val dealReceiver = receivers[DealAlertReceiver::class.java.name]
        val bootReceiver = receivers[BootAndTimeReceiver::class.java.name]
        assertNotNull("DealAlertReceiver missing", dealReceiver)
        assertNotNull("BootAndTimeReceiver missing", bootReceiver)
        assertFalse("DealAlertReceiver must be exported=false", dealReceiver!!.exported)

        // 5. FileProvider strictly non-exported with scoped grantUriPermissions
        val provider = pkgInfo.providers.orEmpty().firstOrNull { it.authority == "${app.packageName}.fileprovider" }
        assertNotNull("FileProvider missing", provider)
        assertFalse("FileProvider must be exported=false", provider!!.exported)
        assertTrue("FileProvider must grant temporary URI permissions", provider.grantUriPermissions)

        // 6. Signature-level internal broadcast permission declared
        val permName = MarketplaceBroadcasts.internalPermission(app)
        val permInfo = pkgInfo.permissions.orEmpty().firstOrNull { it.name == permName }
        assertNotNull("Missing signature broadcast permission $permName", permInfo)
        assertEquals(
            PermissionInfo.PROTECTION_SIGNATURE,
            permInfo!!.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE,
        )

        // 7. HTTPS deep link resolves to MainActivity; cleartext HTTP is rejected!
        val httpsMatches = pm.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://holdhightheshop.app/listing/item2")),
            0,
        ).map { it.activityInfo.name }
        assertTrue(MainActivity::class.java.name in httpsMatches)

        val httpMatches = pm.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse("http://holdhightheshop.app/listing/item2")),
            0,
        ).map { it.activityInfo.name }
        assertFalse("Cleartext http:// deep link must NOT resolve to MainActivity", MainActivity::class.java.name in httpMatches)
    }

    @Test
    fun mainActivity_blocksUnauthenticatedAccessSpoofedEmailAndPreservesPendingDeepLink() {
        // Attempt to bypass login by launching MainActivity directly with a deep link + spoofed EXTRA_EMAIL
        val spoofed = Intent(Intent.ACTION_VIEW, Uri.parse("https://holdhightheshop.app/listing/item2")).apply {
            setClass(app, MainActivity::class.java)
            putExtra(BaseActivity.EXTRA_EMAIL, "attacker@gmail.com")
        }
        val main = Robolectric.buildActivity(MainActivity::class.java, spoofed).create().get()

        assertTrue("Unauthenticated MainActivity must finish immediately", main.isFinishing)
        assertFalse("Spoofed EXTRA_EMAIL must never populate session", UserState.isSignedIn)
        assertEquals("", UserState.profile.email)

        val redirected = shadowOf(main).nextStartedActivity
        assertNotNull(redirected)
        assertEquals(LoginActivity::class.java.name, redirected.component?.className)
        assertEquals(
            "https://holdhightheshop.app/listing/item2",
            redirected.getStringExtra(BaseActivity.EXTRA_PENDING_DEEP_LINK),
        )
    }

    @Test
    fun intentSecurity_blocksFileSchemeAndSelfFileProviderExfiltration() {
        val fileUri = Uri.parse("file:///data/data/${app.packageName}/databases/hold_high_the_shop.db")
        val selfProviderUri = Uri.parse("content://${app.packageName}.fileprovider/photos/private.jpg")
        val externalGalleryUri = Uri.parse("content://com.google.android.apps.photos.contentprovider/0/1/photo.jpg")

        assertFalse(IntentSecurity.isSafeExternalImageUri(app, fileUri))
        assertFalse(IntentSecurity.isSafeExternalImageUri(app, selfProviderUri))
        assertFalse(IntentSecurity.isSafeExternalImageUri(app, null))
        assertTrue(IntentSecurity.isSafeExternalImageUri(app, externalGalleryUri))

        // Malicious ACTION_SEND trying to exfiltrate local DB via file:// URI -> stripped to null!
        val maliciousShare = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, fileUri)
        }
        assertNull(IntentSecurity.sanitizeIncomingShare(app, maliciousShare))

        // Self-FileProvider reflection attack -> stripped!
        val selfShare = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, selfProviderUri)
        }
        assertNull(IntentSecurity.sanitizeIncomingShare(app, selfShare))

        // Legitimate external share -> sanitized, length-clamped, and allowed!
        val safeShare = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_SUBJECT, "Graphing Calculator\n\tExtra")
            putExtra(Intent.EXTRA_TEXT, "A".repeat(500))
            putExtra(Intent.EXTRA_STREAM, externalGalleryUri)
        }
        val payload = IntentSecurity.sanitizeIncomingShare(app, safeShare)
        assertNotNull(payload)
        assertEquals("Graphing Calculator Extra", payload!!.subject)
        assertEquals(300, payload.text!!.length)
        assertEquals(externalGalleryUri, payload.imageUri)
    }

    @Test
    fun handoverCode_isHiddenUntilSellerAcceptsAndGeneratedViaSecureRandom() {
        val seller = Seller("Paolo Mendoza", "PM", "CITCS", "3rd Year", "New", "–", 0, "", true, uid = "uid_paolo")
        AppRepository.seedVerifiedTestSession(app, "maria.santos@students.uc-bcf.edu.ph", "Maria Santos", "COE", "2nd Year")
        AppRepository.registerTestStudent(seller)

        val listing = Listing(
            id = "lst_sec_code",
            title = "Scientific Calculator",
            price = 750,
            program = "CITCS",
            condition = 0,
            type = 0,
            description = "Clean",
            seller = seller,
            meetup = "UC Library Lobby",
            ownerUid = seller.uid,
        )
        AppRepository.addTestListing(listing)

        // Step 1: Buyer submits pending request -> handoverCode MUST be empty!
        val req = UserState.submitRequest(
            listing = listing,
            meetup = listing.meetup,
            meetupNote = "",
            meetMillis = System.currentTimeMillis() + 5 * 60 * 60 * 1000L,
        )
        assertEquals(Deals.PENDING, req.status)
        assertEquals("", req.handoverCode)
        assertEquals("", UserState.reservation(listing.id)!!.handoverCode)

        // Step 2: Seller accepts -> cryptographically random 4-digit code is generated!
        UserState.accept(req)
        val accepted = UserState.accepted(listing.id)
        assertNotNull(accepted)
        assertEquals(Deals.ACCEPTED, accepted!!.status)
        assertTrue("Expected 4-digit handover code, got '${accepted.handoverCode}'", accepted.handoverCode.matches(Regex("^\\d{4}$")))
    }
}
