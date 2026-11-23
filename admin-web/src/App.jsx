import React, { useEffect, useMemo, useState } from 'react'
import { onAuthStateChanged, signInWithEmailAndPassword, signOut } from 'firebase/auth'
import {
  addDoc,
  collection,
  doc,
  getDoc,
  increment,
  onSnapshot,
  orderBy,
  query,
  updateDoc,
} from 'firebase/firestore'
import { auth, db, isFirebaseConfigured } from './firebase'

function formatDate(ms) {
  if (!ms) return '—'
  const d = new Date(typeof ms === 'number' ? ms : ms?.toMillis?.() ?? Date.now())
  return d.toLocaleString('en-PH', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })
}

function MediaThumb({ mediaRef }) {
  const [src, setSrc] = useState(null)

  useEffect(() => {
    let active = true
    if (!mediaRef || !db || !mediaRef.startsWith('media://')) {
      setSrc(null)
      return
    }
    const id = mediaRef.replace('media://', '')
    getDoc(doc(db, 'media', id))
      .then((snap) => {
        if (!active || !snap.exists()) return
        const b64 = snap.data()?.base64Webp
        if (b64) setSrc(`data:image/webp;base64,${b64}`)
      })
      .catch(() => {})
    return () => {
      active = false
    }
  }, [mediaRef])

  if (!src) {
    return (
      <div className="w-16 h-16 rounded-xl bg-[#E4EFEA] flex items-center justify-center text-[11px] font-semibold text-[#07683B] shrink-0">
        No photo
      </div>
    )
  }
  return <img src={src} alt="Item" className="w-16 h-16 rounded-xl object-cover border border-[#E5E0CE] shrink-0" />
}

function StatusPill({ status }) {
  const styles = {
    OPEN: 'bg-[#E4EFEA] text-[#07683B]',
    RESERVED: 'bg-amber-100 text-amber-800',
    ON_LOAN: 'bg-blue-100 text-blue-800',
    PAUSED: 'bg-stone-200 text-stone-700',
    CLOSED: 'bg-stone-200 text-stone-600',
    UNDER_REVIEW: 'bg-red-100 text-red-700',
    REMOVED_BY_ADMIN: 'bg-red-200 text-red-900',
    PENDING_REVIEW: 'bg-amber-100 text-amber-800',
    APPROVED: 'bg-[#E4EFEA] text-[#07683B]',
    RESOLVED: 'bg-[#E4EFEA] text-[#07683B]',
  }
  return (
    <span className={`px-2.5 py-0.5 rounded-full text-[11px] font-semibold ${styles[status] || 'bg-stone-200 text-stone-700'}`}>
      {status || 'UNKNOWN'}
    </span>
  )
}

