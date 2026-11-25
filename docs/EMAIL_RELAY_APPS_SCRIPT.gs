/**
 * Hold High The Shop — Free HTML Email Relay (Google Apps Script)
 * 100% Free (₱0/month, no credit card, no Firebase Blaze plan required).
 *
 * Setup (takes ~60 seconds):
 * 1. Open https://script.google.com and click "New project".
 * 2. Replace the default Code.gs content with this entire file and click Save.
 * 3. (Optional) Set SECRET_TOKEN below to match EMAIL_WEBHOOK_SECRET in local.properties
 *    and VITE_EMAIL_WEBHOOK_SECRET in admin-web/.env.local.
 * 4. Click "Deploy" -> "New deployment":
 *    - Select type: "Web app"
 *    - Execute as: "Me"
 *    - Who has access: "Anyone"
 *    - Click "Deploy", authorize Gmail/Mail permissions, and copy the Web App URL
 *      (ends with `/exec`).
 * 5. Paste the URL into:
 *    - `local.properties` -> EMAIL_WEBHOOK_URL=https://script.google.com/macros/s/.../exec
 *    - `admin-web/.env.local` -> VITE_EMAIL_WEBHOOK_URL=https://script.google.com/macros/s/.../exec
 */

const SECRET_TOKEN = ''; // Optional shared secret (leave empty or match EMAIL_WEBHOOK_SECRET)
const SENDER_NAME = 'Hold High The Shop (UC)';

function doPost(e) {
  try {
    if (!e || !e.postData || !e.postData.contents) {
      return jsonResponse({ ok: false, error: 'Missing request payload' });
    }

    const body = JSON.parse(e.postData.contents);

    if (SECRET_TOKEN && body.secret !== SECRET_TOKEN) {
      return jsonResponse({ ok: false, error: 'Unauthorized' });
    }

    const to = String(body.to || '').trim().toLowerCase();
    const subject = String(body.subject || '').trim();
    const htmlBody = String(body.html || '').trim();
    const plainText = String(body.text || '').trim();

    if (!to || !to.endsWith('@students.uc-bcf.edu.ph')) {
      return jsonResponse({ ok: false, error: 'Recipient must be a @students.uc-bcf.edu.ph address' });
    }
    if (!subject || !htmlBody) {
      return jsonResponse({ ok: false, error: 'Missing subject or html content' });
    }

    MailApp.sendEmail({
      to: to,
      subject: subject,
      body: plainText || 'Please view this message in an HTML-capable email client such as UC Outlook.',
      htmlBody: htmlBody,
      name: SENDER_NAME,
      noReply: true,
    });

    return jsonResponse({ ok: true, quotaRemaining: MailApp.getRemainingDailyQuota() });
  } catch (err) {
    return jsonResponse({ ok: false, error: String(err && err.message ? err.message : err) });
  }
}

function jsonResponse(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
