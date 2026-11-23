package com.nevergrowold.holdhightheshop

import com.nevergrowold.holdhightheshop.security.UcEmailValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive unit tests for [UcEmailValidator]:
 * - Strict `@students.uc-bcf.edu.ph` allow/block matrix & anti-spoofing checks
 * - Password strength scoring policy
 * - Cryptographically random handover code & reference generation
 * - Input sanitization helpers
 */
class UcEmailValidatorTest {

    @Test
    fun isValidUcStudentEmail_acceptsLegitimateUcStudentEmails() {
        val valid = listOf(
            "juan.delacruz@students.uc-bcf.edu.ph",
            "  MARIA_SANTOS2025@STUDENTS.UC-BCF.EDU.PH  ",
            "ac.navalta@students.uc-bcf.edu.ph",
            "j1@students.uc-bcf.edu.ph",
            "student+market@students.uc-bcf.edu.ph",
            "first-last_01@students.uc-bcf.edu.ph",
        )
        valid.forEach { email ->
            assertTrue("Expected valid UC student email: $email", UcEmailValidator.isValidUcStudentEmail(email))
        }
    }

    @Test
    fun isValidUcStudentEmail_blocksNonUcLegacyAndSpoofedEmails() {
        val invalid = listOf(
            null,
            "",
            "   ",
            "student@gmail.com",
            "student@outlook.com",
            "student@yahoo.com",
            "faculty@uc-bcf.edu.ph", // Must strictly be @students.uc-bcf.edu.ph
            "juan@students.uc-bcf.edu.ph.evil.com", // Lookalike suffix spoof
            "juan@evil-students.uc-bcf.edu.ph", // Lookalike prefix spoof
            "juan@sub.students.uc-bcf.edu.ph", // Extra subdomain
            "juan@@students.uc-bcf.edu.ph", // Double @
            "juan@gmail.com@students.uc-bcf.edu.ph", // Multiple @
            "juan..cruz@students.uc-bcf.edu.ph", // Consecutive dots
            ".juan@students.uc-bcf.edu.ph", // Leading dot
            "juan.@students.uc-bcf.edu.ph", // Trailing dot
            "juan cruz@students.uc-bcf.edu.ph", // Embedded space
            "juan\ncruz@students.uc-bcf.edu.ph", // Embedded newline
            "@students.uc-bcf.edu.ph", // Missing local part
        )
        invalid.forEach { email ->
            assertFalse("Expected blocked email: $email", UcEmailValidator.isValidUcStudentEmail(email))
        }
    }

    @Test
    fun normalize_trimsAndLowercases() {
        assertEquals(
            "juan.delacruz@students.uc-bcf.edu.ph",
            UcEmailValidator.normalize("  Juan.DelaCruz@Students.UC-BCF.edu.ph \t"),
        )
        assertEquals("", UcEmailValidator.normalize(null))
    }

    @Test
    fun checkPassword_enforcesLengthLetterDigitAndScoresStrength() {
        assertFalse(UcEmailValidator.checkPassword("abc123").valid)
        assertFalse(UcEmailValidator.checkPassword("abcdefgh").valid)
        assertFalse(UcEmailValidator.checkPassword("12345678").valid)

        val fair = UcEmailValidator.checkPassword("campus26")
        assertTrue(fair.valid)
        assertEquals(2, fair.score)

        val good = UcEmailValidator.checkPassword("Campus2026")
        assertTrue(good.valid)
        assertEquals(3, good.score)

        val strong = UcEmailValidator.checkPassword("HoldHigh@2026!")
        assertTrue(strong.valid)
        assertEquals(4, strong.score)
    }

    @Test
    fun generateHandoverCode_isFourDigitsAndNonDeterministic() {
        val codes = (1..50).map { UcEmailValidator.generateHandoverCode() }
        codes.forEach { code ->
            assertEquals("Code length must be 4: $code", 4, code.length)
            val num = code.toInt()
            assertTrue("Code out of 1000..9999: $code", num in 1000..9999)
        }
        // SecureRandom must produce many distinct values across 50 draws (never constant/deterministic).
        assertTrue("Expected distinct SecureRandom codes, got ${codes.toSet().size}", codes.toSet().size > 20)
    }

    @Test
    fun generateReference_andSanitizers_enforceCleanFormatting() {
        val ref = UcEmailValidator.generateReference("RPT")
        assertTrue("Unexpected reference format: $ref", ref.matches(Regex("^RPT-\\d{5}$")))

        assertEquals(
            "Casio Calculator",
            UcEmailValidator.sanitizeSingleLine("  Casio\n\t   Calculator  ", 50),
        )
        assertEquals("12345", UcEmailValidator.sanitizeSingleLine("123456789", 5))
        assertEquals(
            "Line 1\nLine 2",
            UcEmailValidator.sanitizeMultiLine("  Line 1\r\nLine 2  ", 50),
        )
    }
}
