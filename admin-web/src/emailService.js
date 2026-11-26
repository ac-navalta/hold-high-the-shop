const EMAIL_WEBHOOK_URL = (import.meta.env.VITE_EMAIL_WEBHOOK_URL || '').trim()
const EMAIL_WEBHOOK_SECRET = (import.meta.env.VITE_EMAIL_WEBHOOK_SECRET || '').trim()

export const isEmailWebhookConfigured = EMAIL_WEBHOOK_URL.length > 0

function escapeHtml(raw = '') {
  return String(raw)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/**
 * Generates an Outlook / Microsoft 365 & mobile-safe HTML email layout
 * matching Hold High The Shop's University of the Cordilleras visual identity.
 */
export function buildBrandedHtmlEmail({
  preheader = 'Hold High The Shop — University of the Cordilleras',
  kicker = 'UNIVERSITY OF THE CORDILLERAS',
  title = 'Hold High The Shop Notice',
  greeting = 'Kumusta, UCian!',
  paragraphs = [],
  actionButton = null,
  codeBlock = null,
  callout = null,
  accent = 'green', // 'green' | 'amber' | 'danger'
  footerNote = 'This message was sent to your verified @students.uc-bcf.edu.ph Microsoft Outlook account.',
}) {
  const palette = {
    green: { bg: '#E4EFEA', border: '#07683B', text: '#07683B', badgeBg: '#E4EFEA' },
    amber: { bg: '#FFF6E3', border: '#B7791F', text: '#8A5A00', badgeBg: '#FFF6E3' },
    danger: { bg: '#FDECEA', border: '#C62828', text: '#991B1B', badgeBg: '#FDECEA' },
  }[accent] || { bg: '#E4EFEA', border: '#07683B', text: '#07683B', badgeBg: '#E4EFEA' }

  const bodyParagraphsHtml = paragraphs
    .map(
      (p) =>
        `<p style="margin:0 0 14px 0;font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:15px;line-height:24px;color:#2C352E;">${escapeHtml(p)}</p>`
    )
    .join('')

  const actionButtonHtml =
    actionButton && actionButton.label && actionButton.url
      ? `
      <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="margin:24px 0 18px 0;">
        <tr>
          <td align="center" style="background-color:#E4EFEA;border:1px solid #C7DED3;border-radius:16px;padding:24px 20px;">
            <table role="presentation" cellspacing="0" cellpadding="0" border="0" style="margin:0 auto;">
              <tr>
                <td align="center" bgcolor="#07683B" style="border-radius:12px;">
                  <a href="${escapeHtml(actionButton.url)}" target="_blank" rel="noopener noreferrer" style="display:inline-block;padding:15px 30px;font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:15px;font-weight:700;color:#FFFFFF;text-decoration:none;border-radius:12px;background-color:#07683B;border:1px solid #07683B;">
                    ${escapeHtml(actionButton.label)}
                  </a>
                </td>
              </tr>
            </table>
            ${
              actionButton.caption
                ? `<div style="font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:12px;color:#4B5950;margin-top:12px;">${escapeHtml(actionButton.caption)}</div>`
                : ''
            }
          </td>
        </tr>
      </table>
      <p style="margin:0 0 6px 0;font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:12px;line-height:18px;color:#5F6B62;">
        Button not opening? Copy and paste this secure link into your browser:
      </p>
      <p style="margin:0 0 14px 0;font-family:'Consolas','Courier New',monospace;font-size:11px;line-height:17px;color:#07683B;word-break:break-all;">
        <a href="${escapeHtml(actionButton.url)}" target="_blank" rel="noopener noreferrer" style="color:#07683B;text-decoration:underline;">${escapeHtml(actionButton.url)}</a>
      </p>`
      : ''

  const codeHtml = codeBlock
    ? `
      <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="margin:22px 0;">
        <tr>
          <td align="center" style="background-color:#E4EFEA;border:2px dashed #07683B;border-radius:16px;padding:22px 18px;">
            <div style="font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:11px;font-weight:700;letter-spacing:1.4px;text-transform:uppercase;color:#07683B;margin-bottom:8px;">
              ${escapeHtml(codeBlock.label || 'VERIFICATION CODE')}
            </div>
            <div style="font-family:'Consolas','Courier New',monospace;font-size:34px;font-weight:700;letter-spacing:8px;color:#07683B;line-height:40px;">
              ${escapeHtml(codeBlock.code)}
            </div>
            ${
              codeBlock.caption
                ? `<div style="font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:12px;color:#4B5950;margin-top:8px;">${escapeHtml(codeBlock.caption)}</div>`
                : ''
            }
          </td>
        </tr>
      </table>`
    : ''

  const calloutHtml = callout
    ? `
      <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="margin:20px 0;">
        <tr>
          <td style="background-color:${palette.bg};border-left:4px solid ${palette.border};border-radius:12px;padding:16px 18px;">
            <div style="font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:11px;font-weight:700;letter-spacing:1px;text-transform:uppercase;color:${palette.text};margin-bottom:4px;">
              ${escapeHtml(callout.label)}
            </div>
            <div style="font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:14px;line-height:21px;font-weight:600;color:#1A1A1A;">
              ${escapeHtml(callout.value)}
            </div>
          </td>
        </tr>
      </table>`
    : ''

  return `<!DOCTYPE html>
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
          <!-- Header -->
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
          <!-- Main Content -->
          <tr>
            <td style="padding:30px 28px 24px 28px;">
              <div style="display:inline-block;padding:4px 10px;border-radius:999px;background-color:${palette.badgeBg};color:${palette.text};font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:11px;font-weight:700;letter-spacing:0.9px;text-transform:uppercase;margin-bottom:12px;">
                ${escapeHtml(kicker)}
              </div>
              <h1 style="margin:0 0 14px 0;font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:22px;line-height:28px;font-weight:700;color:#141A16;">
                ${escapeHtml(title)}
              </h1>
              <p style="margin:0 0 14px 0;font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:15px;font-weight:600;color:#141A16;">
                ${escapeHtml(greeting)}
              </p>
              ${bodyParagraphsHtml}
              ${actionButtonHtml}
              ${codeHtml}
              ${calloutHtml}
            </td>
          </tr>
          <!-- Footer -->
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
</html>`
}

export function buildVerificationButtonEmail({
  studentName = 'UCian',
  verificationUrl = 'https://hold-high-the-shop.web.app/auth/action',
}) {
  const first = studentName.trim().split(/\s+/)[0] || 'UCian'
  const subject = 'Verify your UC email — Hold High The Shop'
  const html = buildBrandedHtmlEmail({
    preheader: 'Tap Verify My UC Email to activate your Hold High The Shop student account.',
    kicker: 'VERIFY YOUR UC EMAIL',
    title: 'Confirm your student account',
    greeting: `Kumusta, ${first}!`,
    paragraphs: [
      'Welcome to Hold High The Shop, the University of the Cordilleras campus marketplace! Tap the green button below to verify your official UC student email and unlock buying, selling, lending, and swapping on campus.',
      "If you didn't create a Hold High The Shop account, you can safely ignore this email.",
    ],
    actionButton: {
      label: 'Verify My UC Email',
      url: verificationUrl,
      caption: 'Expires in 24 hours • One-time verification link',
    },
    accent: 'green',
  })
  const text = `Kumusta, ${first}!\n\nVerify your University of the Cordilleras student account for Hold High The Shop by opening this link:\n${verificationUrl}\n\nThis single-use link expires in 24 hours.`
  return { subject, html, text }
}

export function buildVerificationCodeEmail({ studentName = 'UCian', code = '482915' }) {
  const first = studentName.trim().split(/\s+/)[0] || 'UCian'
  const subject = `${code} is your Hold High The Shop verification code`
  const html = buildBrandedHtmlEmail({
    preheader: `Your UC verification code is ${code} (valid for 15 minutes).`,
    kicker: 'VERIFY YOUR UC EMAIL',
    title: 'Confirm your student account',
    greeting: `Kumusta, ${first}!`,
    paragraphs: [
      'Enter the 6-digit verification code below in the Hold High The Shop app to activate your verified University of the Cordilleras student profile.',
      'For your security, never share this code with anyone. Campus moderators will never ask for your verification code.',
    ],
    codeBlock: {
      label: 'YOUR 6-DIGIT VERIFICATION CODE',
      code,
      caption: 'Expires in 15 minutes • One-time use',
    },
    accent: 'green',
  })
  const text = `Kumusta, ${first}!\n\nYour Hold High The Shop verification code is: ${code}\n\nEnter this 6-digit code in the app within 15 minutes to activate your verified UC student account.`
  return { subject, html, text }
}

export function buildListingRemovedEmail({ studentName = 'UCian', listingTitle = '', reason = '' }) {
  const first = studentName.trim().split(/\s+/)[0] || 'UCian'
  const subject = `Moderation Notice: "${listingTitle}" was removed`
  const html = buildBrandedHtmlEmail({
    preheader: `Your listing "${listingTitle}" was removed by UC Campus Moderators.`,
    kicker: 'MODERATION ACTION',
    title: 'Your campus listing was removed',
    greeting: `Hi ${first},`,
    paragraphs: [
      `Our campus moderation team reviewed your listing "${listingTitle}" and removed it from Hold High The Shop because it did not comply with UC Campus Marketplace Guidelines.`,
      'Please review our prohibited-item and listing accuracy standards before posting new items.',
    ],
    callout: {
      label: 'MODERATOR REASON',
      value: reason,
    },
    accent: 'danger',
  })
  const text = `Hi ${first},\n\nYour listing "${listingTitle}" was removed by UC Campus Moderators.\nReason: ${reason}`
  return { subject, html, text }
}

export function buildOfficialWarningEmail({ studentName = 'UCian', warningMessage = '', warningCount = 1 }) {
  const first = studentName.trim().split(/\s+/)[0] || 'UCian'
  const subject = 'Official Campus Marketplace Warning — Hold High The Shop'
  const html = buildBrandedHtmlEmail({
    preheader: 'Official notice from Hold High The Shop campus moderators.',
    kicker: `OFFICIAL WARNING (#${warningCount})`,
    title: 'Please review UC Marketplace Guidelines',
    greeting: `Hi ${first},`,
    paragraphs: [
      'A campus moderator has issued an official warning on your Hold High The Shop student account.',
      'Repeated violations may result in temporary or permanent suspension from the campus marketplace.',
    ],
    callout: {
      label: 'MODERATOR NOTE',
      value: warningMessage,
    },
    accent: 'amber',
  })
  const text = `Hi ${first},\n\nOfficial Campus Warning (#${warningCount}):\n${warningMessage}`
  return { subject, html, text }
}

export function buildAccountSuspendedEmail({ studentName = 'UCian', reason = '' }) {
  const first = studentName.trim().split(/\s+/)[0] || 'UCian'
  const subject = 'Account Suspended — Hold High The Shop (UC)'
  const html = buildBrandedHtmlEmail({
    preheader: 'Your Hold High The Shop account has been suspended by campus moderators.',
    kicker: 'ACCOUNT SUSPENDED',
    title: 'Your marketplace access is suspended',
    greeting: `Hi ${first},`,
    paragraphs: [
      'Your Hold High The Shop student account has been suspended following a campus moderation review.',
      'If you believe this action was taken in error, you may contact UC Student Support from your university email.',
    ],
    callout: {
      label: 'SUSPENSION REASON',
      value: reason,
    },
    accent: 'danger',
  })
  const text = `Hi ${first},\n\nYour Hold High The Shop account has been suspended.\nReason: ${reason}`
  return { subject, html, text }
}

export function buildAccountReinstatedEmail({ studentName = 'UCian' }) {
  const first = studentName.trim().split(/\s+/)[0] || 'UCian'
  const subject = 'Account Reinstated — Hold High The Shop (UC)'
  const html = buildBrandedHtmlEmail({
    preheader: 'Your Hold High The Shop UC student account is active again.',
    kicker: 'ACCOUNT REINSTATED',
    title: 'Welcome back to Campus Market',
    greeting: `Kumusta, ${first}!`,
    paragraphs: [
      'Your Hold High The Shop student account has been reinstated by campus moderators. You can now sign in and buy, sell, lend, or swap on Campus Market again.',
    ],
    accent: 'green',
  })
  const text = `Kumusta, ${first}!\n\nYour Hold High The Shop student account has been reinstated and is active again.`
  return { subject, html, text }
}

export async function sendCampusHtmlEmail({ to, subject, html, text }) {
  if (!isEmailWebhookConfigured || !to) {
    return { sent: false, reason: 'webhook_not_configured' }
  }
  try {
    const res = await fetch(EMAIL_WEBHOOK_URL, {
      method: 'POST',
      headers: {
        // text/plain avoids CORS preflight rejection on Google Apps Script Web Apps
        'Content-Type': 'text/plain;charset=utf-8',
      },
      body: JSON.stringify({
        to: to.trim().toLowerCase(),
        subject,
        html,
        text,
        secret: EMAIL_WEBHOOK_SECRET,
      }),
    })
    const data = await res.json().catch(() => ({ ok: res.ok }))
    return { sent: Boolean(data?.ok ?? res.ok), data }
  } catch (err) {
    return { sent: false, reason: err?.message || 'network_error' }
  }
}
