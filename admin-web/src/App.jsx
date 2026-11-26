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
import {
  AlertTriangle,
  ArrowUpRight,
  Ban,
  Bell,
  CheckCircle2,
  ChevronRight,
  Clock,
  Eye,
  FileText,
  Filter,
  History,
  LayoutDashboard,
  LogOut,
  Mail,
  MapPin,
  Megaphone,
  Menu,
  Package,
  RefreshCw,
  Search,
  Send,
  ShieldAlert,
  ShieldCheck,
  Sparkles,
  Trash2,
  Users,
  X,
} from 'lucide-react'
import { auth, db, isFirebaseConfigured } from './firebase'
import {
  buildAccountReinstatedEmail,
  buildAccountSuspendedEmail,
  buildListingRemovedEmail,
  buildOfficialWarningEmail,
  buildVerificationButtonEmail,
  isEmailWebhookConfigured,
  sendCampusHtmlEmail,
} from './emailService'

const UC_COLLEGES = [
  'CITCS',
  'COE',
  'CBA',
  'CONAHS',
  'CTE',
  'CCJE',
  'CAS',
  'CHTM',
  'COA',
  'CAFA',
  'COL',
]

const REMOVAL_PRESETS = [
  'Prohibited or unsafe campus item (UC Guidelines §3)',
  'Misleading photos, condition, or pricing details',
  'Duplicate or already sold listing',
  'Inappropriate or offensive content',
]

const WARNING_PRESETS = [
  'Please ensure all listings strictly follow UC Hold High The Shop guidelines.',
  'Repeated no-shows or late cancellations affect fellow UC students.',
  'Do not post prohibited items or misleading descriptions on Campus Market.',
]

const SUSPENSION_PRESETS = [
  'Repeated violations of UC Hold High The Shop community & safety guidelines.',
  'Attempting to list prohibited or unsafe items on campus.',
  'Fraudulent activity or harassment reported by verified UC students.',
]

const DEMO_USERS = [
  {
    id: 'demo_u1',
    uid: 'demo_u1',
    name: 'Paolo Mendoza',
    email: 'paolo.mendoza@students.uc-bcf.edu.ph',
    program: 'CITCS',
    year: '3rd Year',
    emailVerified: true,
    suspended: false,
    warningCount: 0,
    createdAt: Date.now() - 86400000 * 14,
  },
  {
    id: 'demo_u2',
    uid: 'demo_u2',
    name: 'Janelle Garcia',
    email: 'janelle.garcia@students.uc-bcf.edu.ph',
    program: 'CONAHS',
    year: '2nd Year',
    emailVerified: true,
    suspended: false,
    warningCount: 1,
    createdAt: Date.now() - 86400000 * 9,
  },
  {
    id: 'demo_u3',
    uid: 'demo_u3',
    name: 'Miguel Santos',
    email: 'miguel.santos@students.uc-bcf.edu.ph',
    program: 'COE',
    year: '4th Year',
    emailVerified: true,
    suspended: false,
    warningCount: 0,
    createdAt: Date.now() - 86400000 * 6,
  },
  {
    id: 'demo_u4',
    uid: 'demo_u4',
    name: 'Carlo Reyes',
    email: 'carlo.reyes@students.uc-bcf.edu.ph',
    program: 'CBA',
    year: '1st Year',
    emailVerified: false,
    suspended: false,
    warningCount: 0,
    createdAt: Date.now() - 3600000 * 5,
  },
]

const DEMO_LISTINGS = [
  {
    id: 'demo_l1',
    title: 'Casio fx-991EX Scientific Calculator',
    price: 950,
    type: 0,
    condition: 0,
    program: 'CITCS',
    targetPrograms: ['CITCS', 'COE'],
    sellerName: 'Paolo Mendoza',
    ownerUid: 'demo_u1',
    meetup: 'UC Library Lobby',
    meetupNote: '2nd floor entrance desk',
    description: 'Original Casio ClassWiz calculator with slide-on hard cover. Used for two terms.',
    status: 'OPEN',
    aiCheckStatus: 'APPROVED',
    reportCount: 0,
    photoUris: [],
    createdAt: Date.now() - 3600000 * 4,
  },
  {
    id: 'demo_l2',
    title: 'Clinical White Lab Gown (Medium)',
    price: 45,
    type: 1,
    condition: 1,
    program: 'CONAHS',
    targetPrograms: ['CONAHS'],
    sellerName: 'Janelle Garcia',
    ownerUid: 'demo_u2',
    meetup: 'UC Main Gate Security Desk',
    meetupNote: '',
    description: 'Clean ironed lab gown available for daily borrowing during lab practicals.',
    status: 'OPEN',
    aiCheckStatus: 'PENDING_REVIEW',
    reportCount: 0,
    photoUris: [],
    createdAt: Date.now() - 3600000 * 9,
  },
  {
    id: 'demo_l3',
    title: 'Mystery Campus Box Bundle',
    price: 500,
    type: 0,
    condition: 2,
    program: 'All colleges',
    targetPrograms: [],
    sellerName: 'Miguel Santos',
    ownerUid: 'demo_u3',
    meetup: 'UC Student Center',
    meetupNote: '',
    description: 'Assorted unlabelled items in a sealed box, no returns.',
    status: 'UNDER_REVIEW',
    aiCheckStatus: 'PENDING_REVIEW',
    reportCount: 3,
    photoUris: [],
    createdAt: Date.now() - 3600000 * 16,
  },
]

const DEMO_REPORTS = [
  {
    id: 'demo_r1',
    ref: 'RPT-48219',
    listingId: 'demo_l3',
    subject: 'Mystery Campus Box Bundle',
    reporterName: 'Paolo Mendoza',
    reporterEmail: 'paolo.mendoza@students.uc-bcf.edu.ph',
    reporterUid: 'demo_u1',
    details: 'Seller refuses to show what is inside the sealed box.',
    status: 'OPEN',
    autoEscalated: true,
    createdAt: Date.now() - 3600000 * 3,
  },
]

const DEMO_MOD_LOGS = [
  {
    id: 'demo_m1',
    title: 'Disposable Pod Mint 5%',
    category: 'ALCOHOL_TOBACCO_VAPE',
    policySection: 'UC Marketplace Guidelines §3.2 — Vapes & Tobacco',
    reason: 'Vape devices, pods, and e-liquids are strictly prohibited on UC campus premises.',
    source: 'GEMINI_AI',
    userEmail: 'carlo.reyes@students.uc-bcf.edu.ph',
    createdAt: Date.now() - 3600000 * 11,
  },
]

function formatDate(ms) {
  if (!ms) return '—'
  const d = new Date(typeof ms === 'number' ? ms : ms?.toMillis?.() ?? Date.now())
  return d.toLocaleString('en-PH', {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  })
}

function formatPrice(listing) {
  if (!listing) return '—'
  if (listing.type === 2) return 'Swap'
  if (!listing.price || listing.price === 0) return 'FREE'
  return `₱${Number(listing.price).toLocaleString('en-PH')}${listing.type === 1 ? ' / day' : ''}`
}

function listingTypeLabel(type) {
  if (type === 1) return 'Lend'
  if (type === 2) return 'Swap'
  return 'Sell'
}

function conditionLabel(cond) {
  if (cond === 0) return 'Like New'
  if (cond === 1) return 'Good'
  return 'Fair'
}

function MediaThumb({ mediaRef, size = 'md' }) {
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

  const dims =
    size === 'lg'
      ? 'w-full h-56 rounded-2xl'
      : size === 'sm'
      ? 'w-11 h-11 rounded-xl'
      : 'w-14 h-14 rounded-xl'

  if (!src) {
    return (
      <div
        className={`${dims} bg-[#E8EFEA] border border-[#D7E4DC] flex flex-col items-center justify-center text-[#07683B] shrink-0`}
      >
        <Package className={size === 'lg' ? 'w-7 h-7 opacity-60' : 'w-4 h-4 opacity-70'} />
        {size === 'lg' && <span className="text-xs font-medium mt-1.5 opacity-75">No photo preview</span>}
      </div>
    )
  }
  return <img src={src} alt="Listing item" className={`${dims} object-cover border border-[#E3DECB] shrink-0`} />
}

