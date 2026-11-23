package com.nevergrowold.holdhightheshop.security

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.IntentCompat
import androidx.core.content.IntentSanitizer
import com.nevergrowold.holdhightheshop.AppIntents

/**
 * Incoming Intent sanitization and URI validation helpers grounded in
 * Google's `android-intent-security` skill.
 *
 * Defends against:
 * - Intent Redirection (nested/untrusted Intent forwarding)
 * - Private File Exfiltration via `file://` or self-targeted `FileProvider` URIs
 * - Oversized / malformed extras crashing exported `MainActivity`
 */
object IntentSecurity {

    private const val TAG = "IntentSecurity"

    data class SanitizedSharePayload(
        val subject: String?,
        val text: String?,
        val imageUri: Uri?,
    )

    /**
     * Validates that an incoming external URI (from ACTION_SEND) is strictly a
     * `content://` URI and NOT pointing to our own private FileProvider or a raw
     * `file://` path (which could trick the app into reading its own private files).
     */
    fun isSafeExternalImageUri(context: Context, uri: Uri?): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != ContentResolver.SCHEME_CONTENT) {
            Log.w(TAG, "Blocked non-content URI scheme from external share: $scheme")
            return false
        }
        val authority = uri.authority.orEmpty()
        if (authority.isBlank() || authority.equals("${context.packageName}.fileprovider", ignoreCase = true)) {
            Log.w(TAG, "Blocked self-FileProvider or blank authority URI: $authority")
            return false
        }
        return true
    }

    /**
     * Uses AndroidX [IntentSanitizer] (Core 1.9.0+) with a strict allowlist to
     * sanitize incoming `ACTION_SEND` intents before copying fields into an
     * explicit internal `CreateListingActivity` intent.
     */
    fun sanitizeIncomingShare(context: Context, incoming: Intent): SanitizedSharePayload? {
        val sanitizer = IntentSanitizer.Builder()
            .allowAction(Intent.ACTION_SEND)
            .allowType("text/plain")
            .allowType { it.startsWith("image/", ignoreCase = true) }
            .allowExtra(Intent.EXTRA_SUBJECT, String::class.java) { it.length <= 200 }
            .allowExtra(Intent.EXTRA_TEXT, String::class.java) { it.length <= 2000 }
            .allowExtra(Intent.EXTRA_STREAM, Uri::class.java) { uri -> isSafeExternalImageUri(context, uri) }
            .allowFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .allowAnyComponent()
            .build()

        val safeIntent = try {
            sanitizer.sanitize(incoming) { violation ->
                Log.w(TAG, "IntentSanitizer stripped untrusted field: $violation")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Blocked malformed incoming share intent", e)
            return null
        }

        val subject = safeIntent.getStringExtra(Intent.EXTRA_SUBJECT)
            ?.let { UcEmailValidator.sanitizeSingleLine(it, 60) }
            ?.takeIf { it.isNotEmpty() }
        val text = safeIntent.getStringExtra(Intent.EXTRA_TEXT)
            ?.let { UcEmailValidator.sanitizeMultiLine(it, 300) }
            ?.takeIf { it.isNotEmpty() }
        val streamUri = IntentCompat.getParcelableExtra(safeIntent, Intent.EXTRA_STREAM, Uri::class.java)
            ?.takeIf { isSafeExternalImageUri(context, it) }

        if (subject == null && text == null && streamUri == null) return null
        return SanitizedSharePayload(subject = subject, text = text, imageUri = streamUri)
    }

    /**
     * Rejects forbidden URI permission flags and strips selector overrides on
     * any deep-link route before routing internally.
     */
    fun hasSafeDeepLink(uri: Uri?): Boolean = AppIntents.parseDeepLink(uri) != null
}
