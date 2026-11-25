package com.nevergrowold.holdhightheshop.security

import android.util.Log
import com.nevergrowold.holdhightheshop.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Branded HTML Email Builder & Free Spark-Compatible Webhook Dispatcher.
 *
 * Ensures every email sent by Hold High The Shop (6-digit UC Outlook verification codes,
 * account security notices, and transaction/moderation alerts) uses a responsive,
 * Microsoft Outlook-compatible HTML layout styled in Hold High The Shop's UC palette.
 */
object CampusEmailService {

    private const val TAG = "CampusEmailService"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 12_000

    data class EmailPayload(
        val to: String,
        val subject: String,
        val html: String,
        val text: String,
    )

    sealed class DeliveryResult {
        data object Sent : DeliveryResult()
        data object WebhookNotConfigured : DeliveryResult()
        data class Failed(val message: String) : DeliveryResult()
    }

    /** Optional hook for deterministic unit/Robolectric tests. */
    @Volatile
    internal var testSender: (suspend (EmailPayload) -> DeliveryResult)? = null

    fun isWebhookConfigured(): Boolean =
        testSender != null || BuildConfig.EMAIL_WEBHOOK_URL.trim().startsWith("https://")

    /**
     * Builds a proper table-layouted HTML email that renders crisply inside
     * University of the Cordilleras Microsoft 365 Outlook (desktop, web, and mobile).
     */
    fun buildBrandedHtmlLayout(
        preheader: String,
        kicker: String,
        title: String,
        greeting: String,
        paragraphs: List<String>,
        verificationCode: String? = null,
        codeCaption: String = "Expires in 15 minutes • One-time use",
        calloutLabel: String? = null,
        calloutValue: String? = null,
        footerNote: String = "Sent to your official @students.uc-bcf.edu.ph Microsoft Outlook inbox.",
    ): String {
        val bodyHtml = paragraphs.joinToString("\n") { p ->
            """<p style="margin:0 0 14px 0;font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:15px;line-height:24px;color:#2C352E;">${escapeHtml(p)}</p>"""
        }

        val codeBoxHtml = if (!verificationCode.isNullOrBlank()) {
            val spacedCode = verificationCode.trim()
            """
            <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="margin:22px 0;">
              <tr>
                <td align="center" style="background-color:#E4EFEA;border:2px dashed #07683B;border-radius:16px;padding:22px 18px;">
                  <div style="font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:11px;font-weight:700;letter-spacing:1.4px;text-transform:uppercase;color:#07683B;margin-bottom:8px;">
                    YOUR 6-DIGIT VERIFICATION CODE
                  </div>
                  <div style="font-family:'Consolas','Courier New',monospace;font-size:34px;font-weight:700;letter-spacing:8px;color:#07683B;line-height:40px;">
                    ${escapeHtml(spacedCode)}
                  </div>
                  <div style="font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:12px;color:#4B5950;margin-top:8px;">
                    ${escapeHtml(codeCaption)}
                  </div>
                </td>
              </tr>
            </table>
            """.trimIndent()
        } else {
            ""
        }

        val calloutHtml = if (!calloutLabel.isNullOrBlank() && !calloutValue.isNullOrBlank()) {
            """
            <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="margin:20px 0;">
              <tr>
                <td style="background-color:#E4EFEA;border-left:4px solid #07683B;border-radius:12px;padding:16px 18px;">
                  <div style="font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:11px;font-weight:700;letter-spacing:1px;text-transform:uppercase;color:#07683B;margin-bottom:4px;">
                    ${escapeHtml(calloutLabel)}
                  </div>
                  <div style="font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:14px;line-height:21px;font-weight:600;color:#1A1A1A;">
                    ${escapeHtml(calloutValue)}
                  </div>
                </td>
              </tr>
            </table>
            """.trimIndent()
        } else {
            ""
        }

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <title>${escapeHtml(title)}</title>
            </head>
            <body style="margin:0;padding:0;background-color:#F9F6E7;font-family:'Segoe UI',Helvetica,Arial,sans-serif;-webkit-font-smoothing:antialiased;">
              <div style="display:none;max-height:0;overflow:hidden;opacity:0;color:transparent;">
                ${escapeHtml(preheader)}
              </div>
              <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="background-color:#F9F6E7;padding:32px 16px;">
                <tr>
                  <td align="center">
                    <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="max-width:560px;background-color:#FFFFFF;border:1px solid #E5E0CE;border-radius:20px;overflow:hidden;">
                      <tr>
                        <td style="background-color:#07683B;padding:24px 28px;">
                          <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0">
                            <tr>
                              <td width="44" valign="middle">
                                <div style="width:40px;height:40px;line-height:40px;text-align:center;background-color:rgba(255,255,255,0.16);border-radius:12px;font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:14px;font-weight:700;color:#FFFFFF;">
                                  UC
                                </div>
                              </td>
                              <td valign="middle" style="padding-left:12px;">
                                <div style="font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:17px;font-weight:700;color:#FFFFFF;line-height:22px;">
                                  Hold High The Shop
                                </div>
                                <div style="font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:11px;color:#C9DCD1;letter-spacing:0.4px;">
                                  University of the Cordilleras • Campus Marketplace
                                </div>
                              </td>
                            </tr>
                          </table>
                        </td>
                      </tr>
                      <tr>
                        <td style="padding:30px 28px 24px 28px;">
                          <div style="display:inline-block;padding:4px 10px;border-radius:999px;background-color:#E4EFEA;color:#07683B;font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:11px;font-weight:700;letter-spacing:0.9px;text-transform:uppercase;margin-bottom:12px;">
                            ${escapeHtml(kicker)}
                          </div>
                          <h1 style="margin:0 0 14px 0;font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:22px;line-height:28px;font-weight:700;color:#141A16;">
                            ${escapeHtml(title)}
                          </h1>
                          <p style="margin:0 0 14px 0;font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:15px;font-weight:600;color:#141A16;">
                            ${escapeHtml(greeting)}
                          </p>
                          $bodyHtml
                          $codeBoxHtml
                          $calloutHtml
                        </td>
                      </tr>
                      <tr>
                        <td style="background-color:#FAF8EE;border-top:1px solid #ECE7D5;padding:18px 28px;">
                          <p style="margin:0 0 4px 0;font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:12px;line-height:18px;color:#5F6B62;">
                            ${escapeHtml(footerNote)}
                          </p>
                          <p style="margin:0;font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:11px;color:#8A8575;">
                            Hold High The Shop • Gov. Pack Rd., Baguio City • Pass it on. Make campus greener.
                          </p>
                        </td>
                      </tr>
                    </table>
                  </td>
                </tr>
              </table>
            </body>
            </html>
        """.trimIndent()
    }

    fun buildVerificationCodeEmail(
        recipientEmail: String,
        studentName: String,
        code: String,
    ): EmailPayload {
        val first = studentName.trim().substringBefore(' ').ifBlank { "UCian" }
        val subject = "$code is your Hold High The Shop verification code"
        val html = buildBrandedHtmlLayout(
            preheader = "Your UC verification code is $code (valid for 15 minutes).",
            kicker = "VERIFY YOUR UC EMAIL",
            title = "Confirm your student account",
            greeting = "Kumusta, $first!",
            paragraphs = listOf(
                "Enter the 6-digit verification code below in the Hold High The Shop app to activate your verified University of the Cordilleras student profile.",
                "For your security, never share this code with anyone. Campus moderators will never ask for your verification code.",
            ),
            verificationCode = code,
            codeCaption = "Expires in 15 minutes • One-time use",
        )
        val text = buildString {
            appendLine("Kumusta, $first!")
            appendLine()
            appendLine("Your Hold High The Shop verification code is: $code")
            appendLine()
            appendLine("Enter this 6-digit code in the app within 15 minutes to activate your verified UC student account.")
        }
        return EmailPayload(
            to = UcEmailValidator.normalize(recipientEmail),
            subject = subject,
            html = html,
            text = text,
        )
    }

    suspend fun sendVerificationCode(
        recipientEmail: String,
        studentName: String,
        code: String,
    ): DeliveryResult {
        val payload = buildVerificationCodeEmail(recipientEmail, studentName, code)
        return sendEmail(payload)
    }

    suspend fun sendEmail(payload: EmailPayload): DeliveryResult = withContext(Dispatchers.IO) {
        testSender?.let { return@withContext it(payload) }

        val webhookUrl = BuildConfig.EMAIL_WEBHOOK_URL.trim()
        if (!webhookUrl.startsWith("https://")) {
            return@withContext DeliveryResult.WebhookNotConfigured
        }

        val bodyJson = JSONObject().apply {
            put("to", payload.to)
            put("subject", payload.subject)
            put("html", payload.html)
            put("text", payload.text)
            put("secret", BuildConfig.EMAIL_WEBHOOK_SECRET.trim())
        }.toString()

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(webhookUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false // Handle cross-host 302 from Google Apps Script manually
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(bodyJson)
                writer.flush()
            }

            val status = conn.responseCode
            val responseText = when {
                status in 200..299 -> conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                status in listOf(301, 302, 303, 307, 308) -> {
                    val redirectUrl = conn.getHeaderField("Location")
                    if (!redirectUrl.isNullOrBlank() && redirectUrl.startsWith("https://")) {
                        followRedirectGet(redirectUrl)
                    } else {
                        """{"ok":true}"""
                    }
                }
                else -> {
                    Log.w(TAG, "Email webhook HTTP $status")
                    return@withContext DeliveryResult.Failed("Email relay returned HTTP $status.")
                }
            }

            val parsedOk = runCatching {
                val obj = JSONObject(responseText)
                if (obj.has("ok")) obj.optBoolean("ok", false) else true
            }.getOrDefault(true)

            if (parsedOk) {
                DeliveryResult.Sent
            } else {
                val errMsg = runCatching { JSONObject(responseText).optString("error") }.getOrDefault("")
                DeliveryResult.Failed(errMsg.ifBlank { "Email relay rejected the request." })
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dispatch HTML email via webhook", e)
            DeliveryResult.Failed("Could not reach email service. Check your internet connection.")
        } finally {
            conn?.disconnect()
        }
    }

    private fun followRedirectGet(url: String): String {
        var redirectConn: HttpURLConnection? = null
        return try {
            redirectConn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            if (redirectConn.responseCode in 200..299) {
                redirectConn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                """{"ok":true}"""
            }
        } finally {
            redirectConn?.disconnect()
        }
    }

    internal fun escapeHtml(raw: String): String =
        raw.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
}
