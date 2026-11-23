package com.nevergrowold.holdhightheshop.security

import android.content.Context
import android.util.Base64
import android.util.Log
import com.nevergrowold.holdhightheshop.BuildConfig
import com.nevergrowold.holdhightheshop.media.ImagePipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Headliner External REST API Integration:
 * Hybrid Multimodal Prohibited-Item Safety Gate for Hold High The Shop.
 *
 * How it works:
 * 1. Runs [LocalSafetyFilter] instantaneously to catch explicit prohibited text
 *    even when offline.
 * 2. When online and `BuildConfig.GEMINI_API_KEY` (loaded from local.properties)
 *    is configured, sends an HTTPS POST request on `Dispatchers.IO` to the
 *    Google Gemini 2.5 Flash REST API containing:
 *    - The listing's title, description, swap wants, and meetup note
 *    - Up to 3 compressed WebP item photos (catches disguised photos of vapes,
 *      weapons, alcohol, prescription drugs, or exam leaks!)
 * 3. Parses the structured JSON verdict (`allowed`, `category`, `policySection`,
 *    `reason`).
 * 4. Hybrid fallback: If offline, rate-limited, or unconfigured, listings that
 *    passed [LocalSafetyFilter] are returned as [Verdict.AllowedPendingReview]
 *    so the Admin Web Portal flags them for review without blocking legitimate
 *    campus transactions.
 */
object CampusSafetyApi {

    private const val TAG = "CampusSafetyApi"
    private const val MODEL = "gemini-2.5-flash"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val MAX_IMAGES_TO_INSPECT = 3

    sealed class Verdict {
        /** Cleared by both local rules and live Gemini multimodal check. */
        data object Approved : Verdict()

        /** Passed local rules, but Gemini API was offline/unconfigured/rate-limited -> flagged in Admin Portal. */
        data class PendingAiReview(val note: String) : Verdict()

        /** Blocked from posting/editing due to campus marketplace policy violation. */
        data class Blocked(
            val category: String,
            val policySection: String,
            val reason: String,
            val source: String, // "LOCAL_FILTER" or "GEMINI_AI"
        ) : Verdict()
    }

    /** Optional hook for deterministic unit/Robolectric tests. */
    @Volatile
    internal var testOverride: (suspend (String, String, String?, List<String>) -> Verdict)? = null

    suspend fun evaluateListing(
        context: Context,
        title: String,
        description: String,
        wants: String?,
        meetupNote: String?,
        photoRefs: List<String>,
    ): Verdict = withContext(Dispatchers.IO) {
        testOverride?.let { return@withContext it(title, description, wants, photoRefs) }

        // Stage 1: Instant local keyword & evasion pre-screen (works offline & online).
        LocalSafetyFilter.check(title, description, wants, meetupNote)?.let { violation ->
            return@withContext Verdict.Blocked(
                category = violation.category,
                policySection = violation.policySection,
                reason = violation.reason,
                source = "LOCAL_FILTER",
            )
        }

        val apiKey = BuildConfig.GEMINI_API_KEY.trim()
        if (apiKey.isEmpty()) {
            return@withContext Verdict.PendingAiReview("Gemini API key not configured locally")
        }

        // Stage 2: Live Google Gemini 2.5 Flash multimodal (text + photo) inspection.
        try {
            val imageBytesList = photoRefs
                .take(MAX_IMAGES_TO_INSPECT)
                .mapNotNull { ref -> ImagePipeline.loadCompressedBytesSync(context, ref) }

            val requestBody = buildGeminiPayload(
                title = title,
                description = description,
                wants = wants,
                meetupNote = meetupNote,
                webpImages = imageBytesList,
            )

            val responseText = postJson(ENDPOINT, apiKey, requestBody)
                ?: return@withContext Verdict.PendingAiReview("Safety service temporarily unreachable")

            parseGeminiResponse(responseText)
        } catch (e: Exception) {
            Log.w(TAG, "Gemini moderation check fell back to pending review", e)
            Verdict.PendingAiReview("Offline or network timeout during AI review")
        }
    }

