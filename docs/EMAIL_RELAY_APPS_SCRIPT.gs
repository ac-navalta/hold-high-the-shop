/**
 * Hold High The Shop — Free Authenticated HTML Email Relay (Google Apps Script)
 * 100% Free (₱0/month, no credit card, no Firebase Blaze plan required).
 *
 * Why GmailApp instead of MailApp?
 * `GmailApp.sendEmail` sends directly through your authenticated Gmail mailbox
 * (full gmail.com SPF + DKIM + DMARC alignment, and every message is logged in
 * your Gmail "Sent" folder), whereas `MailApp` uses `gappssmtp.com` bounce
 * headers that strict Microsoft 365 EDU gateways often silently drop.
 *
 * Setup / Update (~60 seconds):
 * 1. Open https://script.google.com and open your project (or click "New project").
 * 2. Replace `Code.gs` with this entire file and click Save (Ctrl+S).
 * 3. Select `testRelay` in the toolbar dropdown and click "Run" once to grant
 *    Gmail send permissions (Review permissions -> Advanced -> Go to project -> Allow).
 * 4. Click "Deploy" -> "New deployment" (or "Manage deployments" -> Edit -> Version: "New version"):
 *    - Select type: "Web app"
 *    - Execute as: "Me"
 *    - Who has access: "Anyone"
 *    - Click "Deploy" and copy the Web App URL (ends with `/exec`).
 * 5. Paste the `/exec` URL into:
 *    - `local.properties` -> EMAIL_WEBHOOK_URL=https://script.google.com/macros/s/.../exec
 *    - `admin-web/.env.local` -> VITE_EMAIL_WEBHOOK_URL=https://script.google.com/macros/s/.../exec
 * 6. Rebuild the Android app (`Sync Project with Gradle Files` -> Run) and restart `npm run dev`.
 */

const SECRET_TOKEN = ''; // Optional shared secret (leave empty or match EMAIL_WEBHOOK_SECRET)
const SENDER_NAME = 'Hold High The Shop (UC)';

function doGet() {
  return jsonResponse({
    ok: true,
    service: 'Hold High The Shop HTML Email Relay (GmailApp)',
    quotaRemaining: MailApp.getRemainingDailyQuota(),
  });
}

function doPost(e) {
  try {
    if (!e || !e.postData || !e.postData.contents) {
      return jsonResponse({ ok: false, error: 'Missing request payload' });
    }

    const body = JSON.parse(e.postData.contents);

    if (SECRET_TOKEN && body.secret !== SECRET_TOKEN) {
      return jsonResponse({ ok: false, error: 'Unauthorized secret token' });
    }

    const to = String(body.to || '').trim().toLowerCase();
    const subject = String(body.subject || '').trim();
    const htmlBody = String(body.html || '').trim();
    const plainText =
      String(body.text || '').trim() ||
      'Please view this message in an HTML-capable email client such as UC Outlook.';

    if (!to || !to.endsWith('@students.uc-bcf.edu.ph')) {
      return jsonResponse({ ok: false, error: 'Recipient must be a @students.uc-bcf.edu.ph address' });
    }
    if (!subject || !htmlBody) {
      return jsonResponse({ ok: false, error: 'Missing subject or html content' });
    }

    // Authenticated Gmail send: passes gmail.com SPF + DKIM + DMARC and logs to Gmail -> Sent.
    GmailApp.sendEmail(to, subject, plainText, {
      htmlBody: htmlBody,
      name: SENDER_NAME,
    });

    return jsonResponse({
      ok: true,
      quotaRemaining: MailApp.getRemainingDailyQuota(),
    });
  } catch (err) {
    return jsonResponse({ ok: false, error: String(err && err.message ? err.message : err) });
  }
}

/**
 * Optional 1-click authorization helper inside the Apps Script editor:
 * Select `testRelay` in the top toolbar and click "Run" to trigger the Gmail permission prompt.
 */
function testRelay() {
  Logger.log('Remaining daily quota: ' + MailApp.getRemainingDailyQuota());
  GmailApp.getAliases();
}

function jsonResponse(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
