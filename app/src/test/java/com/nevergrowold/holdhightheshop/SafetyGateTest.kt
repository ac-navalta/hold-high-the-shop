package com.nevergrowold.holdhightheshop

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nevergrowold.holdhightheshop.security.CampusSafetyApi
import com.nevergrowold.holdhightheshop.security.LocalSafetyFilter
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the Hybrid Multimodal Prohibited-Item Safety Gate:
 * - [LocalSafetyFilter] offline rule engine (prohibited categories, evasion resistance, zero false positives on campus items)
 * - [CampusSafetyApi] Gemini 2.5 Flash payload builder, multimodal image encoding, JSON verdict parser, and offline fallback
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafetyGateTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        CampusSafetyApi.testOverride = null
    }

    @After
    fun tearDown() {
        CampusSafetyApi.testOverride = null
    }

    @Test
    fun localSafetyFilter_blocksAllFiveProhibitedCategories() {
        assertEquals(
            "WEAPONS_OR_DANGEROUS",
            LocalSafetyFilter.check("Sharp balisong knife", "Barely used")?.category,
        )
        assertEquals(
            "ALCOHOL_TOBACCO_VAPE",
            LocalSafetyFilter.check("Relx Vape Starter Kit", "Comes with 2 pods")?.category,
        )
        assertEquals(
            "DRUGS_MEDICATION",
            LocalSafetyFilter.check("Leftover prescription drug", "Unopened bottle")?.category,
        )
        assertEquals(
            "ACADEMIC_DISHONESTY",
            LocalSafetyFilter.check("Midterm help", "I can do your thesis for 500 pesos")?.category,
        )
        assertEquals(
            "ADULT_OR_STOLEN",
            LocalSafetyFilter.check("Cheap phone", "Stolen laptop also available")?.category,
        )
    }

    @Test
    fun localSafetyFilter_catchesZeroWidthEvasionAndSecondaryFields() {
        // Zero-width space injected inside "vape"
        val evasion = LocalSafetyFilter.check("Brand new v\u200Bape", "Mint condition")
        assertNotNull("Zero-width character evasion must be caught", evasion)
        assertEquals("ALCOHOL_TOBACCO_VAPE", evasion!!.category)

        // Prohibited text smuggled inside swap "wants" or "meetupNote"
        val inWants = LocalSafetyFilter.check("Physics Reviewer", "Clean pages", wants = "Trade for soju")
        assertNotNull(inWants)
        assertEquals("ALCOHOL_TOBACCO_VAPE", inWants!!.category)

        val inNote = LocalSafetyFilter.check("Notebook", "Clean", meetupNote = "Bring fake uc id")
        assertNotNull(inNote)
        assertEquals("ACADEMIC_DISHONESTY", inNote!!.category)
    }

    @Test
    fun localSafetyFilter_allowsLegitimateUcCampusItems() {
        val safeItems = listOf(
            "Casio fx-991EX Scientific Calculator" to "Used for 1 semester in COE, crisp LCD.",
            "CONAHS White Lab Gown (Medium)" to "Washed and ironed, ready for chem lab.",
            "Drafting Compass & T-Square Set" to "Complete CAFA architecture drafting tools.",
            "Data Structures & Algorithms Textbook" to "No highlights, great for CITCS 2nd year.",
            "UC PE Uniform Size Large" to "Clean, barely worn twice.",
            "Anker USB-C 30W Fast Charger" to "Works with laptop and phone.",
        )
        safeItems.forEach { (title, desc) ->
            assertNull("False positive on safe campus item: $title", LocalSafetyFilter.check(title, desc))
        }
    }

    @Test
    fun buildGeminiPayload_includesTextFieldsAndWebpInlineImages() {
        val fakeWebp = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x10, 0x00, 0x00, 0x00)
        val raw = CampusSafetyApi.buildGeminiPayload(
            title = "Casio fx-991EX",
            description = "Clean scientific calculator",
            wants = "Arduino Uno",
            meetupNote = "3rd floor lobby near M304",
            webpImages = listOf(fakeWebp),
        )
        val root = JSONObject(raw)
        val parts = root.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals(2, parts.length())

        val prompt = parts.getJSONObject(0).getString("text")
        assertTrue(prompt.contains("Casio fx-991EX"))
        assertTrue(prompt.contains("Arduino Uno"))
        assertTrue(prompt.contains("3rd floor lobby near M304"))

        val inlineData = parts.getJSONObject(1).getJSONObject("inline_data")
        assertEquals("image/webp", inlineData.getString("mime_type"))
        assertTrue(inlineData.getString("data").isNotBlank())

        val genCfg = root.getJSONObject("generationConfig")
        assertEquals(0.0, genCfg.getDouble("temperature"), 0.001)
        assertEquals("application/json", genCfg.getString("responseMimeType"))
    }

    @Test
    fun parseGeminiResponse_handlesApprovedBlockedFencedEmptyAndMalformedJson() {
        val approvedJson = """
            {"candidates":[{"content":{"parts":[{"text":"{\"allowed\":true,\"category\":\"SAFE\",\"policySection\":\"\",\"reason\":\"Standard campus item.\"}"}]}}]}
        """.trimIndent()
        assertEquals(CampusSafetyApi.Verdict.Approved, CampusSafetyApi.parseGeminiResponse(approvedJson))

        val blockedFencedJson = """
            {"candidates":[{"content":{"parts":[{"text":"```json\n{\"allowed\":false,\"category\":\"ALCOHOL_TOBACCO_VAPE\",\"policySection\":\"UC Marketplace Guidelines §3.2\",\"reason\":\"Photo shows a disposable vape disguised as a highlighter.\"}\n```"}]}}]}
        """.trimIndent()
        val blocked = CampusSafetyApi.parseGeminiResponse(blockedFencedJson)
        assertTrue(blocked is CampusSafetyApi.Verdict.Blocked)
        blocked as CampusSafetyApi.Verdict.Blocked
        assertEquals("ALCOHOL_TOBACCO_VAPE", blocked.category)
        assertEquals("GEMINI_AI", blocked.source)
        assertTrue(blocked.reason.contains("disposable vape"))

        val emptyCandidates = """{"candidates":[]}"""
        val filtered = CampusSafetyApi.parseGeminiResponse(emptyCandidates)
        assertTrue(filtered is CampusSafetyApi.Verdict.Blocked)
        assertEquals("SAFETY_FILTERED", (filtered as CampusSafetyApi.Verdict.Blocked).category)

        val malformed = "{not valid json"
        assertTrue(CampusSafetyApi.parseGeminiResponse(malformed) is CampusSafetyApi.Verdict.PendingAiReview)
    }

    @Test
    fun evaluateListing_blocksLocalViolationImmediatelyAndFallsBackSafelyWithoutApiKey() = runBlocking {
        // Prohibited item is blocked instantly by Stage 1 LocalSafetyFilter
        val blocked = CampusSafetyApi.evaluateListing(
            context = context,
            title = "Disposable Pod Flava",
            description = "Mint flavor",
            wants = null,
            meetupNote = null,
            photoRefs = emptyList(),
        )
        assertTrue(blocked is CampusSafetyApi.Verdict.Blocked)
        assertEquals("LOCAL_FILTER", (blocked as CampusSafetyApi.Verdict.Blocked).source)

        // Safe item without configured API key gracefully falls back to PendingAiReview (never crashes)
        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            val safe = CampusSafetyApi.evaluateListing(
                context = context,
                title = "TI-84 Graphing Calculator",
                description = "Works great for Calculus",
                wants = null,
                meetupNote = null,
                photoRefs = emptyList(),
            )
            assertTrue(safe is CampusSafetyApi.Verdict.PendingAiReview)
        }
    }
}