function StatusPill({ status }) {
  const styles = {
    OPEN: 'bg-[#E4EFEA] text-[#07683B] border-[#C4DDD0]',
    RESERVED: 'bg-[#FFF6E3] text-[#8A5A00] border-[#F0DCAC]',
    ON_LOAN: 'bg-sky-50 text-sky-800 border-sky-200',
    PAUSED: 'bg-stone-100 text-stone-600 border-stone-200',
    CLOSED: 'bg-stone-100 text-stone-600 border-stone-200',
    UNDER_REVIEW: 'bg-[#FDECEA] text-[#B91C1C] border-[#F5C6C2]',
    REMOVED_BY_ADMIN: 'bg-red-950/10 text-red-900 border-red-300',
    PENDING_REVIEW: 'bg-[#FFF6E3] text-[#8A5A00] border-[#F0DCAC]',
    APPROVED: 'bg-[#E4EFEA] text-[#07683B] border-[#C4DDD0]',
    REJECTED: 'bg-[#FDECEA] text-[#B91C1C] border-[#F5C6C2]',
    RESOLVED: 'bg-[#E4EFEA] text-[#07683B] border-[#C4DDD0]',
    VERIFIED: 'bg-[#E4EFEA] text-[#07683B] border-[#C4DDD0]',
    UNVERIFIED: 'bg-[#FFF6E3] text-[#8A5A00] border-[#F0DCAC]',
    SUSPENDED: 'bg-[#FDECEA] text-[#B91C1C] border-[#F5C6C2]',
  }
  const labels = {
    OPEN: 'Open',
    RESERVED: 'Reserved',
    ON_LOAN: 'On Loan',
    PAUSED: 'Paused',
    CLOSED: 'Closed',
    UNDER_REVIEW: 'Under Review',
    REMOVED_BY_ADMIN: 'Removed',
    PENDING_REVIEW: 'Pending AI',
    APPROVED: 'AI Approved',
    REJECTED: 'Rejected',
    RESOLVED: 'Resolved',
    VERIFIED: 'Verified',
    UNVERIFIED: 'Code Pending',
    SUSPENDED: 'Suspended',
  }
  return (
    <span
      className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-[11px] font-semibold border ${
        styles[status] || 'bg-stone-100 text-stone-700 border-stone-200'
      }`}
    >
      {labels[status] || status || 'Unknown'}
    </span>
  )
}

export default function App() {
  const [demoMode, setDemoMode] = useState(() => {
    if (typeof window === 'undefined') return false
    if (window.__HHTS_AUTO_PREVIEW__) return true
    const params = new URLSearchParams(window.location.search)
    return params.get('preview') === '1'
  })
  const [authUser, setAuthUser] = useState(null)
  const [authReady, setAuthReady] = useState(false)
  const [isAdmin, setIsAdmin] = useState(false)
  const [loginEmail, setLoginEmail] = useState('')
  const [loginPassword, setLoginPassword] = useState('')
  const [loginError, setLoginError] = useState('')
  const [loginBusy, setLoginBusy] = useState(false)

  const [tab, setTab] = useState('dashboard')
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false)
  const [search, setSearch] = useState('')
  const [toast, setToast] = useState(null)

  // View-specific subfilters
  const [modFilter, setModFilter] = useState('all') // all | flagged | pending_ai | reports | resolved
  const [listingFilter, setListingFilter] = useState('all') // all | OPEN | ACTIVE_DEAL | UNDER_REVIEW | REMOVED_BY_ADMIN
  const [collegeFilter, setCollegeFilter] = useState('ALL')
  const [studentFilter, setStudentFilter] = useState('all') // all | verified | unverified | warned | suspended
  const [emailPreviewType, setEmailPreviewType] = useState('verification')
  const [testEmailTo, setTestEmailTo] = useState('')
  const [sendingTestEmail, setSendingTestEmail] = useState(false)

  // Firestore collections (or Demo state)
  const [users, setUsers] = useState([])
  const [listings, setListings] = useState([])
  const [reports, setReports] = useState([])
  const [modLogs, setModLogs] = useState([])
  const [announcements, setAnnouncements] = useState([])
  const [auditLogs, setAuditLogs] = useState([])
  const [exchangesCount, setExchangesCount] = useState(0)

  // Announcement composer
  const [annTitle, setAnnTitle] = useState('')
  const [annBody, setAnnBody] = useState('')
  const [annSending, setAnnSending] = useState(false)

  // Detail Drawer & Moderation Action Modal
  const [inspectedListingId, setInspectedListingId] = useState(null)
  const [inspectedPhotoIdx, setInspectedPhotoIdx] = useState(0)
  const [actionModal, setActionModal] = useState(null)
  // actionModal shape: { kind: 'remove_listing' | 'warn_student' | 'suspend_student', listing?, report?, student?, input: '', busy: false }

  function showToast(message, tone = 'default') {
    setToast({ message, tone })
    setTimeout(() => {
      setToast((prev) => (prev?.message === message ? null : prev))
    }, 3600)
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
          setLoginError('This account is not authorized in Firestore `admins/{uid}`.')
        }
      } catch {
        setLoginError('Unable to verify moderator role. Check Firestore rules and connection.')
        setIsAdmin(false)
      } finally {
        setAuthReady(true)
      }
    })
  }, [])

  useEffect(() => {
    if (demoMode) {
      setUsers(DEMO_USERS)
      setListings(DEMO_LISTINGS)
      setReports(DEMO_REPORTS)
      setModLogs(DEMO_MOD_LOGS)
      setAnnouncements([
        {
          id: 'demo_a1',
          title: 'Safe Campus Meetup Desks Open 8 AM – 6 PM',
          body: 'Meet fellow UCians at the Main Gate Security Desk, Library Lobby, or Student Center for verified handovers.',
          authorEmail: 'moderator@students.uc-bcf.edu.ph',
          createdAt: Date.now() - 86400000 * 2,
        },
      ])
      setAuditLogs([
        {
          id: 'demo_aud1',
          action: 'PUBLISH_ANNOUNCEMENT',
          details: 'Published "Safe Campus Meetup Desks Open 8 AM – 6 PM"',
          adminEmail: 'moderator@students.uc-bcf.edu.ph',
          createdAt: Date.now() - 86400000 * 2,
        },
      ])
      setExchangesCount(28)
      return
    }

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
  }, [isAdmin, demoMode])

  useEffect(() => {
    function handleKeyDown(e) {
      if (e.key !== 'Escape') return
      if (actionModal && !actionModal.busy) {
        setActionModal(null)
      } else if (inspectedListingId) {
        setInspectedListingId(null)
      } else if (mobileMenuOpen) {
        setMobileMenuOpen(false)
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [actionModal, inspectedListingId, mobileMenuOpen])

  const usersById = useMemo(() => Object.fromEntries(users.map((u) => [u.id, u])), [users])
  const listingsById = useMemo(() => Object.fromEntries(listings.map((l) => [l.id, l])), [listings])
  const inspectedListing = inspectedListingId ? listingsById[inspectedListingId] || null : null

  /**
   * CRITICAL FIX:
   * Strictly exclude REMOVED_BY_ADMIN and CLOSED listings so that once a moderator
   * removes an item (even if its aiCheckStatus was previously PENDING_REVIEW), it
   * immediately leaves the Moderation Queue without requiring "Approve"!
   */
  const flaggedListings = useMemo(
    () =>
      listings.filter(
        (l) =>
          l.status !== 'REMOVED_BY_ADMIN' &&
          l.status !== 'CLOSED' &&
          (l.status === 'UNDER_REVIEW' || l.aiCheckStatus === 'PENDING_REVIEW')
      ),
    [listings]
  )

  const openReports = useMemo(
    () =>
      reports.filter((r) => {
        if (r.status === 'RESOLVED') return false
        const target = listingsById[r.listingId]
        if (target && target.status === 'REMOVED_BY_ADMIN') return false
        return true
      }),
    [reports, listingsById]
  )

  const resolvedReports = useMemo(
    () =>
      reports.filter((r) => {
        if (r.status === 'RESOLVED') return true
        const target = listingsById[r.listingId]
        return Boolean(target && target.status === 'REMOVED_BY_ADMIN')
      }),
    [reports, listingsById]
  )

  // Distinct actionable count so a listing with both UNDER_REVIEW and an open report isn't confusingly stuck
  const moderationActionCount = useMemo(() => {
    const ids = new Set(flaggedListings.map((l) => `L:${l.id}`))
    openReports.forEach((r) => ids.add(`R:${r.id}`))
    return ids.size
  }, [flaggedListings, openReports])

  const collegeBreakdown = useMemo(() => {
    const counts = Object.fromEntries(UC_COLLEGES.map((c) => [c, 0]))
    let universalCount = 0
    listings
      .filter((l) => l.status !== 'REMOVED_BY_ADMIN')
      .forEach((l) => {
        if (Array.isArray(l.targetPrograms) && l.targetPrograms.length > 0) {
          l.targetPrograms.forEach((code) => {
            if (counts[code] !== undefined) counts[code] += 1
          })
        } else if (l.program && counts[l.program] !== undefined) {
          counts[l.program] += 1
        } else {
          universalCount += 1
        }
      })
    const rows = [
      { code: 'All Colleges', count: universalCount },
      ...UC_COLLEGES.map((code) => ({ code, count: counts[code] })),
    ].sort((a, b) => b.count - a.count)
    const max = Math.max(1, ...rows.map((r) => r.count))
    return rows.map((r) => ({ ...r, pct: Math.round((r.count / max) * 100) }))
  }, [listings])

  async function logAudit(action, targetId, details) {
    if (demoMode) {
      setAuditLogs((prev) => [
        {
          id: `aud_${Date.now()}`,
          adminEmail: 'demo-moderator@students.uc-bcf.edu.ph',
          action,
          targetId,
          details,
          createdAt: Date.now(),
        },
        ...prev,
      ])
      return
    }
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
    if (demoMode || !db || !recipientUid) return
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
      setLoginError('Invalid moderator email or password.')
    } finally {
      setLoginBusy(false)
    }
  }

  async function approveListing(listing, reportId = null) {
    const nextStatus =
      listing.status === 'UNDER_REVIEW' || listing.status === 'REMOVED_BY_ADMIN' || !listing.status
        ? 'OPEN'
        : listing.status

    const relatedOpenReports = reports.filter(
      (r) => r.status !== 'RESOLVED' && (r.id === reportId || r.listingId === listing.id)
    )

    if (demoMode) {
      setListings((prev) =>
        prev.map((item) =>
          item.id === listing.id ? { ...item, status: nextStatus, aiCheckStatus: 'APPROVED' } : item
        )
      )
      if (relatedOpenReports.length > 0) {
        const ids = new Set(relatedOpenReports.map((r) => r.id))
        setReports((prev) =>
          prev.map((r) => (ids.has(r.id) ? { ...r, status: 'RESOLVED', resolution: 'APPROVED_RESTORED' } : r))
        )
      }
      await logAudit('APPROVE_LISTING', listing.id, `Approved "${listing.title}" (${nextStatus})`)
      showToast(`Approved "${listing.title}"`, 'success')
      return
    }

    await updateDoc(doc(db, 'listings', listing.id), {
      status: nextStatus,
      aiCheckStatus: 'APPROVED',
      moderatedAt: Date.now(),
      moderatedBy: authUser?.email || '',
    })

    await Promise.all(
      relatedOpenReports.map((r) =>
        updateDoc(doc(db, 'reports', r.id), {
          status: 'RESOLVED',
          resolution: 'APPROVED_RESTORED',
          resolvedAt: Date.now(),
        })
      )
    )

    await logAudit('APPROVE_LISTING', listing.id, `Approved "${listing.title}" (${nextStatus})`)
    showToast(`Approved "${listing.title}"`, 'success')
  }

  function openRemoveModal(listing, report = null) {
    setActionModal({
      kind: 'remove_listing',
      listing,
      report,
      input: REMOVAL_PRESETS[0],
      busy: false,
    })
  }

  function openWarnModal(student) {
    setActionModal({
      kind: 'warn_student',
      student,
      input: WARNING_PRESETS[0],
      busy: false,
    })
  }

  function openSuspendModal(student) {
    if (student.suspended) {
      reinstateStudent(student)
      return
    }
    setActionModal({
      kind: 'suspend_student',
      student,
      input: SUSPENSION_PRESETS[0],
      busy: false,
    })
  }

  async function submitActionModal(e) {
    e.preventDefault()
    if (!actionModal || !actionModal.input.trim()) return
    const reason = actionModal.input.trim()
    setActionModal((prev) => ({ ...prev, busy: true }))

    try {
      if (actionModal.kind === 'remove_listing') {
        const { listing, report } = actionModal
        const relatedReports = reports.filter(
          (r) => r.status !== 'RESOLVED' && (r.listingId === listing.id || (report && r.id === report.id))
        )
        const sellerUser = usersById[listing.ownerUid]
        const sellerEmail = listing.sellerEmail || sellerUser?.email || ''
        const sellerName = listing.sellerName || sellerUser?.name || 'UCian'

        if (demoMode) {
          setListings((prev) =>
            prev.map((item) =>
              item.id === listing.id
                ? {
                    ...item,
                    status: 'REMOVED_BY_ADMIN',
                    aiCheckStatus: 'REJECTED',
                    removalReason: reason,
                  }
                : item
            )
          )
          const rIds = new Set(relatedReports.map((r) => r.id))
          setReports((prev) =>
            prev.map((r) => (rIds.has(r.id) ? { ...r, status: 'RESOLVED', resolution: 'REMOVED_BY_ADMIN' } : r))
          )
        } else {
          // Update BOTH status AND aiCheckStatus so it immediately leaves the moderation queue!
          await updateDoc(doc(db, 'listings', listing.id), {
            status: 'REMOVED_BY_ADMIN',
            aiCheckStatus: 'REJECTED',
            removalReason: reason,
            moderatedAt: Date.now(),
            moderatedBy: authUser?.email || '',
          })

          // Resolve ALL open reports targeting this listing and notify unique reporters
          const notifiedReporters = new Set()
          for (const rep of relatedReports) {
            await updateDoc(doc(db, 'reports', rep.id), {
              status: 'RESOLVED',
              resolution: 'REMOVED_BY_ADMIN',
              resolvedAt: Date.now(),
            })
            if (rep.reporterUid && !notifiedReporters.has(rep.reporterUid)) {
              notifiedReporters.add(rep.reporterUid)
              await notifyStudent(
                rep.reporterUid,
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

          if (sellerEmail) {
            const emailPayload = buildListingRemovedEmail({
              studentName: sellerName,
              listingTitle: listing.title,
              reason,
            })
            await sendCampusHtmlEmail({ to: sellerEmail, ...emailPayload })
          }
        }

        if (inspectedListingId === listing.id) {
          setInspectedListingId(null)
        }
        await logAudit('REMOVE_LISTING', listing.id, `Removed "${listing.title}": ${reason}`)
        showToast(`Removed "${listing.title}" from Campus Market`, 'danger')
      } else if (actionModal.kind === 'warn_student') {
        const { student } = actionModal
        const nextCount = (student.warningCount || 0) + 1
        if (demoMode) {
          setUsers((prev) => prev.map((u) => (u.id === student.id ? { ...u, warningCount: nextCount } : u)))
        } else {
          await updateDoc(doc(db, 'users', student.id), { warningCount: increment(1) })
          await notifyStudent(student.id, 'Official campus warning', reason)
          if (student.email) {
            const emailPayload = buildOfficialWarningEmail({
              studentName: student.name,
              warningMessage: reason,
              warningCount: nextCount,
            })
            await sendCampusHtmlEmail({ to: student.email, ...emailPayload })
          }
        }
        await logAudit('WARN_STUDENT', student.id, `Warned ${student.email}: ${reason}`)
        showToast(`Issued official warning to ${student.name}`, 'amber')
      } else if (actionModal.kind === 'suspend_student') {
        const { student } = actionModal
        if (demoMode) {
          setUsers((prev) =>
            prev.map((u) => (u.id === student.id ? { ...u, suspended: true, suspendedReason: reason } : u))
          )
        } else {
          await updateDoc(doc(db, 'users', student.id), { suspended: true, suspendedReason: reason })
          if (student.email) {
            const emailPayload = buildAccountSuspendedEmail({
              studentName: student.name,
              reason,
            })
            await sendCampusHtmlEmail({ to: student.email, ...emailPayload })
          }
        }
        await logAudit('SUSPEND_STUDENT', student.id, `Suspended ${student.email}: ${reason}`)
        showToast(`Suspended ${student.name}`, 'danger')
      }
      setActionModal(null)
    } catch (err) {
      showToast(err?.message || 'Moderation action failed. Check permissions.', 'danger')
      setActionModal((prev) => (prev ? { ...prev, busy: false } : null))
    }
  }

  async function reinstateStudent(student) {
    if (demoMode) {
      setUsers((prev) =>
        prev.map((u) => (u.id === student.id ? { ...u, suspended: false, suspendedReason: '' } : u))
      )
    } else {
      await updateDoc(doc(db, 'users', student.id), { suspended: false, suspendedReason: '' })
      await notifyStudent(
        student.id,
        'Account reinstated',
        'Your Hold High The Shop student account is active again.'
      )
      if (student.email) {
        const emailPayload = buildAccountReinstatedEmail({ studentName: student.name })
        await sendCampusHtmlEmail({ to: student.email, ...emailPayload })
      }
    }
    await logAudit('REINSTATE_STUDENT', student.id, `Reinstated ${student.email}`)
    showToast(`Reinstated ${student.name}`, 'success')
  }

  async function publishAnnouncement(e) {
    e.preventDefault()
    if (!annTitle.trim() || !annBody.trim()) return
    setAnnSending(true)
    try {
      if (demoMode) {
        const created = {
          id: `ann_${Date.now()}`,
          title: annTitle.trim(),
          body: annBody.trim(),
          authorEmail: 'demo-moderator@students.uc-bcf.edu.ph',
          createdAt: Date.now(),
        }
        setAnnouncements((prev) => [created, ...prev])
        await logAudit('PUBLISH_ANNOUNCEMENT', created.id, `Published "${created.title}"`)
      } else {
        const ref = await addDoc(collection(db, 'announcements'), {
          title: annTitle.trim(),
          body: annBody.trim(),
          authorEmail: authUser.email,
          createdAt: Date.now(),
        })
        await logAudit('PUBLISH_ANNOUNCEMENT', ref.id, `Published "${annTitle.trim()}"`)
      }
      setAnnTitle('')
      setAnnBody('')
      showToast('Campus announcement broadcast live to all UC students', 'success')
    } finally {
      setAnnSending(false)
    }
  }

  const previewEmail = useMemo(() => {
    switch (emailPreviewType) {
      case 'removed':
        return buildListingRemovedEmail({
          studentName: 'Juan Dela Cruz',
          listingTitle: 'Mystery Campus Box Bundle',
          reason: 'Misleading photos, condition, or pricing details',
        })
      case 'warning':
        return buildOfficialWarningEmail({
          studentName: 'Juan Dela Cruz',
          warningMessage: 'Please ensure all listings strictly follow UC Hold High The Shop guidelines.',
          warningCount: 1,
        })
      case 'suspended':
        return buildAccountSuspendedEmail({
          studentName: 'Juan Dela Cruz',
          reason: 'Repeated violations of UC Hold High The Shop community & safety guidelines.',
        })
      case 'reinstated':
        return buildAccountReinstatedEmail({ studentName: 'Juan Dela Cruz' })
      default:
        return buildVerificationButtonEmail({
          studentName: 'Juan Dela Cruz',
          verificationUrl: `${window.location.origin}/auth/action`,
        })
    }
  }, [emailPreviewType])

  async function handleSendTestEmail(e) {
    e.preventDefault()
    if (!testEmailTo.trim()) return
    setSendingTestEmail(true)
    try {
      const res = await sendCampusHtmlEmail({
        to: testEmailTo.trim(),
        ...previewEmail,
      })
      if (res.sent) {
        showToast(`Sent HTML preview email to ${testEmailTo.trim()}`, 'success')
      } else {
        showToast(`Could not send email (${res.reason || 'check VITE_EMAIL_WEBHOOK_URL'})`, 'danger')
      }
    } finally {
      setSendingTestEmail(false)
    }
  }

  // ---- Setup / Login Gates ----
  if (!isFirebaseConfigured && !demoMode) {
    return (
      <div className="min-h-screen flex items-center justify-center p-6 bg-[#F5F3EA]">
        <div className="max-w-xl w-full admin-card p-8 sm:p-10 space-y-6">
          <div className="flex items-center gap-3.5">
            <div className="w-12 h-12 rounded-2xl bg-[#07683B] text-white font-display font-bold flex items-center justify-center text-lg shadow-sm">
              UC
            </div>
            <div>
              <p className="text-[11px] font-bold tracking-wider uppercase text-[#07683B]">
                Hold High The Shop • Operations
              </p>
              <h1 className="text-2xl font-bold text-[#131A15]">Connect Firebase Spark</h1>
            </div>
          </div>

          <p className="text-sm text-[#606D64] leading-relaxed">
            Copy <code className="bg-[#F5F3EA] px-2 py-0.5 rounded font-mono text-xs text-[#131A15]">admin-web/.env.example</code>{' '}
            to <code className="bg-[#F5F3EA] px-2 py-0.5 rounded font-mono text-xs text-[#131A15]">admin-web/.env.local</code>{' '}
            and paste your Firebase Web App keys and optional Apps Script HTML email webhook URL (see{' '}
            <code className="bg-[#F5F3EA] px-2 py-0.5 rounded font-mono text-xs text-[#131A15]">docs/FIREBASE_SETUP.md</code>).
          </p>

          <div className="p-4 rounded-xl bg-[#FAF9F3] border border-[#E3DECB] text-xs text-[#37433B] space-y-1.5">
            <p className="font-semibold text-[#131A15]">Want to inspect the new Admin Dashboard UI right now?</p>
            <p className="text-[#606D64]">
              Launch interactive preview mode with sample campus moderation items, student reports, and HTML email templates.
            </p>
          </div>

          <div className="flex flex-wrap gap-3">
            <button
              id="btn-launch-demo-dashboard"
              type="button"
              onClick={() => setDemoMode(true)}
              className="btn-uc-primary px-5 py-3 text-sm"
            >
              <LayoutDashboard className="w-4 h-4" />
              Preview Interactive Dashboard
            </button>
          </div>
        </div>
      </div>
    )
  }

  if (!authReady && !demoMode) {
    return (
      <div className="min-h-screen flex flex-col items-center justify-center gap-3 bg-[#F5F3EA] text-[#07683B]">
        <RefreshCw className="w-6 h-6 animate-spin" />
        <p className="text-sm font-semibold">Connecting to Hold High The Shop Operations…</p>
      </div>
    )
  }

  if (!isAdmin && !demoMode) {
    return (
      <div className="min-h-screen grid lg:grid-cols-12 bg-[#F5F3EA]">
        <div className="hidden lg:flex lg:col-span-5 bg-gradient-to-b from-[#06351F] to-[#042616] text-white p-12 flex-col justify-between">
          <div className="flex items-center gap-3">
            <div className="w-11 h-11 rounded-xl bg-white/15 font-display font-bold flex items-center justify-center text-base">
              UC
            </div>
            <div>
              <p className="font-display font-bold text-base leading-tight">Hold High The Shop</p>
              <p className="text-xs text-white/65">University of the Cordilleras</p>
            </div>
          </div>

          <div className="space-y-5 max-w-md">
            <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-white/10 text-xs font-semibold text-[#A9DFC3]">
              <ShieldCheck className="w-3.5 h-3.5" />
              Verified Campus Governance
            </span>
            <h2 className="font-display text-3xl font-bold leading-tight">
              Keep every student exchange safe, fair, and campus-verified.
            </h2>
            <p className="text-sm text-white/75 leading-relaxed">
              Review flagged listings, inspect Gemini multimodal AI safety checks, manage verified{' '}
              <code className="text-white">@students.uc-bcf.edu.ph</code> accounts, and broadcast campus-wide notices.
            </p>
          </div>

          <p className="text-xs text-white/50">
            Console-protected access via Firestore <code className="text-white/75">admins/{'{uid}'}</code>
          </p>
        </div>

        <div className="lg:col-span-7 flex items-center justify-center p-6 sm:p-12">
          <form onSubmit={handleAdminSignIn} className="max-w-md w-full admin-card p-8 sm:p-10 space-y-6">
            <div>
              <p className="text-xs font-bold tracking-wider uppercase text-[#07683B]">Moderator Sign In</p>
              <h1 className="text-2xl font-bold text-[#131A15] mt-1">Admin Operations Portal</h1>
              <p className="text-xs text-[#606D64] mt-1.5 leading-relaxed">
                Sign in with an authorized moderator account listed in Firestore{' '}
                <code className="bg-[#F5F3EA] px-1.5 py-0.5 rounded">admins/{'{uid}'}</code>.
              </p>
            </div>

            <div className="space-y-4">
              <div>
                <label htmlFor="admin-login-email" className="block text-xs font-semibold text-[#131A15] mb-1.5">
                  Moderator Email
                </label>
                <input
                  id="admin-login-email"
                  type="email"
                  required
                  placeholder="admin@students.uc-bcf.edu.ph"
                  value={loginEmail}
                  onChange={(e) => setLoginEmail(e.target.value)}
                  className="admin-input"
                />
              </div>
              <div>
                <label htmlFor="admin-login-password" className="block text-xs font-semibold text-[#131A15] mb-1.5">
                  Password
                </label>
                <input
                  id="admin-login-password"
                  type="password"
                  required
                  placeholder="Enter your password"
                  value={loginPassword}
                  onChange={(e) => setLoginPassword(e.target.value)}
                  className="admin-input"
                />
              </div>
            </div>

            {loginError && (
              <div className="p-3 rounded-xl bg-[#FDECEA] border border-[#F5C6C2] text-xs font-medium text-[#B91C1C]">
                {loginError}
              </div>
            )}

            <button
              id="btn-admin-signin"
              type="submit"
              disabled={loginBusy}
              className="btn-uc-primary w-full py-3 text-sm disabled:opacity-50"
            >
              {loginBusy ? 'Verifying credentials…' : 'Sign in to Dashboard'}
            </button>
          </form>
        </div>
      </div>
    )
  }

  // ---- Filtered Collections ----
  const q = search.trim().toLowerCase()

  const filteredListings = listings.filter((l) => {
    if (listingFilter === 'OPEN' && l.status !== 'OPEN') return false
    if (listingFilter === 'ACTIVE_DEAL' && !['RESERVED', 'ON_LOAN'].includes(l.status)) return false
    if (listingFilter === 'UNDER_REVIEW' && l.status !== 'UNDER_REVIEW' && l.aiCheckStatus !== 'PENDING_REVIEW')
      return false
    if (listingFilter === 'REMOVED_BY_ADMIN' && l.status !== 'REMOVED_BY_ADMIN') return false
    if (collegeFilter !== 'ALL') {
      const inTarget = Array.isArray(l.targetPrograms) && l.targetPrograms.includes(collegeFilter)
      if (!inTarget && l.program !== collegeFilter) return false
    }
    if (!q) return true
    return (
      l.title?.toLowerCase().includes(q) ||
      l.sellerName?.toLowerCase().includes(q) ||
      l.program?.toLowerCase().includes(q) ||
      l.meetup?.toLowerCase().includes(q)
    )
  })

  const filteredStudents = users.filter((u) => {
    const isVerified = u.emailVerified !== false
    if (studentFilter === 'verified' && (!isVerified || u.suspended)) return false
    if (studentFilter === 'unverified' && isVerified) return false
    if (studentFilter === 'warned' && !(u.warningCount > 0)) return false
    if (studentFilter === 'suspended' && !u.suspended) return false
    if (!q) return true
    return (
      u.name?.toLowerCase().includes(q) ||
      u.email?.toLowerCase().includes(q) ||
      u.program?.toLowerCase().includes(q)
    )
  })

  const navGroups = [
    {
      heading: 'Governance',
      items: [
        { id: 'dashboard', label: 'Overview', icon: LayoutDashboard },
        {
          id: 'moderation',
          label: 'Moderation Queue',
          icon: ShieldAlert,
          badge: moderationActionCount,
          urgent: moderationActionCount > 0,
        },
        { id: 'ai_logs', label: 'AI Safety Gate', icon: Sparkles, badge: modLogs.length || null },
      ],
    },
    {
      heading: 'Marketplace',
      items: [
        {
          id: 'listings',
          label: 'Campus Listings',
          icon: Package,
          badge: listings.filter((l) => l.status !== 'REMOVED_BY_ADMIN').length,
        },
        { id: 'users', label: 'UC Students', icon: Users, badge: users.length },
      ],
    },
    {
      heading: 'Communications',
      items: [
        { id: 'announcements', label: 'Announcements', icon: Megaphone },
        { id: 'emails', label: 'HTML Email Layouts', icon: Mail },
        { id: 'audit', label: 'Audit Trail', icon: History },
      ],
    },
  ]

  const pageMeta = {
    dashboard: {
      title: 'Campus Operations Overview',
      subtitle: 'Real-time marketplace health, moderation queue status, and UC college activity.',
    },
    moderation: {
      title: 'Moderation & Triage Queue',
      subtitle: 'Review auto-escalated listings, pending AI safety checks, and private student reports.',
    },
    ai_logs: {
      title: 'Hybrid AI Safety Gate Logs',
      subtitle: 'Prohibited items blocked automatically before reaching Campus Market.',
    },
    listings: {
      title: 'Campus Listings Directory',
      subtitle: 'Inspect, filter, remove, or restore student sell, lend, and swap listings.',
    },
    users: {
      title: 'Verified UC Student Directory',
      subtitle: 'Manage @students.uc-bcf.edu.ph student standing, official warnings, and suspensions.',
    },
    announcements: {
      title: 'Campus Announcements',
      subtitle: 'Broadcast official notices straight to every student’s Android Notifications feed.',
    },
    emails: {
      title: 'HTML Email Layouts & Relay',
      subtitle: 'Preview branded UC Outlook HTML templates for verification codes and moderation notices.',
    },
    audit: {
      title: 'Immutable Admin Audit Trail',
      subtitle: 'Append-only record of every moderator action protected by Firestore Security Rules.',
    },
  }[tab] || { title: 'Dashboard', subtitle: '' }

  const openCount = listings.filter((l) => l.status === 'OPEN').length
  const sellCount = listings.filter((l) => l.status !== 'REMOVED_BY_ADMIN' && (l.type === 0 || !l.type)).length
  const lendCount = listings.filter((l) => l.status !== 'REMOVED_BY_ADMIN' && l.type === 1).length
  const swapCount = listings.filter((l) => l.status !== 'REMOVED_BY_ADMIN' && l.type === 2).length
  const activeTotal = Math.max(1, sellCount + lendCount + swapCount)

  const sidebarContent = (
    <>
      <div className="p-5 border-b border-white/10 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-[#07683B] border border-white/15 font-display font-bold flex items-center justify-center text-sm shadow-sm">
            UC
          </div>
          <div>
            <p className="font-display font-bold text-sm leading-tight text-white">Hold High The Shop</p>
            <p className="text-[11px] text-white/60">Admin Control Center</p>
          </div>
        </div>
        <button
          type="button"
          onClick={() => setMobileMenuOpen(false)}
          className="lg:hidden p-1.5 rounded-lg text-white/70 hover:text-white hover:bg-white/10"
          aria-label="Close navigation"
        >
          <X className="w-4 h-4" />
        </button>
      </div>

      <div className="flex-1 overflow-y-auto px-3.5 py-4 space-y-6">
        {navGroups.map((group) => (
          <div key={group.heading}>
            <p className="px-3 mb-2 text-[10px] font-bold uppercase tracking-wider text-white/40">
              {group.heading}
            </p>
            <div className="space-y-1">
              {group.items.map((item) => {
                const Icon = item.icon
                const active = tab === item.id
                return (
                  <button
                    key={item.id}
                    id={`nav-${item.id}`}
                    type="button"
                    onClick={() => {
                      setTab(item.id)
                      setMobileMenuOpen(false)
                    }}
                    className={`admin-nav-btn ${active ? 'admin-nav-btn-active' : ''}`}
                  >
                    <span className="flex items-center gap-2.5 truncate">
                      <Icon className="w-4 h-4 shrink-0 opacity-90" />
                      <span className="truncate">{item.label}</span>
                    </span>
                    {item.badge !== undefined && item.badge !== null && (
                      <span
                        className={`px-2 py-0.5 rounded-full text-[11px] font-bold tabular-nums ${
                          item.urgent
                            ? 'bg-red-500 text-white'
                            : active
                            ? 'bg-white/20 text-white'
                            : 'bg-white/10 text-white/75'
                        }`}
                      >
                        {item.badge}
                      </span>
                    )}
                  </button>
                )
              })}
            </div>
          </div>
        ))}
      </div>

      <div className="p-4 border-t border-white/10 space-y-3 bg-black/15">
        <div className="flex items-center gap-2.5">
          <div className="w-8 h-8 rounded-full bg-emerald-500/20 border border-emerald-400/30 flex items-center justify-center text-xs font-bold text-emerald-200">
            {(authUser?.email || 'M')[0].toUpperCase()}
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-xs font-semibold text-white truncate">
              {demoMode ? 'Preview Moderator' : authUser?.email}
            </p>
            <p className="text-[11px] text-emerald-300/85 flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 inline-block" />
              {demoMode ? 'Sample Preview Mode' : 'Live Firestore Sync'}
            </p>
          </div>
        </div>
        <button
          id="btn-admin-signout"
          type="button"
          onClick={() => (demoMode ? setDemoMode(false) : signOut(auth))}
          className="w-full flex items-center justify-center gap-2 py-2 px-3 rounded-xl bg-white/10 hover:bg-white/15 text-xs font-semibold text-white transition"
        >
          <LogOut className="w-3.5 h-3.5" />
          {demoMode ? 'Exit Preview' : 'Sign out'}
        </button>
      </div>
    </>
  )

  return (
    <div className="admin-shell">
      {/* Desktop Sidebar */}
      <aside className="hidden lg:flex admin-sidebar">{sidebarContent}</aside>

      {/* Mobile Sidebar Overlay */}
      {mobileMenuOpen && (
        <div className="fixed inset-0 z-50 lg:hidden flex">
          <div className="fixed inset-0 modal-backdrop" onClick={() => setMobileMenuOpen(false)} />
          <aside className="relative w-72 max-w-[85vw] bg-[#06351F] text-white flex flex-col h-full z-10">
            {sidebarContent}
          </aside>
        </div>
      )}

      {/* Main Content Area */}
      <div className="flex-1 flex flex-col min-w-0">
        {/* Sticky Header */}
        <header className="sticky top-0 z-20 bg-[#F5F3EA]/90 backdrop-blur-md border-b border-[#E3DECB] px-5 sm:px-8 py-4">
          <div className="max-w-7xl mx-auto flex flex-wrap items-center justify-between gap-4">
            <div className="flex items-center gap-3 min-w-0">
              <button
                type="button"
                onClick={() => setMobileMenuOpen(true)}
                className="lg:hidden p-2 rounded-xl bg-white border border-[#E3DECB] text-[#131A15]"
                aria-label="Open navigation"
              >
                <Menu className="w-5 h-5" />
              </button>
              <div className="min-w-0">
                <h1 className="text-lg sm:text-xl font-bold text-[#131A15] truncate">{pageMeta.title}</h1>
                <p className="text-xs text-[#606D64] hidden sm:block truncate">{pageMeta.subtitle}</p>
              </div>
            </div>

            <div className="flex items-center gap-2.5 w-full sm:w-auto">
              <div className="relative flex-1 sm:w-64">
                <Search className="w-4 h-4 text-[#606D64] absolute left-3.5 top-1/2 -translate-y-1/2 pointer-events-none" />
                <input
                  id="global-admin-search"
                  type="search"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder="Search listings, students…"
                  className="admin-input pl-9 pr-3 py-2 text-xs bg-white"
                />
              </div>

              {moderationActionCount > 0 && tab !== 'moderation' && (
                <button
                  type="button"
                  onClick={() => setTab('moderation')}
                  className="btn-uc-danger py-2 px-3 text-xs shrink-0"
                >
                  <ShieldAlert className="w-3.5 h-3.5" />
                  <span>{moderationActionCount} to review</span>
                </button>
              )}

              <button
                type="button"
                onClick={() => setTab('announcements')}
                className="btn-uc-outline py-2 px-3 text-xs hidden md:inline-flex shrink-0"
              >
                <Megaphone className="w-3.5 h-3.5 text-[#07683B]" />
                <span>Broadcast</span>
              </button>
            </div>
          </div>
        </header>

        {demoMode && (
          <div className="bg-[#FFF6E3] border-b border-[#F0DCAC] px-5 sm:px-8 py-2.5 text-xs text-[#8A5A00] flex flex-wrap items-center justify-between gap-2">
            <span>
              <strong>Interactive Preview Mode:</strong> Changes update in-memory sample data so you can test moderation removal, drawers, modals, and HTML emails before connecting <code className="font-mono">.env.local</code>.
            </span>
            <button
              type="button"
              onClick={() => setDemoMode(false)}
              className="font-semibold underline hover:no-underline"
            >
              Setup guide
            </button>
          </div>
        )}

        {/* Toast Notification */}
        {toast && (
          <div
            role="status"
            className={`fixed bottom-6 right-6 z-50 flex items-center gap-2.5 px-4 py-3 rounded-xl shadow-lg text-xs font-semibold text-white ${
              toast.tone === 'danger'
                ? 'bg-[#991B1B]'
                : toast.tone === 'amber'
                ? 'bg-[#92400E]'
                : 'bg-[#131A15]'
            }`}
          >
            <CheckCircle2 className="w-4 h-4 shrink-0 text-emerald-300" />
            <span>{toast.message}</span>
          </div>
        )}

        {/* Workspace Content */}
        <main className="flex-1 px-5 sm:px-8 py-6 max-w-7xl w-full mx-auto space-y-6">
          {/* ======================== OVERVIEW TAB ======================== */}
          {tab === 'dashboard' && (
            <div className="space-y-6">
              {/* KPI Row */}
              <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-4">
                <button
                  type="button"
                  onClick={() => setTab('moderation')}
                  className="admin-kpi-card text-left"
                >
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-semibold text-[#606D64]">Action Required</span>
                    <span
                      className={`w-8 h-8 rounded-lg flex items-center justify-center ${
                        moderationActionCount > 0
                          ? 'bg-[#FDECEA] text-[#B91C1C]'
                          : 'bg-[#E4EFEA] text-[#07683B]'
                      }`}
                    >
                      <ShieldAlert className="w-4 h-4" />
                    </span>
                  </div>
                  <p
                    className={`text-3xl font-bold font-display mt-2 tabular-nums ${
                      moderationActionCount > 0 ? 'text-[#B91C1C]' : 'text-[#07683B]'
                    }`}
                  >
                    {moderationActionCount}
                  </p>
                  <p className="text-xs text-[#606D64] mt-1 flex items-center justify-between">
                    <span>
                      {flaggedListings.length} flagged • {openReports.length} open report(s)
                    </span>
                    <ArrowUpRight className="w-3.5 h-3.5" />
                  </p>
                </button>

                <button
                  type="button"
                  onClick={() => setTab('listings')}
                  className="admin-kpi-card text-left"
                >
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-semibold text-[#606D64]">Open Campus Listings</span>
                    <span className="w-8 h-8 rounded-lg bg-[#E4EFEA] text-[#07683B] flex items-center justify-center">
                      <Package className="w-4 h-4" />
                    </span>
                  </div>
                  <p className="text-3xl font-bold font-display mt-2 text-[#131A15] tabular-nums">{openCount}</p>
                  <p className="text-xs text-[#606D64] mt-1 flex items-center justify-between">
                    <span>{listings.length} total recorded listings</span>
                    <ArrowUpRight className="w-3.5 h-3.5" />
                  </p>
                </button>

                <button
                  type="button"
                  onClick={() => setTab('users')}
                  className="admin-kpi-card text-left"
                >
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-semibold text-[#606D64]">Registered UCians</span>
                    <span className="w-8 h-8 rounded-lg bg-[#E4EFEA] text-[#07683B] flex items-center justify-center">
                      <Users className="w-4 h-4" />
                    </span>
                  </div>
                  <p className="text-3xl font-bold font-display mt-2 text-[#131A15] tabular-nums">{users.length}</p>
                  <p className="text-xs text-[#606D64] mt-1 flex items-center justify-between">
                    <span>
                      {users.filter((u) => u.suspended).length} suspended •{' '}
                      {users.filter((u) => (u.warningCount || 0) > 0).length} warned
                    </span>
                    <ArrowUpRight className="w-3.5 h-3.5" />
                  </p>
                </button>

                <button
                  type="button"
                  onClick={() => setTab('ai_logs')}
                  className="admin-kpi-card text-left"
                >
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-semibold text-[#606D64]">Completed Deals &amp; Safety</span>
                    <span className="w-8 h-8 rounded-lg bg-[#E4EFEA] text-[#07683B] flex items-center justify-center">
                      <ShieldCheck className="w-4 h-4" />
                    </span>
                  </div>
                  <p className="text-3xl font-bold font-display mt-2 text-[#07683B] tabular-nums">
                    {exchangesCount}
                  </p>
                  <p className="text-xs text-[#606D64] mt-1 flex items-center justify-between">
                    <span>{modLogs.length} prohibited attempt(s) blocked</span>
                    <ArrowUpRight className="w-3.5 h-3.5" />
                  </p>
                </button>
              </div>

              {/* Analytics Row */}
              <div className="grid lg:grid-cols-12 gap-6">
                {/* College Distribution */}
                <section className="lg:col-span-7 admin-card p-6">
                  <div className="flex items-center justify-between mb-4">
                    <div>
                      <h2 className="text-sm font-bold text-[#131A15]">Listings by UC College</h2>
                      <p className="text-xs text-[#606D64]">Active supply distribution across university colleges</p>
                    </div>
                    <span className="text-xs font-semibold text-[#07683B] bg-[#E4EFEA] px-2.5 py-1 rounded-full">
                      11 UC Colleges
                    </span>
                  </div>

                  <div className="space-y-2.5">
                    {collegeBreakdown.slice(0, 7).map((row) => (
                      <div key={row.code} className="flex items-center gap-3 text-xs">
                        <span className="w-24 font-semibold text-[#37433B] truncate">{row.code}</span>
                        <div className="flex-1 h-2.5 rounded-full bg-[#F1EFE4] overflow-hidden">
                          <div
                            className="h-full rounded-full bg-[#07683B] transition-all duration-300"
                            style={{ width: `${Math.max(row.count > 0 ? 6 : 0, row.pct)}%` }}
                          />
                        </div>
                        <span className="w-8 text-right font-semibold text-[#131A15] tabular-nums">{row.count}</span>
                      </div>
                    ))}
                  </div>
                </section>

                {/* Exchange Mode Mix & System Health */}
                <section className="lg:col-span-5 admin-card p-6 flex flex-col justify-between space-y-5">
                  <div>
                    <h2 className="text-sm font-bold text-[#131A15]">Marketplace Mode Mix</h2>
                    <p className="text-xs text-[#606D64]">Breakdown of Sell, Lend, and Swap listings</p>

                    <div className="mt-4 h-3 w-full rounded-full bg-[#F1EFE4] overflow-hidden flex">
                      <div
                        style={{ width: `${(sellCount / activeTotal) * 100}%` }}
                        className="bg-[#07683B] h-full"
                        title={`Sell: ${sellCount}`}
                      />
                      <div
                        style={{ width: `${(lendCount / activeTotal) * 100}%` }}
                        className="bg-sky-600 h-full"
                        title={`Lend: ${lendCount}`}
                      />
                      <div
                        style={{ width: `${(swapCount / activeTotal) * 100}%` }}
                        className="bg-amber-500 h-full"
                        title={`Swap: ${swapCount}`}
                      />
                    </div>

                    <div className="grid grid-cols-3 gap-2 mt-4">
                      <div className="p-3 rounded-xl bg-[#FAF9F3] border border-[#E3DECB]">
                        <span className="inline-block w-2 h-2 rounded-full bg-[#07683B] mr-1.5" />
                        <span className="text-[11px] font-medium text-[#606D64]">Sell</span>
                        <p className="text-lg font-bold text-[#131A15] mt-0.5 tabular-nums">{sellCount}</p>
                      </div>
                      <div className="p-3 rounded-xl bg-[#FAF9F3] border border-[#E3DECB]">
                        <span className="inline-block w-2 h-2 rounded-full bg-sky-600 mr-1.5" />
                        <span className="text-[11px] font-medium text-[#606D64]">Lend</span>
                        <p className="text-lg font-bold text-[#131A15] mt-0.5 tabular-nums">{lendCount}</p>
                      </div>
                      <div className="p-3 rounded-xl bg-[#FAF9F3] border border-[#E3DECB]">
                        <span className="inline-block w-2 h-2 rounded-full bg-amber-500 mr-1.5" />
                        <span className="text-[11px] font-medium text-[#606D64]">Swap</span>
                        <p className="text-lg font-bold text-[#131A15] mt-0.5 tabular-nums">{swapCount}</p>
                      </div>
                    </div>
                  </div>

                  <div className="p-3.5 rounded-xl bg-[#FAF9F3] border border-[#E3DECB] flex items-center justify-between gap-3">
                    <div className="flex items-center gap-2.5 min-w-0">
                      <Mail className="w-4 h-4 text-[#07683B] shrink-0" />
                      <div className="min-w-0">
                        <p className="text-xs font-semibold text-[#131A15] truncate">
                          HTML Email Relay:{' '}
                          {isEmailWebhookConfigured ? 'Connected' : 'Ready to configure'}
                        </p>
                        <p className="text-[11px] text-[#606D64] truncate">
                          6-digit verification &amp; moderation notices
                        </p>
                      </div>
                    </div>
                    <button
                      type="button"
                      onClick={() => setTab('emails')}
                      className="text-xs font-semibold text-[#07683B] hover:underline shrink-0"
                    >
                      Preview
                    </button>
                  </div>
                </section>
              </div>

              {/* Priority Triage + Recent Audit */}
              <div className="grid lg:grid-cols-12 gap-6">
                <section className="lg:col-span-7 admin-card overflow-hidden">
                  <div className="px-6 py-4 border-b border-[#E3DECB] flex items-center justify-between">
                    <div>
                      <h2 className="text-sm font-bold text-[#131A15]">Priority Moderation Triage</h2>
                      <p className="text-xs text-[#606D64]">
                        Items needing immediate moderator review ({flaggedListings.length})
                      </p>
                    </div>
                    <button
                      type="button"
                      onClick={() => setTab('moderation')}
                      className="text-xs font-semibold text-[#07683B] hover:underline flex items-center gap-1"
                    >
                      View full queue <ChevronRight className="w-3.5 h-3.5" />
                    </button>
                  </div>

                  {flaggedListings.length === 0 ? (
                    <div className="p-8 text-center">
                      <CheckCircle2 className="w-8 h-8 text-[#07683B] mx-auto mb-2 opacity-80" />
                      <p className="text-sm font-semibold text-[#131A15]">Moderation queue is clear</p>
                      <p className="text-xs text-[#606D64] mt-0.5">
                        No flagged or pending-AI listings waiting for review right now.
                      </p>
                    </div>
                  ) : (
                    <div className="divide-y divide-[#EFECE0]">
                      {flaggedListings.slice(0, 4).map((l) => (
                        <div key={l.id} className="p-4 sm:px-6 flex flex-wrap items-center gap-4">
                          <MediaThumb mediaRef={l.photoUris?.[0]} size="sm" />
                          <div className="flex-1 min-w-[180px]">
                            <div className="flex items-center gap-2 flex-wrap">
                              <button
                                type="button"
                                onClick={() => {
                                  setInspectedListingId(l.id)
                                  setInspectedPhotoIdx(0)
                                }}
                                className="font-semibold text-sm text-[#131A15] hover:text-[#07683B] text-left"
                              >
                                {l.title}
                              </button>
                              <StatusPill status={l.status} />
                              {l.aiCheckStatus === 'PENDING_REVIEW' && <StatusPill status="PENDING_REVIEW" />}
                            </div>
                            <p className="text-xs text-[#606D64] mt-0.5">
                              {l.sellerName} • {formatPrice(l)} • Reports: {l.reportCount || 0}
                            </p>
                          </div>
                          <div className="flex items-center gap-2">
                            <button
                              type="button"
                              onClick={() => approveListing(l)}
                              className="btn-uc-primary py-1.5 px-3 text-xs"
                            >
                              Approve
                            </button>
                            <button
                              type="button"
                              onClick={() => openRemoveModal(l)}
                              className="btn-uc-danger py-1.5 px-3 text-xs"
                            >
                              Remove
                            </button>
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </section>

                <section className="lg:col-span-5 admin-card overflow-hidden">
                  <div className="px-6 py-4 border-b border-[#E3DECB] flex items-center justify-between">
                    <div>
                      <h2 className="text-sm font-bold text-[#131A15]">Recent Moderator Actions</h2>
                      <p className="text-xs text-[#606D64]">Latest append-only audit trail entries</p>
                    </div>
                    <button
                      type="button"
                      onClick={() => setTab('audit')}
                      className="text-xs font-semibold text-[#07683B] hover:underline"
                    >
                      All logs
                    </button>
                  </div>

                  {auditLogs.length === 0 ? (
                    <div className="p-8 text-center text-xs text-[#606D64]">No moderator actions recorded yet.</div>
                  ) : (
                    <div className="divide-y divide-[#EFECE0]">
                      {auditLogs.slice(0, 5).map((log) => (
                        <div key={log.id} className="px-6 py-3.5 text-xs space-y-1">
                          <div className="flex items-center justify-between gap-2">
                            <span className="font-bold text-[#07683B] tracking-wide">{log.action}</span>
                            <span className="text-[11px] text-[#606D64] tabular-nums">
                              {formatDate(log.createdAt)}
                            </span>
                          </div>
                          <p className="text-[#131A15] line-clamp-1">{log.details}</p>
                        </div>
                      ))}
                    </div>
                  )}
                </section>
              </div>
            </div>
          )}

          {/* ======================== MODERATION QUEUE TAB ======================== */}
          {tab === 'moderation' && (
            <div className="space-y-5">
              <div className="flex flex-wrap items-center gap-2">
                {[
                  { id: 'all', label: `Actionable Queue (${moderationActionCount})` },
                  {
                    id: 'flagged',
                    label: `Under Review (${flaggedListings.filter((l) => l.status === 'UNDER_REVIEW').length})`,
                  },
                  {
                    id: 'pending_ai',
                    label: `Pending AI (${flaggedListings.filter((l) => l.aiCheckStatus === 'PENDING_REVIEW').length})`,
                  },
                  { id: 'reports', label: `Open Reports (${openReports.length})` },
                  { id: 'resolved', label: `Resolved Reports (${resolvedReports.length})` },
                ].map((pill) => (
                  <button
                    key={pill.id}
                    type="button"
                    onClick={() => setModFilter(pill.id)}
                    className={`admin-pill-tab ${modFilter === pill.id ? 'admin-pill-tab-active' : ''}`}
                  >
                    {pill.label}
                  </button>
                ))}
              </div>

              {(modFilter === 'all' || modFilter === 'flagged' || modFilter === 'pending_ai') && (
                <section className="admin-card overflow-hidden">
                  <div className="px-6 py-4 border-b border-[#E3DECB] flex items-center justify-between">
                    <div>
                      <h2 className="text-sm font-bold text-[#131A15]">Flagged &amp; Pending-AI Listings</h2>
                      <p className="text-xs text-[#606D64]">
                        Removing a listing sets its status to <code className="font-mono">REMOVED_BY_ADMIN</code>, marks AI review as{' '}
                        <code className="font-mono">REJECTED</code>, resolves linked reports, and immediately clears it from this queue.
                      </p>
                    </div>
                  </div>

                  {(() => {
                    const list = flaggedListings.filter((l) => {
                      if (modFilter === 'flagged') return l.status === 'UNDER_REVIEW'
                      if (modFilter === 'pending_ai') return l.aiCheckStatus === 'PENDING_REVIEW'
                      return true
                    })
                    if (list.length === 0) {
                      return (
                        <div className="p-10 text-center">
                          <CheckCircle2 className="w-8 h-8 text-[#07683B] mx-auto mb-2 opacity-80" />
                          <p className="text-sm font-semibold text-[#131A15]">All clear in this queue</p>
                          <p className="text-xs text-[#606D64] mt-1">
                            No listings currently awaiting moderator approval or removal.
                          </p>
                        </div>
                      )
                    }
                    return (
                      <div className="divide-y divide-[#EFECE0]">
                        {list.map((l) => (
                          <div key={l.id} className="p-5 sm:px-6 flex flex-wrap items-start justify-between gap-4">
                            <div className="flex items-start gap-4 flex-1 min-w-[240px]">
                              <MediaThumb mediaRef={l.photoUris?.[0]} />
                              <div className="space-y-1 min-w-0 flex-1">
                                <div className="flex items-center gap-2 flex-wrap">
                                  <button
                                    type="button"
                                    onClick={() => {
                                      setInspectedListingId(l.id)
                                      setInspectedPhotoIdx(0)
                                    }}
                                    className="font-bold text-sm text-[#131A15] hover:text-[#07683B] text-left"
                                  >
                                    {l.title}
                                  </button>
                                  <StatusPill status={l.status} />
                                  <StatusPill status={l.aiCheckStatus} />
                                </div>
                                <p className="text-xs text-[#606D64]">
                                  Seller: <strong className="text-[#37433B]">{l.sellerName}</strong> •{' '}
                                  {formatPrice(l)} • Reports: <strong>{l.reportCount || 0}</strong> •{' '}
                                  {formatDate(l.createdAt)}
                                </p>
                                {l.description && (
                                  <p className="text-xs text-[#37433B] line-clamp-2">{l.description}</p>
                                )}
                              </div>
                            </div>

                            <div className="flex items-center gap-2 shrink-0">
                              <button
                                type="button"
                                onClick={() => {
                                  setInspectedListingId(l.id)
                                  setInspectedPhotoIdx(0)
                                }}
                                className="btn-uc-outline py-2 px-3 text-xs"
                              >
                                <Eye className="w-3.5 h-3.5" />
                                Inspect
                              </button>
                              <button
                                type="button"
                                onClick={() => approveListing(l)}
                                className="btn-uc-primary py-2 px-3.5 text-xs"
                              >
                                <CheckCircle2 className="w-3.5 h-3.5" />
                                Approve
                              </button>
                              <button
                                type="button"
                                onClick={() => openRemoveModal(l)}
                                className="btn-uc-danger py-2 px-3.5 text-xs"
                              >
                                <Trash2 className="w-3.5 h-3.5" />
                                Remove
                              </button>
                            </div>
                          </div>
                        ))}
                      </div>
                    )
                  })()}
                </section>
              )}

              {(modFilter === 'all' || modFilter === 'reports' || modFilter === 'resolved') && (
                <section className="admin-card overflow-hidden">
                  <div className="px-6 py-4 border-b border-[#E3DECB]">
                    <h2 className="text-sm font-bold text-[#131A15]">
                      {modFilter === 'resolved' ? 'Resolved Student Reports' : 'Open Student Reports'}
                    </h2>
                    <p className="text-xs text-[#606D64]">
                      Confidential reports submitted by verified UC students (never visible to the reported seller).
                    </p>
                  </div>

                  {(() => {
                    const list = modFilter === 'resolved' ? resolvedReports : openReports
                    if (list.length === 0) {
                      return (
                        <div className="p-8 text-center text-xs text-[#606D64]">
                          {modFilter === 'resolved'
                            ? 'No resolved reports yet.'
                            : 'No open student reports waiting for action.'}
                        </div>
                      )
                    }
                    return (
                      <div className="divide-y divide-[#EFECE0]">
                        {list.map((r) => {
                          const targetListing = listingsById[r.listingId]
                          const isResolved =
                            r.status === 'RESOLVED' ||
                            (targetListing && targetListing.status === 'REMOVED_BY_ADMIN')
                          return (
                            <div
                              key={r.id}
                              className="p-5 sm:px-6 flex flex-wrap items-start justify-between gap-4"
                            >
                              <div className="space-y-1.5 flex-1 min-w-[240px]">
                                <div className="flex items-center gap-2 flex-wrap">
                                  <span className="font-mono text-xs font-bold text-[#07683B]">
                                    {r.ref || r.id}
                                  </span>
                                  <StatusPill status={isResolved ? 'RESOLVED' : r.status || 'OPEN'} />
                                  {r.autoEscalated && !isResolved && <StatusPill status="UNDER_REVIEW" />}
                                  {r.resolution && (
                                    <span className="text-[11px] text-[#606D64]">({r.resolution})</span>
                                  )}
                                </div>
                                <p className="text-sm font-semibold text-[#131A15]">
                                  Listing: {targetListing ? targetListing.title : r.subject || 'Removed item'}
                                </p>
                                <p className="text-xs text-[#606D64]">
                                  Reported by {r.reporterName || r.reporterEmail || 'Verified Student'} •{' '}
                                  {formatDate(r.createdAt)}
                                </p>
                                {r.details && (
                                  <div className="p-2.5 rounded-xl bg-[#FAF9F3] border border-[#E3DECB] text-xs text-[#37433B]">
                                    “{r.details}”
                                  </div>
                                )}
                              </div>

                              {targetListing && !isResolved && (
                                <div className="flex items-center gap-2 shrink-0">
                                  <button
                                    type="button"
                                    onClick={() => {
                                      setInspectedListingId(targetListing.id)
                                      setInspectedPhotoIdx(0)
                                    }}
                                    className="btn-uc-outline py-2 px-3 text-xs"
                                  >
                                    Inspect
                                  </button>
                                  <button
                                    type="button"
                                    onClick={() => approveListing(targetListing, r.id)}
                                    className="btn-uc-outline py-2 px-3 text-xs text-[#07683B] border-[#07683B]/40"
                                  >
                                    Dismiss &amp; Keep
                                  </button>
                                  <button
                                    type="button"
                                    onClick={() => openRemoveModal(targetListing, r)}
                                    className="btn-uc-danger py-2 px-3.5 text-xs"
                                  >
                                    Remove Listing
                                  </button>
                                </div>
                              )}
                            </div>
                          )
                        })}
                      </div>
                    )
                  })()}
                </section>
              )}
            </div>
          )}

          {/* ======================== AI SAFETY GATE TAB ======================== */}
          {tab === 'ai_logs' && (
            <section className="admin-card overflow-hidden">
              <div className="px-6 py-4 border-b border-[#E3DECB] flex flex-wrap items-center justify-between gap-3">
                <div>
                  <h2 className="text-sm font-bold text-[#131A15]">
                    Blocked Prohibited-Item Attempts ({modLogs.length})
                  </h2>
                  <p className="text-xs text-[#606D64]">
                    Intercepted pre-publication by Local Safety Filter &amp; Google Gemini 3.5 Flash-Lite multimodal inspection.
                  </p>
                </div>
              </div>

              {modLogs.length === 0 ? (
                <div className="p-10 text-center text-xs text-[#606D64]">
                  No prohibited listing attempts have been blocked yet.
                </div>
              ) : (
                <div className="overflow-x-auto">
                  <table className="admin-table">
                    <thead>
                      <tr>
                        <th>Attempted Item</th>
                        <th>Policy &amp; Category</th>
                        <th>Detection Engine</th>
                        <th>Student</th>
                        <th>Timestamp</th>
                      </tr>
                    </thead>
                    <tbody>
                      {modLogs
                        .filter(
                          (m) =>
                            !q ||
                            m.title?.toLowerCase().includes(q) ||
                            m.category?.toLowerCase().includes(q) ||
                            m.userEmail?.toLowerCase().includes(q)
                        )
                        .map((m) => (
                          <tr key={m.id}>
                            <td>
                              <p className="text-sm font-semibold text-[#131A15]">{m.title}</p>
                              <p className="text-xs text-[#B91C1C] mt-0.5 max-w-md">{m.reason}</p>
                            </td>
                            <td>
                              <span className="inline-block text-[11px] font-semibold px-2.5 py-0.5 rounded-full bg-[#FDECEA] text-[#B91C1C]">
                                {m.category}
                              </span>
                              <p className="text-[11px] text-[#606D64] mt-1">{m.policySection}</p>
                            </td>
                            <td>
                              <span className="text-xs font-mono font-semibold text-[#37433B]">
                                {m.source || 'SAFETY_GATE'}
                              </span>
                            </td>
                            <td className="text-xs text-[#37433B]">{m.userEmail || m.uid}</td>
                            <td className="text-xs text-[#606D64] tabular-nums whitespace-nowrap">
                              {formatDate(m.createdAt)}
                            </td>
                          </tr>
                        ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>
          )}

          {/* ======================== LISTINGS TAB ======================== */}
          {tab === 'listings' && (
            <div className="space-y-4">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="flex flex-wrap items-center gap-1.5">
                  {[
                    { id: 'all', label: 'All Listings' },
                    { id: 'OPEN', label: 'Open' },
                    { id: 'ACTIVE_DEAL', label: 'Reserved / Loan' },
                    { id: 'UNDER_REVIEW', label: 'Needs Review' },
                    { id: 'REMOVED_BY_ADMIN', label: 'Removed' },
                  ].map((pill) => (
                    <button
                      key={pill.id}
                      type="button"
                      onClick={() => setListingFilter(pill.id)}
                      className={`admin-pill-tab ${listingFilter === pill.id ? 'admin-pill-tab-active' : ''}`}
                    >
                      {pill.label}
                    </button>
                  ))}
                </div>

                <div className="flex items-center gap-2">
                  <Filter className="w-3.5 h-3.5 text-[#606D64]" />
                  <select
                    id="listings-college-filter"
                    value={collegeFilter}
                    onChange={(e) => setCollegeFilter(e.target.value)}
                    className="admin-input py-1.5 px-3 text-xs w-auto bg-white"
                  >
                    <option value="ALL">All UC Colleges</option>
                    {UC_COLLEGES.map((c) => (
                      <option key={c} value={c}>
                        {c}
                      </option>
                    ))}
                  </select>
                </div>
              </div>

              <section className="admin-card overflow-hidden">
                {filteredListings.length === 0 ? (
                  <div className="p-10 text-center text-xs text-[#606D64]">
                    No listings match your current filter.
                  </div>
                ) : (
                  <div className="overflow-x-auto">
                    <table className="admin-table">
                      <thead>
                        <tr>
                          <th>Item</th>
                          <th>Mode &amp; Price</th>
                          <th>Colleges</th>
                          <th>Seller</th>
                          <th>Status</th>
                          <th className="text-right">Actions</th>
                        </tr>
                      </thead>
                      <tbody>
                        {filteredListings.map((l) => (
                          <tr key={l.id}>
                            <td>
                              <div className="flex items-center gap-3">
                                <MediaThumb mediaRef={l.photoUris?.[0]} size="sm" />
                                <div className="min-w-0">
                                  <button
                                    type="button"
                                    onClick={() => {
                                      setInspectedListingId(l.id)
                                      setInspectedPhotoIdx(0)
                                    }}
                                    className="text-sm font-semibold text-[#131A15] hover:text-[#07683B] text-left truncate block max-w-xs"
                                  >
                                    {l.title}
                                  </button>
                                  <p className="text-[11px] text-[#606D64] truncate max-w-xs">
                                    {l.meetup} • {formatDate(l.createdAt)}
                                  </p>
                                </div>
                              </div>
                            </td>
                            <td>
                              <p className="text-xs font-bold text-[#131A15] tabular-nums">{formatPrice(l)}</p>
                              <p className="text-[11px] text-[#606D64]">
                                {listingTypeLabel(l.type)} • {conditionLabel(l.condition)}
                              </p>
                            </td>
                            <td className="text-xs text-[#37433B]">
                              {l.targetPrograms?.length ? l.targetPrograms.join(', ') : 'All colleges'}
                            </td>
                            <td className="text-xs text-[#37433B] font-medium">{l.sellerName}</td>
                            <td>
                              <div className="flex flex-wrap gap-1">
                                <StatusPill status={l.status} />
                                {l.aiCheckStatus === 'PENDING_REVIEW' && l.status !== 'REMOVED_BY_ADMIN' && (
                                  <StatusPill status="PENDING_REVIEW" />
                                )}
                              </div>
                            </td>
                            <td className="text-right whitespace-nowrap">
                              <div className="inline-flex items-center gap-1.5">
                                <button
                                  type="button"
                                  onClick={() => {
                                    setInspectedListingId(l.id)
                                    setInspectedPhotoIdx(0)
                                  }}
                                  className="btn-uc-outline py-1.5 px-2.5 text-xs"
                                >
                                  Inspect
                                </button>
                                {l.status !== 'REMOVED_BY_ADMIN' ? (
                                  <button
                                    type="button"
                                    onClick={() => openRemoveModal(l)}
                                    className="px-2.5 py-1.5 rounded-lg bg-[#FDECEA] text-[#B91C1C] hover:bg-red-100 text-xs font-semibold transition"
                                  >
                                    Remove
                                  </button>
                                ) : (
                                  <button
                                    type="button"
                                    onClick={() => approveListing(l)}
                                    className="px-2.5 py-1.5 rounded-lg bg-[#E4EFEA] text-[#07683B] hover:bg-[#d2e6dc] text-xs font-semibold transition"
                                  >
                                    Restore
                                  </button>
                                )}
                              </div>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </section>
            </div>
          )}

          {/* ======================== STUDENTS TAB ======================== */}
          {tab === 'users' && (
            <div className="space-y-4">
              <div className="flex flex-wrap items-center gap-1.5">
                {[
                  { id: 'all', label: `All Students (${users.length})` },
                  {
                    id: 'verified',
                    label: `Active Verified (${users.filter((u) => u.emailVerified !== false && !u.suspended).length})`,
                  },
                  {
                    id: 'unverified',
                    label: `Pending Code (${users.filter((u) => u.emailVerified === false).length})`,
                  },
                  {
                    id: 'warned',
                    label: `Warned (${users.filter((u) => (u.warningCount || 0) > 0).length})`,
                  },
                  {
                    id: 'suspended',
                    label: `Suspended (${users.filter((u) => u.suspended).length})`,
                  },
                ].map((pill) => (
                  <button
                    key={pill.id}
                    type="button"
                    onClick={() => setStudentFilter(pill.id)}
                    className={`admin-pill-tab ${studentFilter === pill.id ? 'admin-pill-tab-active' : ''}`}
                  >
                    {pill.label}
                  </button>
                ))}
              </div>

              <section className="admin-card overflow-hidden">
                {filteredStudents.length === 0 ? (
                  <div className="p-10 text-center text-xs text-[#606D64]">
                    No student accounts match your filter.
                  </div>
                ) : (
                  <div className="overflow-x-auto">
                    <table className="admin-table">
                      <thead>
                        <tr>
                          <th>Student</th>
                          <th>College &amp; Year</th>
                          <th>Verification &amp; Standing</th>
                          <th>Joined</th>
                          <th className="text-right">Governance</th>
                        </tr>
                      </thead>
                      <tbody>
                        {filteredStudents.map((u) => (
                          <tr key={u.id}>
                            <td>
                              <p className="text-sm font-semibold text-[#131A15]">{u.name}</p>
                              <p className="text-xs text-[#606D64]">{u.email}</p>
                              {u.suspended && u.suspendedReason && (
                                <p className="text-[11px] text-[#B91C1C] mt-0.5">
                                  Reason: {u.suspendedReason}
                                </p>
                              )}
                            </td>
                            <td className="text-xs text-[#37433B]">
                              <span className="font-semibold">{u.program || 'CITCS'}</span> • {u.year || '1st Year'}
                            </td>
                            <td>
                              <div className="flex flex-wrap items-center gap-1.5">
                                {u.suspended ? (
                                  <StatusPill status="SUSPENDED" />
                                ) : u.emailVerified === false ? (
                                  <StatusPill status="UNVERIFIED" />
                                ) : (
                                  <StatusPill status="VERIFIED" />
                                )}
                                {(u.warningCount || 0) > 0 && (
                                  <span className="px-2 py-0.5 rounded-full text-[11px] font-semibold bg-[#FFF6E3] text-[#8A5A00] border border-[#F0DCAC]">
                                    {u.warningCount} warning{u.warningCount > 1 ? 's' : ''}
                                  </span>
                                )}
                              </div>
                            </td>
                            <td className="text-xs text-[#606D64] tabular-nums">{formatDate(u.createdAt)}</td>
                            <td className="text-right whitespace-nowrap">
                              <div className="inline-flex items-center gap-2">
                                <button
                                  type="button"
                                  onClick={() => openWarnModal(u)}
                                  className="btn-uc-outline py-1.5 px-3 text-xs text-[#8A5A00] border-[#E8CE92]"
                                >
                                  <AlertTriangle className="w-3.5 h-3.5" />
                                  Warn
                                </button>
                                <button
                                  type="button"
                                  onClick={() => openSuspendModal(u)}
                                  className={
                                    u.suspended
                                      ? 'btn-uc-primary py-1.5 px-3 text-xs'
                                      : 'btn-uc-danger py-1.5 px-3 text-xs'
                                  }
                                >
                                  <Ban className="w-3.5 h-3.5" />
                                  {u.suspended ? 'Reinstate' : 'Suspend'}
                                </button>
                              </div>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </section>
            </div>
          )}

          {/* ======================== ANNOUNCEMENTS TAB ======================== */}
          {tab === 'announcements' && (
            <div className="grid lg:grid-cols-12 gap-6">
              <form onSubmit={publishAnnouncement} className="lg:col-span-5 admin-card p-6 space-y-4 h-fit">
                <div>
                  <h2 className="text-sm font-bold text-[#131A15]">Compose Campus Broadcast</h2>
                  <p className="text-xs text-[#606D64] mt-0.5">
                    Delivered in real time to every verified student’s Android Notifications tab &amp; OS notification tray.
                  </p>
                </div>

                <div>
                  <div className="flex justify-between text-xs mb-1">
                    <label htmlFor="ann-title" className="font-semibold text-[#131A15]">
                      Headline *
                    </label>
                    <span className="text-[#606D64] tabular-nums">{annTitle.length}/80</span>
                  </div>
                  <input
                    id="ann-title"
                    required
                    maxLength={80}
                    placeholder="e.g. Finals Week Safe Meetup Desk Hours"
                    value={annTitle}
                    onChange={(e) => setAnnTitle(e.target.value)}
                    className="admin-input"
                  />
                </div>

                <div>
                  <div className="flex justify-between text-xs mb-1">
                    <label htmlFor="ann-body" className="font-semibold text-[#131A15]">
                      Message *
                    </label>
                    <span className="text-[#606D64] tabular-nums">{annBody.length}/400</span>
                  </div>
                  <textarea
                    id="ann-body"
                    required
                    rows={4}
                    maxLength={400}
                    placeholder="Write a concise campus update for UC students…"
                    value={annBody}
                    onChange={(e) => setAnnBody(e.target.value)}
                    className="admin-input"
                  />
                </div>

                {(annTitle.trim() || annBody.trim()) && (
                  <div className="p-3.5 rounded-xl bg-[#FAF9F3] border border-[#E3DECB] space-y-1">
                    <p className="text-[10px] font-bold uppercase tracking-wider text-[#07683B]">
                      Android In-App Preview
                    </p>
                    <p className="text-xs font-bold text-[#131A15]">{annTitle || 'Announcement headline'}</p>
                    <p className="text-xs text-[#606D64]">{annBody || 'Announcement message…'}</p>
                  </div>
                )}

                <button
                  id="btn-publish-announcement"
                  type="submit"
                  disabled={annSending}
                  className="btn-uc-primary w-full py-2.5 text-xs disabled:opacity-50"
                >
                  <Send className="w-3.5 h-3.5" />
                  {annSending ? 'Broadcasting…' : 'Broadcast to All UC Students'}
                </button>
              </form>

              <section className="lg:col-span-7 admin-card overflow-hidden h-fit">
                <div className="px-6 py-4 border-b border-[#E3DECB]">
                  <h2 className="text-sm font-bold text-[#131A15]">
                    Published Broadcasts ({announcements.length})
                  </h2>
                </div>
                {announcements.length === 0 ? (
                  <div className="p-8 text-center text-xs text-[#606D64]">No campus broadcasts sent yet.</div>
                ) : (
                  <div className="divide-y divide-[#EFECE0]">
                    {announcements.map((a) => (
                      <div key={a.id} className="p-5 sm:px-6 space-y-1">
                        <div className="flex items-center justify-between gap-2">
                          <span className="text-sm font-bold text-[#07683B]">{a.title}</span>
                          <span className="text-[11px] text-[#606D64] tabular-nums">
                            {formatDate(a.createdAt)}
                          </span>
                        </div>
                        <p className="text-xs text-[#37433B] leading-relaxed">{a.body}</p>
                        <p className="text-[11px] text-[#606D64]">Sent by {a.authorEmail}</p>
                      </div>
                    ))}
                  </div>
                )}
              </section>
            </div>
          )}

          {/* ======================== HTML EMAIL LAYOUTS TAB ======================== */}
          {tab === 'emails' && (
            <div className="grid lg:grid-cols-12 gap-6">
              <div className="lg:col-span-4 min-w-0 space-y-4">
                <section className="admin-card p-5 space-y-3">
                  <h2 className="text-sm font-bold text-[#131A15]">Email Template Selector</h2>
                  <p className="text-xs text-[#606D64]">
                    Every verification button and moderation notice uses responsive, UC Outlook-compatible HTML tables.
                  </p>
                  <div className="space-y-1.5">
                    {[
                      { id: 'verification', label: 'Verify UC Email (HTML Button)' },
                      { id: 'removed', label: 'Listing Removed Notice' },
                      { id: 'warning', label: 'Official Student Warning' },
                      { id: 'suspended', label: 'Account Suspension Notice' },
                      { id: 'reinstated', label: 'Account Reinstated Notice' },
                    ].map((tpl) => (
                      <button
                        key={tpl.id}
                        type="button"
                        onClick={() => setEmailPreviewType(tpl.id)}
                        className={`w-full text-left px-3.5 py-2.5 rounded-xl text-xs font-semibold transition flex items-center justify-between ${
                          emailPreviewType === tpl.id
                            ? 'bg-[#07683B] text-white'
                            : 'bg-[#FAF9F3] text-[#37433B] hover:bg-[#EFECE0]'
                        }`}
                      >
                        <span>{tpl.label}</span>
                        <ChevronRight className="w-3.5 h-3.5 opacity-75" />
                      </button>
                    ))}
                  </div>
                  <div className="pt-2 border-t border-[#E3DECB]">
                    <a
                      href="/auth/action"
                      target="_blank"
                      rel="noopener noreferrer"
                      className="w-full inline-flex items-center justify-center gap-1.5 px-3.5 py-2 rounded-xl border border-[#07683B]/30 bg-[#E4EFEA]/60 text-[#07683B] hover:bg-[#E4EFEA] text-xs font-semibold transition"
                    >
                      <span>Preview Custom Verify Page (/auth/action)</span>
                      <ChevronRight className="w-3.5 h-3.5" />
                    </a>
                  </div>
                </section>

                <form onSubmit={handleSendTestEmail} className="admin-card p-5 space-y-3">
                  <div className="flex items-center justify-between gap-2">
                    <h3 className="text-xs font-bold uppercase tracking-wider text-[#131A15]">
                      Webhook Status &amp; Test
                    </h3>
                    <StatusPill status={isEmailWebhookConfigured ? 'APPROVED' : 'PENDING_REVIEW'} />
                  </div>
                  <p className="text-xs text-[#606D64] leading-relaxed break-words">
                    {isEmailWebhookConfigured
                      ? 'Apps Script HTML email relay is active. Send a live test of the selected template to a @students.uc-bcf.edu.ph inbox.'
                      : 'Deploy docs/EMAIL_RELAY_APPS_SCRIPT.gs and set VITE_EMAIL_WEBHOOK_URL in admin-web/.env.local to send live emails.'}
                  </p>
                  <input
                    type="email"
                    placeholder="student@students.uc-bcf.edu.ph"
                    value={testEmailTo}
                    onChange={(e) => setTestEmailTo(e.target.value)}
                    className="admin-input text-xs"
                  />
                  <button
                    type="submit"
                    disabled={!isEmailWebhookConfigured || sendingTestEmail || !testEmailTo.trim()}
                    className="btn-uc-primary w-full py-2 text-xs disabled:opacity-50"
                  >
                    <Send className="w-3.5 h-3.5" />
                    {sendingTestEmail ? 'Sending HTML Email…' : 'Send Test HTML Email'}
                  </button>
                </form>
              </div>

              <section className="lg:col-span-8 min-w-0 admin-card overflow-hidden">
                <div className="px-6 py-3.5 border-b border-[#E3DECB] bg-[#FAF9F3] flex flex-wrap items-center justify-between gap-2">
                  <div className="min-w-0">
                    <p className="text-[11px] text-[#606D64]">Subject Line</p>
                    <p className="text-xs font-bold text-[#131A15] truncate">{previewEmail.subject}</p>
                  </div>
                  <span className="text-[11px] font-medium px-2.5 py-1 rounded-full bg-white border border-[#E3DECB] text-[#37433B]">
                    UC Microsoft Outlook HTML Preview
                  </span>
                </div>
                <iframe
                  title="HTML Email Preview"
                  srcDoc={previewEmail.html}
                  className="w-full h-[560px] border-0 bg-[#F9F6E7]"
                />
              </section>
            </div>
          )}

          {/* ======================== AUDIT TRAIL TAB ======================== */}
          {tab === 'audit' && (
            <section className="admin-card overflow-hidden">
              <div className="px-6 py-4 border-b border-[#E3DECB]">
                <h2 className="text-sm font-bold text-[#131A15]">
                  Immutable Moderator Audit Trail ({auditLogs.length})
                </h2>
                <p className="text-xs text-[#606D64]">
                  Protected by Firestore Security Rules — audit records cannot be altered or deleted by any client.
                </p>
              </div>

              {auditLogs.length === 0 ? (
                <div className="p-10 text-center text-xs text-[#606D64]">No audit entries recorded yet.</div>
              ) : (
                <div className="overflow-x-auto">
                  <table className="admin-table">
                    <thead>
                      <tr>
                        <th>Action</th>
                        <th>Details</th>
                        <th>Target ID</th>
                        <th>Moderator</th>
                        <th>Timestamp</th>
                      </tr>
                    </thead>
                    <tbody>
                      {auditLogs
                        .filter(
                          (log) =>
                            !q ||
                            log.action?.toLowerCase().includes(q) ||
                            log.details?.toLowerCase().includes(q) ||
                            log.adminEmail?.toLowerCase().includes(q)
                        )
                        .map((log) => (
                          <tr key={log.id}>
                            <td>
                              <span className="px-2.5 py-1 rounded-md bg-[#E4EFEA] text-[#07683B] font-mono text-[11px] font-bold">
                                {log.action}
                              </span>
                            </td>
                            <td className="text-xs text-[#131A15]">{log.details}</td>
                            <td className="text-xs font-mono text-[#606D64]">{log.targetId || '—'}</td>
                            <td className="text-xs text-[#37433B]">{log.adminEmail}</td>
                            <td className="text-xs text-[#606D64] tabular-nums whitespace-nowrap">
                              {formatDate(log.createdAt)}
                            </td>
                          </tr>
                        ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>
          )}
        </main>
      </div>

      {/* ======================== LISTING INSPECTION DRAWER ======================== */}
      {inspectedListing && (
        <div className="fixed inset-0 z-50 flex justify-end">
          <div className="fixed inset-0 modal-backdrop" onClick={() => setInspectedListingId(null)} />
          <aside className="relative w-full max-w-md bg-white h-full shadow-2xl border-l border-[#E3DECB] z-10 flex flex-col drawer-panel">
            <div className="px-6 py-4 border-b border-[#E3DECB] flex items-center justify-between">
              <div>
                <p className="text-[11px] font-bold uppercase tracking-wider text-[#07683B]">Listing Inspection</p>
                <h2 className="text-base font-bold text-[#131A15] line-clamp-1">{inspectedListing.title}</h2>
              </div>
              <button
                type="button"
                onClick={() => setInspectedListingId(null)}
                className="p-2 rounded-xl hover:bg-[#FAF9F3] text-[#606D64]"
                aria-label="Close drawer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="flex-1 overflow-y-auto p-6 space-y-5">
              <div className="space-y-2">
                <MediaThumb mediaRef={inspectedListing.photoUris?.[inspectedPhotoIdx]} size="lg" />
                {Array.isArray(inspectedListing.photoUris) && inspectedListing.photoUris.length > 1 && (
                  <div className="flex gap-2 overflow-x-auto pb-1">
                    {inspectedListing.photoUris.map((ref, idx) => (
                      <button
                        key={`${ref}-${idx}`}
                        type="button"
                        onClick={() => setInspectedPhotoIdx(idx)}
                        className={`rounded-xl p-0.5 border-2 transition ${
                          inspectedPhotoIdx === idx ? 'border-[#07683B]' : 'border-transparent opacity-70'
                        }`}
                      >
                        <MediaThumb mediaRef={ref} size="sm" />
                      </button>
                    ))}
                  </div>
                )}
              </div>

              <div className="flex flex-wrap items-center gap-2">
                <StatusPill status={inspectedListing.status} />
                <StatusPill status={inspectedListing.aiCheckStatus} />
                <span className="text-xs font-bold text-[#07683B] ml-auto tabular-nums">
                  {formatPrice(inspectedListing)}
                </span>
              </div>

              <div className="space-y-2 text-xs">
                <p className="font-semibold text-[#131A15]">Description</p>
                <p className="text-[#37433B] leading-relaxed bg-[#FAF9F3] p-3.5 rounded-xl border border-[#E3DECB]">
                  {inspectedListing.description || 'No description provided.'}
                </p>
              </div>

              <div className="grid grid-cols-2 gap-3 text-xs">
                <div className="p-3 rounded-xl bg-[#FAF9F3] border border-[#E3DECB]">
                  <p className="text-[#606D64]">Seller</p>
                  <p className="font-semibold text-[#131A15] mt-0.5">{inspectedListing.sellerName}</p>
                </div>
                <div className="p-3 rounded-xl bg-[#FAF9F3] border border-[#E3DECB]">
                  <p className="text-[#606D64]">Applicable Colleges</p>
                  <p className="font-semibold text-[#131A15] mt-0.5">
                    {inspectedListing.targetPrograms?.length
                      ? inspectedListing.targetPrograms.join(', ')
                      : 'All colleges'}
                  </p>
                </div>
                <div className="p-3 rounded-xl bg-[#FAF9F3] border border-[#E3DECB] col-span-2">
                  <p className="text-[#606D64] flex items-center gap-1">
                    <MapPin className="w-3.5 h-3.5 text-[#07683B]" /> Safe Meetup Spot
                  </p>
                  <p className="font-semibold text-[#131A15] mt-0.5">
                    {inspectedListing.meetup}
                    {inspectedListing.meetupNote ? ` • ${inspectedListing.meetupNote}` : ''}
                  </p>
                </div>
              </div>
            </div>

            <div className="p-5 border-t border-[#E3DECB] bg-[#FAF9F3] flex items-center gap-2.5">
              {(inspectedListing.status === 'UNDER_REVIEW' ||
                inspectedListing.status === 'REMOVED_BY_ADMIN' ||
                inspectedListing.aiCheckStatus === 'PENDING_REVIEW') && (
                <button
                  type="button"
                  onClick={() => approveListing(inspectedListing)}
                  className="btn-uc-primary flex-1 py-2.5 text-xs"
                >
                  <CheckCircle2 className="w-4 h-4" />
                  {inspectedListing.status === 'REMOVED_BY_ADMIN' ? 'Restore Listing' : 'Approve Listing'}
                </button>
              )}
              {inspectedListing.status !== 'REMOVED_BY_ADMIN' && (
                <button
                  type="button"
                  onClick={() => openRemoveModal(inspectedListing)}
                  className="btn-uc-danger flex-1 py-2.5 text-xs"
                >
                  <Trash2 className="w-4 h-4" />
                  Remove Listing
                </button>
              )}
            </div>
          </aside>
        </div>
      )}

      {/* ======================== MODERATION REASON MODAL ======================== */}
      {actionModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div
            className="fixed inset-0 modal-backdrop"
            onClick={() => !actionModal.busy && setActionModal(null)}
          />
          <form
            onSubmit={submitActionModal}
            className="relative z-10 w-full max-w-lg admin-card p-6 sm:p-7 space-y-5 modal-panel"
          >
            <div className="flex items-start justify-between gap-3">
              <div>
                <p className="text-[11px] font-bold uppercase tracking-wider text-[#B91C1C]">
                  {actionModal.kind === 'remove_listing'
                    ? 'Remove Campus Listing'
                    : actionModal.kind === 'warn_student'
                    ? 'Official Student Warning'
                    : 'Suspend Student Account'}
                </p>
                <h2 className="text-lg font-bold text-[#131A15] mt-0.5">
                  {actionModal.kind === 'remove_listing'
                    ? actionModal.listing?.title
                    : actionModal.student?.name}
                </h2>
              </div>
              <button
                type="button"
                onClick={() => setActionModal(null)}
                disabled={actionModal.busy}
                className="p-1.5 rounded-lg hover:bg-[#FAF9F3] text-[#606D64]"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="space-y-2">
              <p className="text-xs font-semibold text-[#37433B]">Quick Reason Presets</p>
              <div className="flex flex-wrap gap-1.5">
                {(actionModal.kind === 'remove_listing'
                  ? REMOVAL_PRESETS
                  : actionModal.kind === 'warn_student'
                  ? WARNING_PRESETS
                  : SUSPENSION_PRESETS
                ).map((preset) => (
                  <button
                    key={preset}
                    type="button"
                    onClick={() => setActionModal((prev) => ({ ...prev, input: preset }))}
                    className={`text-left text-[11px] px-2.5 py-1.5 rounded-lg border transition ${
                      actionModal.input === preset
                        ? 'bg-[#E4EFEA] border-[#07683B] text-[#07683B] font-semibold'
                        : 'bg-[#FAF9F3] border-[#E3DECB] text-[#37433B] hover:bg-[#EFECE0]'
                    }`}
                  >
                    {preset}
                  </button>
                ))}
              </div>
            </div>

            <div>
              <label htmlFor="moderation-reason-input" className="block text-xs font-semibold text-[#131A15] mb-1.5">
                Message sent to student (In-App Notification &amp; HTML Email) *
              </label>
              <textarea
                id="moderation-reason-input"
                required
                rows={3}
                value={actionModal.input}
                onChange={(e) => setActionModal((prev) => ({ ...prev, input: e.target.value }))}
                className="admin-input"
              />
            </div>

            <div className="flex items-center justify-end gap-2.5 pt-1">
              <button
                type="button"
                disabled={actionModal.busy}
                onClick={() => setActionModal(null)}
                className="btn-uc-outline py-2 px-4 text-xs"
              >
                Cancel
              </button>
              <button
                type="submit"
                disabled={actionModal.busy || !actionModal.input.trim()}
                className={
                  actionModal.kind === 'warn_student'
                    ? 'btn-uc-primary py-2 px-4 text-xs'
                    : 'btn-uc-danger py-2 px-4 text-xs'
                }
              >
                {actionModal.busy
                  ? 'Applying…'
                  : actionModal.kind === 'remove_listing'
                  ? 'Confirm Removal'
                  : actionModal.kind === 'warn_student'
                  ? 'Send Official Warning'
                  : 'Suspend Account'}
              </button>
            </div>
          </form>
        </div>
      )}
    </div>
  )
}
