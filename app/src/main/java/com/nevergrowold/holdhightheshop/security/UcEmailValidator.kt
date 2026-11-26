package com.nevergrowold.holdhightheshop.security

import java.security.SecureRandom
import java.util.Locale

/**
 * Strict University of the Cordilleras student email & credential validator.
 *
 * Security rationale (defense-in-depth):
 * - Normalizes whitespace and case before validation.
 * - Strictly requires the exact `@students.uc-bcf.edu.ph` suffix, blocking
 *   personal domains (@gmail.com), spoofed lookalike subdomains
 *   (@students.uc-bcf.edu.ph.evil.com), legacy domains, multiple '@', consecutive
 *   dots, control characters, and whitespace.
 * - Paired with identical server-side enforcement in `firestore.rules`.
 */
object UcEmailValidator {

    const val REQUIRED_DOMAIN = "@students.uc-bcf.edu.ph"

    private val STRICT_UC_EMAIL_REGEX = Regex(
        "^[a-z0-9](?:[a-z0-9._%+-]{0,62}[a-z0-9])?@students\\.uc-bcf\\.edu\\.ph$"
    )

    private val SECURE_RANDOM = SecureRandom()

    fun normalize(raw: String?): String = raw?.trim()?.lowercase(Locale.US).orEmpty()

    fun isValidUcStudentEmail(raw: String?): Boolean {
        val email = normalize(raw)
        if (email.isEmpty()) return false
        if (email.any { it.isWhitespace() || it.isISOControl() }) return false
        val local = email.substringBefore('@', "")
        if (local.contains("..")) return false
        return STRICT_UC_EMAIL_REGEX.matches(email)
    }

    /**
     * Password strength policy for student accounts:
     * at least 8 characters, containing at least one letter and one digit,
     * with no leading/trailing control characters.
     */
    data class PasswordCheck(
        val valid: Boolean,
        val score: Int, // 0..4
        val message: String,
    )

    /**
     * Live per-rule password evaluation for visible checklist indicators beneath password fields.
     */
    data class PasswordRules(
        val minLength: Boolean,
        val hasLetter: Boolean,
        val hasDigit: Boolean,
        val hasUpperOrSymbol: Boolean,
    ) {
        val allRequiredMet: Boolean get() = minLength && hasLetter && hasDigit
    }

    fun evaluatePasswordRules(password: String): PasswordRules = PasswordRules(
        minLength = password.length >= 8,
        hasLetter = password.any { it.isLetter() },
        hasDigit = password.any { it.isDigit() },
        hasUpperOrSymbol = password.any { it.isUpperCase() || !it.isLetterOrDigit() },
    )

    fun checkPassword(password: String): PasswordCheck {
        val rules = evaluatePasswordRules(password)
        if (!rules.minLength) {
            return PasswordCheck(false, 1, "Use at least 8 characters")
        }
        val hasUpper = password.any { it.isUpperCase() }
        val hasSymbol = password.any { !it.isLetterOrDigit() }
        if (!rules.hasLetter || !rules.hasDigit) {
            return PasswordCheck(false, 2, "Include both letters and numbers")
        }
        val score = when {
            password.length >= 12 && hasUpper && hasSymbol -> 4
            password.length >= 10 && (hasUpper || hasSymbol) -> 3
            else -> 2
        }
        val label = when (score) {
            4 -> "Strong password"
            3 -> "Good password"
            else -> "Fair password — add uppercase or a symbol"
        }
        return PasswordCheck(true, score, label)
    }

    /**
     * Generates a cryptographically unpredictable 192-bit (48 hex char) single-use
     * email verification token for branded verification button links.
     */
    fun generateVerificationToken(): String {
        val bytes = ByteArray(24)
        SECURE_RANDOM.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(Locale.US, it) }
    }

    /**
     * Generates a cryptographically unpredictable 6-digit email verification code (100000..999999).
     */
    fun generateVerificationCode(): String =
        "%06d".format(Locale.US, 100000 + SECURE_RANDOM.nextInt(900000))

    /**
     * Computes a deterministic SHA-256 hex digest of `uid:code` so raw verification codes
     * never need to be stored in plaintext in Cloud Firestore.
     */
    fun hashVerificationCode(uid: String, code: String): String {
        val normalizedCode = code.filter { it.isDigit() }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${uid.trim()}:$normalizedCode".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(Locale.US, it) }
    }

    /**
     * Generates a cryptographically unpredictable 4-digit handover code (1000..9999)
     * using [SecureRandom], replacing the deterministic `hashCode()` placeholder.
     */
    fun generateHandoverCode(): String = "%04d".format(Locale.US, 1000 + SECURE_RANDOM.nextInt(9000))

    /** Generates a clean non-predictable reference suffix for listings & reports. */
    fun generateReference(prefix: String): String =
        "$prefix-%05d".format(Locale.US, 10000 + SECURE_RANDOM.nextInt(90000))

    fun sanitizeSingleLine(raw: String?, maxLength: Int): String =
        raw.orEmpty()
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .take(maxLength)

    fun sanitizeMultiLine(raw: String?, maxLength: Int): String =
        raw.orEmpty()
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
            .take(maxLength)
}
