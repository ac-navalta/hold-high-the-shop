package com.nevergrowold.holdhightheshop

import android.app.Application
import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.nevergrowold.holdhightheshop.BaseActivity.Companion.EXTRA_LISTING
import com.nevergrowold.holdhightheshop.data.AppRepository
import com.nevergrowold.holdhightheshop.security.CampusEmailService
import com.nevergrowold.holdhightheshop.security.CampusSafetyApi
import com.nevergrowold.holdhightheshop.security.UcEmailValidator
import kotlinx.coroutines.CompletableDeferred
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
 * Regression & UX verification tests covering:
 * 1. 6-digit HTML email verification code flow (CampusEmailService + VerifyEmailActivity)
 * 2. Visible live password rule checklist beneath SignUp password field
 * 3. CreateListing 'All colleges' default pill auto-unselect / reselect + Safety Gate loading overlay
 * 4. MarketTab merged 'All colleges' filter pill (single pill + 11 UC colleges = 12 chips)
 * 5. ListingDetail SpringPhotoCarousel multi-photo sliding & pagination dots
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiPolishAndVerificationTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Listings.reset()
        AppRepository.resetAllForTests()
        CampusEmailService.testSender = null
        CampusSafetyApi.testOverride = null
    }

    @After
    fun tearDown() {
        CampusEmailService.testSender = null
        CampusSafetyApi.testOverride = null
        AppRepository.resetAllForTests()
    }

    @Test
    fun htmlEmailBuilder_andSixDigitVerificationFlow_verifyStudentEndToEnd() {
        var capturedPayload: CampusEmailService.EmailPayload? = null
        CampusEmailService.testSender = { payload ->
            capturedPayload = payload
            CampusEmailService.DeliveryResult.Sent
        }

        // 1. SignUpActivity shows password rules visibly underneath the password field
        val signUp = Robolectric.buildActivity(SignUpActivity::class.java).setup().get()
        val rulesBox = signUp.findViewById<View>(R.id.passwordRulesContainer)
        assertTrue("Password rules checklist must be visible beneath the password field", rulesBox.isVisible)

        val pwInput = signUp.findViewById<EditText>(R.id.inputPassword)
        val confirmInput = signUp.findViewById<EditText>(R.id.inputConfirmPassword)
        assertFalse(
            "Password rules should not be stuffed inside hint",
            pwInput.hint.toString().contains("8 characters", ignoreCase = true),
        )

        // Type partial -> full password and verify live rule detection
        pwInput.setText("abc")
        assertFalse(UcEmailValidator.evaluatePasswordRules(pwInput.text.toString()).minLength)
        assertTrue(UcEmailValidator.evaluatePasswordRules(pwInput.text.toString()).hasLetter)
        assertFalse(UcEmailValidator.evaluatePasswordRules(pwInput.text.toString()).hasDigit)

        pwInput.setText("Campus2026")
        confirmInput.setText("Campus2026")
        val fullRules = UcEmailValidator.evaluatePasswordRules(pwInput.text.toString())
        assertTrue(fullRules.allRequiredMet)
        assertEquals(
            signUp.getColor(R.color.uc_green),
            signUp.findViewById<TextView>(R.id.ruleMinLengthText).currentTextColor,
        )
        assertEquals(
            signUp.getColor(R.color.uc_green),
            signUp.findViewById<TextView>(R.id.ruleHasLetterText).currentTextColor,
        )
        assertEquals(
            signUp.getColor(R.color.uc_green),
            signUp.findViewById<TextView>(R.id.ruleHasNumberText).currentTextColor,
        )

        // Submit Sign-Up -> sends HTML email with 6-digit code and opens VerifyEmailActivity
        signUp.findViewById<EditText>(R.id.inputFullName).setText("Mika Dela Cruz")
        signUp.findViewById<EditText>(R.id.inputEmail).setText("mika.delacruz@students.uc-bcf.edu.ph")
        signUp.findViewById<View>(R.id.btnCreateAccount).performClick()
        drainMainLooper()

        assertNotNull("Expected branded HTML verification email to be dispatched", capturedPayload)
        val html = capturedPayload!!.html
        assertTrue("Email must be a full HTML document", html.contains("<!DOCTYPE html>") && html.contains("<table"))
        assertTrue("Email must feature Hold High The Shop UC header", html.contains("Hold High The Shop"))
        val codeMatch = Regex("\\b(\\d{6})\\b").find(capturedPayload!!.subject)
        assertNotNull("Subject must contain a 6-digit verification code", codeMatch)
        val sixDigitCode = codeMatch!!.groupValues[1]
        assertTrue("HTML body must display the 6-digit code", html.contains(sixDigitCode))

        val toVerify = shadowOf(signUp).nextStartedActivity
        assertNotNull(toVerify)
        assertEquals(VerifyEmailActivity::class.java.name, toVerify.component?.className)

        // 2. VerifyEmailActivity rejects wrong 6-digit code, then accepts the issued 6-digit code
        val verify = Robolectric.buildActivity(VerifyEmailActivity::class.java, toVerify).setup().get()
        val wrongCode = if (sixDigitCode == "111111") "222222" else "111111"
        verify.fillCodeAcrossBoxes(wrongCode)
        verify.findViewById<View>(R.id.btnCheckVerified).performClick()
        drainMainLooper()

        assertTrue(verify.findViewById<View>(R.id.verifyError).isVisible)
        assertNull("Wrong code must not enter MainActivity", shadowOf(verify).nextStartedActivity)

        // Enter exact 6-digit code -> verified & enters MainActivity!
        verify.fillCodeAcrossBoxes(sixDigitCode)
        verify.findViewById<View>(R.id.btnCheckVerified).performClick()
        drainMainLooper()

        val toMain = shadowOf(verify).nextStartedActivity
        assertNotNull("Correct 6-digit code must launch MainActivity", toMain)
        assertEquals(MainActivity::class.java.name, toMain.component?.className)
        assertTrue(AppRepository.isVerifiedSignedIn())
    }

    @Test
    fun createListing_defaultAllCollegesPillAutoUnselects_andSafetyOverlayDisplaysCleanly() {
        AppRepository.seedVerifiedTestSession(app)
        val create = Robolectric.buildActivity(CreateListingActivity::class.java).setup().get()

        // No green summary pill on the right; index 0 in rail is "All colleges" selected by default
        assertEquals(0, create.resources.getIdentifier("selectedCollegesSummary", "id", create.packageName))
        val chipBox = create.findViewById<LinearLayout>(R.id.collegeTagChips)
        assertEquals(1 + Listings.PROGRAMS.size, chipBox.childCount)

        val allChip = chipBox.getChildAt(0) as TextView
        val citcsChip = chipBox.getChildAt(1) as TextView
        val coeChip = chipBox.getChildAt(2) as TextView

        assertEquals(create.getString(R.string.tag_all_colleges), allChip.text.toString())
        assertEquals(true, allChip.getTag(R.id.choice_on))
        assertEquals(false, citcsChip.getTag(R.id.choice_on))

        // Clicking CITCS auto-unselects "All colleges"
        citcsChip.performClick()
        assertEquals(false, allChip.getTag(R.id.choice_on))
        assertEquals(true, citcsChip.getTag(R.id.choice_on))

        // Clicking COE selects both CITCS & COE while keeping "All colleges" unselected
        coeChip.performClick()
        assertEquals(false, allChip.getTag(R.id.choice_on))
        assertEquals(true, citcsChip.getTag(R.id.choice_on))
        assertEquals(true, coeChip.getTag(R.id.choice_on))

        // Clicking "All colleges" clears specific colleges and re-selects "All colleges"
        allChip.performClick()
        assertEquals(true, allChip.getTag(R.id.choice_on))
        assertEquals(false, citcsChip.getTag(R.id.choice_on))
        assertEquals(false, coeChip.getTag(R.id.choice_on))

        // Selecting CITCS then unselecting CITCS auto-restores "All colleges"
        citcsChip.performClick()
        assertEquals(false, allChip.getTag(R.id.choice_on))
        citcsChip.performClick()
        assertEquals(true, allChip.getTag(R.id.choice_on))

        // Verify Safety Gate loading overlay appears while checking guidelines and button text stays compact
        val gateHold = CompletableDeferred<CampusSafetyApi.Verdict>()
        CampusSafetyApi.testOverride = { _, _, _, _ -> gateHold.await() }

        create.addPhotoRefForTest("content://test/item.jpg")
        create.findViewById<EditText>(R.id.inputTitle).setText("Calculus Textbook")
        create.findViewById<View>(R.id.condition1).performClick()
        create.findViewById<EditText>(R.id.inputPrice).setText("350")
        create.postItem(create.findViewById(R.id.btnPostItem))

        val overlay = create.findViewById<View>(R.id.safetyLoadingOverlay)
        val postBtn = create.findViewById<TextView>(R.id.btnPostItem)
        assertTrue("Safety loading screen overlay must be visible while checking", overlay.isVisible)
        assertEquals(create.getString(R.string.safety_checking_short), postBtn.text.toString())

        gateHold.complete(CampusSafetyApi.Verdict.Approved)
        drainMainLooper()
        assertFalse(overlay.isVisible)
    }

    @Test
    fun marketTab_mergesAllAndAllCollegesIntoSingleFilterChip_andDetailCarouselSlidesPhotos() {
        AppRepository.seedVerifiedTestSession(app)
        val seller = Seller("Paolo Mendoza", "PM", "CITCS", "3rd Year", "4.9", "98%", 4, "", true, uid = "uid_paolo")
        AppRepository.registerTestStudent(seller)
        val multiPhotoListing = Listing(
            id = "lst_multi",
            title = "Drafting Set",
            price = 400,
            program = "COE",
            condition = 0,
            type = 0,
            description = "Complete set with 3 photos.",
            seller = seller,
            meetup = "UC Library Lobby",
            photoUris = listOf("content://test/p1.jpg", "content://test/p2.jpg", "content://test/p3.jpg"),
            targetPrograms = listOf("COE"),
            ownerUid = seller.uid,
        )
        AppRepository.addTestListing(multiPhotoListing)

        // 1. MarketTab has exactly 1 "All colleges" chip + 11 college chips (no duplicate "All" + "All colleges")
        val main = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_NAV_TAB, MainActivity.TAB_MARKET),
        ).setup().get()
        val chipRow = main.findViewById<LinearLayout>(R.id.chipRow)
        assertEquals(1 + Listings.PROGRAMS.size, chipRow.childCount)
        assertEquals(main.getString(R.string.tag_all_colleges), (chipRow.getChildAt(0) as TextView).text.toString())
        assertEquals("CITCS", (chipRow.getChildAt(1) as TextView).text.toString())

        // 2. ListingDetailActivity SpringPhotoCarousel displays dots and advances through photos
        val detail = Robolectric.buildActivity(
            ListingDetailActivity::class.java,
            Intent(app, ListingDetailActivity::class.java).putExtra(EXTRA_LISTING, "lst_multi"),
        ).setup().get()

        val countPill = detail.findViewById<TextView>(R.id.detailPhotoCount)
        val dots = detail.findViewById<LinearLayout>(R.id.detailPhotoDots)
        assertEquals("1 / 3", countPill.text.toString())
        assertTrue(dots.isVisible)
        assertEquals(3, dots.childCount)

        detail.cycleHeroPhoto(detail.findViewById(R.id.detailPhotoStage))
        ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("2 / 3", countPill.text.toString())

        detail.cycleHeroPhoto(detail.findViewById(R.id.detailPhotoStage))
        ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("3 / 3", countPill.text.toString())
    }

    private fun drainMainLooper() {
        repeat(8) {
            Thread.sleep(20)
            ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }
}
