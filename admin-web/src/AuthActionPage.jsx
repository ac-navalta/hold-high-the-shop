import React, { useEffect, useMemo, useState } from 'react'
import {
  applyActionCode,
  checkActionCode,
  confirmPasswordReset,
  sendPasswordResetEmail,
  verifyPasswordResetCode,
} from 'firebase/auth'
import { doc, getDoc, writeBatch } from 'firebase/firestore'
import {
  AlertTriangle,
  Check,
  CheckCircle2,
  Eye,
  EyeOff,
  KeyRound,
  Loader2,
  MailCheck,
  ShieldCheck,
  Smartphone,
} from 'lucide-react'
import { auth, db, isFirebaseConfigured } from './firebase'

const APP_DEEP_LINK = 'holdhightheshop://market'

function getSafeContinueUrl(raw) {
  if (!raw) return null
  try {
    const parsed = new URL(raw)
    if (parsed.protocol === 'https:' || parsed.protocol === 'holdhightheshop:') {
      return parsed.toString()
    }
  } catch {
    // Ignore malformed continueUrl values.
  }
  return null
}

function mapActionError(err) {
  const code = err?.code || ''
  if (code === 'auth/expired-action-code') {
    return 'This link has expired. Return to Hold High The Shop and tap "Resend verification email".'
  }
  if (code === 'auth/invalid-action-code') {
    return 'This link is invalid or has already been used. If you already verified, return to the app and tap "I\'ve verified — Continue".'
  }
  if (code === 'auth/user-disabled') {
    return 'This student account is currently suspended. Contact UC campus moderators for assistance.'
  }
  if (code === 'auth/weak-password') {
    return 'Please choose a stronger password (at least 8 characters with letters and numbers).'
  }
  if (code === 'permission-denied') {
    return 'This verification link has expired, was already used, or the account is unavailable. Try requesting a fresh email from the app.'
  }
  return err?.message || 'Unable to complete this request. Please request a fresh email from Hold High The Shop.'
}

function evaluatePasswordRules(password, confirmPassword) {
  return {
    minLength: password.length >= 8,
    hasLetter: /[A-Za-z]/.test(password),
    hasDigit: /[0-9]/.test(password),
    matches: password.length > 0 && password === confirmPassword,
  }
}