    internal fun buildGeminiPayload(
        title: String,
        description: String,
        wants: String?,
        meetupNote: String?,
        webpImages: List<ByteArray>,
    ): String {
        val prompt = buildString {
            appendLine("You are the Campus Safety Moderator for Hold High The Shop, a university student marketplace at the University of the Cordilleras (UC Baguio).")
            appendLine("Inspect BOTH the listing text AND every attached photo carefully. Watch for disguised items (e.g. a photo of a vape, knife, alcohol, drugs, or exam answer sheet labeled as school supplies).")
            appendLine("Prohibited categories:")
            appendLine("- WEAPONS_OR_DANGEROUS (§3.1): firearms, knives/blades (note: normal drafting compasses, school scissors, rulers, kitchen culinary knives only if clearly unsafe weapons; student drafting sets, calculators, uniforms, lab gowns, textbooks are SAFE)")
            appendLine("- ALCOHOL_TOBACCO_VAPE (§3.2): vapes, e-cigarettes, pods, e-liquid, cigarettes, tobacco, alcohol/liquor")
            appendLine("- DRUGS_MEDICATION (§3.3): illegal drugs, cannabis, paraphernalia, prescription medication")
            appendLine("- ACADEMIC_DISHONESTY (§3.4): leaked exams, paid essay/thesis ghostwriting, forged university IDs or medical certificates")
            appendLine("- ADULT_OR_STOLEN (§3.5): adult/NSFW items, stolen goods, counterfeit IDs, hateful/harassing content")
            appendLine()
            appendLine("Listing to inspect:")
            appendLine("Title: $title")
            appendLine("Description: ${description.ifBlank { "(none)" }}")
            if (!wants.isNullOrBlank()) appendLine("Looking to swap for: $wants")
            if (!meetupNote.isNullOrBlank()) appendLine("Meetup note: $meetupNote")
            appendLine()
            append("Respond strictly with valid JSON only (no markdown fences): ")
            append("""{"allowed":true|false,"category":"SAFE|WEAPONS_OR_DANGEROUS|ALCOHOL_TOBACCO_VAPE|DRUGS_MEDICATION|ACADEMIC_DISHONESTY|ADULT_OR_STOLEN","policySection":"UC Marketplace Guidelines §3.x — ...","reason":"1-2 clear student-friendly sentences."}""")
        }

        val parts = JSONArray().apply {
            put(JSONObject().put("text", prompt))
            webpImages.forEach { bytes ->
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject()
                            .put("mime_type", "image/webp")
                            .put("data", encoded),
                    ),
                )
            }
        }

        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.0)
                    .put("responseMimeType", "application/json"),
            )
            .toString()
    }

    internal fun parseGeminiResponse(rawJson: String): Verdict {
        return try {
            val root = JSONObject(rawJson)
            val candidates = root.optJSONArray("candidates")
                ?: return Verdict.PendingAiReview("No candidates returned")
            if (candidates.length() == 0) {
                return Verdict.Blocked(
                    category = "SAFETY_FILTERED",
                    policySection = "UC Marketplace Guidelines §3.5 — Prohibited & Unsafe Content",
                    reason = "This listing triggered safety moderation filters and cannot be published on the campus marketplace.",
                    source = "GEMINI_AI",
                )
            }
            val textPart = candidates.getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val verdictJson = JSONObject(textPart)
            val allowed = verdictJson.optBoolean("allowed", true)
            if (allowed) {
                Verdict.Approved
            } else {
                Verdict.Blocked(
                    category = verdictJson.optString("category", "PROHIBITED_ITEM"),
                    policySection = verdictJson.optString(
                        "policySection",
                        "UC Marketplace Guidelines §3 — Prohibited & Unsafe Items",
                    ),
                    reason = verdictJson.optString(
                        "reason",
                        "This item violates Hold High The Shop's campus safety and community guidelines.",
                    ),
                    source = "GEMINI_AI",
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Gemini verdict JSON", e)
            Verdict.PendingAiReview("Unparseable AI moderation response")
        }
    }

    private fun postJson(urlString: String, apiKey: String, body: String): String? {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            // Send API key in header rather than query string so it never leaks into URL logs.
            setRequestProperty("x-goog-api-key", apiKey)
        }
        return try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body)
                writer.flush()
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                Log.w(TAG, "Gemini HTTP ${conn.responseCode}")
                null
            }
        } finally {
            conn.disconnect()
        }
    }
}