export default function App() {
  const [authUser, setAuthUser] = useState(null)
  const [authReady, setAuthReady] = useState(false)
  const [isAdmin, setIsAdmin] = useState(false)
  const [loginEmail, setLoginEmail] = useState('')
  const [loginPassword, setLoginPassword] = useState('')
  const [loginError, setLoginError] = useState('')
  const [loginBusy, setLoginBusy] = useState(false)

  const [tab, setTab] = useState('dashboard')
  const [search, setSearch] = useState('')
  const [toast, setToast] = useState('')

  const [users, setUsers] = useState([])
  const [listings, setListings] = useState([])
  const [reports, setReports] = useState([])
  const [modLogs, setModLogs] = useState([])
  const [announcements, setAnnouncements] = useState([])
  const [auditLogs, setAuditLogs] = useState([])
  const [exchangesCount, setExchangesCount] = useState(0)

  const [annTitle, setAnnTitle] = useState('')
  const [annBody, setAnnBody] = useState('')
  const [annSending, setAnnSending] = useState(false)

  function showToast(msg) {
    setToast(msg)
    setTimeout(() => setToast(''), 3200)
  }

  useEffect(() => {
    if (!isFirebaseConfigured || !auth || !db) {
      setAuthReady(true)
      return
    }
    return onAuthStateChanged(auth, async (user) => {
      if (!user) {
        setAuthUser(null)
        setIsAdmin(false)
        setAuthReady(true)
        return
      }
      try {
        const adminSnap = await getDoc(doc(db, 'admins', user.uid))
        if (adminSnap.exists()) {
          setAuthUser(user)
          setIsAdmin(true)
        } else {
          await signOut(auth)
          setAuthUser(null)
          setIsAdmin(false)
          setLoginError('This account is not listed in the Firestore `admins/{uid}` collection.')
        }
      } catch (e) {
        setLoginError('Unable to verify admin role. Check Firestore rules and connection.')
        setIsAdmin(false)
      } finally {
        setAuthReady(true)
      }
    })
  }, [])

  useEffect(() => {
    if (!isAdmin || !db) return
    const unsubs = [
      onSnapshot(collection(db, 'users'), (snap) => {
        setUsers(snap.docs.map((d) => ({ id: d.id, ...d.data() })))
      }),
      onSnapshot(query(collection(db, 'listings'), orderBy('createdAt', 'desc')), (snap) => {
        setListings(snap.docs.map((d) => ({ id: d.id, ...d.data() })))
      }),
      onSnapshot(query(collection(db, 'reports'), orderBy('createdAt', 'desc')), (snap) => {
        setReports(snap.docs.map((d) => ({ id: d.id, ...d.data() })))
      }),
      onSnapshot(query(collection(db, 'moderation_logs'), orderBy('createdAt', 'desc')), (snap) => {
        setModLogs(snap.docs.map((d) => ({ id: d.id, ...d.data() })))
      }),
      onSnapshot(query(collection(db, 'announcements'), orderBy('createdAt', 'desc')), (snap) => {
        setAnnouncements(snap.docs.map((d) => ({ id: d.id, ...d.data() })))
      }),
      onSnapshot(query(collection(db, 'admin_audit_logs'), orderBy('createdAt', 'desc')), (snap) => {
        setAuditLogs(snap.docs.map((d) => ({ id: d.id, ...d.data() })))
      }),
      onSnapshot(collection(db, 'exchanges'), (snap) => {
        setExchangesCount(snap.size)
      }),
    ]
    return () => unsubs.forEach((u) => u())
  }, [isAdmin])

  async function logAudit(action, targetId, details) {
    if (!db || !authUser) return
    await addDoc(collection(db, 'admin_audit_logs'), {
      adminUid: authUser.uid,
      adminEmail: authUser.email,
      action,
      targetId,
      details,
      createdAt: Date.now(),
    })
  }

  async function notifyStudent(recipientUid, title, body, targetType = 'NONE', targetId = '') {
    if (!db || !recipientUid) return
    await addDoc(collection(db, 'notifications'), {
      recipientUid,
      type: 'MODERATION',
      title,
      body,
      targetType,
      targetId,
      read: false,
      createdAt: Date.now(),
    })
  }

  async function handleAdminSignIn(e) {
    e.preventDefault()
    if (!auth) return
    setLoginError('')
    setLoginBusy(true)
    try {
      await signInWithEmailAndPassword(auth, loginEmail.trim(), loginPassword)
    } catch {
      setLoginError('Invalid admin email or password.')
    } finally {
      setLoginBusy(false)
    }
  }

  async function approveListing(listing, reportId = null) {
    await updateDoc(doc(db, 'listings', listing.id), {
      status: 'OPEN',
      aiCheckStatus: 'APPROVED',
    })
    if (reportId) {
      await updateDoc(doc(db, 'reports', reportId), { status: 'RESOLVED', resolution: 'APPROVED_RESTORED' })
    }
    await logAudit('APPROVE_LISTING', listing.id, `Restored "${listing.title}" to OPEN`)
    showToast(`Approved "${listing.title}"`)
  }

  async function removeListing(listing, report = null) {
    const reason = window.prompt(
      `Reason for removing "${listing.title}" (sent to the seller):`,
      'Violates UC Hold High The Shop marketplace guidelines.'
    )
    if (!reason) return
    await updateDoc(doc(db, 'listings', listing.id), { status: 'REMOVED_BY_ADMIN' })
    if (report) {
      await updateDoc(doc(db, 'reports', report.id), { status: 'RESOLVED', resolution: 'REMOVED_BY_ADMIN' })
      if (report.reporterUid) {
        await notifyStudent(
          report.reporterUid,
          'Report reviewed',
          `Thanks for helping keep UC safe. Our moderation team reviewed your report on "${listing.title}" and removed the listing.`
        )
      }
    }
    if (listing.ownerUid) {
      await notifyStudent(
        listing.ownerUid,
        'Listing removed by moderator',
        `Your listing "${listing.title}" was removed. Reason: ${reason}`
      )
    }
    await logAudit('REMOVE_LISTING', listing.id, `Removed "${listing.title}": ${reason}`)
    showToast(`Removed "${listing.title}"`)
  }

  async function warnStudent(student) {
    const note = window.prompt(`Warning message for ${student.name}:`, 'Please follow UC campus marketplace guidelines.')
    if (!note) return
    await updateDoc(doc(db, 'users', student.id), { warningCount: increment(1) })
    await notifyStudent(student.id, 'Official campus warning', note)
    await logAudit('WARN_STUDENT', student.id, `Warned ${student.email}: ${note}`)
    showToast(`Sent warning to ${student.name}`)
  }

  async function toggleSuspendStudent(student) {
    if (student.suspended) {
      await updateDoc(doc(db, 'users', student.id), { suspended: false, suspendedReason: '' })
      await notifyStudent(student.id, 'Account reinstated', 'Your Hold High The Shop student account is active again.')
      await logAudit('REINSTATE_STUDENT', student.id, `Reinstated ${student.email}`)
      showToast(`Reinstated ${student.name}`)
    } else {
      const reason = window.prompt(
        `Suspension reason for ${student.name}:`,
        'Repeated violations of UC Hold High The Shop guidelines.'
      )
      if (!reason) return
      await updateDoc(doc(db, 'users', student.id), { suspended: true, suspendedReason: reason })
      await logAudit('SUSPEND_STUDENT', student.id, `Suspended ${student.email}: ${reason}`)
      showToast(`Suspended ${student.name}`)
    }
  }

  async function publishAnnouncement(e) {
    e.preventDefault()
    if (!annTitle.trim() || !annBody.trim() || !db) return
    setAnnSending(true)
    try {
      const ref = await addDoc(collection(db, 'announcements'), {
        title: annTitle.trim(),
        body: annBody.trim(),
        authorEmail: authUser.email,
        createdAt: Date.now(),
      })
      await logAudit('PUBLISH_ANNOUNCEMENT', ref.id, `Published "${annTitle.trim()}"`)
      setAnnTitle('')
      setAnnBody('')
      showToast('Campus announcement published live to all students!')
    } finally {
      setAnnSending(false)
    }
  }

  const flaggedListings = useMemo(
    () => listings.filter((l) => l.status === 'UNDER_REVIEW' || l.aiCheckStatus === 'PENDING_REVIEW'),
    [listings]
  )
  const openReports = useMemo(() => reports.filter((r) => r.status !== 'RESOLVED'), [reports])
  const listingsById = useMemo(() => Object.fromEntries(listings.map((l) => [l.id, l])), [listings])

  if (!isFirebaseConfigured) {
    return (
      <div className="min-h-screen flex items-center justify-center p-6">
        <div className="max-w-lg w-full bg-white rounded-3xl shadow-sm border border-[#E5E0CE] p-8">
          <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-[#E4EFEA] text-[#07683B] text-xs font-semibold mb-4">
            HOLD HIGH THE SHOP • ADMIN PORTAL
          </div>
          <h1 className="text-2xl font-bold text-[#141A16]">Connect Firebase Spark First</h1>
          <p className="text-sm text-[#5F6B62] mt-2 leading-relaxed">
            Copy <code className="bg-[#F9F6E7] px-1.5 py-0.5 rounded">admin-web/.env.example</code> to{' '}
            <code className="bg-[#F9F6E7] px-1.5 py-0.5 rounded">admin-web/.env.local</code> and paste your free
            Firebase Web App config keys (see <code className="bg-[#F9F6E7] px-1.5 py-0.5 rounded">docs/FIREBASE_SETUP.md</code>).
          </p>
        </div>
      </div>
    )
  }

  if (!authReady) {
    return (
      <div className="min-h-screen flex items-center justify-center text-[#07683B] font-semibold">
        Loading Hold High The Shop Admin Portal…
      </div>
    )
  }

  if (!isAdmin) {
    return (
      <div className="min-h-screen flex items-center justify-center p-6">
        <form
          onSubmit={handleAdminSignIn}
          className="max-w-md w-full bg-white rounded-3xl shadow-sm border border-[#E5E0CE] p-8 space-y-5"
        >
          <div className="flex items-center gap-3">
            <div className="w-12 h-12 rounded-2xl bg-[#07683B] text-white font-bold flex items-center justify-center text-lg">
              UC
            </div>
            <div>
              <p className="text-[11px] font-semibold tracking-wider text-[#07683B] uppercase">Hold High The Shop</p>
              <h1 className="text-xl font-bold text-[#141A16]">Moderator Portal</h1>
            </div>
          </div>
          <p className="text-xs text-[#5F6B62]">
            Sign in with an authorized campus administrator account registered in Firestore{' '}
            <code className="bg-[#F9F6E7] px-1 rounded">admins/{'{uid}'}</code>.
          </p>
          <div className="space-y-3">
            <input
              type="email"
              required
              placeholder="Admin email"
              value={loginEmail}
              onChange={(e) => setLoginEmail(e.target.value)}
              className="w-full px-4 py-3 rounded-xl border border-[#DFD8C5] bg-[#F9F6E7]/50 text-sm focus:outline-none focus:border-[#07683B]"
            />
            <input
              type="password"
              required
              placeholder="Password"
              value={loginPassword}
              onChange={(e) => setLoginPassword(e.target.value)}
              className="w-full px-4 py-3 rounded-xl border border-[#DFD8C5] bg-[#F9F6E7]/50 text-sm focus:outline-none focus:border-[#07683B]"
            />
          </div>
          {loginError && <p className="text-xs font-medium text-red-600">{loginError}</p>}
          <button
            type="submit"
            disabled={loginBusy}
            className="w-full py-3 rounded-xl bg-[#07683B] text-white font-semibold text-sm hover:bg-[#05522E] transition disabled:opacity-50"
          >
            {loginBusy ? 'Verifying…' : 'Sign in to Admin Portal'}
          </button>
        </form>
      </div>
    )
  }

  const navItems = [
    { id: 'dashboard', label: 'Overview' },
    { id: 'moderation', label: `Moderation (${openReports.length + flaggedListings.length})` },
    { id: 'ai_logs', label: `AI Safety Log (${modLogs.length})` },
    { id: 'listings', label: `Listings (${listings.length})` },
    { id: 'users', label: `Students (${users.length})` },
    { id: 'announcements', label: 'Announcements' },
    { id: 'audit', label: 'Audit Trail' },
  ]

  const q = search.trim().toLowerCase()

  return (
    <div className="min-h-screen flex flex-col">
      {/* Top Bar */}
      <header className="bg-[#07683B] text-white px-6 py-4 flex flex-wrap items-center justify-between gap-4 shadow-sm">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-white/15 font-bold flex items-center justify-center">UC</div>
          <div>
            <h1 className="font-bold text-lg leading-tight">Hold High The Shop — Admin Portal</h1>
            <p className="text-xs text-white/75">University of the Cordilleras • Campus Moderation & Safety</p>
          </div>
        </div>
        <div className="flex items-center gap-3">
          <span className="text-xs bg-white/15 px-3 py-1.5 rounded-full">{authUser?.email}</span>
          <button
            onClick={() => signOut(auth)}
            className="text-xs font-semibold bg-white text-[#07683B] px-4 py-1.5 rounded-full hover:bg-[#E4EFEA]"
          >
            Sign out
          </button>
        </div>
      </header>

      {/* Navigation Tabs */}
      <nav className="bg-white border-b border-[#E5E0CE] px-6 flex gap-2 overflow-x-auto">
        {navItems.map((item) => (
          <button
            key={item.id}
            onClick={() => setTab(item.id)}
            className={`px-4 py-3.5 text-xs font-semibold border-b-2 whitespace-nowrap transition ${
              tab === item.id
                ? 'border-[#07683B] text-[#07683B]'
                : 'border-transparent text-[#5F6B62] hover:text-[#141A16]'
            }`}
          >
            {item.label}
          </button>
        ))}
      </nav>

      {toast && (
        <div className="fixed bottom-6 right-6 bg-[#141A16] text-white text-xs font-medium px-4 py-3 rounded-xl shadow-lg z-50">
          {toast}
        </div>
      )}

      <main className="max-w-6xl w-full mx-auto p-6 space-y-6">
        {tab === 'dashboard' && (
          <div className="space-y-6">
            <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4">
              {[
                { label: 'UC Students', value: users.length, accent: 'text-[#07683B]' },
                { label: 'Open Listings', value: listings.filter((l) => l.status === 'OPEN').length, accent: 'text-[#07683B]' },
                { label: 'Open Reports', value: openReports.length, accent: 'text-red-600' },
                { label: 'Review Queue', value: flaggedListings.length, accent: 'text-amber-700' },
                { label: 'AI Blocked', value: modLogs.length, accent: 'text-red-700' },
                { label: 'Completed Deals', value: exchangesCount, accent: 'text-[#07683B]' },
              ].map((kpi) => (
                <div key={kpi.label} className="bg-white rounded-2xl p-4 border border-[#E5E0CE]">
                  <p className="text-[11px] font-medium text-[#5F6B62]">{kpi.label}</p>
                  <p className={`text-2xl font-bold mt-1 ${kpi.accent}`}>{kpi.value}</p>
                </div>
              ))}
            </div>

            <div className="bg-white rounded-2xl border border-[#E5E0CE] p-6">
              <h2 className="font-bold text-base mb-1">Campus Safety Health</h2>
              <p className="text-xs text-[#5F6B62] mb-4">
                Listings auto-escalate to <span className="font-semibold text-red-600">UNDER_REVIEW</span> at 3 distinct
                student reports, and any listing posted while Gemini AI was unreachable is tagged{' '}
                <span className="font-semibold text-amber-700">PENDING_REVIEW</span>.
              </p>
              <button
                onClick={() => setTab('moderation')}
                className="px-4 py-2 rounded-xl bg-[#07683B] text-white text-xs font-semibold hover:bg-[#05522E]"
              >
                Open Moderation Queue ({openReports.length + flaggedListings.length})
              </button>
            </div>
          </div>
        )}

        {tab === 'moderation' && (
          <div className="space-y-6">
            <section className="bg-white rounded-2xl border border-[#E5E0CE] p-6">
              <h2 className="font-bold text-base mb-4">Flagged &amp; Pending-AI Listings ({flaggedListings.length})</h2>
              {flaggedListings.length === 0 ? (
                <p className="text-xs text-[#5F6B62]">All clear! No listings currently waiting for moderation review.</p>
              ) : (
                <div className="space-y-3">
                  {flaggedListings.map((l) => (
                    <div key={l.id} className="flex flex-wrap items-center gap-4 p-4 rounded-xl bg-[#F9F6E7]/60 border border-[#E5E0CE]">
                      <MediaThumb mediaRef={l.photoUris?.[0]} />
                      <div className="flex-1 min-w-[200px]">
                        <div className="flex items-center gap-2 flex-wrap">
                          <span className="font-bold text-sm">{l.title}</span>
                          <StatusPill status={l.status} />
                          <StatusPill status={l.aiCheckStatus} />
                        </div>
                        <p className="text-xs text-[#5F6B62] mt-1">
                          Seller: {l.sellerName} • Reports: {l.reportCount || 0} • ₱{l.price}
                        </p>
                        <p className="text-xs text-[#141A16] mt-1 line-clamp-2">{l.description}</p>
                      </div>
                      <div className="flex gap-2">
                        <button
                          onClick={() => approveListing(l)}
                          className="px-3.5 py-2 rounded-xl bg-[#07683B] text-white text-xs font-semibold"
                        >
                          Approve &amp; Restore
                        </button>
                        <button
                          onClick={() => removeListing(l)}
                          className="px-3.5 py-2 rounded-xl bg-red-600 text-white text-xs font-semibold"
                        >
                          Remove Listing
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </section>

            <section className="bg-white rounded-2xl border border-[#E5E0CE] p-6">
              <h2 className="font-bold text-base mb-4">Student Reports ({reports.length})</h2>
              {reports.length === 0 ? (
                <p className="text-xs text-[#5F6B62]">No student reports submitted yet.</p>
              ) : (
                <div className="space-y-3">
                  {reports.map((r) => {
                    const targetListing = listingsById[r.listingId]
                    return (
                      <div key={r.id} className="p-4 rounded-xl border border-[#E5E0CE] flex flex-wrap items-center justify-between gap-4">
                        <div className="space-y-1">
                          <div className="flex items-center gap-2">
                            <span className="font-bold text-xs text-[#07683B]">{r.ref || r.id}</span>
                            <StatusPill status={r.status || 'OPEN'} />
                            {r.autoEscalated && <StatusPill status="UNDER_REVIEW" />}
                          </div>
                          <p className="text-sm font-semibold">
                            Subject: {targetListing ? targetListing.title : r.subject}
                          </p>
                          <p className="text-xs text-[#5F6B62]">
                            Reported by {r.reporterName || r.reporterEmail} • {formatDate(r.createdAt)}
                          </p>
                          {r.details && <p className="text-xs bg-[#F9F6E7] p-2 rounded-lg">“{r.details}”</p>}
                        </div>
                        {targetListing && r.status !== 'RESOLVED' && (
                          <div className="flex gap-2">
                            <button
                              onClick={() => approveListing(targetListing, r.id)}
                              className="px-3 py-2 rounded-xl border border-[#07683B] text-[#07683B] text-xs font-semibold"
                            >
                              Dismiss &amp; Keep
                            </button>
                            <button
                              onClick={() => removeListing(targetListing, r)}
                              className="px-3 py-2 rounded-xl bg-red-600 text-white text-xs font-semibold"
                            >
                              Remove Listing
                            </button>
                          </div>
                        )}
                      </div>
                    )
                  })}
                </div>
              )}
            </section>
          </div>
        )}

        {tab === 'ai_logs' && (
          <section className="bg-white rounded-2xl border border-[#E5E0CE] p-6">
            <h2 className="font-bold text-base mb-1">Hybrid Safety Gate — Blocked Attempts</h2>
            <p className="text-xs text-[#5F6B62] mb-4">
              Every listing blocked by Local Safety Filter or Google Gemini 2.5 Flash multimodal inspection.
            </p>
            {modLogs.length === 0 ? (
              <p className="text-xs text-[#5F6B62]">No blocked listing attempts logged yet.</p>
            ) : (
              <div className="space-y-3">
                {modLogs.map((m) => (
                  <div key={m.id} className="p-4 rounded-xl bg-red-50/60 border border-red-200">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <span className="font-bold text-sm text-red-900">{m.title}</span>
                      <span className="text-[11px] font-semibold px-2 py-0.5 rounded-full bg-red-100 text-red-800">
                        {m.source} • {m.category}
                      </span>
                    </div>
                    <p className="text-xs text-red-800 mt-1">{m.reason}</p>
                    <p className="text-[11px] text-[#5F6B62] mt-1">
                      {m.policySection} • Student: {m.userEmail || m.uid} • {formatDate(m.createdAt)}
                    </p>
                  </div>
                ))}
              </div>
            )}
          </section>
        )}

        {tab === 'listings' && (
          <section className="bg-white rounded-2xl border border-[#E5E0CE] p-6 space-y-4">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <h2 className="font-bold text-base">All Campus Listings</h2>
              <input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search title, seller, college…"
                className="px-4 py-2 rounded-xl border border-[#DFD8C5] text-xs w-64"
              />
            </div>
            <div className="space-y-3">
              {listings
                .filter(
                  (l) =>
                    !q ||
                    l.title?.toLowerCase().includes(q) ||
                    l.sellerName?.toLowerCase().includes(q) ||
                    l.program?.toLowerCase().includes(q)
                )
                .map((l) => (
                  <div key={l.id} className="flex flex-wrap items-center gap-4 p-3 rounded-xl border border-[#E5E0CE]">
                    <MediaThumb mediaRef={l.photoUris?.[0]} />
                    <div className="flex-1 min-w-[200px]">
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className="font-semibold text-sm">{l.title}</span>
                        <StatusPill status={l.status} />
                      </div>
                      <p className="text-xs text-[#5F6B62]">
                        {l.sellerName} • {l.targetPrograms?.length ? l.targetPrograms.join(', ') : 'All colleges'} •{' '}
                        {l.type === 2 ? 'Swap' : l.price === 0 ? 'FREE' : `₱${l.price}${l.type === 1 ? '/day' : ''}`}
                      </p>
                    </div>
                    {l.status !== 'REMOVED_BY_ADMIN' ? (
                      <button
                        onClick={() => removeListing(l)}
                        className="px-3 py-1.5 rounded-lg bg-red-50 text-red-700 border border-red-200 text-xs font-semibold"
                      >
                        Remove
                      </button>
                    ) : (
                      <button
                        onClick={() => approveListing(l)}
                        className="px-3 py-1.5 rounded-lg bg-[#E4EFEA] text-[#07683B] text-xs font-semibold"
                      >
                        Restore
                      </button>
                    )}
                  </div>
                ))}
            </div>
          </section>
        )}

        {tab === 'users' && (
          <section className="bg-white rounded-2xl border border-[#E5E0CE] p-6 space-y-4">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <h2 className="font-bold text-base">Verified UC Students</h2>
              <input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search name, email, college…"
                className="px-4 py-2 rounded-xl border border-[#DFD8C5] text-xs w-64"
              />
            </div>
            <div className="divide-y divide-[#E5E0CE]">
              {users
                .filter(
                  (u) =>
                    !q ||
                    u.name?.toLowerCase().includes(q) ||
                    u.email?.toLowerCase().includes(q) ||
                    u.program?.toLowerCase().includes(q)
                )
                .map((u) => (
                  <div key={u.id} className="py-3 flex flex-wrap items-center justify-between gap-4">
                    <div>
                      <div className="flex items-center gap-2">
                        <span className="font-semibold text-sm">{u.name}</span>
                        {u.suspended && <StatusPill status="REMOVED_BY_ADMIN" />}
                        {(u.warningCount || 0) > 0 && (
                          <span className="text-[11px] bg-amber-100 text-amber-800 px-2 py-0.5 rounded-full font-semibold">
                            {u.warningCount} warning(s)
                          </span>
                        )}
                      </div>
                      <p className="text-xs text-[#5F6B62]">
                        {u.email} • {u.program} • {u.year}
                      </p>
                      {u.suspended && u.suspendedReason && (
                        <p className="text-xs text-red-600 mt-0.5">Suspended: {u.suspendedReason}</p>
                      )}
                    </div>
                    <div className="flex gap-2">
                      <button
                        onClick={() => warnStudent(u)}
                        className="px-3 py-1.5 rounded-lg border border-amber-400 text-amber-800 text-xs font-semibold"
                      >
                        Warn
                      </button>
                      <button
                        onClick={() => toggleSuspendStudent(u)}
                        className={`px-3 py-1.5 rounded-lg text-xs font-semibold ${
                          u.suspended
                            ? 'bg-[#07683B] text-white'
                            : 'bg-red-600 text-white'
                        }`}
                      >
                        {u.suspended ? 'Reinstate' : 'Suspend'}
                      </button>
                    </div>
                  </div>
                ))}
            </div>
          </section>
        )}

        {tab === 'announcements' && (
          <div className="grid md:grid-cols-2 gap-6">
            <form onSubmit={publishAnnouncement} className="bg-white rounded-2xl border border-[#E5E0CE] p-6 space-y-4">
              <h2 className="font-bold text-base">Publish Campus Announcement</h2>
              <p className="text-xs text-[#5F6B62]">
                Appears live inside every student’s Android Notifications tab and pushes an Android OS alert.
              </p>
              <input
                required
                maxLength={80}
                placeholder="Announcement title (e.g. Finals Week Meetup Hours)"
                value={annTitle}
                onChange={(e) => setAnnTitle(e.target.value)}
                className="w-full px-4 py-3 rounded-xl border border-[#DFD8C5] text-sm"
              />
              <textarea
                required
                rows={4}
                maxLength={400}
                placeholder="Write a clear message for UC students…"
                value={annBody}
                onChange={(e) => setAnnBody(e.target.value)}
                className="w-full px-4 py-3 rounded-xl border border-[#DFD8C5] text-sm"
              />
              <button
                type="submit"
                disabled={annSending}
                className="px-5 py-3 rounded-xl bg-[#07683B] text-white text-xs font-semibold hover:bg-[#05522E] disabled:opacity-50"
              >
                {annSending ? 'Publishing…' : 'Broadcast to All UC Students'}
              </button>
            </form>

            <div className="bg-white rounded-2xl border border-[#E5E0CE] p-6 space-y-3">
              <h2 className="font-bold text-base">Published Announcements ({announcements.length})</h2>
              {announcements.length === 0 ? (
                <p className="text-xs text-[#5F6B62]">No announcements published yet.</p>
              ) : (
                announcements.map((a) => (
                  <div key={a.id} className="p-3.5 rounded-xl bg-[#F9F6E7] border border-[#E5E0CE]">
                    <p className="font-semibold text-sm text-[#07683B]">{a.title}</p>
                    <p className="text-xs text-[#141A16] mt-1">{a.body}</p>
                    <p className="text-[10px] text-[#5F6B62] mt-1.5">
                      {a.authorEmail} • {formatDate(a.createdAt)}
                    </p>
                  </div>
                ))
              )}
            </div>
          </div>
        )}

        {tab === 'audit' && (
          <section className="bg-white rounded-2xl border border-[#E5E0CE] p-6 space-y-3">
            <h2 className="font-bold text-base">Immutable Admin Audit Trail</h2>
            <p className="text-xs text-[#5F6B62]">
              Protected by Firestore Security Rules — audit entries are append-only and cannot be edited or deleted.
            </p>
            {auditLogs.length === 0 ? (
              <p className="text-xs text-[#5F6B62]">No moderation actions recorded yet.</p>
            ) : (
              <div className="divide-y divide-[#E5E0CE]">
                {auditLogs.map((log) => (
                  <div key={log.id} className="py-3 flex flex-wrap items-center justify-between gap-2 text-xs">
                    <div>
                      <span className="font-bold text-[#07683B] mr-2">{log.action}</span>
                      <span>{log.details}</span>
                    </div>
                    <span className="text-[#5F6B62]">
                      {log.adminEmail} • {formatDate(log.createdAt)}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </section>
        )}
      </main>
    </div>
  )
}