export default function AuthActionPage() {
  const params = useMemo(() => new URLSearchParams(window.location.search), [])
  const initialMode = params.get('mode') || ''
  const initialOobCode = params.get('oobCode') || ''
  const initialToken = params.get('token') || ''
  const initialDone = params.get('done') || ''
  const safeContinueUrl = useMemo(() => getSafeContinueUrl(params.get('continueUrl')), [params])

  const [mode] = useState(initialMode)
  const [oobCode] = useState(initialOobCode)
  const [linkToken] = useState(initialToken)

  const [phase, setPhase] = useState(() => {
    if (initialDone) return 'success'
    if (!isFirebaseConfigured || !auth || !db) return 'unconfigured'
    if (initialMode === 'verifyLink' && initialToken) return 'checking'
    if (initialMode && initialOobCode) return 'checking'
    return 'idle'
  })

  const [doneMode, setDoneMode] = useState(initialDone || initialMode)
  const [alreadyVerifiedNotice, setAlreadyVerifiedNotice] = useState(false)
  const [accountEmail, setAccountEmail] = useState('')
  const [studentFirstName, setStudentFirstName] = useState('')
  const [targetUid, setTargetUid] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const [busy, setBusy] = useState(false)

  // Password reset form state
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [showNewPwd, setShowNewPwd] = useState(false)
  const [showConfirmPwd, setShowConfirmPwd] = useState(false)
  const [formError, setFormError] = useState('')

  // Optional follow-up reset email after recoverEmail
  const [resetSent, setResetSent] = useState(false)

  const pwRules = useMemo(
    () => evaluatePasswordRules(newPassword, confirmPassword),
    [newPassword, confirmPassword]
  )

  useEffect(() => {
    const titles = {
      verifyLink: 'Verify UC Email — Hold High The Shop',
      verifyEmail: 'Verify UC Email — Hold High The Shop',
      verifyAndChangeEmail: 'Confirm Email Change — Hold High The Shop',
      resetPassword: 'Reset Password — Hold High The Shop',
      recoverEmail: 'Recover Email — Hold High The Shop',
    }
    document.title = titles[doneMode || mode] || 'UC Account Verification — Hold High The Shop'
  }, [mode, doneMode])

  useEffect(() => {
    if (phase !== 'checking') return
    let active = true

    async function inspectAction() {
      try {
        if (mode === 'verifyLink') {
          if (!db || linkToken.length < 32) {
            setErrorMessage('This verification link is incomplete or invalid. Request a fresh email from Hold High The Shop.')
            setPhase('error')
            return
          }
          // Read-only lookup: does NOT mark the token used yet so Microsoft 365 Safe Links
          // prefetch scanners cannot consume the student's verification link early.
          const snap = await getDoc(doc(db, 'email_verifications', linkToken))
          if (!active) return
          if (!snap.exists()) {
            setErrorMessage('This verification link is invalid or was already cleared. If you already verified, switch back to the app.')
            setPhase('error')
            return
          }
          const data = snap.data() || {}
          setAccountEmail(data.email || '')
          setStudentFirstName(data.firstName || '')
          setTargetUid(data.uid || '')

          if (data.used === true) {
            setAlreadyVerifiedNotice(true)
            setDoneMode('verifyLink')
            setPhase('success')
            return
          }
          if (typeof data.expiresAt === 'number' && Date.now() > data.expiresAt) {
            setErrorMessage('This verification link has expired (24-hour limit). Return to Hold High The Shop and tap "Resend verification email".')
            setPhase('error')
            return
          }
          setPhase('ready')
          return
        }

        if (!auth || !oobCode) {
          setErrorMessage('This verification link is missing required parameters.')
          setPhase('error')
          return
        }

        if (mode === 'resetPassword') {
          const email = await verifyPasswordResetCode(auth, oobCode)
          if (!active) return
          setAccountEmail(email || '')
          setPhase('ready')
        } else if (
          mode === 'verifyEmail' ||
          mode === 'recoverEmail' ||
          mode === 'verifyAndChangeEmail'
        ) {
          // Read-only validation: requires explicit button click before applyActionCode().
          const info = await checkActionCode(auth, oobCode)
          if (!active) return
          setAccountEmail(info?.data?.email || info?.data?.previousEmail || '')
          setPhase('ready')
        } else {
          setErrorMessage('Unsupported verification action. Please request a fresh link from Hold High The Shop.')
          setPhase('error')
        }
      } catch (err) {
        if (!active) return
        setErrorMessage(mapActionError(err))
        setPhase('error')
      }
    }

    inspectAction()
    return () => {
      active = false
    }
  }, [phase, mode, oobCode, linkToken])

  function scrubUrlToDone(completedMode) {
    setDoneMode(completedMode)
    try {
      window.history.replaceState({}, '', `/auth/action?done=${encodeURIComponent(completedMode)}`)
    } catch {
      // Ignore history restrictions.
    }
  }

  async function handleConfirmVerification() {
    if (busy) return
    setBusy(true)
    setErrorMessage('')
    try {
      if (mode === 'verifyLink') {
        if (!db || !targetUid || linkToken.length < 32) {
          throw new Error('Missing verification details. Please request a fresh email.')
        }
        const now = Date.now()
        // Atomic batch enforced server-side by firestore.rules:
        // verifies token ownership, unused status, server-time expiry, and marks used == true.
        const batch = writeBatch(db)
        batch.update(doc(db, 'email_verifications', linkToken), {
          used: true,
          verifiedAt: now,
        })
        batch.update(doc(db, 'users', targetUid), {
          emailVerified: true,
          verifiedAt: now,
          verifiedToken: linkToken,
        })
        await batch.commit()
        scrubUrlToDone('verifyLink')
        setPhase('success')
      } else {
        if (!auth || !oobCode) {
          throw new Error('Missing verification code. Please request a fresh email.')
        }
        await applyActionCode(auth, oobCode)
        scrubUrlToDone(mode)
        setPhase('success')
      }
    } catch (err) {
      setErrorMessage(mapActionError(err))
      setPhase('error')
    } finally {
      setBusy(false)
    }
  }

  async function handleResetPasswordSubmit(e) {
    e.preventDefault()
    if (!auth || !oobCode || busy) return
    setFormError('')
    if (!pwRules.minLength) {
      setFormError('Password must be at least 8 characters long.')
      return
    }
    if (!pwRules.hasLetter || !pwRules.hasDigit) {
      setFormError('Include at least one letter and one number.')
      return
    }
    if (!pwRules.matches) {
      setFormError('Passwords do not match.')
      return
    }
    setBusy(true)
    try {
      await confirmPasswordReset(auth, oobCode, newPassword)
      setNewPassword('')
      setConfirmPassword('')
      scrubUrlToDone('resetPassword')
      setPhase('success')
    } catch (err) {
      setFormError(mapActionError(err))
    } finally {
      setBusy(false)
    }
  }

  async function handleSendPostRecoveryReset() {
    if (!auth || !accountEmail || resetSent) return
    try {
      await sendPasswordResetEmail(auth, accountEmail)
      setResetSent(true)
    } catch (err) {
      setErrorMessage(mapActionError(err))
    }
  }

  const returnHref = safeContinueUrl || APP_DEEP_LINK
  const isVerifyAction =
    mode === 'verifyLink' ||
    mode === 'verifyEmail' ||
    mode === 'recoverEmail' ||
    mode === 'verifyAndChangeEmail'

  return (
    <div className="min-h-screen bg-[#F5F3EA] flex flex-col items-center justify-center px-4 py-10 relative overflow-hidden">
      {/* Subtle UC pine/mint ambient accents */}
      <div
        aria-hidden="true"
        className="pointer-events-none absolute -top-28 -right-24 w-80 h-80 rounded-full bg-[#C7DED3]/40 blur-3xl"
      />
      <div
        aria-hidden="true"
        className="pointer-events-none absolute -bottom-28 -left-24 w-80 h-80 rounded-full bg-[#C7DED3]/35 blur-3xl"
      />

      <div className="w-full max-w-md relative z-10">
        {/* UC Brand Header */}
        <div className="flex flex-col items-center mb-6 text-center">
          <div className="inline-flex items-center gap-2 px-3.5 py-1.5 rounded-full bg-[#E4EFEA] border border-[#C7DED3] text-[#07683B] text-[11px] font-semibold tracking-wider uppercase">
            <ShieldCheck className="w-3.5 h-3.5" />
            University of the Cordilleras · Baguio
          </div>
          <h1 className="mt-3 text-xl font-bold text-[#131A15] tracking-tight font-display">
            Hold High The Shop
          </h1>
          <p className="text-xs text-[#606D64] mt-0.5">
            Campus Marketplace · Verified Student Access
          </p>
        </div>

        {/* Main Card */}
        <div className="bg-white rounded-3xl border border-[#E3DECB] shadow-sm overflow-hidden">
          <div className="h-1.5 w-full bg-gradient-to-r from-[#07683B] via-[#0E8A50] to-[#05512E]" />

          <div className="p-7 sm:p-8">
            {/* Checking State */}
            {phase === 'checking' && (
              <div className="flex flex-col items-center text-center py-6">
                <div className="w-16 h-16 rounded-full bg-[#E4EFEA] flex items-center justify-center">
                  <Loader2 className="w-7 h-7 text-[#07683B] animate-spin" />
                </div>
                <h2 className="mt-5 text-lg font-bold text-[#131A15] font-display">
                  Checking your link…
                </h2>
                <p className="mt-1.5 text-sm text-[#606D64] leading-relaxed">
                  Validating your one-time UC security token. This takes just a moment.
                </p>
              </div>
            )}

            {/* Ready: Verify Link / Verify Email / Recover Email / Change Email */}
            {phase === 'ready' && isVerifyAction && (
              <div className="flex flex-col items-center text-center">
                <div className="w-16 h-16 rounded-full bg-[#E4EFEA] flex items-center justify-center">
                  <MailCheck className="w-8 h-8 text-[#07683B]" />
                </div>
                <span className="mt-4 text-[11px] font-semibold uppercase tracking-widest text-[#07683B]">
                  {mode === 'recoverEmail'
                    ? 'Account Recovery'
                    : mode === 'verifyAndChangeEmail'
                      ? 'Confirm Email Update'
                      : 'UC Student Verification'}
                </span>
                <h2 className="mt-1.5 text-2xl font-bold text-[#131A15] font-display">
                  {mode === 'recoverEmail'
                    ? 'Restore your UC email'
                    : mode === 'verifyAndChangeEmail'
                      ? 'Confirm your new email'
                      : studentFirstName
                        ? `Kumusta, ${studentFirstName}!`
                        : 'Confirm your UC email'}
                </h2>

                {accountEmail && (
                  <div className="mt-3 px-3.5 py-1.5 rounded-full bg-[#FAF9F3] border border-[#E3DECB] text-xs font-semibold text-[#131A15] break-all">
                    {accountEmail}
                  </div>
                )}

                <p className="mt-3.5 text-sm text-[#37433B] leading-relaxed">
                  {mode === 'recoverEmail'
                    ? 'Tap below to undo the recent email change and restore access to your UC account.'
                    : 'Tap the button below to activate your verified University of the Cordilleras student profile and unlock Hold High The Shop.'}
                </p>

                <button
                  type="button"
                  onClick={handleConfirmVerification}
                  disabled={busy}
                  className="mt-6 w-full h-12 rounded-2xl bg-[#07683B] hover:bg-[#05512E] active:scale-[0.99] transition text-white text-sm font-semibold flex items-center justify-center gap-2 shadow-sm disabled:opacity-60"
                >
                  {busy ? (
                    <>
                      <Loader2 className="w-4 h-4 animate-spin" />
                      Verifying…
                    </>
                  ) : (
                    <>
                      <CheckCircle2 className="w-4 h-4" />
                      {mode === 'recoverEmail' ? 'Restore My Email' : 'Confirm & Verify UC Email'}
                    </>
                  )}
                </button>

                <p className="mt-4 text-[11px] text-[#606D64] leading-relaxed">
                  Single-use security link · Protected against automated email scanners
                </p>
              </div>
            )}

            {/* Ready: Reset Password */}
            {phase === 'ready' && mode === 'resetPassword' && (
              <div>
                <div className="flex flex-col items-center text-center">
                  <div className="w-16 h-16 rounded-full bg-[#E4EFEA] flex items-center justify-center">
                    <KeyRound className="w-7 h-7 text-[#07683B]" />
                  </div>
                  <span className="mt-4 text-[11px] font-semibold uppercase tracking-widest text-[#07683B]">
                    Password Reset
                  </span>
                  <h2 className="mt-1.5 text-2xl font-bold text-[#131A15] font-display">
                    Create a new password
                  </h2>
                  {accountEmail && (
                    <div className="mt-2.5 px-3.5 py-1.5 rounded-full bg-[#FAF9F3] border border-[#E3DECB] text-xs font-semibold text-[#131A15] break-all">
                      {accountEmail}
                    </div>
                  )}
                </div>

                <form onSubmit={handleResetPasswordSubmit} className="mt-6 space-y-4">
                  <div>
                    <label
                      htmlFor="new-password"
                      className="block text-xs font-semibold text-[#37433B] mb-1.5"
                    >
                      New password
                    </label>
                    <div className="relative">
                      <input
                        id="new-password"
                        type={showNewPwd ? 'text' : 'password'}
                        value={newPassword}
                        onChange={(e) => setNewPassword(e.target.value)}
                        placeholder="Create a new password"
                        autoComplete="new-password"
                        required
                        minLength={8}
                        className="w-full h-11 rounded-xl border border-[#E3DECB] bg-[#FAF9F3] px-3.5 pr-10 text-sm text-[#131A15] focus:outline-none focus:border-[#07683B] focus:bg-white transition"
                      />
                      <button
                        type="button"
                        onClick={() => setShowNewPwd((v) => !v)}
                        aria-label={showNewPwd ? 'Hide password' : 'Show password'}
                        className="absolute right-2.5 top-1/2 -translate-y-1/2 p-1 text-[#606D64] hover:text-[#07683B]"
                      >
                        {showNewPwd ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                      </button>
                    </div>
                  </div>

                  {/* Live password rules checklist mirroring Android SignUpActivity */}
                  <div className="rounded-xl bg-[#FAF9F3] border border-[#E3DECB] p-3 space-y-1.5">
                    {[
                      { ok: pwRules.minLength, label: 'At least 8 characters' },
                      { ok: pwRules.hasLetter, label: 'Contains a letter (A–Z, a–z)' },
                      { ok: pwRules.hasDigit, label: 'Contains a number (0–9)' },
                      { ok: pwRules.matches, label: 'Passwords match' },
                    ].map((rule) => (
                      <div
                        key={rule.label}
                        className={`flex items-center gap-2 text-xs font-medium ${
                          rule.ok ? 'text-[#07683B]' : 'text-[#606D64]'
                        }`}
                      >
                        <span
                          className={`w-4 h-4 rounded-full flex items-center justify-center ${
                            rule.ok ? 'bg-[#07683B] text-white' : 'bg-[#E3DECB] text-transparent'
                          }`}
                        >
                          <Check className="w-2.5 h-2.5" />
                        </span>
                        <span>{rule.label}</span>
                      </div>
                    ))}
                  </div>

                  <div>
                    <label
                      htmlFor="confirm-password"
                      className="block text-xs font-semibold text-[#37433B] mb-1.5"
                    >
                      Confirm new password
                    </label>
                    <div className="relative">
                      <input
                        id="confirm-password"
                        type={showConfirmPwd ? 'text' : 'password'}
                        value={confirmPassword}
                        onChange={(e) => setConfirmPassword(e.target.value)}
                        placeholder="Re-enter your new password"
                        autoComplete="new-password"
                        required
                        minLength={8}
                        className="w-full h-11 rounded-xl border border-[#E3DECB] bg-[#FAF9F3] px-3.5 pr-10 text-sm text-[#131A15] focus:outline-none focus:border-[#07683B] focus:bg-white transition"
                      />
                      <button
                        type="button"
                        onClick={() => setShowConfirmPwd((v) => !v)}
                        aria-label={showConfirmPwd ? 'Hide password' : 'Show password'}
                        className="absolute right-2.5 top-1/2 -translate-y-1/2 p-1 text-[#606D64] hover:text-[#07683B]"
                      >
                        {showConfirmPwd ? (
                          <EyeOff className="w-4 h-4" />
                        ) : (
                          <Eye className="w-4 h-4" />
                        )}
                      </button>
                    </div>
                  </div>

                  {formError && (
                    <div className="rounded-xl bg-[#FDECEA] border border-red-200 px-3.5 py-2.5 text-xs text-[#B91C1C] font-medium">
                      {formError}
                    </div>
                  )}

                  <button
                    type="submit"
                    disabled={busy}
                    className="w-full h-12 rounded-2xl bg-[#07683B] hover:bg-[#05512E] active:scale-[0.99] transition text-white text-sm font-semibold flex items-center justify-center gap-2 shadow-sm disabled:opacity-60"
                  >
                    {busy ? (
                      <>
                        <Loader2 className="w-4 h-4 animate-spin" />
                        Updating password…
                      </>
                    ) : (
                      'Save New Password'
                    )}
                  </button>
                </form>
              </div>
            )}

            {/* Success State */}
            {phase === 'success' && (
              <div className="flex flex-col items-center text-center">
                {/* Dual-ring celebration stamp echoing the Android app */}
                <div className="relative flex items-center justify-center w-24 h-24">
                  <span
                    aria-hidden="true"
                    className="absolute inset-0 rounded-full border-2 border-[#C7DED3]"
                  />
                  <span
                    aria-hidden="true"
                    className="absolute inset-2 rounded-full border-2 border-[#07683B]/25"
                  />
                  <div className="w-16 h-16 rounded-full bg-[#07683B] flex items-center justify-center shadow-sm">
                    <CheckCircle2 className="w-8 h-8 text-white" />
                  </div>
                </div>

                <span className="mt-4 text-[11px] font-semibold uppercase tracking-widest text-[#07683B]">
                  {alreadyVerifiedNotice ? 'Already Verified' : 'All Set'}
                </span>
                <h2 className="mt-1.5 text-2xl font-bold text-[#131A15] font-display">
                  {doneMode === 'resetPassword'
                    ? 'Password updated!'
                    : doneMode === 'recoverEmail'
                      ? 'Email restored!'
                      : 'UC Email Verified!'}
                </h2>

                {accountEmail && (
                  <div className="mt-2.5 px-3.5 py-1.5 rounded-full bg-[#E4EFEA] text-xs font-semibold text-[#07683B] break-all">
                    {accountEmail}
                  </div>
                )}

                <p className="mt-3 text-sm text-[#37433B] leading-relaxed">
                  {doneMode === 'resetPassword'
                    ? 'Your new password is active. Return to Hold High The Shop and sign in with your updated password.'
                    : doneMode === 'recoverEmail'
                      ? 'Your original UC student email has been restored. We recommend resetting your password below if you did not request that change.'
                      : 'Your University of the Cordilleras student account is now active. Return to Hold High The Shop — the app unlocks automatically.'}
                </p>

                <a
                  href={returnHref}
                  className="mt-6 w-full h-12 rounded-2xl bg-[#07683B] hover:bg-[#05512E] active:scale-[0.99] transition text-white text-sm font-semibold flex items-center justify-center gap-2 shadow-sm"
                >
                  <Smartphone className="w-4 h-4" />
                  Open Hold High The Shop
                </a>

                {doneMode === 'recoverEmail' && accountEmail && (
                  <button
                    type="button"
                    onClick={handleSendPostRecoveryReset}
                    disabled={resetSent}
                    className="mt-3 w-full h-11 rounded-2xl border border-[#07683B] text-[#07683B] hover:bg-[#E4EFEA]/60 transition text-xs font-semibold disabled:opacity-60"
                  >
                    {resetSent ? 'Password reset email sent' : 'Send password reset email'}
                  </button>
                )}

                <p className="mt-4 text-xs text-[#606D64]">
                  Already have the app open on your phone? Just switch back to it — Campus Market
                  unlocks automatically.
                </p>
              </div>
            )}

            {/* Error State */}
            {phase === 'error' && (
              <div className="flex flex-col items-center text-center">
                <div className="w-16 h-16 rounded-full bg-[#FDECEA] flex items-center justify-center">
                  <AlertTriangle className="w-8 h-8 text-[#B91C1C]" />
                </div>
                <span className="mt-4 text-[11px] font-semibold uppercase tracking-widest text-[#B91C1C]">
                  Link Unavailable
                </span>
                <h2 className="mt-1.5 text-xl font-bold text-[#131A15] font-display">
                  Could not complete verification
                </h2>
                <p className="mt-2.5 text-sm text-[#37433B] leading-relaxed">{errorMessage}</p>

                <a
                  href={APP_DEEP_LINK}
                  className="mt-6 w-full h-12 rounded-2xl bg-[#07683B] hover:bg-[#05512E] transition text-white text-sm font-semibold flex items-center justify-center gap-2 shadow-sm"
                >
                  <Smartphone className="w-4 h-4" />
                  Return to Hold High The Shop
                </a>
              </div>
            )}

            {/* Idle / Direct Visit Hub */}
            {phase === 'idle' && (
              <div className="flex flex-col items-center text-center">
                <div className="w-16 h-16 rounded-full bg-[#E4EFEA] flex items-center justify-center">
                  <ShieldCheck className="w-8 h-8 text-[#07683B]" />
                </div>
                <span className="mt-4 text-[11px] font-semibold uppercase tracking-widest text-[#07683B]">
                  Official Auth Endpoint
                </span>
                <h2 className="mt-1.5 text-xl font-bold text-[#131A15] font-display">
                  UC Account Verification Hub
                </h2>
                <p className="mt-2.5 text-sm text-[#37433B] leading-relaxed">
                  Open the verification or password reset email sent to your{' '}
                  <span className="font-semibold text-[#131A15]">@students.uc-bcf.edu.ph</span>{' '}
                  inbox and tap the green button to confirm your account here.
                </p>
                <a
                  href={APP_DEEP_LINK}
                  className="mt-6 w-full h-12 rounded-2xl bg-[#07683B] hover:bg-[#05512E] transition text-white text-sm font-semibold flex items-center justify-center gap-2 shadow-sm"
                >
                  <Smartphone className="w-4 h-4" />
                  Open Hold High The Shop
                </a>
              </div>
            )}

            {/* Unconfigured Firebase Env */}
            {phase === 'unconfigured' && (
              <div className="flex flex-col items-center text-center">
                <div className="w-16 h-16 rounded-full bg-[#FFF6E3] flex items-center justify-center">
                  <AlertTriangle className="w-7 h-7 text-[#8A5A00]" />
                </div>
                <h2 className="mt-4 text-lg font-bold text-[#131A15] font-display">
                  Firebase Hosting config missing
                </h2>
                <p className="mt-2 text-xs text-[#37433B] leading-relaxed">
                  Rebuild <code className="font-mono">admin-web</code> with your{' '}
                  <code className="font-mono">.env.local</code> Firebase keys and deploy with{' '}
                  <code className="font-mono">npx firebase-tools deploy --only hosting</code>.
                </p>
              </div>
            )}
          </div>
        </div>

        {/* Footer */}
        <p className="mt-5 text-center text-[11px] text-[#606D64]">
          Hold High The Shop · Gov. Pack Rd., Baguio City · Exclusive to verified UC students
        </p>
      </div>
    </div>
  )
}
