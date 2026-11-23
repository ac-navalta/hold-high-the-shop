package com.nevergrowold.holdhightheshop.data

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.nevergrowold.holdhightheshop.Availability
import com.nevergrowold.holdhightheshop.DealEvent
import com.nevergrowold.holdhightheshop.Deals
import com.nevergrowold.holdhightheshop.Exchange
import com.nevergrowold.holdhightheshop.FreeTime
import com.nevergrowold.holdhightheshop.Issue
import com.nevergrowold.holdhightheshop.Listing
import com.nevergrowold.holdhightheshop.Listings
import com.nevergrowold.holdhightheshop.R
import com.nevergrowold.holdhightheshop.Rentals
import com.nevergrowold.holdhightheshop.Request
import com.nevergrowold.holdhightheshop.Seller
import com.nevergrowold.holdhightheshop.UserState
import com.nevergrowold.holdhightheshop.media.ImagePipeline
import com.nevergrowold.holdhightheshop.security.SecureSessionStore
import com.nevergrowold.holdhightheshop.security.UcEmailValidator
import com.nevergrowold.holdhightheshop.system.MarketplaceBroadcasts
import com.nevergrowold.holdhightheshop.system.NotificationHelper
import com.nevergrowold.holdhightheshop.system.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single Source of Truth repository powering Hold High The Shop.
 *
 * Dual-mode architecture (strictly ₱0 / Firebase Spark compatible):
 * 1. **Live Cloud Mode (when `app/google-services.json` is configured)**:
 *    - Firebase Auth (Email + Password + real UC Outlook email verification)
 *    - Cloud Firestore real-time `addSnapshotListener` streams across all devices
 *      (`users`, `listings`, `requests`, `rentals`, `exchanges`, `chats`, `messages`,
 *      `notifications`, `announcements`, `reports`, `moderation_logs`)
 *    - Firestore `media/{mediaId}` WebP sync via [ImagePipeline] (zero Blaze Storage!)
 * 2. **Offline Cache & Local Multi-User Engine (Room SQLite + Android Keystore)**:
 *    - Persists drafts, scheduled alarms, notifications, encrypted session keys, and
 *      full marketplace snapshot in Room so the app stays responsive offline and runs
 *      every single end-to-end flow deterministically in Robolectric tests or before
 *      Firebase credentials are added.
 */
object AppRepository {

    private const val TAG = "AppRepository"
    private const val SNAPSHOT_KEY = "marketplace_state_v1"
    private const val KEY_ACTIVE_UID = "active_uid"
    private const val KEY_ACTIVE_EMAIL = "active_email"
    private const val KEY_SETTINGS_JSON = "settings_json"
    private const val KEY_DEFAULT_MEETUP = "default_meetup"

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var initialized = false

    private val _stateVersion = MutableStateFlow(0L)
    val stateVersion: StateFlow<Long> = _stateVersion.asStateFlow()

    // ---- Reactive in-memory mirrored state (backed by Firestore + Room snapshot) ----
    private var activeUid: String = ""
    private var profile: UserState.UserProfile = UserState.UserProfile()
    private var settings: UserState.SettingsState = UserState.SettingsState()
    private var defaultMeetupIndex: Int = 0
    private var draft: UserState.ListingDraft? = null

    private val users = LinkedHashMap<String, Seller>()
    private val listings = CopyOnWriteArrayList<Listing>()
    private val requests = CopyOnWriteArrayList<Request>()
    private val rentals = LinkedHashMap<String, UserState.Rental>() // key = listingId
    private val exchanges = CopyOnWriteArrayList<Exchange>()
    private val threads = LinkedHashMap<String, ChatThread>()
    private val messagesByThread = LinkedHashMap<String, MutableList<UserState.ChatMessage>>()
    private val notifications = CopyOnWriteArrayList<AppNotification>()
    private val announcements = CopyOnWriteArrayList<CampusAnnouncement>()
    private val issuesBySubjectAndReporter = LinkedHashMap<String, Issue>()
    private val savedByUid = LinkedHashMap<String, MutableSet<String>>()
    private val blockedByUid = LinkedHashMap<String, MutableSet<String>>()
    private val reportedListingsByUid = LinkedHashMap<String, MutableSet<String>>()
    private val receivedByUid = LinkedHashMap<String, MutableSet<String>>()
    private val swappedInByUid = LinkedHashMap<String, MutableSet<String>>()
    private val lateCancelsByUid = LinkedHashMap<String, Int>()
    private val ratingsByUserAndExchange = LinkedHashMap<String, Pair<Int, String>>()
    private val ratedAtByUserAndExchange = LinkedHashMap<String, Long>()
    private val moderationLogs = CopyOnWriteArrayList<Map<String, Any>>()

    private val firestoreListeners = mutableListOf<ListenerRegistration>()
    private val threadMessageListeners = LinkedHashMap<String, ListenerRegistration>()
    private val notifiedIds = HashSet<String>()
    private var initialNotifSnapshotDone = false
    private var initialAnnounceSnapshotDone = false

    // =========================================================================
    // Initialization & Firebase Detection
    // =========================================================================

    fun isFirebaseConfigured(context: Context? = appContext): Boolean {
        val ctx = context?.applicationContext ?: return false
        return try {
            FirebaseApp.getApps(ctx).isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    fun ensureInitialized(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            restoreFromLocalStorageSync(ctx)
            restoreSessionSync(ctx)
            initialized = true
            if (isFirebaseConfigured(ctx) && activeUid.isNotEmpty() && profile.emailVerified && !profile.suspended) {
                startFirestoreListeners(ctx)
            }
        }
    }

    private fun bumpState(broadcast: Boolean = true, action: String = MarketplaceBroadcasts.ACTION_STATE_CHANGED) {
        _stateVersion.value = _stateVersion.value + 1L
        val ctx = appContext ?: return
        persistSnapshotAsync(ctx)
        if (broadcast) {
            MarketplaceBroadcasts.sendInternal(ctx, action)
        }
    }

    // =========================================================================
    // Authentication (Strict @students.uc-bcf.edu.ph + Email Verification)
    // =========================================================================

    fun currentUid(): String = activeUid
    fun isSignedIn(): Boolean = activeUid.isNotEmpty()
    fun isVerifiedSignedIn(): Boolean = activeUid.isNotEmpty() && profile.emailVerified && !profile.suspended
    fun currentProfile(): UserState.UserProfile = profile
    fun currentSettings(): UserState.SettingsState = settings
    fun currentDefaultMeetup(): Int = defaultMeetupIndex
    fun setDefaultMeetupInMemory(value: Int) {
        defaultMeetupIndex = value.coerceIn(0, Listings.MEETUP_POINTS.lastIndex)
    }

    fun currentDraft(): UserState.ListingDraft? = draft
    fun setDraftLocal(value: UserState.ListingDraft?) {
        draft = value
        val ctx = appContext ?: return
        val uid = activeUid.ifEmpty { return }
        ioScope.launch {
            try {
                val dao = AppDatabase.get(ctx).draftDao()
                if (value == null) dao.clearDraft(uid)
                else dao.saveDraft(draftToEntity(uid, value))
            } catch (e: Exception) {
                Log.w(TAG, "Draft persist error", e)
            }
        }
    }

    suspend fun signUp(
        context: Context,
        rawEmail: String,
        password: String,
        rawName: String,
        program: String,
        year: String,
    ): AuthResult = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        val email = UcEmailValidator.normalize(rawEmail)
        if (!UcEmailValidator.isValidUcStudentEmail(email)) {
            return@withContext AuthResult.Error("Only @students.uc-bcf.edu.ph student accounts are allowed.")
        }
        val pwCheck = UcEmailValidator.checkPassword(password)
        if (!pwCheck.valid) {
            return@withContext AuthResult.Error(pwCheck.message)
        }
        val fullName = UcEmailValidator.sanitizeSingleLine(rawName, 60)
        if (fullName.length < 2) {
            return@withContext AuthResult.Error("Please enter your full name.")
        }
        val validProgram = if (program in Listings.PROGRAMS) program else Listings.PROGRAMS.first()
        val validYear = UcEmailValidator.sanitizeSingleLine(year, 24).ifEmpty { "1st Year" }

        if (isFirebaseConfigured(context)) {
            return@withContext signUpFirebase(context, email, password, fullName, validProgram, validYear)
        }
        return@withContext signUpLocal(context, email, password, fullName, validProgram, validYear)
    }

    private suspend fun signUpFirebase(
        context: Context,
        email: String,
        password: String,
        fullName: String,
        program: String,
        year: String,
    ): AuthResult {
        return try {
            val auth = FirebaseAuth.getInstance()
            val result = auth.createUserWithEmailAndPassword(email, password).await()
            val user = result.user ?: return AuthResult.Error("Could not create account. Please try again.")
            val uid = user.uid

            // Write initial Firestore student profile (allowed before email verification by firestore.rules).
            val now = System.currentTimeMillis()
            val userDoc = hashMapOf(
                "uid" to uid,
                "email" to email,
                "name" to fullName,
                "program" to program,
                "year" to year,
                "bio" to "",
                "avatarUri" to "",
                "showProgram" to true,
                "showActiveStatus" to true,
                "defaultMeetup" to 0,
                "suspended" to false,
                "suspendedReason" to "",
                "warningCount" to 0,
                "lateCancels" to 0,
                "blockedUids" to emptyList<String>(),
                "savedIds" to emptyList<String>(),
                "createdAt" to now,
            )
            FirebaseFirestore.getInstance().collection("users").document(uid).set(userDoc).await()
            user.sendEmailVerification().await()

            activateSession(
                context,
                UserState.UserProfile(
                    uid = uid,
                    name = fullName,
                    email = email,
                    program = program,
                    year = year,
                    emailVerified = false,
                ),
            )
            AuthResult.NeedsVerification(uid, email)
        } catch (e: FirebaseAuthException) {
            AuthResult.Error(friendlyAuthError(e))
        } catch (e: Exception) {
            Log.w(TAG, "Firebase signup error", e)
            AuthResult.Error("Unable to create account right now. Check your internet connection.")
        }
    }

    private suspend fun signUpLocal(
        context: Context,
        email: String,
        password: String,
        fullName: String,
        program: String,
        year: String,
    ): AuthResult {
        val dao = AppDatabase.get(context).localAccountDao()
        if (dao.findByEmail(email) != null) {
            return AuthResult.Error("An account with this UC student email already exists. Try signing in instead.")
        }
        val uid = "uc_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val entity = LocalAccountEntity(
            uid = uid,
            email = email,
            name = fullName,
            program = program,
            year = year,
            passwordHash = SecureSessionStore.hashPassword(password),
            emailVerified = false,
        )
        dao.upsert(entity)
        registerSellerFromEntity(entity)
        activateSession(context, entityToProfile(entity))
        return AuthResult.NeedsVerification(uid, email)
    }

    suspend fun signIn(
        context: Context,
        rawEmail: String,
        password: String,
    ): AuthResult = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        val email = UcEmailValidator.normalize(rawEmail)
        if (!UcEmailValidator.isValidUcStudentEmail(email)) {
            return@withContext AuthResult.Error("Enter your @students.uc-bcf.edu.ph email.")
        }
        if (password.isEmpty()) {
            return@withContext AuthResult.Error("Enter your password.")
        }
        if (isFirebaseConfigured(context)) {
            return@withContext signInFirebase(context, email, password)
        }
        return@withContext signInLocal(context, email, password)
    }

    private suspend fun signInFirebase(context: Context, email: String, password: String): AuthResult {
        return try {
            val auth = FirebaseAuth.getInstance()
            val result = auth.signInWithEmailAndPassword(email, password).await()
            val user = result.user ?: return AuthResult.Error("Invalid email or password.")
            user.reload().await()
            val verified = user.isEmailVerified
            val uid = user.uid

            val snap = try {
                FirebaseFirestore.getInstance().collection("users").document(uid).get().await()
            } catch (_: Exception) {
                null
            }
            val loadedProfile = if (snap != null && snap.exists()) {
                UserState.UserProfile(
                    uid = uid,
                    name = snap.getString("name").orEmpty().ifBlank { email.substringBefore('@') },
                    email = email,
                    program = snap.getString("program").orEmpty().ifBlank { "CITCS" },
                    year = snap.getString("year").orEmpty().ifBlank { "1st Year" },
                    bio = snap.getString("bio").orEmpty(),
                    avatarUri = snap.getString("avatarUri")?.takeIf { it.isNotBlank() },
                    emailVerified = verified,
                    suspended = snap.getBoolean("suspended") == true,
                    suspendedReason = snap.getString("suspendedReason").orEmpty(),
                    warningCount = (snap.getLong("warningCount") ?: 0L).toInt(),
                )
            } else {
                UserState.UserProfile(uid = uid, name = email.substringBefore('@'), email = email, emailVerified = verified)
            }

            activateSession(context, loadedProfile)
            when {
                loadedProfile.suspended -> AuthResult.Suspended(
                    loadedProfile.suspendedReason.ifBlank { "Your account has been suspended by campus moderators." },
                )
                !verified -> AuthResult.NeedsVerification(uid, email)
                else -> {
                    startFirestoreListeners(context)
                    AuthResult.VerifiedSuccess(uid, email)
                }
            }
        } catch (e: FirebaseAuthException) {
            AuthResult.Error(friendlyAuthError(e))
        } catch (e: Exception) {
            Log.w(TAG, "Firebase sign-in failed", e)
            AuthResult.Error("Invalid email or password.")
        }
    }

    private suspend fun signInLocal(context: Context, email: String, password: String): AuthResult {
        val dao = AppDatabase.get(context).localAccountDao()
        val account = dao.findByEmail(email)
            ?: return AuthResult.Error("Invalid email or password.")
        if (!SecureSessionStore.verifyPassword(password, account.passwordHash)) {
            return AuthResult.Error("Invalid email or password.")
        }
        registerSellerFromEntity(account)
        val loaded = entityToProfile(account)
        activateSession(context, loaded)
        return when {
            loaded.suspended -> AuthResult.Suspended(
                loaded.suspendedReason.ifBlank { "Your account has been suspended by campus moderators." },
            )
            !loaded.emailVerified -> AuthResult.NeedsVerification(loaded.uid, loaded.email)
            else -> AuthResult.VerifiedSuccess(loaded.uid, loaded.email)
        }
    }

    suspend fun checkEmailVerified(context: Context): AuthResult = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        if (isFirebaseConfigured(context)) {
            return@withContext try {
                val user = FirebaseAuth.getInstance().currentUser
                    ?: return@withContext AuthResult.Error("Session expired. Please sign in again.")
                user.reload().await()
                if (user.isEmailVerified) {
                    val updated = profile.copy(emailVerified = true)
                    activateSession(context, updated)
                    startFirestoreListeners(context)
                    AuthResult.VerifiedSuccess(updated.uid, updated.email)
                } else {
                    AuthResult.NeedsVerification(user.uid, user.email.orEmpty())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Email verification reload failed", e)
                AuthResult.Error("Could not check verification right now. Check your connection.")
            }
        }
        // Local mode (when google-services.json isn't added yet): check Room account flag.
        val uid = activeUid.ifEmpty { return@withContext AuthResult.Error("Not signed in.") }
        val dao = AppDatabase.get(context).localAccountDao()
        val entity = dao.findByUid(uid) ?: return@withContext AuthResult.Error("Account not found.")
        return@withContext if (entity.emailVerified) {
            val updated = entityToProfile(entity)
            activateSession(context, updated)
            AuthResult.VerifiedSuccess(updated.uid, updated.email)
        } else {
            AuthResult.NeedsVerification(entity.uid, entity.email)
        }
    }

    /**
     * Local-only verification helper used exclusively when Firebase isn't configured yet
     * (offline classroom preview or Robolectric E2E tests). Never active when Firebase is connected.
     */
    suspend fun markEmailVerifiedLocalOnly(context: Context): AuthResult = withContext(Dispatchers.IO) {
        if (isFirebaseConfigured(context)) {
            return@withContext checkEmailVerified(context)
        }
        val uid = activeUid.ifEmpty { return@withContext AuthResult.Error("Not signed in.") }
        val dao = AppDatabase.get(context).localAccountDao()
        dao.setVerified(uid, true)
        val updated = profile.copy(emailVerified = true)
        activateSession(context, updated)
        AuthResult.VerifiedSuccess(updated.uid, updated.email)
    }

    suspend fun resendVerificationEmail(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        if (isFirebaseConfigured(context)) {
            return@withContext try {
                val user = FirebaseAuth.getInstance().currentUser
                    ?: return@withContext Result.failure(IllegalStateException("Not signed in"))
                user.sendEmailVerification().await()
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        Result.success(Unit)
    }

    suspend fun sendPasswordReset(context: Context, rawEmail: String): Result<Unit> = withContext(Dispatchers.IO) {
        val email = UcEmailValidator.normalize(rawEmail)
        if (!UcEmailValidator.isValidUcStudentEmail(email)) {
            return@withContext Result.failure(IllegalArgumentException("Enter your @students.uc-bcf.edu.ph email."))
        }
        if (isFirebaseConfigured(context)) {
            return@withContext try {
                FirebaseAuth.getInstance().sendPasswordResetEmail(email).await()
                Result.success(Unit)
            } catch (_: Exception) {
                // Always return success neutrally to prevent user enumeration.
                Result.success(Unit)
            }
        }
        Result.success(Unit)
    }

    /**
     * Full clean Logout: detaches Firestore listeners, cancels scheduled alarms &
     * OS notifications for this user, wipes encrypted session tokens & local draft.
     */
    suspend fun signOut(context: Context) = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        val departingUid = activeUid
        detachAllFirestoreListeners()
        if (isFirebaseConfigured(ctx)) {
            try {
                FirebaseAuth.getInstance().signOut()
            } catch (_: Exception) {
            }
        }
        if (departingUid.isNotEmpty()) {
            ReminderScheduler.cancelAllForUser(ctx, departingUid)
        }
        NotificationHelper.cancelAll(ctx)
        SecureSessionStore.clearAll(ctx)
        ImagePipeline.clearMemoryCache()

        activeUid = ""
        profile = UserState.UserProfile()
        draft = null
        notifiedIds.clear()
        initialNotifSnapshotDone = false
        initialAnnounceSnapshotDone = false
        bumpState(broadcast = true)
    }

    /**
     * Google Play-compliant Account Deletion with password re-authentication.
     * Closes the student's open listings, deletes their user record, cleans up local state,
     * and deletes their Firebase Auth user.
     */
    suspend fun deleteAccount(context: Context, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        val uid = activeUid.ifEmpty { return@withContext Result.failure(IllegalStateException("Not signed in")) }

        // Block deletion if student currently has an unreturned loan!
        val hasActiveLoan = rentals.values.any { it.lenderUid == uid || it.borrowerUid == uid }
        if (hasActiveLoan) {
            return@withContext Result.failure(
                IllegalStateException("Finish or return your active campus loans before deleting your account."),
            )
        }

        if (isFirebaseConfigured(ctx)) {
            try {
                val user = FirebaseAuth.getInstance().currentUser
                    ?: return@withContext Result.failure(IllegalStateException("Session expired. Sign in again."))
                val credential = EmailAuthProvider.getCredential(user.email ?: profile.email, password)
                user.reauthenticate(credential).await()

                // Close own open/paused listings in Firestore
                val db = FirebaseFirestore.getInstance()
                listings.filter { it.ownerUid == uid && it.status in setOf(ListingStatus.OPEN, ListingStatus.PAUSED) }
                    .forEach { listing ->
                        runCatching {
                            db.collection("listings").document(listing.id)
                                .update("status", ListingStatus.CLOSED).await()
                        }
                    }
                runCatching { db.collection("users").document(uid).delete().await() }
                user.delete().await()
            } catch (e: FirebaseAuthException) {
                return@withContext Result.failure(IllegalArgumentException(friendlyAuthError(e)))
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        } else {
            val dao = AppDatabase.get(ctx).localAccountDao()
            val acct = dao.findByUid(uid)
            if (acct != null && !SecureSessionStore.verifyPassword(password, acct.passwordHash)) {
                return@withContext Result.failure(IllegalArgumentException("Incorrect password."))
            }
            dao.deleteByUid(uid)
        }

        // Clean local open listings and sign out completely.
        for (i in listings.indices) {
            val item = listings[i]
            if (item.ownerUid == uid && item.status in setOf(ListingStatus.OPEN, ListingStatus.PAUSED)) {
                listings[i] = item.copy(status = ListingStatus.CLOSED)
            }
        }
        users.remove(uid)
        signOut(ctx)
        Result.success(Unit)
    }

    private fun activateSession(context: Context, userProfile: UserState.UserProfile) {
        val ctx = context.applicationContext
        activeUid = userProfile.uid
        profile = userProfile
        users[userProfile.uid] = Seller(
            name = userProfile.name,
            initials = userProfile.initials,
            program = userProfile.program,
            year = userProfile.year,
            rating = "New",
            onTime = "\u2013",
            completed = 0,
            lastMessage = "",
            online = settings.showActiveStatus,
            uid = userProfile.uid,
            email = userProfile.email,
            bio = userProfile.bio,
            avatarUri = userProfile.avatarUri,
            showProgram = settings.showProgram,
            suspended = userProfile.suspended,
            suspendedReason = userProfile.suspendedReason,
            warningCount = userProfile.warningCount,
        )
        SecureSessionStore.putEncrypted(ctx, KEY_ACTIVE_UID, userProfile.uid)
        SecureSessionStore.putEncrypted(ctx, KEY_ACTIVE_EMAIL, userProfile.email)
        ioScope.launch {
            try {
                AppDatabase.get(ctx).draftDao().getDraft(userProfile.uid)?.let {
                    draft = entityToDraft(it)
                }
            } catch (_: Exception) {
            }
        }
        bumpState(broadcast = true)
    }

    // =========================================================================
    // Profile, Settings, Saved & Blocked
    // =========================================================================

    fun allUsers(): List<Seller> = users.values.toList()
    fun userByUid(uid: String): Seller? = if (uid == activeUid && uid.isNotEmpty()) UserState.me() else users[uid]

    fun updateProfile(
        context: Context,
        name: String,
        program: String,
        year: String,
        bio: String,
        avatarUri: String?,
    ) {
        ensureInitialized(context)
        val cleanName = UcEmailValidator.sanitizeSingleLine(name, 60).ifEmpty { profile.name }
        val cleanProgram = if (program in Listings.PROGRAMS) program else profile.program
        val cleanYear = UcEmailValidator.sanitizeSingleLine(year, 24).ifEmpty { profile.year }
        val cleanBio = UcEmailValidator.sanitizeMultiLine(bio, 180)
        profile = profile.copy(
            name = cleanName,
            program = cleanProgram,
            year = cleanYear,
            bio = cleanBio,
            avatarUri = avatarUri,
        )
        users[activeUid] = UserState.me()
        // Update owner snapshot in own listings so UI stays consistent
        for (i in listings.indices) {
            if (listings[i].ownerUid == activeUid) {
                listings[i] = listings[i].copy(seller = UserState.me())
            }
        }

        val ctx = context.applicationContext
        val uid = activeUid
        ioScope.launch {
            if (avatarUri != null && uid.isNotEmpty()) {
                ImagePipeline.uploadMediaIfFirebase(ctx, avatarUri, uid)
            }
            if (isFirebaseConfigured(ctx) && uid.isNotEmpty()) {
                runCatching {
                    FirebaseFirestore.getInstance().collection("users").document(uid).update(
                        mapOf(
                            "name" to cleanName,
                            "program" to cleanProgram,
                            "year" to cleanYear,
                            "bio" to cleanBio,
                            "avatarUri" to avatarUri.orEmpty(),
                        ),
                    ).await()
                }
            } else if (uid.isNotEmpty()) {
                val dao = AppDatabase.get(ctx).localAccountDao()
                dao.findByUid(uid)?.let { existing ->
                    dao.upsert(
                        existing.copy(
                            name = cleanName,
                            program = cleanProgram,
                            year = cleanYear,
                            bio = cleanBio,
                            avatarRef = avatarUri,
                        ),
                    )
                }
            }
        }
        bumpState()
    }

    fun updateSettings(context: Context, next: UserState.SettingsState) {
        ensureInitialized(context)
        settings = next
        val json = JSONObject().apply {
            put("notifyMessages", next.notifyMessages)
            put("notifyReservations", next.notifyReservations)
            put("notifyReminders", next.notifyReminders)
            put("notifyPriceDrops", next.notifyPriceDrops)
            put("showProgram", next.showProgram)
            put("showActiveStatus", next.showActiveStatus)
        }.toString()
        SecureSessionStore.putEncrypted(context, KEY_SETTINGS_JSON, json)

        val uid = activeUid
        if (isFirebaseConfigured(context) && uid.isNotEmpty()) {
            ioScope.launch {
                runCatching {
                    FirebaseFirestore.getInstance().collection("users").document(uid).update(
                        mapOf(
                            "showProgram" to next.showProgram,
                            "showActiveStatus" to next.showActiveStatus,
                        ),
                    ).await()
                }
            }
        }
        bumpState()
    }

    fun updateDefaultMeetup(context: Context, index: Int) {
        ensureInitialized(context)
        defaultMeetupIndex = index.coerceIn(0, Listings.MEETUP_POINTS.lastIndex)
        SecureSessionStore.putEncrypted(context, KEY_DEFAULT_MEETUP, defaultMeetupIndex.toString())
        val uid = activeUid
        if (isFirebaseConfigured(context) && uid.isNotEmpty()) {
            ioScope.launch {
                runCatching {
                    FirebaseFirestore.getInstance().collection("users").document(uid)
                        .update("defaultMeetup", defaultMeetupIndex).await()
                }
            }
        }
        bumpState()
    }

    fun savedListings(): List<Listing> {
        val ids = savedByUid[activeUid].orEmpty()
        return listings.filter { it.id in ids && !UserState.isHiddenForMe(it) && it.status == ListingStatus.OPEN }
    }

    fun isSaved(id: String): Boolean = id in savedByUid[activeUid].orEmpty()

    fun setSaved(id: String, on: Boolean) {
        val uid = activeUid.ifEmpty { return }
        val set = savedByUid.getOrPut(uid) { LinkedHashSet() }
        if (on) set.add(id) else set.remove(id)
        syncUserArrayField(uid, "savedIds", set.toList())
        bumpState()
    }

    fun blockedUids(): Set<String> = blockedByUid[activeUid]?.toSet().orEmpty()

    fun blockUser(targetUid: String) {
        val uid = activeUid.ifEmpty { return }
        if (targetUid.isBlank() || targetUid == uid) return
        val set = blockedByUid.getOrPut(uid) { LinkedHashSet() }
        set.add(targetUid)
        syncUserArrayField(uid, "blockedUids", set.toList())
        bumpState()
    }

    fun unblockUser(targetUid: String) {
        val uid = activeUid.ifEmpty { return }
        val set = blockedByUid[uid] ?: return
        if (set.remove(targetUid)) {
            syncUserArrayField(uid, "blockedUids", set.toList())
            bumpState()
        }
    }

    fun reportedListingIdsByMe(): Set<String> = reportedListingsByUid[activeUid]?.toSet().orEmpty()

    // =========================================================================
    // Listings CRUD (1..10 Photos, Multi-Select College Tags, Day Lending)
    // =========================================================================

    fun allListings(): List<Listing> = listings.toList()

    fun nextListingId(): String = "item_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"

    fun canEditPriceOrType(listingId: String): Boolean {
        val hasReq = requests.any {
            it.listingId == listingId && effectiveRequestStatus(it) in setOf(Deals.PENDING, Deals.ACCEPTED)
        }
        return !hasReq && !rentals.containsKey(listingId)
    }

    fun canDeleteListing(listingId: String): Boolean {
        val hasAccepted = acceptedRequestForListing(listingId) != null
        return !hasAccepted && !rentals.containsKey(listingId)
    }

    fun createListingLocalOrSync(listing: Listing): Listing {
        val normalized = listing.copy(
            ownerUid = activeUid.ifEmpty { listing.ownerUid },
            seller = if (activeUid.isNotEmpty()) UserState.me() else listing.seller,
            targetPrograms = listing.targetPrograms.filter { it in Listings.PROGRAMS }.distinct(),
            program = listing.targetPrograms.firstOrNull { it in Listings.PROGRAMS } ?: "All colleges",
            photoUris = listing.photoUris.take(Listings.MAX_PHOTOS),
            price = if (listing.isSwap) 0 else listing.price.coerceIn(0, Listings.MAX_PRICE),
            maxDays = listing.maxDays.coerceIn(1, 120),
            createdAt = System.currentTimeMillis(),
            postedDaysAgo = 0,
        )
        listings.add(0, normalized)
        val ctx = appContext
        if (ctx != null && isFirebaseConfigured(ctx)) {
            ioScope.launch {
                normalized.photoUris.forEach { ref ->
                    ImagePipeline.uploadMediaIfFirebase(ctx, ref, normalized.ownerUid)
                }
                runCatching {
                    FirebaseFirestore.getInstance()
                        .collection("listings")
                        .document(normalized.id)
                        .set(listingToFirestoreMap(normalized))
                        .await()
                }.onFailure { Log.w(TAG, "Failed uploading listing ${normalized.id}", it) }
            }
        }
        bumpState()
        return normalized
    }

    fun updateListingLocalOrSync(updated: Listing): Boolean {
        val index = listings.indexOfFirst { it.id == updated.id }
        if (index < 0) return false
        val existing = listings[index]
        val normalized = updated.copy(
            ownerUid = existing.ownerUid,
            seller = existing.seller,
            targetPrograms = updated.targetPrograms.filter { it in Listings.PROGRAMS }.distinct(),
            program = updated.targetPrograms.firstOrNull { it in Listings.PROGRAMS } ?: "All colleges",
            photoUris = updated.photoUris.take(Listings.MAX_PHOTOS),
            price = if (updated.isSwap) 0 else updated.price.coerceIn(0, Listings.MAX_PRICE),
            maxDays = updated.maxDays.coerceIn(1, 120),
            createdAt = existing.createdAt,
            reportCount = existing.reportCount,
            reportedByUids = existing.reportedByUids,
        )
        listings[index] = normalized
        val ctx = appContext
        if (ctx != null && isFirebaseConfigured(ctx)) {
            ioScope.launch {
                normalized.photoUris.forEach { ref ->
                    ImagePipeline.uploadMediaIfFirebase(ctx, ref, normalized.ownerUid)
                }
                runCatching {
                    FirebaseFirestore.getInstance()
                        .collection("listings")
                        .document(normalized.id)
                        .set(listingToFirestoreMap(normalized))
                        .await()
                }
            }
        }
        bumpState()
        return true
    }

    fun deleteListingLocalOrSync(listingId: String): Boolean {
        if (!canDeleteListing(listingId)) return false
        val listing = listings.firstOrNull { it.id == listingId } ?: return false
        if (listing.ownerUid != activeUid) return false

        // Decline any lingering pending requests gracefully before deleting
        requests.filter { it.listingId == listingId && effectiveRequestStatus(it) == Deals.PENDING }
            .forEach { req ->
                replaceRequest(req.copy(status = Deals.DECLINED))
                addNotificationInternal(
                    recipientUid = req.requesterUid,
                    type = NotificationType.REQUEST,
                    title = "Listing removed",
                    body = "${listing.seller.first} removed ${listing.title}.",
                    targetType = NotificationTarget.NONE,
                    targetId = listing.id,
                    postOs = false,
                )
            }
        listings.removeAll { it.id == listingId }
        appContext?.let { ReminderScheduler.cancelForListing(it, listingId) }
        val ctx = appContext
        if (ctx != null && isFirebaseConfigured(ctx)) {
            ioScope.launch {
                runCatching {
                    FirebaseFirestore.getInstance().collection("listings").document(listingId).delete().await()
                }
            }
        }
        bumpState()
        return true
    }

    fun setListingPaused(id: String, paused: Boolean) {
        val idx = listings.indexOfFirst { it.id == id }
        if (idx < 0) return
        val nextStatus = if (paused) ListingStatus.PAUSED else ListingStatus.OPEN
        listings[idx] = listings[idx].copy(status = nextStatus)
        updateFirestoreField("listings", id, "status", nextStatus)
        bumpState()
    }

    fun closeListingByOwner(id: String) {
        val idx = listings.indexOfFirst { it.id == id }
        if (idx < 0) return
        val listing = listings[idx]
        listings[idx] = listing.copy(status = ListingStatus.CLOSED)
        exchanges.add(
            0,
            Exchange(
                id = "ex_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(5)}",
                title = listing.title,
                price = listing.price,
                kind = when {
                    listing.isSwap -> Exchange.SWAPPED
                    listing.isRent -> Exchange.LENT
                    else -> Exchange.SOLD
                },
                partner = null,
                finishedMillis = System.currentTimeMillis(),
                meetup = listing.meetup,
                theirRating = 0,
                yourRating = 0,
                listingId = listing.id,
                events = listOf(DealEvent(R.string.ev_closed_by_you, System.currentTimeMillis())),
                giverUid = listing.ownerUid,
                receiverUid = "",
            ),
        )
        appContext?.let { ReminderScheduler.cancelForListing(it, id) }
        updateFirestoreField("listings", id, "status", ListingStatus.CLOSED)
        bumpState()
    }

    // =========================================================================
    // Requests, Reservations, 2-Step Handover (SecureRandom Code) & Loans
    // =========================================================================

    fun allRequests(): List<Request> = requests.toList()

    fun requestsForListing(listingId: String): List<Request> =
        requests.filter { it.listingId == listingId }

    fun effectiveRequestStatus(request: Request): Int {
        if (request.status == Deals.PENDING && System.currentTimeMillis() > request.expiresMillis) {
            return Deals.EXPIRED
        }
        val acceptedForListing = requests.firstOrNull {
            it.listingId == request.listingId && it.status == Deals.ACCEPTED
        }
        if (request.status == Deals.PENDING && acceptedForListing != null && acceptedForListing.id != request.id) {
            return Deals.WAITLIST
        }
        return request.status
    }

    fun acceptedRequestForListing(listingId: String): Request? =
        requests.firstOrNull { it.listingId == listingId && it.status == Deals.ACCEPTED }

    fun latestRequestBetween(partnerUid: String, listingId: String): Request? =
        requests.lastOrNull {
            it.listingId == listingId && (it.requesterUid == partnerUid || it.ownerUid == partnerUid)
        }

    fun myOffersUsing(listingId: String): List<Listing> =
        requests.filter {
            it.requesterUid == activeUid &&
                it.offerId == listingId &&
                effectiveRequestStatus(it) in setOf(Deals.PENDING, Deals.ACCEPTED)
        }.mapNotNull { Listings.byIdOrNull(it.listingId) }

    fun myReservationFor(listingId: String): UserState.Reservation? {
        val uid = activeUid.ifEmpty { return null }
        val req = requests.lastOrNull {
            it.listingId == listingId &&
                it.requesterUid == uid &&
                effectiveRequestStatus(it) in setOf(Deals.PENDING, Deals.WAITLIST, Deals.ACCEPTED)
        } ?: return null
        val summary = buildString {
            append(req.meetup)
            appContext?.let { ctx ->
                append(" \u2022 ")
                append(Rentals.whenLabel(ctx, req.meetMillis))
            }
        }
        return UserState.Reservation(
            requestId = req.id,
            summary = summary,
            days = req.days,
            meetMillis = req.meetMillis,
            meetup = req.meetup,
            meetupNote = req.meetupNote,
            offerId = req.offerId,
            madeMillis = req.sentMillis,
            status = effectiveRequestStatus(req),
            handoverCode = if (req.status == Deals.ACCEPTED) req.handoverCode else "",
            agreedPrice = req.agreedPrice,
        )
    }

    fun myReservedListings(): List<Listing> =
        listings.filter { myReservationFor(it.id) != null }

    fun submitRequest(
        listing: Listing,
        meetup: String,
        meetupNote: String,
        meetMillis: Long,
        days: Int,
        offerId: String?,
        message: String,
    ): Request {
        val now = System.currentTimeMillis()
        val reqId = "req_${now}_${UUID.randomUUID().toString().take(6)}"
        val agreedPrice = when {
            listing.isSwap -> 0
            listing.isRent -> listing.price * days.coerceAtLeast(1)
            else -> listing.price
        }
        val req = Request(
            id = reqId,
            listingId = listing.id,
            from = UserState.me(),
            sentMillis = now,
            meetup = meetup,
            meetMillis = meetMillis,
            message = UcEmailValidator.sanitizeMultiLine(message, 250),
            days = days,
            offerId = offerId,
            ownerUid = listing.ownerUid,
            requesterUid = activeUid,
            agreedPrice = agreedPrice,
            meetupNote = UcEmailValidator.sanitizeSingleLine(meetupNote, 120),
            handoverCode = "",
            status = Deals.PENDING,
        )
        requests.add(req)

        // Ensure a per-listing chat thread exists and append system deal card
        val thread = getOrCreateThread(listing.id, listing.ownerUid)
        val detail = appContext?.let { ctx ->
            val whenStr = Rentals.whenLabel(ctx, meetMillis)
            val extra = if (days > 0) " (${Listings.daysLabel(ctx, days)})" else ""
            "$meetup \u2022 $whenStr$extra"
        } ?: meetup
        sendChatMessage(
            thread.threadId,
            UserState.ChatMessage(
                senderUid = activeUid,
                outgoing = true,
                systemTitle = when {
                    listing.isSwap -> "Swap offer sent"
                    listing.isRent -> "Borrow request sent"
                    else -> "Reservation requested"
                },
                systemDetail = detail,
                meetupPlace = meetup,
                meetupMillis = meetMillis,
            ),
        )
        if (req.message.isNotBlank()) {
            sendChatMessage(
                thread.threadId,
                UserState.ChatMessage(
                    senderUid = activeUid,
                    text = req.message,
                    outgoing = true,
                ),
            )
        }

        // Notify the seller/lender
        addNotificationInternal(
            recipientUid = listing.ownerUid,
            type = NotificationType.REQUEST,
            title = "New request on ${listing.title}",
            body = "${UserState.me().first} wants to meet at $detail",
            targetType = NotificationTarget.LISTING,
            targetId = listing.id,
            postOs = false,
        )

        // Schedule request-expiry reminder for owner/requester
        appContext?.let { ctx ->
            ReminderScheduler.scheduleRequestExpiryReminder(
                context = ctx,
                requestId = req.id,
                listingId = listing.id,
                listingTitle = listing.title,
                expiresMillis = req.expiresMillis,
                recipientUid = listing.ownerUid,
            )
        }

        writeRequestFirestore(req)
        bumpState(action = MarketplaceBroadcasts.ACTION_DEAL_UPDATED)
        return req
    }

    fun acceptRequest(request: Request): List<Listing> {
        val listing = Listings.byIdOrNull(request.listingId) ?: return emptyList()
        val now = System.currentTimeMillis()
        // Cryptographically unpredictable 4-digit handover code (SecureRandom!)
        val code = UcEmailValidator.generateHandoverCode()
        val accepted = request.copy(
            status = Deals.ACCEPTED,
            handoverCode = code,
            acceptedAtMillis = now,
        )
        replaceRequest(accepted)
        setListingStatusInternal(listing.id, ListingStatus.RESERVED)

        // Withdraw conflicting swap offers if the seller had offered this listing elsewhere
        val withdrawn = mutableListOf<Listing>()
        for (i in requests.indices) {
            val other = requests[i]
            if (other.id != request.id && other.offerId == listing.id && effectiveRequestStatus(other) == Deals.PENDING) {
                requests[i] = other.copy(status = Deals.DECLINED)
                writeRequestFirestore(requests[i])
                Listings.byIdOrNull(other.listingId)?.let { withdrawn.add(it) }
            }
        }

        val thread = getOrCreateThread(listing.id, request.requesterUid)
        val detail = appContext?.let { ctx ->
            "${accepted.meetup} \u2022 ${Rentals.whenLabel(ctx, accepted.meetMillis)}"
        } ?: accepted.meetup

        sendChatMessage(
            thread.threadId,
            UserState.ChatMessage(
                senderUid = activeUid,
                outgoing = true,
                systemTitle = "Request accepted \u2022 Handover code unlocked",
                systemDetail = detail,
                meetupPlace = accepted.meetup,
                meetupMillis = accepted.meetMillis,
            ),
        )

        addNotificationInternal(
            recipientUid = request.requesterUid,
            type = NotificationType.RESERVATION,
            title = "Request accepted: ${listing.title}",
            body = "Meet ${UserState.me().first} at $detail. Your 4-digit handover code is ready!",
            targetType = NotificationTarget.LISTING,
            targetId = listing.id,
            postOs = false,
        )

        appContext?.let { ctx ->
            ReminderScheduler.cancelForRequest(ctx, request.id)
            ReminderScheduler.scheduleMeetupReminders(
                context = ctx,
                listingId = listing.id,
                listingTitle = listing.title,
                meetupPlace = accepted.meetup,
                meetMillis = accepted.meetMillis,
                recipientUid = activeUid,
            )
        }

        writeRequestFirestore(accepted)
        bumpState(action = MarketplaceBroadcasts.ACTION_DEAL_UPDATED)
        return withdrawn
    }

    fun declineRequest(request: Request) {
        val declined = request.copy(status = Deals.DECLINED)
        replaceRequest(declined)
        val listing = Listings.byIdOrNull(request.listingId)
        appContext?.let { ReminderScheduler.cancelForRequest(it, request.id) }
        if (listing != null) {
            addNotificationInternal(
                recipientUid = request.requesterUid,
                type = NotificationType.REQUEST,
                title = "Request declined",
                body = "${UserState.me().first} couldn't accept your request for ${listing.title}.",
                targetType = NotificationTarget.LISTING,
                targetId = listing.id,
                postOs = false,
            )
        }
        writeRequestFirestore(declined)
        bumpState(action = MarketplaceBroadcasts.ACTION_DEAL_UPDATED)
    }

    fun cancelMyReservation(listingId: String): Boolean {
        val uid = activeUid.ifEmpty { return false }
        val req = requests.lastOrNull {
            it.listingId == listingId &&
                it.requesterUid == uid &&
                effectiveRequestStatus(it) in setOf(Deals.PENDING, Deals.WAITLIST, Deals.ACCEPTED)
        } ?: return false
        val wasAccepted = req.status == Deals.ACCEPTED
        val late = wasAccepted && Deals.isLate(req.meetMillis)
        if (late) {
            lateCancelsByUid[uid] = (lateCancelsByUid[uid] ?: 0) + 1
            syncUserLateCancels(uid)
        }
        replaceRequest(req.copy(status = Deals.DECLINED))
        if (wasAccepted) {
            setListingStatusInternal(listingId, ListingStatus.OPEN)
            expirePendingProposalsForListing(listingId)
        }
        appContext?.let {
            ReminderScheduler.cancelForRequest(it, req.id)
            ReminderScheduler.cancelForListing(it, listingId)
        }
        Listings.byIdOrNull(listingId)?.let { listing ->
            addNotificationInternal(
                recipientUid = listing.ownerUid,
                type = NotificationType.RESERVATION,
                title = "Reservation cancelled",
                body = "${UserState.me().first} cancelled their request on ${listing.title}.",
                targetType = NotificationTarget.LISTING,
                targetId = listing.id,
                postOs = false,
            )
        }
        writeRequestFirestore(req.copy(status = Deals.DECLINED))
        bumpState(action = MarketplaceBroadcasts.ACTION_DEAL_UPDATED)
        return late
    }

    fun cancelAcceptedDeal(listingId: String): Boolean {
        val req = acceptedRequestForListing(listingId) ?: return false
        val late = Deals.isLate(req.meetMillis)
        if (late) {
            lateCancelsByUid[activeUid] = (lateCancelsByUid[activeUid] ?: 0) + 1
            syncUserLateCancels(activeUid)
        }
        val cancelled = req.copy(status = Deals.DECLINED)
        replaceRequest(cancelled)
        setListingStatusInternal(listingId, ListingStatus.OPEN)
        expirePendingProposalsForListing(listingId)
        appContext?.let { ReminderScheduler.cancelForListing(it, listingId) }

        Listings.byIdOrNull(listingId)?.let { listing ->
            addNotificationInternal(
                recipientUid = req.requesterUid,
                type = NotificationType.RESERVATION,
                title = "Meetup cancelled",
                body = "${UserState.me().first} cancelled the scheduled meetup for ${listing.title}.",
                targetType = NotificationTarget.LISTING,
                targetId = listing.id,
                postOs = false,
            )
        }
        writeRequestFirestore(cancelled)
        bumpState(action = MarketplaceBroadcasts.ACTION_DEAL_UPDATED)
        return late
    }

    fun reportBuyerSideNoShow(listing: Listing, theyMissed: Boolean) {
        val req = requests.lastOrNull {
            it.listingId == listing.id && it.requesterUid == activeUid && it.status == Deals.ACCEPTED
        } ?: return
        if (!theyMissed) {
            lateCancelsByUid[activeUid] = (lateCancelsByUid[activeUid] ?: 0) + 1
            syncUserLateCancels(activeUid)
        }
        replaceRequest(req.copy(status = Deals.DECLINED))
        setListingStatusInternal(listing.id, ListingStatus.OPEN)
        expirePendingProposalsForListing(listing.id)
        appContext?.let { ReminderScheduler.cancelForListing(it, listing.id) }
        writeRequestFirestore(req.copy(status = Deals.DECLINED))
        bumpState(action = MarketplaceBroadcasts.ACTION_DEAL_UPDATED)
    }

    fun reportOwnerSideNoShow(listing: Listing, theyMissed: Boolean) {
        val req = acceptedRequestForListing(listing.id) ?: return
        if (!theyMissed) {
            lateCancelsByUid[activeUid] = (lateCancelsByUid[activeUid] ?: 0) + 1
            syncUserLateCancels(activeUid)
        }
        replaceRequest(req.copy(status = Deals.DECLINED))
        setListingStatusInternal(listing.id, ListingStatus.OPEN)
        expirePendingProposalsForListing(listing.id)
        appContext?.let { ReminderScheduler.cancelForListing(it, listing.id) }
        writeRequestFirestore(req.copy(status = Deals.DECLINED))
        bumpState(action = MarketplaceBroadcasts.ACTION_DEAL_UPDATED)
    }

    /**
     * Completes a handover at campus meetup (verified via 4-digit SecureRandom code
     * or manual confirmation). Works symmetrically whether seller or buyer confirms!
     */
    fun completeHandover(listing: Listing, proof: Int) {
        val accepted = acceptedRequestForListing(listing.id)
            ?: requests.lastOrNull { it.listingId == listing.id && it.status == Deals.ACCEPTED }
            ?: return
        val now = System.currentTimeMillis()
        replaceRequest(accepted.copy(status = Deals.COMPLETED))
        writeRequestFirestore(accepted.copy(status = Deals.COMPLETED))
        expirePendingProposalsForListing(listing.id)
        appContext?.let { ReminderScheduler.cancelForListing(it, listing.id) }

        val ownerSeller = Listings.sellerOf(listing)
        val buyerSeller = userByUid(accepted.requesterUid) ?: accepted.from

        val baseEvents = listOf(
            DealEvent(
                if (listing.isSwap) R.string.ev_they_offered else R.string.ev_they_requested,
                accepted.sentMillis,
                buyerSeller.first,
            ),
            DealEvent(R.string.ev_you_accepted, accepted.acceptedAtMillis ?: accepted.sentMillis, ownerSeller.first),
            DealEvent(
                when (proof) {
                    Exchange.PROOF_MANUAL -> R.string.ev_handover_manual
                    Exchange.PROOF_RECEIVED -> R.string.ev_you_got_it
                    else -> R.string.ev_handover_code
                },
                now,
            ),
        )

        if (listing.isRent) {
            val days = accepted.days.coerceIn(1, listing.maxDays.coerceAtLeast(1))
            val dueMillis = now + days * Rentals.DAY_MS
            val rental = UserState.Rental(
                id = "rent_${now}_${UUID.randomUUID().toString().take(5)}",
                partner = if (activeUid == listing.ownerUid) buyerSeller else ownerSeller,
                dueMillis = dueMillis,
                lending = activeUid == listing.ownerUid,
                returnPending = false,
                startMillis = now,
                events = baseEvents,
                proof = proof,
                days = days,
                lenderUid = listing.ownerUid,
                borrowerUid = accepted.requesterUid,
            )
            rentals[listing.id] = rental
            setListingStatusInternal(listing.id, ListingStatus.ON_LOAN)
            writeRentalFirestore(listing.id, rental)

            appContext?.let { ctx ->
                ReminderScheduler.scheduleLoanReminders(
                    context = ctx,
                    listingId = listing.id,
                    listingTitle = listing.title,
                    dueMillis = dueMillis,
                    recipientUid = activeUid,
                )
            }

            val otherUid = if (activeUid == listing.ownerUid) accepted.requesterUid else listing.ownerUid
            addNotificationInternal(
                recipientUid = otherUid,
                type = NotificationType.LOAN,
                title = "Loan started: ${listing.title}",
                body = "Due back on ${Rentals.dateLabel(dueMillis)}.",
                targetType = NotificationTarget.LISTING,
                targetId = listing.id,
                postOs = false,
            )
        } else {
            // Sell or Swap completes immediately into Completed Exchanges!
            setListingStatusInternal(listing.id, ListingStatus.CLOSED)
            if (listing.isSwap && !accepted.offerId.isNullOrBlank()) {
                setListingStatusInternal(accepted.offerId, ListingStatus.CLOSED)
                swappedInByUid.getOrPut(accepted.requesterUid) { LinkedHashSet() }.add(listing.id)
                swappedInByUid.getOrPut(listing.ownerUid) { LinkedHashSet() }.add(accepted.offerId)
            } else {
                receivedByUid.getOrPut(accepted.requesterUid) { LinkedHashSet() }.add(listing.id)
            }

            val ex = Exchange(
                id = "ex_${now}_${UUID.randomUUID().toString().take(6)}",
                title = listing.title,
                price = accepted.agreedPrice,
                kind = if (listing.isSwap) Exchange.SWAPPED else Exchange.SOLD,
                partner = buyerSeller,
                finishedMillis = now,
                meetup = accepted.meetup,
                theirRating = 0,
                yourRating = 0,
                onTime = true,
                proof = proof,
                listingId = listing.id,
                events = baseEvents,
                giverUid = listing.ownerUid,
                receiverUid = accepted.requesterUid,
            )
            exchanges.add(0, ex)
            writeExchangeFirestore(ex)

            val otherUid = if (activeUid == listing.ownerUid) accepted.requesterUid else listing.ownerUid
            addNotificationInternal(
                recipientUid = otherUid,
                type = NotificationType.RATING,
                title = "Handover complete: ${listing.title}",
                body = "Leave a quick rating within 7 days!",
                targetType = NotificationTarget.EXCHANGE,
                targetId = ex.id,
                postOs = false,
            )
        }

        // System card in chat thread
        val thread = getOrCreateThread(listing.id, if (activeUid == listing.ownerUid) accepted.requesterUid else listing.ownerUid)
        sendChatMessage(
            thread.threadId,
            UserState.ChatMessage(
                senderUid = activeUid,
                outgoing = true,
                systemTitle = if (listing.isRent) "Handed over \u2022 Loan active" else "Handover complete",
                systemDetail = listing.title,
            ),
        )
        bumpState(action = MarketplaceBroadcasts.ACTION_DEAL_UPDATED)
    }

    fun activeRentalFor(listingId: String): UserState.Rental? {
        val r = rentals[listingId] ?: return null
        val uid = activeUid
        if (uid.isEmpty()) return r
        val isLending = r.lenderUid == uid
        val partnerUid = if (isLending) r.borrowerUid else r.lenderUid
        val partner = userByUid(partnerUid) ?: r.partner
        return r.copy(lending = isLending, partner = partner)
    }

    fun myBorrowing(): List<Pair<Listing, UserState.Rental>> =
        rentals.entries.mapNotNull { (listingId, r) ->
            if (r.borrowerUid != activeUid) return@mapNotNull null
            val listing = Listings.byIdOrNull(listingId) ?: return@mapNotNull null
            listing to (activeRentalFor(listingId) ?: r)
        }

    fun myLending(): List<Pair<Listing, UserState.Rental>> =
        rentals.entries.mapNotNull { (listingId, r) ->
            if (r.lenderUid != activeUid) return@mapNotNull null
            val listing = Listings.byIdOrNull(listingId) ?: return@mapNotNull null
            listing to (activeRentalFor(listingId) ?: r)
        }

    fun lendDirectly(listing: Listing, partner: Seller, days: Int) {
        val now = System.currentTimeMillis()
        val safeDays = days.coerceIn(1, listing.maxDays.coerceAtLeast(1))
        val dueMillis = now + safeDays * Rentals.DAY_MS
        val rental = UserState.Rental(
            id = "rent_${now}_${UUID.randomUUID().toString().take(5)}",
            partner = partner,
            dueMillis = dueMillis,
            lending = true,
            returnPending = false,
            startMillis = now,
            days = safeDays,
            lenderUid = activeUid,
            borrowerUid = partner.uid,
        )
        rentals[listing.id] = rental
        setListingStatusInternal(listing.id, ListingStatus.ON_LOAN)
        writeRentalFirestore(listing.id, rental)
        appContext?.let { ctx ->
            ReminderScheduler.scheduleLoanReminders(ctx, listing.id, listing.title, dueMillis, activeUid)
        }
        bumpState(action = MarketplaceBroadcasts.ACTION_DEAL_UPDATED)
    }

    fun reportReturned(listing: Listing) {
        val r = rentals[listing.id] ?: return
        val now = System.currentTimeMillis()
        val updated = r.copy(
            returnPending = true,
            reportedMillis = now,
            events = r.events + DealEvent(R.string.ev_you_returned, now),
        )
        rentals[listing.id] = updated
        writeRentalFirestore(listing.id, updated)
        addNotificationInternal(
            recipientUid = r.lenderUid,
            type = NotificationType.LOAN,
            title = "Return reported: ${listing.title}",
            body = "${UserState.me().first} says they returned it. Confirm once you have it back.",
            targetType = NotificationTarget.LISTING,
            targetId = listing.id,
            postOs = false,
        )
        bumpState(action = MarketplaceBroadcasts.ACTION_DEAL_UPDATED)
    }

    fun rejectReturn(listing: Listing) {
        val r = rentals[listing.id] ?: return
        val updated = r.copy(returnPending = false, reportedMillis = null)
        rentals[listing.id] = updated
        writeRentalFirestore(listing.id, updated)
        addNotificationInternal(
            recipientUid = r.borrowerUid,
            type = NotificationType.LOAN,
            title = "Return not yet confirmed",
            body = "${UserState.me().first} hasn't received ${listing.title} yet. Check in via chat.",
            targetType = NotificationTarget.LISTING,
            targetId = listing.id,
            postOs = false,
        )
        bumpState(action = MarketplaceBroadcasts.ACTION_DEAL_UPDATED)
    }

    /**
     * Lender confirms item returned in good condition.
     * Supports the post-return prompt: [keepOpen] = true re-opens listing (`OPEN`) for future borrowers;
     * [keepOpen] = false puts listing in `PAUSED` so it's hidden from Market until unpaused.
     */
    fun confirmReturned(listing: Listing, keepOpen: Boolean) {
        val r = rentals.remove(listing.id) ?: return
        val now = System.currentTimeMillis()
        val onTime = now <= r.dueMillis + Rentals.DAY_MS
        val nextStatus = if (keepOpen) ListingStatus.OPEN else ListingStatus.PAUSED
        setListingStatusInternal(listing.id, nextStatus)
        expirePendingProposalsForListing(listing.id)
        appContext?.let { ReminderScheduler.cancelForListing(it, listing.id) }

        val borrower = userByUid(r.borrowerUid) ?: r.partner
        val ex = Exchange(
            id = "ex_${now}_${UUID.randomUUID().toString().take(6)}",
            title = listing.title,
            price = listing.price * r.days.coerceAtLeast(1),
            kind = Exchange.LENT,
            partner = borrower,
            finishedMillis = now,
            meetup = listing.meetup,
            theirRating = 0,
            yourRating = 0,
            onTime = onTime,
            proof = r.proof,
            listingId = listing.id,
            events = r.events + DealEvent(R.string.ev_return_confirmed, now),
            giverUid = r.lenderUid,
            receiverUid = r.borrowerUid,
        )
        exchanges.add(0, ex)
        writeExchangeFirestore(ex)
        deleteRentalFirestore(listing.id)

        addNotificationInternal(
            recipientUid = r.borrowerUid,
            type = NotificationType.RATING,
            title = "Return confirmed: ${listing.title}",
            body = "Thanks for returning on campus! Rate your experience within 7 days.",
            targetType = NotificationTarget.EXCHANGE,
            targetId = ex.id,
            postOs = false,
        )
        bumpState(action = MarketplaceBroadcasts.ACTION_DEAL_UPDATED)
    }

    fun isTakenForCurrentUser(id: String): Boolean {
        val uid = activeUid.ifEmpty { return false }
        return myReservationFor(id) != null ||
            rentals[id]?.let { it.borrowerUid == uid || it.lenderUid == uid } == true ||
            id in receivedByUid[uid].orEmpty() ||
            id in swappedInByUid[uid].orEmpty()
    }

    fun hasReceivedListing(id: String): Boolean = id in receivedByUid[activeUid].orEmpty()
    fun gotInSwap(id: String): Boolean = id in swappedInByUid[activeUid].orEmpty()
    fun incomingSwapOffer(id: String): Request? = requests.firstOrNull {
        it.offerId == id && effectiveRequestStatus(it) in setOf(Deals.PENDING, Deals.ACCEPTED)
    }

    fun completedForCurrentUser(): List<Exchange> {
        val uid = activeUid.ifEmpty { return emptyList() }
        return exchanges.filter { it.giverUid == uid || it.receiverUid == uid }.map { ex ->
            val iAmGiver = ex.giverUid == uid
            val partnerUid = if (iAmGiver) ex.receiverUid else ex.giverUid
            val partner = if (partnerUid.isBlank()) null else (userByUid(partnerUid) ?: ex.partner)
            val displayKind = when {
                ex.kind == Exchange.SWAPPED -> Exchange.SWAPPED
                ex.kind == Exchange.LENT || ex.kind == Exchange.BORROWED ->
                    if (iAmGiver) Exchange.LENT else Exchange.BORROWED
                else -> if (iAmGiver) Exchange.SOLD else Exchange.BOUGHT
            }
            val myStars = if (iAmGiver) ex.giverRating else ex.receiverRating
            val theirStars = if (iAmGiver) ex.receiverRating else ex.giverRating
            ex.copy(
                kind = displayKind,
                partner = partner,
                yourRating = myStars,
                theirRating = theirStars,
            )
        }
    }

    fun rateExchange(exchangeId: String, stars: Int, note: String) {
        val uid = activeUid.ifEmpty { return }
        val cleanStars = stars.coerceIn(1, 5)
        val cleanNote = UcEmailValidator.sanitizeSingleLine(note, 180)
        val key = "$uid:$exchangeId"
        ratingsByUserAndExchange[key] = cleanStars to cleanNote
        ratedAtByUserAndExchange[key] = System.currentTimeMillis()

        val idx = exchanges.indexOfFirst { it.id == exchangeId }
        if (idx >= 0) {
            val ex = exchanges[idx]
            val updated = if (ex.giverUid == uid) {
                ex.copy(giverRating = cleanStars, giverNote = cleanNote)
            } else {
                ex.copy(receiverRating = cleanStars, receiverNote = cleanNote)
            }
            exchanges[idx] = updated
            writeExchangeFirestore(updated)
        }
        bumpState()
    }

    fun ratedAt(exchangeId: String): Long = ratedAtByUserAndExchange["$activeUid:$exchangeId"] ?: 0L

    fun myRatingFor(exchangeId: String): Pair<Int, String>? = ratingsByUserAndExchange["$activeUid:$exchangeId"]

    fun myLateCancels(): Int = lateCancelsByUid[activeUid] ?: 0

    // =========================================================================
    // Per-Listing Chat Threads & Interactive Schedule/Extension Proposals
    // =========================================================================

    fun chatThreadsForCurrentUser(): List<ChatThread> {
        val uid = activeUid.ifEmpty { return emptyList() }
        val blocked = blockedUids()
        return threads.values
            .filter { (it.ownerUid == uid || it.buyerUid == uid) && it.partnerUid(uid) !in blocked }
            .sortedByDescending { it.lastMessageMillis }
    }

    fun threadById(threadId: String): ChatThread? = threads[threadId]

    fun getOrCreateThread(listingId: String, partnerUid: String): ChatThread {
        val uid = activeUid
        val listing = Listings.byIdOrNull(listingId)
        val ownerUid = listing?.ownerUid ?: partnerUid
        val buyerUid = if (uid == ownerUid) partnerUid else uid
        val threadId = ChatThread.idFor(listingId, buyerUid)
        threads[threadId]?.let { return it }

        val ownerSeller = userByUid(ownerUid) ?: listing?.seller ?: UserState.me()
        val buyerSeller = userByUid(buyerUid) ?: UserState.me()
        val created = ChatThread(
            threadId = threadId,
            listingId = listingId,
            ownerUid = ownerUid,
            buyerUid = buyerUid,
            ownerName = ownerSeller.name,
            buyerName = buyerSeller.name,
            ownerProgram = ownerSeller.program,
            buyerProgram = buyerSeller.program,
            lastMessagePreview = "",
            lastMessageMillis = System.currentTimeMillis(),
            lastSenderUid = "",
            unreadByUid = mapOf(ownerUid to false, buyerUid to false),
        )
        threads[threadId] = created
        writeThreadFirestore(created)
        return created
    }

    fun chatMessages(threadId: String): List<UserState.ChatMessage> {
        val uid = activeUid
        return messagesByThread[threadId].orEmpty().map { msg ->
            msg.copy(outgoing = msg.senderUid == uid || (msg.senderUid.isEmpty() && msg.outgoing))
        }
    }

    fun sendChatMessage(threadId: String, rawMessage: UserState.ChatMessage) {
        val thread = threads[threadId] ?: return
        val uid = activeUid.ifEmpty { rawMessage.senderUid }
        val message = rawMessage.copy(
            senderUid = uid,
            outgoing = true,
            text = rawMessage.text?.let { UcEmailValidator.sanitizeMultiLine(it, 600) },
            createdAt = System.currentTimeMillis(),
        )
        val list = messagesByThread.getOrPut(threadId) { mutableListOf() }
        list.add(message)

        val preview = when {
            message.proposal != null && message.proposal.proposalType == ProposalType.MEETUP_CHANGE ->
                "Proposed a meetup change"
            message.proposal != null && message.proposal.proposalType == ProposalType.RETURN_EXTENSION ->
                "Proposed a return extension (+${message.proposal.extraDays}d)"
            !message.text.isNullOrBlank() -> message.text
            !message.photoUri.isNullOrBlank() -> "Sent a photo"
            !message.systemTitle.isNullOrBlank() -> message.systemTitle
            else -> "New activity"
        }
        val partnerUid = thread.partnerUid(uid)
        val updatedThread = thread.copy(
            lastMessagePreview = preview.take(90),
            lastMessageMillis = message.createdAt,
            lastSenderUid = uid,
            unreadByUid = thread.unreadByUid + mapOf(uid to false, partnerUid to true),
        )
        threads[threadId] = updatedThread

        val ctx = appContext
        if (ctx != null && isFirebaseConfigured(ctx)) {
            ioScope.launch {
                message.photoUri?.let { ImagePipeline.uploadMediaIfFirebase(ctx, it, uid) }
                writeThreadFirestoreSuspend(updatedThread)
                writeMessageFirestoreSuspend(threadId, message)
            }
        }
        bumpState(action = MarketplaceBroadcasts.ACTION_NEW_CHAT_MESSAGE)
    }

    fun markThreadRead(threadId: String) {
        val uid = activeUid.ifEmpty { return }
        val thread = threads[threadId] ?: return
        if (thread.unreadByUid[uid] == true) {
            val updated = thread.copy(unreadByUid = thread.unreadByUid + (uid to false))
            threads[threadId] = updated
            writeThreadFirestore(updated)
            bumpState()
        }
    }

    fun hasPendingProposal(threadId: String, type: String? = null): Boolean =
        messagesByThread[threadId].orEmpty().any { msg ->
            val p = msg.proposal ?: return@any false
            p.proposalStatus == ProposalStatus.PENDING && (type == null || p.proposalType == type)
        }

    /**
     * Interactive Proposal #1 (available ONLY when listing has an active ACCEPTED deal):
     * Proposes changing the campus meetup place, optional room note, and/or meetup time.
     * Schedule does NOT change until the other student explicitly taps Confirm!
     */
    fun proposeMeetupChange(
        threadId: String,
        listing: Listing,
        request: Request,
        newMeetup: String,
        newMeetupNote: String,
        newMeetMillis: Long,
        note: String,
    ): ProposalPayload {
        // Supersede any existing pending meetup proposal by this user in the thread
        withdrawOwnPendingProposals(threadId, ProposalType.MEETUP_CHANGE)

        val payload = ProposalPayload(
            proposalId = "prop_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(5)}",
            proposalType = ProposalType.MEETUP_CHANGE,
            proposalStatus = ProposalStatus.PENDING,
            proposerUid = activeUid,
            proposerName = UserState.me().first,
            listingId = listing.id,
            requestId = request.id,
            oldMeetup = request.meetup,
            oldMeetMillis = request.meetMillis,
            newMeetup = newMeetup,
            newMeetupNote = UcEmailValidator.sanitizeSingleLine(newMeetupNote, 100),
            newMeetMillis = newMeetMillis,
            note = UcEmailValidator.sanitizeSingleLine(note, 160),
        )
        sendChatMessage(
            threadId,
            UserState.ChatMessage(
                senderUid = activeUid,
                outgoing = true,
                proposal = payload,
            ),
        )

        val partnerUid = if (activeUid == listing.ownerUid) request.requesterUid else listing.ownerUid
        addNotificationInternal(
            recipientUid = partnerUid,
            type = NotificationType.MESSAGE,
            title = "Schedule change proposed",
            body = "${UserState.me().first} proposed a new meetup for ${listing.title}. Review in chat.",
            targetType = NotificationTarget.CHAT,
            targetId = threadId,
            postOs = false,
        )
        bumpState(action = MarketplaceBroadcasts.ACTION_PROPOSAL_UPDATED)
        return payload
    }

    /**
     * Interactive Proposal #2 (available ONLY during an active ON_LOAN rental):
     * Proposes extending the loan due date by +N days with transparent extra daily fee.
     * Due date & alarms update ONLY when the other student taps Confirm!
     */
    fun proposeReturnExtension(
        threadId: String,
        listing: Listing,
        rental: UserState.Rental,
        extraDays: Int,
        note: String,
    ): ProposalPayload {
        withdrawOwnPendingProposals(threadId, ProposalType.RETURN_EXTENSION)

        val safeExtra = extraDays.coerceIn(1, 60)
        val newDue = rental.dueMillis + safeExtra * Rentals.DAY_MS
        val extraCost = if (listing.price > 0) listing.price * safeExtra else 0
        val payload = ProposalPayload(
            proposalId = "prop_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(5)}",
            proposalType = ProposalType.RETURN_EXTENSION,
            proposalStatus = ProposalStatus.PENDING,
            proposerUid = activeUid,
            proposerName = UserState.me().first,
            listingId = listing.id,
            oldMeetMillis = rental.dueMillis,
            extraDays = safeExtra,
            extraCostPesos = extraCost,
            newDueMillis = newDue,
            note = UcEmailValidator.sanitizeSingleLine(note, 160),
        )
        sendChatMessage(
            threadId,
            UserState.ChatMessage(
                senderUid = activeUid,
                outgoing = true,
                proposal = payload,
            ),
        )

        val partnerUid = if (activeUid == rental.lenderUid) rental.borrowerUid else rental.lenderUid
        addNotificationInternal(
            recipientUid = partnerUid,
            type = NotificationType.LOAN,
            title = "Return extension proposed",
            body = "${UserState.me().first} requested +$safeExtra day(s) for ${listing.title}. Review in chat.",
            targetType = NotificationTarget.CHAT,
            targetId = threadId,
            postOs = false,
        )
        bumpState(action = MarketplaceBroadcasts.ACTION_PROPOSAL_UPDATED)
        return payload
    }

    /**
     * Resolves a pending proposal card (`ACCEPTED`, `DECLINED`, or `WITHDRAWN`).
     * Enforces authorization: only the proposer can withdraw; only the counterparty can accept/decline.
     * When accepted, atomically updates the Request/Rental AND cancels+reschedules AlarmManager reminders!
     */
    fun respondToProposal(threadId: String, proposalId: String, nextStatus: String): Boolean {
        val list = messagesByThread[threadId] ?: return false
        val idx = list.indexOfFirst { it.proposal?.proposalId == proposalId }
        if (idx < 0) return false
        val msg = list[idx]
        val proposal = msg.proposal ?: return false
        if (proposal.proposalStatus != ProposalStatus.PENDING) return false

        val uid = activeUid
        val isProposer = proposal.proposerUid == uid
        if (nextStatus == ProposalStatus.WITHDRAWN && !isProposer) return false
        if ((nextStatus == ProposalStatus.ACCEPTED || nextStatus == ProposalStatus.DECLINED) && isProposer) return false

        val now = System.currentTimeMillis()
        val resolvedProposal = proposal.copy(
            proposalStatus = nextStatus,
            resolvedByUid = uid,
            resolvedAt = now,
        )
        val updatedMsg = msg.copy(proposal = resolvedProposal)
        list[idx] = updatedMsg
        writeMessageFirestore(threadId, updatedMsg)

        val listing = Listings.byIdOrNull(proposal.listingId)
        if (nextStatus == ProposalStatus.ACCEPTED && listing != null) {
            if (proposal.proposalType == ProposalType.MEETUP_CHANGE) {
                val req = acceptedRequestForListing(listing.id)
                if (req != null) {
                    val newPlace = proposal.newMeetup ?: req.meetup
                    val newMillis = proposal.newMeetMillis ?: req.meetMillis
                    val updatedReq = req.copy(
                        meetup = newPlace,
                        meetupNote = proposal.newMeetupNote.ifBlank { req.meetupNote },
                        meetMillis = newMillis,
                    )
                    replaceRequest(updatedReq)
                    writeRequestFirestore(updatedReq)
                    appContext?.let { ctx ->
                        ReminderScheduler.scheduleMeetupReminders(
                            context = ctx,
                            listingId = listing.id,
                            listingTitle = listing.title,
                            meetupPlace = newPlace,
                            meetMillis = newMillis,
                            recipientUid = activeUid,
                        )
                    }
                    val detail = appContext?.let { ctx ->
                        "$newPlace \u2022 ${Rentals.whenLabel(ctx, newMillis)}"
                    } ?: newPlace
                    sendChatMessage(
                        threadId,
                        UserState.ChatMessage(
                            senderUid = uid,
                            outgoing = true,
                            systemTitle = "Meetup updated",
                            systemDetail = detail,
                            meetupPlace = newPlace,
                            meetupMillis = newMillis,
                        ),
                    )
                }
            } else if (proposal.proposalType == ProposalType.RETURN_EXTENSION) {
                val r = rentals[listing.id]
                if (r != null && proposal.newDueMillis != null) {
                    val updatedRental = r.copy(
                        dueMillis = proposal.newDueMillis,
                        days = r.days + proposal.extraDays,
                    )
                    rentals[listing.id] = updatedRental
                    writeRentalFirestore(listing.id, updatedRental)
                    appContext?.let { ctx ->
                        ReminderScheduler.scheduleLoanReminders(
                            context = ctx,
                            listingId = listing.id,
                            listingTitle = listing.title,
                            dueMillis = updatedRental.dueMillis,
                            recipientUid = activeUid,
                        )
                    }
                    sendChatMessage(
                        threadId,
                        UserState.ChatMessage(
                            senderUid = uid,
                            outgoing = true,
                            systemTitle = "Return date extended (+${proposal.extraDays}d)",
                            systemDetail = "New due date: ${Rentals.dateLabel(updatedRental.dueMillis)}",
                        ),
                    )
                }
            }
        }

        // Notify proposer when counterparty responds
        if (!isProposer && listing != null) {
            val verb = if (nextStatus == ProposalStatus.ACCEPTED) "accepted" else "declined"
            addNotificationInternal(
                recipientUid = proposal.proposerUid,
                type = NotificationType.RESERVATION,
                title = "Proposal $verb",
                body = "${UserState.me().first} $verb your proposal for ${listing.title}.",
                targetType = NotificationTarget.CHAT,
                targetId = threadId,
                postOs = false,
            )
        }

        bumpState(action = MarketplaceBroadcasts.ACTION_PROPOSAL_UPDATED)
        return true
    }

    private fun withdrawOwnPendingProposals(threadId: String, type: String) {
        val list = messagesByThread[threadId] ?: return
        for (i in list.indices) {
            val p = list[i].proposal ?: continue
            if (p.proposalStatus == ProposalStatus.PENDING && p.proposalType == type && p.proposerUid == activeUid) {
                val updated = list[i].copy(proposal = p.copy(proposalStatus = ProposalStatus.WITHDRAWN))
                list[i] = updated
                writeMessageFirestore(threadId, updated)
            }
        }
    }

    private fun expirePendingProposalsForListing(listingId: String) {
        threads.values.filter { it.listingId == listingId }.forEach { thread ->
            val list = messagesByThread[thread.threadId] ?: return@forEach
            for (i in list.indices) {
                val p = list[i].proposal ?: continue
                if (p.proposalStatus == ProposalStatus.PENDING) {
                    val updated = list[i].copy(proposal = p.copy(proposalStatus = ProposalStatus.EXPIRED))
                    list[i] = updated
                    writeMessageFirestore(thread.threadId, updated)
                }
            }
        }
    }

    // =========================================================================
    // Reports, 3-Report Auto UNDER_REVIEW & AI Moderation Logs
    // =========================================================================

    fun myIssueFor(subject: String): Issue? = issuesBySubjectAndReporter["$activeUid:$subject"]

    /**
     * Submits a verified student report:
     * - Duplicate-protected per `(reporterUid, subject)`
     * - Immediately hides reported listing for the reporter
     * - Auto-flags listing `UNDER_REVIEW` (hidden from Campus Market) when >= 3 distinct
     *   verified UC students report it!
     */
    fun submitReport(
        subject: String,
        reason: Int,
        partner: Seller?,
        details: String,
        isListingReport: Boolean,
    ): Issue {
        val uid = activeUid.ifEmpty { "anon" }
        val key = "$uid:$subject"
        issuesBySubjectAndReporter[key]?.let { return it }

        val ref = UcEmailValidator.generateReference("HTS-R")
        val now = System.currentTimeMillis()
        val issue = Issue(
            ref = ref,
            subject = subject,
            reason = reason,
            partner = partner,
            millis = now,
            details = UcEmailValidator.sanitizeMultiLine(details, 400),
            reporterUid = uid,
            status = "OPEN",
        )
        issuesBySubjectAndReporter[key] = issue

        var escalatedToReview = false
        if (isListingReport) {
            reportedListingsByUid.getOrPut(uid) { LinkedHashSet() }.add(subject)
            val idx = listings.indexOfFirst { it.id == subject }
            if (idx >= 0) {
                val item = listings[idx]
                val nextReporters = item.reportedByUids + uid
                val nextCount = nextReporters.size
                val nextStatus = if (nextCount >= 3 && item.status == ListingStatus.OPEN) {
                    escalatedToReview = true
                    ListingStatus.UNDER_REVIEW
                } else {
                    item.status
                }
                listings[idx] = item.copy(
                    reportCount = nextCount,
                    reportedByUids = nextReporters,
                    status = nextStatus,
                )
                val ctx = appContext
                if (ctx != null && isFirebaseConfigured(ctx)) {
                    ioScope.launch {
                        runCatching {
                            val updates = hashMapOf<String, Any>(
                                "reportCount" to nextCount,
                                "reportedByUids" to nextReporters.toList(),
                            )
                            if (escalatedToReview) updates["status"] = ListingStatus.UNDER_REVIEW
                            FirebaseFirestore.getInstance().collection("listings").document(subject)
                                .update(updates).await()
                        }
                    }
                }
            }
        }

        // Write private report document to Firestore for Admin Web Portal triage
        val ctx = appContext
        if (ctx != null && isFirebaseConfigured(ctx)) {
            ioScope.launch {
                runCatching {
                    val reportMap = hashMapOf(
                        "ref" to ref,
                        "subject" to subject,
                        "listingId" to if (isListingReport) subject else "",
                        "reason" to reason,
                        "details" to issue.details,
                        "reporterUid" to uid,
                        "reporterEmail" to profile.email,
                        "reporterName" to profile.name,
                        "reportedUserUid" to partner?.uid.orEmpty(),
                        "reportedUserName" to partner?.name.orEmpty(),
                        "status" to "OPEN",
                        "autoEscalated" to escalatedToReview,
                        "createdAt" to now,
                    )
                    FirebaseFirestore.getInstance().collection("reports").document(ref).set(reportMap).await()
                }
            }
        }

        bumpState()
        return issue
    }

    fun logModerationBlock(
        title: String,
        description: String,
        category: String,
        policySection: String,
        reason: String,
        source: String,
    ) {
        val entry = hashMapOf<String, Any>(
            "id" to "mod_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(5)}",
            "uid" to activeUid,
            "userEmail" to profile.email,
            "userName" to profile.name,
            "title" to title.take(100),
            "description" to description.take(250),
            "category" to category,
            "policySection" to policySection,
            "reason" to reason,
            "source" to source,
            "createdAt" to System.currentTimeMillis(),
        )
        moderationLogs.add(0, entry)
        val ctx = appContext
        if (ctx != null && isFirebaseConfigured(ctx) && activeUid.isNotEmpty()) {
            ioScope.launch {
                runCatching {
                    FirebaseFirestore.getInstance()
                        .collection("moderation_logs")
                        .document(entry["id"].toString())
                        .set(entry)
                        .await()
                }
            }
        }
    }

    // =========================================================================
    // Notifications, Announcements & Background Maintenance
    // =========================================================================

    fun notificationsForCurrentUser(): List<AppNotification> {
        val uid = activeUid.ifEmpty { return emptyList() }
        return notifications
            .filter { it.recipientUid == uid || it.recipientUid == "ALL" }
            .sortedByDescending { it.createdAt }
    }

    fun announcements(): List<CampusAnnouncement> = announcements.sortedByDescending { it.createdAt }

    fun markNotificationRead(id: String) {
        val idx = notifications.indexOfFirst { it.id == id }
        if (idx >= 0 && !notifications[idx].read) {
            notifications[idx] = notifications[idx].copy(read = true)
            updateFirestoreField("notifications", id, "read", true)
            appContext?.let { ctx ->
                ioScope.launch { runCatching { AppDatabase.get(ctx).notificationDao().markRead(id) } }
            }
            bumpState()
        }
    }

    fun markAllNotificationsRead() {
        val uid = activeUid.ifEmpty { return }
        var changed = false
        for (i in notifications.indices) {
            val n = notifications[i]
            if (!n.read && (n.recipientUid == uid || n.recipientUid == "ALL")) {
                notifications[i] = n.copy(read = true)
                updateFirestoreField("notifications", n.id, "read", true)
                changed = true
            }
        }
        if (changed) {
            appContext?.let { ctx ->
                ioScope.launch { runCatching { AppDatabase.get(ctx).notificationDao().markAllRead(uid) } }
            }
            bumpState()
        }
    }

    fun addLocalNotification(
        recipientUid: String = activeUid,
        type: String,
        title: String,
        body: String,
        targetType: String,
        targetId: String,
        extraArg: String? = null,
        postOsNotification: Boolean = true,
    ): AppNotification = addNotificationInternal(
        recipientUid = recipientUid,
        type = type,
        title = title,
        body = body,
        targetType = targetType,
        targetId = targetId,
        extraArg = extraArg,
        postOs = postOsNotification,
    )

    private fun addNotificationInternal(
        recipientUid: String,
        type: String,
        title: String,
        body: String,
        targetType: String,
        targetId: String,
        extraArg: String? = null,
        postOs: Boolean = false,
    ): AppNotification {
        val notif = AppNotification(
            id = "notif_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}",
            recipientUid = recipientUid,
            type = type,
            title = title,
            body = body,
            targetType = targetType,
            targetId = targetId,
            extraArg = extraArg,
            read = false,
            createdAt = System.currentTimeMillis(),
        )
        notifications.add(0, notif)
        val ctx = appContext
        if (ctx != null) {
            ioScope.launch {
                runCatching {
                    AppDatabase.get(ctx).notificationDao().upsert(
                        NotificationEntity(
                            id = notif.id,
                            recipientUid = notif.recipientUid,
                            type = notif.type,
                            title = notif.title,
                            body = notif.body,
                            targetType = notif.targetType,
                            targetId = notif.targetId,
                            extraArg = notif.extraArg,
                            read = notif.read,
                            createdAt = notif.createdAt,
                        ),
                    )
                }
                if (isFirebaseConfigured(ctx) && recipientUid.isNotEmpty() && recipientUid != "ALL") {
                    runCatching {
                        FirebaseFirestore.getInstance()
                            .collection("notifications")
                            .document(notif.id)
                            .set(
                                hashMapOf(
                                    "id" to notif.id,
                                    "recipientUid" to notif.recipientUid,
                                    "type" to notif.type,
                                    "title" to notif.title,
                                    "body" to notif.body,
                                    "targetType" to notif.targetType,
                                    "targetId" to notif.targetId,
                                    "extraArg" to notif.extraArg.orEmpty(),
                                    "read" to false,
                                    "createdAt" to notif.createdAt,
                                ),
                            ).await()
                    }
                }
            }
            if (postOs && recipientUid == activeUid) {
                NotificationHelper.postDealNotification(
                    context = ctx,
                    title = title,
                    body = body,
                    targetType = targetType,
                    targetId = targetId,
                    appNotificationId = notif.id,
                )
            }
        }
        return notif
    }

    /**
     * Executed on `Dispatchers.IO` by [com.nevergrowold.holdhightheshop.system.MarketplaceSyncService]
     * and [com.nevergrowold.holdhightheshop.system.MarketplaceSyncWorker].
     * - Expires pending requests older than 24h
     * - Detects overdue loans and alerts the student
     * - Re-checks suspension status when Firebase is connected
     */
    suspend fun runBackgroundMaintenance(context: Context): Int = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        val uid = activeUid
        if (uid.isEmpty()) return@withContext 0

        var changes = 0
        val now = System.currentTimeMillis()

        // 1. Expire stale pending requests (> 24h)
        for (i in requests.indices) {
            val req = requests[i]
            if (req.status == Deals.PENDING && now > req.expiresMillis) {
                requests[i] = req.copy(status = Deals.EXPIRED)
                writeRequestFirestore(requests[i])
                changes++
            }
        }

        // 2. Check overdue rentals for current user
        rentals.entries.forEach { (listingId, rental) ->
            if ((rental.borrowerUid == uid || rental.lenderUid == uid) && Rentals.isOverdue(rental.dueMillis)) {
                val listing = Listings.byIdOrNull(listingId)
                if (listing != null) {
                    val overdueKey = "overdue_${listingId}_${Rentals.daysUntil(rental.dueMillis)}"
                    if (notifiedIds.add(overdueKey)) {
                        NotificationHelper.postReminderNotification(
                            context = context,
                            alarmKey = overdueKey,
                            title = "Loan overdue: ${listing.title}",
                            body = "Coordinate return in chat with ${rental.partner.first} today.",
                            targetType = NotificationTarget.LISTING,
                            targetId = listingId,
                        )
                        changes++
                    }
                }
            }
        }

        // 3. Refresh current user's suspension/warning flags from Firestore
        if (isFirebaseConfigured(context)) {
            runCatching {
                val snap = FirebaseFirestore.getInstance().collection("users").document(uid).get().await()
                if (snap.exists()) {
                    val suspended = snap.getBoolean("suspended") == true
                    val reason = snap.getString("suspendedReason").orEmpty()
                    val warnings = (snap.getLong("warningCount") ?: 0L).toInt()
                    if (suspended != profile.suspended || warnings != profile.warningCount) {
                        profile = profile.copy(suspended = suspended, suspendedReason = reason, warningCount = warnings)
                        changes++
                    }
                }
            }
        }

        if (changes > 0) bumpState(broadcast = true)
        changes
    }

    // =========================================================================
    // Firestore Real-Time Snapshot Listeners (Live 5-Phone Multi-User Sync)
    // =========================================================================

    private fun startFirestoreListeners(context: Context) {
        detachAllFirestoreListeners()
        if (!isFirebaseConfigured(context) || activeUid.isEmpty()) return
        val db = FirebaseFirestore.getInstance()
        val uid = activeUid

        // 1. Live self user listener (detects instant Admin suspension/warning!)
        firestoreListeners += db.collection("users").document(uid).addSnapshotListener { snap, _ ->
            if (snap == null || !snap.exists()) return@addSnapshotListener
            profile = profile.copy(
                name = snap.getString("name").orEmpty().ifBlank { profile.name },
                program = snap.getString("program").orEmpty().ifBlank { profile.program },
                year = snap.getString("year").orEmpty().ifBlank { profile.year },
                bio = snap.getString("bio").orEmpty(),
                avatarUri = snap.getString("avatarUri")?.takeIf { it.isNotBlank() },
                suspended = snap.getBoolean("suspended") == true,
                suspendedReason = snap.getString("suspendedReason").orEmpty(),
                warningCount = (snap.getLong("warningCount") ?: 0L).toInt(),
            )
            @Suppress("UNCHECKED_CAST")
            (snap.get("savedIds") as? List<String>)?.let { savedByUid[uid] = it.toMutableSet() }
            @Suppress("UNCHECKED_CAST")
            (snap.get("blockedUids") as? List<String>)?.let { blockedByUid[uid] = it.toMutableSet() }
            lateCancelsByUid[uid] = (snap.getLong("lateCancels") ?: 0L).toInt()
            bumpState(broadcast = true)
        }

        // 2. All UC students directory (public profiles)
        firestoreListeners += db.collection("users").addSnapshotListener { snaps, _ ->
            if (snaps == null) return@addSnapshotListener
            snaps.documents.forEach { doc ->
                val dUid = doc.id
                val name = doc.getString("name").orEmpty().ifBlank { "UC Student" }
                users[dUid] = Seller(
                    name = name,
                    initials = UserState.UserProfile(name = name).initials,
                    program = doc.getString("program").orEmpty().ifBlank { "CITCS" },
                    year = doc.getString("year").orEmpty().ifBlank { "1st Year" },
                    rating = doc.getString("rating") ?: "New",
                    onTime = doc.getString("onTime") ?: "\u2013",
                    completed = (doc.getLong("completed") ?: 0L).toInt(),
                    lastMessage = "",
                    online = doc.getBoolean("showActiveStatus") != false,
                    uid = dUid,
                    email = doc.getString("email").orEmpty(),
                    bio = doc.getString("bio").orEmpty(),
                    avatarUri = doc.getString("avatarUri")?.takeIf { it.isNotBlank() },
                    showProgram = doc.getBoolean("showProgram") != false,
                    suspended = doc.getBoolean("suspended") == true,
                    suspendedReason = doc.getString("suspendedReason").orEmpty(),
                    warningCount = (doc.getLong("warningCount") ?: 0L).toInt(),
                )
            }
            bumpState(broadcast = true)
        }

        // 3. Live campus listings
        firestoreListeners += db.collection("listings")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snaps, _ ->
                if (snaps == null) return@addSnapshotListener
                val mapped = snaps.documents.mapNotNull { doc -> firestoreDocToListing(doc.id, doc.data) }
                listings.clear()
                listings.addAll(mapped)
                bumpState(broadcast = true)
            }

        // 4. Live requests (scoped to participants so handover codes stay private on the server!)
        firestoreListeners += db.collection("requests")
            .whereArrayContains("participants", uid)
            .addSnapshotListener { snaps, _ ->
                if (snaps == null) return@addSnapshotListener
                val mapped = snaps.documents.mapNotNull { doc -> firestoreDocToRequest(doc.id, doc.data) }
                requests.clear()
                requests.addAll(mapped)
                bumpState(broadcast = true, action = MarketplaceBroadcasts.ACTION_DEAL_UPDATED)
            }

        // 5. Live rentals (scoped to lender/borrower participants)
        firestoreListeners += db.collection("rentals")
            .whereArrayContains("participants", uid)
            .addSnapshotListener { snaps, _ ->
                if (snaps == null) return@addSnapshotListener
                rentals.clear()
                snaps.documents.forEach { doc ->
                    firestoreDocToRental(doc.data)?.let { rentals[doc.id] = it }
                }
                bumpState(broadcast = true, action = MarketplaceBroadcasts.ACTION_DEAL_UPDATED)
            }

        // 6. Live completed exchanges
        firestoreListeners += db.collection("exchanges")
            .orderBy("finishedMillis", Query.Direction.DESCENDING)
            .addSnapshotListener { snaps, _ ->
                if (snaps == null) return@addSnapshotListener
                val mapped = snaps.documents.mapNotNull { doc -> firestoreDocToExchange(doc.id, doc.data) }
                exchanges.clear()
                exchanges.addAll(mapped)
                bumpState(broadcast = true)
            }

        // 7. Live chats where current user participates
        firestoreListeners += db.collection("chats")
            .whereArrayContains("participants", uid)
            .addSnapshotListener { snaps, _ ->
                if (snaps == null) return@addSnapshotListener
                snaps.documents.forEach { doc ->
                    firestoreDocToThread(doc.id, doc.data)?.let { threads[doc.id] = it }
                }
                bumpState(broadcast = true, action = MarketplaceBroadcasts.ACTION_NEW_CHAT_MESSAGE)
            }

        // 8. Live personal notifications (triggers real OS notifications on new arrivals!)
        firestoreListeners += db.collection("notifications")
            .whereEqualTo("recipientUid", uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(60)
            .addSnapshotListener { snaps, _ ->
                if (snaps == null) return@addSnapshotListener
                val incoming = snaps.documents.mapNotNull { doc ->
                    val d = doc.data ?: return@mapNotNull null
                    AppNotification(
                        id = doc.id,
                        recipientUid = d["recipientUid"]?.toString().orEmpty(),
                        type = d["type"]?.toString() ?: NotificationType.RESERVATION,
                        title = d["title"]?.toString().orEmpty(),
                        body = d["body"]?.toString().orEmpty(),
                        targetType = d["targetType"]?.toString() ?: NotificationTarget.NONE,
                        targetId = d["targetId"]?.toString().orEmpty(),
                        extraArg = d["extraArg"]?.toString()?.takeIf { it.isNotBlank() },
                        read = d["read"] == true,
                        createdAt = (d["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                    )
                }
                if (initialNotifSnapshotDone) {
                    incoming.filter { !it.read && notifiedIds.add(it.id) }.forEach { n ->
                        if (n.type == NotificationType.MESSAGE && n.targetType == NotificationTarget.CHAT) {
                            val th = threads[n.targetId]
                            NotificationHelper.postChatMessageNotification(
                                context = context,
                                threadId = n.targetId,
                                listingId = th?.listingId.orEmpty(),
                                senderName = n.title,
                                messageBody = n.body,
                                appNotificationId = n.id,
                            )
                        } else {
                            NotificationHelper.postDealNotification(
                                context = context,
                                title = n.title,
                                body = n.body,
                                targetType = n.targetType,
                                targetId = n.targetId,
                                appNotificationId = n.id,
                                bypassToggle = n.type == NotificationType.MODERATION,
                            )
                        }
                    }
                } else {
                    incoming.forEach { notifiedIds.add(it.id) }
                    initialNotifSnapshotDone = true
                }
                notifications.removeAll { it.recipientUid == uid }
                notifications.addAll(incoming)
                bumpState(broadcast = true)
            }

        // 9. Live campus announcements from Admin Web Portal
        firestoreListeners += db.collection("announcements")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(20)
            .addSnapshotListener { snaps, _ ->
                if (snaps == null) return@addSnapshotListener
                val incoming = snaps.documents.mapNotNull { doc ->
                    val d = doc.data ?: return@mapNotNull null
                    CampusAnnouncement(
                        id = doc.id,
                        title = d["title"]?.toString().orEmpty(),
                        body = d["body"]?.toString().orEmpty(),
                        authorEmail = d["authorEmail"]?.toString().orEmpty(),
                        createdAt = (d["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                    )
                }
                if (initialAnnounceSnapshotDone) {
                    incoming.filter { notifiedIds.add("ann_${it.id}") }.forEach { a ->
                        NotificationHelper.postAnnouncementNotification(context, a.id, a.title, a.body)
                    }
                } else {
                    incoming.forEach { notifiedIds.add("ann_${it.id}") }
                    initialAnnounceSnapshotDone = true
                }
                announcements.clear()
                announcements.addAll(incoming)
                bumpState(broadcast = true)
            }
    }

    fun attachThreadListener(threadId: String) {
        val ctx = appContext ?: return
        if (!isFirebaseConfigured(ctx) || threadMessageListeners.containsKey(threadId)) return
        val reg = FirebaseFirestore.getInstance()
            .collection("chats")
            .document(threadId)
            .collection("messages")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snaps, _ ->
                if (snaps == null) return@addSnapshotListener
                val msgs = snaps.documents.mapNotNull { doc -> firestoreDocToMessage(doc.id, doc.data) }
                messagesByThread[threadId] = msgs.toMutableList()
                bumpState(broadcast = true, action = MarketplaceBroadcasts.ACTION_NEW_CHAT_MESSAGE)
            }
        threadMessageListeners[threadId] = reg
    }

    fun detachThreadListener(threadId: String) {
        threadMessageListeners.remove(threadId)?.remove()
    }

    private fun detachAllFirestoreListeners() {
        firestoreListeners.forEach { runCatching { it.remove() } }
        firestoreListeners.clear()
        threadMessageListeners.values.forEach { runCatching { it.remove() } }
        threadMessageListeners.clear()
    }

    // =========================================================================
    // Firestore Write Helpers
    // =========================================================================

    private fun replaceRequest(updated: Request) {
        val idx = requests.indexOfFirst { it.id == updated.id }
        if (idx >= 0) requests[idx] = updated else requests.add(updated)
    }

    private fun setListingStatusInternal(listingId: String, status: String) {
        val idx = listings.indexOfFirst { it.id == listingId }
        if (idx >= 0) {
            listings[idx] = listings[idx].copy(status = status)
            updateFirestoreField("listings", listingId, "status", status)
        }
    }

    private fun updateFirestoreField(collection: String, docId: String, field: String, value: Any) {
        val ctx = appContext ?: return
        if (!isFirebaseConfigured(ctx) || activeUid.isEmpty()) return
        ioScope.launch {
            runCatching {
                FirebaseFirestore.getInstance().collection(collection).document(docId)
                    .update(field, value).await()
            }
        }
    }

    private fun syncUserArrayField(uid: String, field: String, values: List<String>) {
        val ctx = appContext ?: return
        if (!isFirebaseConfigured(ctx)) return
        ioScope.launch {
            runCatching {
                FirebaseFirestore.getInstance().collection("users").document(uid)
                    .update(field, values).await()
            }
        }
    }

    private fun syncUserLateCancels(uid: String) {
        val ctx = appContext ?: return
        if (!isFirebaseConfigured(ctx)) return
        ioScope.launch {
            runCatching {
                FirebaseFirestore.getInstance().collection("users").document(uid)
                    .update("lateCancels", lateCancelsByUid[uid] ?: 0).await()
            }
        }
    }

    private fun writeRequestFirestore(req: Request) {
        val ctx = appContext ?: return
        if (!isFirebaseConfigured(ctx)) return
        ioScope.launch {
            runCatching {
                FirebaseFirestore.getInstance().collection("requests").document(req.id).set(
                    hashMapOf(
                        "id" to req.id,
                        "listingId" to req.listingId,
                        "ownerUid" to req.ownerUid,
                        "requesterUid" to req.requesterUid,
                        "participants" to listOf(req.ownerUid, req.requesterUid),
                        "requesterName" to req.from.name,
                        "requesterProgram" to req.from.program,
                        "sentMillis" to req.sentMillis,
                        "meetup" to req.meetup,
                        "meetupNote" to req.meetupNote,
                        "meetMillis" to req.meetMillis,
                        "message" to req.message,
                        "days" to req.days,
                        "offerId" to req.offerId.orEmpty(),
                        "agreedPrice" to req.agreedPrice,
                        "handoverCode" to req.handoverCode,
                        "status" to req.status,
                        "acceptedAtMillis" to (req.acceptedAtMillis ?: 0L),
                    ),
                ).await()
            }
        }
    }

    private fun writeRentalFirestore(listingId: String, rental: UserState.Rental) {
        val ctx = appContext ?: return
        if (!isFirebaseConfigured(ctx)) return
        ioScope.launch {
            runCatching {
                FirebaseFirestore.getInstance().collection("rentals").document(listingId).set(
                    hashMapOf(
                        "id" to rental.id,
                        "listingId" to listingId,
                        "lenderUid" to rental.lenderUid,
                        "borrowerUid" to rental.borrowerUid,
                        "participants" to listOf(rental.lenderUid, rental.borrowerUid),
                        "dueMillis" to rental.dueMillis,
                        "startMillis" to rental.startMillis,
                        "returnPending" to rental.returnPending,
                        "reportedMillis" to (rental.reportedMillis ?: 0L),
                        "days" to rental.days,
                        "proof" to rental.proof,
                    ),
                ).await()
            }
        }
    }

    private fun deleteRentalFirestore(listingId: String) {
        val ctx = appContext ?: return
        if (!isFirebaseConfigured(ctx)) return
        ioScope.launch {
            runCatching { FirebaseFirestore.getInstance().collection("rentals").document(listingId).delete().await() }
        }
    }

    private fun writeExchangeFirestore(ex: Exchange) {
        val ctx = appContext ?: return
        if (!isFirebaseConfigured(ctx)) return
        ioScope.launch {
            runCatching {
                FirebaseFirestore.getInstance().collection("exchanges").document(ex.id).set(
                    hashMapOf(
                        "id" to ex.id,
                        "title" to ex.title,
                        "price" to ex.price,
                        "kind" to ex.kind,
                        "finishedMillis" to ex.finishedMillis,
                        "meetup" to ex.meetup,
                        "onTime" to ex.onTime,
                        "proof" to ex.proof,
                        "listingId" to ex.listingId.orEmpty(),
                        "giverUid" to ex.giverUid,
                        "receiverUid" to ex.receiverUid,
                        "giverRating" to ex.giverRating,
                        "giverNote" to ex.giverNote,
                        "receiverRating" to ex.receiverRating,
                        "receiverNote" to ex.receiverNote,
                    ),
                ).await()
            }
        }
    }

    private fun writeThreadFirestore(thread: ChatThread) {
        val ctx = appContext ?: return
        if (!isFirebaseConfigured(ctx)) return
        ioScope.launch { writeThreadFirestoreSuspend(thread) }
    }

    private suspend fun writeThreadFirestoreSuspend(thread: ChatThread) {
        runCatching {
            FirebaseFirestore.getInstance().collection("chats").document(thread.threadId).set(
                hashMapOf(
                    "threadId" to thread.threadId,
                    "listingId" to thread.listingId,
                    "ownerUid" to thread.ownerUid,
                    "buyerUid" to thread.buyerUid,
                    "participants" to listOf(thread.ownerUid, thread.buyerUid),
                    "ownerName" to thread.ownerName,
                    "buyerName" to thread.buyerName,
                    "ownerProgram" to thread.ownerProgram,
                    "buyerProgram" to thread.buyerProgram,
                    "lastMessagePreview" to thread.lastMessagePreview,
                    "lastMessageMillis" to thread.lastMessageMillis,
                    "lastSenderUid" to thread.lastSenderUid,
                    "unreadByUid" to thread.unreadByUid,
                ),
            ).await()
        }
    }

    private fun writeMessageFirestore(threadId: String, msg: UserState.ChatMessage) {
        val ctx = appContext ?: return
        if (!isFirebaseConfigured(ctx)) return
        ioScope.launch { writeMessageFirestoreSuspend(threadId, msg) }
    }

    private suspend fun writeMessageFirestoreSuspend(threadId: String, msg: UserState.ChatMessage) {
        runCatching {
            val map = hashMapOf<String, Any>(
                "id" to msg.id,
                "senderUid" to msg.senderUid,
                "text" to msg.text.orEmpty(),
                "photoUri" to msg.photoUri.orEmpty(),
                "createdAt" to msg.createdAt,
                "systemTitle" to msg.systemTitle.orEmpty(),
                "systemDetail" to msg.systemDetail.orEmpty(),
                "meetupPlace" to msg.meetupPlace.orEmpty(),
                "meetupMillis" to (msg.meetupMillis ?: 0L),
            )
            msg.proposal?.let { p ->
                map["proposal"] = hashMapOf(
                    "proposalId" to p.proposalId,
                    "proposalType" to p.proposalType,
                    "proposalStatus" to p.proposalStatus,
                    "proposerUid" to p.proposerUid,
                    "proposerName" to p.proposerName,
                    "listingId" to p.listingId,
                    "requestId" to p.requestId.orEmpty(),
                    "oldMeetup" to p.oldMeetup.orEmpty(),
                    "oldMeetMillis" to (p.oldMeetMillis ?: 0L),
                    "newMeetup" to p.newMeetup.orEmpty(),
                    "newMeetupNote" to p.newMeetupNote,
                    "newMeetMillis" to (p.newMeetMillis ?: 0L),
                    "extraDays" to p.extraDays,
                    "extraCostPesos" to p.extraCostPesos,
                    "newDueMillis" to (p.newDueMillis ?: 0L),
                    "note" to p.note,
                    "resolvedByUid" to p.resolvedByUid.orEmpty(),
                    "resolvedAt" to (p.resolvedAt ?: 0L),
                )
            }
            FirebaseFirestore.getInstance()
                .collection("chats")
                .document(threadId)
                .collection("messages")
                .document(msg.id)
                .set(map)
                .await()
        }
    }

    private fun listingToFirestoreMap(listing: Listing): HashMap<String, Any> = hashMapOf(
        "id" to listing.id,
        "title" to listing.title,
        "price" to listing.price,
        "program" to listing.program,
        "targetPrograms" to listing.targetPrograms,
        "condition" to listing.condition,
        "type" to listing.type,
        "description" to listing.description,
        "ownerUid" to listing.ownerUid,
        "sellerName" to listing.seller.name,
        "sellerProgram" to listing.seller.program,
        "meetup" to listing.meetup,
        "meetupNote" to listing.meetupNote,
        "wants" to listing.wants.orEmpty(),
        "maxDays" to listing.maxDays,
        "photoUris" to listing.photoUris,
        "availabilityJson" to availabilityToJson(listing.availability),
        "status" to listing.status,
        "aiCheckStatus" to listing.aiCheckStatus,
        "reportCount" to listing.reportCount,
        "reportedByUids" to listing.reportedByUids.toList(),
        "createdAt" to listing.createdAt,
        "updatedAt" to FieldValue.serverTimestamp(),
    )

    // =========================================================================
    // Firestore Document Mappers
    // =========================================================================

    @Suppress("UNCHECKED_CAST")
    private fun firestoreDocToListing(id: String, data: Map<String, Any>?): Listing? {
        if (data == null) return null
        val ownerUid = data["ownerUid"]?.toString().orEmpty()
        val seller = users[ownerUid] ?: Seller(
            name = data["sellerName"]?.toString().orEmpty().ifBlank { "UC Student" },
            initials = UserState.UserProfile(name = data["sellerName"]?.toString().orEmpty()).initials,
            program = data["sellerProgram"]?.toString().orEmpty().ifBlank { "CITCS" },
            year = "UCian",
            rating = "New",
            onTime = "\u2013",
            completed = 0,
            lastMessage = "",
            online = true,
            uid = ownerUid,
        )
        val createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
        val daysAgo = ((System.currentTimeMillis() - createdAt) / Rentals.DAY_MS).toInt().coerceAtLeast(0)
        val targetPrograms = (data["targetPrograms"] as? List<String>)?.filter { it in Listings.PROGRAMS }.orEmpty()
        val photoUris = (data["photoUris"] as? List<String>).orEmpty()
        val reportedBy = (data["reportedByUids"] as? List<String>)?.toSet().orEmpty()
        return Listing(
            id = id,
            title = data["title"]?.toString().orEmpty(),
            price = (data["price"] as? Number)?.toInt() ?: 0,
            program = targetPrograms.firstOrNull() ?: "All colleges",
            condition = (data["condition"] as? Number)?.toInt() ?: 1,
            type = (data["type"] as? Number)?.toInt() ?: 0,
            description = data["description"]?.toString().orEmpty(),
            seller = seller,
            meetup = data["meetup"]?.toString().orEmpty().ifBlank { Listings.MEETUP_POINTS.first() },
            postedDaysAgo = daysAgo,
            availability = availabilityFromJson(data["availabilityJson"]?.toString()),
            wants = data["wants"]?.toString()?.takeIf { it.isNotBlank() },
            maxDays = (data["maxDays"] as? Number)?.toInt()?.coerceIn(1, 120) ?: 14,
            photoUris = photoUris,
            targetPrograms = targetPrograms,
            meetupNote = data["meetupNote"]?.toString().orEmpty(),
            ownerUid = ownerUid,
            status = data["status"]?.toString() ?: ListingStatus.OPEN,
            aiCheckStatus = data["aiCheckStatus"]?.toString() ?: AiCheckStatus.APPROVED,
            reportCount = (data["reportCount"] as? Number)?.toInt() ?: reportedBy.size,
            reportedByUids = reportedBy,
            createdAt = createdAt,
        )
    }

    private fun firestoreDocToRequest(id: String, data: Map<String, Any>?): Request? {
        if (data == null) return null
        val reqUid = data["requesterUid"]?.toString().orEmpty()
        val from = users[reqUid] ?: Seller(
            name = data["requesterName"]?.toString().orEmpty().ifBlank { "UC Student" },
            initials = UserState.UserProfile(name = data["requesterName"]?.toString().orEmpty()).initials,
            program = data["requesterProgram"]?.toString().orEmpty().ifBlank { "CITCS" },
            year = "UCian",
            rating = "New",
            onTime = "\u2013",
            completed = 0,
            lastMessage = "",
            online = true,
            uid = reqUid,
        )
        return Request(
            id = id,
            listingId = data["listingId"]?.toString().orEmpty(),
            from = from,
            sentMillis = (data["sentMillis"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            meetup = data["meetup"]?.toString().orEmpty(),
            meetMillis = (data["meetMillis"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            message = data["message"]?.toString().orEmpty(),
            days = (data["days"] as? Number)?.toInt() ?: 0,
            offerId = data["offerId"]?.toString()?.takeIf { it.isNotBlank() },
            ownerUid = data["ownerUid"]?.toString().orEmpty(),
            requesterUid = reqUid,
            agreedPrice = (data["agreedPrice"] as? Number)?.toInt() ?: 0,
            meetupNote = data["meetupNote"]?.toString().orEmpty(),
            handoverCode = data["handoverCode"]?.toString().orEmpty(),
            status = (data["status"] as? Number)?.toInt() ?: Deals.PENDING,
            acceptedAtMillis = (data["acceptedAtMillis"] as? Number)?.toLong()?.takeIf { it > 0L },
        )
    }

    private fun firestoreDocToRental(data: Map<String, Any>?): UserState.Rental? {
        if (data == null) return null
        val lenderUid = data["lenderUid"]?.toString().orEmpty()
        val borrowerUid = data["borrowerUid"]?.toString().orEmpty()
        val partner = users[if (activeUid == lenderUid) borrowerUid else lenderUid] ?: UserState.me()
        return UserState.Rental(
            id = data["id"]?.toString().orEmpty(),
            partner = partner,
            dueMillis = (data["dueMillis"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            lending = activeUid == lenderUid,
            returnPending = data["returnPending"] == true,
            startMillis = (data["startMillis"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            reportedMillis = (data["reportedMillis"] as? Number)?.toLong()?.takeIf { it > 0L },
            proof = (data["proof"] as? Number)?.toInt() ?: Exchange.PROOF_CODE,
            days = (data["days"] as? Number)?.toInt() ?: 1,
            lenderUid = lenderUid,
            borrowerUid = borrowerUid,
        )
    }

    private fun firestoreDocToExchange(id: String, data: Map<String, Any>?): Exchange? {
        if (data == null) return null
        val giverUid = data["giverUid"]?.toString().orEmpty()
        val receiverUid = data["receiverUid"]?.toString().orEmpty()
        return Exchange(
            id = id,
            title = data["title"]?.toString().orEmpty(),
            price = (data["price"] as? Number)?.toInt() ?: 0,
            kind = (data["kind"] as? Number)?.toInt() ?: Exchange.SOLD,
            partner = users[receiverUid],
            finishedMillis = (data["finishedMillis"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            meetup = data["meetup"]?.toString().orEmpty(),
            theirRating = 0,
            yourRating = 0,
            onTime = data["onTime"] != false,
            proof = (data["proof"] as? Number)?.toInt() ?: Exchange.PROOF_CODE,
            listingId = data["listingId"]?.toString()?.takeIf { it.isNotBlank() },
            giverUid = giverUid,
            receiverUid = receiverUid,
            giverRating = (data["giverRating"] as? Number)?.toInt() ?: 0,
            giverNote = data["giverNote"]?.toString().orEmpty(),
            receiverRating = (data["receiverRating"] as? Number)?.toInt() ?: 0,
            receiverNote = data["receiverNote"]?.toString().orEmpty(),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun firestoreDocToThread(id: String, data: Map<String, Any>?): ChatThread? {
        if (data == null) return null
        val unreadRaw = (data["unreadByUid"] as? Map<String, Any>).orEmpty()
        return ChatThread(
            threadId = id,
            listingId = data["listingId"]?.toString().orEmpty(),
            ownerUid = data["ownerUid"]?.toString().orEmpty(),
            buyerUid = data["buyerUid"]?.toString().orEmpty(),
            ownerName = data["ownerName"]?.toString().orEmpty(),
            buyerName = data["buyerName"]?.toString().orEmpty(),
            ownerProgram = data["ownerProgram"]?.toString().orEmpty(),
            buyerProgram = data["buyerProgram"]?.toString().orEmpty(),
            lastMessagePreview = data["lastMessagePreview"]?.toString().orEmpty(),
            lastMessageMillis = (data["lastMessageMillis"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            lastSenderUid = data["lastSenderUid"]?.toString().orEmpty(),
            unreadByUid = unreadRaw.mapValues { it.value == true },
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun firestoreDocToMessage(id: String, data: Map<String, Any>?): UserState.ChatMessage? {
        if (data == null) return null
        val senderUid = data["senderUid"]?.toString().orEmpty()
        val pMap = data["proposal"] as? Map<String, Any>
        val proposal = pMap?.let { p ->
            ProposalPayload(
                proposalId = p["proposalId"]?.toString().orEmpty(),
                proposalType = p["proposalType"]?.toString() ?: ProposalType.MEETUP_CHANGE,
                proposalStatus = p["proposalStatus"]?.toString() ?: ProposalStatus.PENDING,
                proposerUid = p["proposerUid"]?.toString().orEmpty(),
                proposerName = p["proposerName"]?.toString().orEmpty(),
                listingId = p["listingId"]?.toString().orEmpty(),
                requestId = p["requestId"]?.toString()?.takeIf { it.isNotBlank() },
                oldMeetup = p["oldMeetup"]?.toString()?.takeIf { it.isNotBlank() },
                oldMeetMillis = (p["oldMeetMillis"] as? Number)?.toLong()?.takeIf { it > 0L },
                newMeetup = p["newMeetup"]?.toString()?.takeIf { it.isNotBlank() },
                newMeetupNote = p["newMeetupNote"]?.toString().orEmpty(),
                newMeetMillis = (p["newMeetMillis"] as? Number)?.toLong()?.takeIf { it > 0L },
                extraDays = (p["extraDays"] as? Number)?.toInt() ?: 0,
                extraCostPesos = (p["extraCostPesos"] as? Number)?.toInt() ?: 0,
                newDueMillis = (p["newDueMillis"] as? Number)?.toLong()?.takeIf { it > 0L },
                note = p["note"]?.toString().orEmpty(),
                resolvedByUid = p["resolvedByUid"]?.toString()?.takeIf { it.isNotBlank() },
                resolvedAt = (p["resolvedAt"] as? Number)?.toLong()?.takeIf { it > 0L },
            )
        }
        return UserState.ChatMessage(
            id = id,
            senderUid = senderUid,
            text = data["text"]?.toString()?.takeIf { it.isNotBlank() },
            photoUri = data["photoUri"]?.toString()?.takeIf { it.isNotBlank() },
            outgoing = senderUid == activeUid,
            createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            systemTitle = data["systemTitle"]?.toString()?.takeIf { it.isNotBlank() },
            systemDetail = data["systemDetail"]?.toString()?.takeIf { it.isNotBlank() },
            meetupPlace = data["meetupPlace"]?.toString()?.takeIf { it.isNotBlank() },
            meetupMillis = (data["meetupMillis"] as? Number)?.toLong()?.takeIf { it > 0L },
            proposal = proposal,
        )
    }

    // =========================================================================
    // Local Room Snapshot Persistence (Offline Resilience + Local Multi-User)
    // =========================================================================

    private fun restoreSessionSync(context: Context) {
        SecureSessionStore.getEncrypted(context, KEY_SETTINGS_JSON)?.let { raw ->
            runCatching {
                val obj = JSONObject(raw)
                settings = UserState.SettingsState(
                    notifyMessages = obj.optBoolean("notifyMessages", true),
                    notifyReservations = obj.optBoolean("notifyReservations", true),
                    notifyReminders = obj.optBoolean("notifyReminders", true),
                    notifyPriceDrops = obj.optBoolean("notifyPriceDrops", false),
                    showProgram = obj.optBoolean("showProgram", true),
                    showActiveStatus = obj.optBoolean("showActiveStatus", true),
                )
            }
        }
        SecureSessionStore.getEncrypted(context, KEY_DEFAULT_MEETUP)?.toIntOrNull()?.let {
            defaultMeetupIndex = it.coerceIn(0, Listings.MEETUP_POINTS.lastIndex)
        }

        if (isFirebaseConfigured(context)) {
            val fbUser = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
            if (fbUser != null) {
                activeUid = fbUser.uid
                val cachedSeller = users[fbUser.uid]
                profile = UserState.UserProfile(
                    uid = fbUser.uid,
                    name = cachedSeller?.name ?: fbUser.email?.substringBefore('@') ?: "UC Student",
                    email = fbUser.email.orEmpty(),
                    program = cachedSeller?.program ?: "CITCS",
                    year = cachedSeller?.year ?: "1st Year",
                    bio = cachedSeller?.bio.orEmpty(),
                    avatarUri = cachedSeller?.avatarUri,
                    emailVerified = fbUser.isEmailVerified,
                    suspended = cachedSeller?.suspended == true,
                    suspendedReason = cachedSeller?.suspendedReason.orEmpty(),
                    warningCount = cachedSeller?.warningCount ?: 0,
                )
            }
            return
        }

        val savedUid = SecureSessionStore.getEncrypted(context, KEY_ACTIVE_UID).orEmpty()
        if (savedUid.isNotEmpty()) {
            runBlocking(Dispatchers.IO) {
                runCatching {
                    val dao = AppDatabase.get(context).localAccountDao()
                    dao.all().forEach { registerSellerFromEntity(it) }
                    dao.findByUid(savedUid)?.let { acct ->
                        activeUid = acct.uid
                        profile = entityToProfile(acct)
                    }
                    AppDatabase.get(context).draftDao().getDraft(savedUid)?.let {
                        draft = entityToDraft(it)
                    }
                }
            }
        }
    }

    private fun restoreFromLocalStorageSync(context: Context) {
        runBlocking(Dispatchers.IO) {
            runCatching {
                val db = AppDatabase.get(context)
                db.localAccountDao().all().forEach { registerSellerFromEntity(it) }
                db.stateSnapshotDao().get(SNAPSHOT_KEY)?.jsonPayload?.let { restoreSnapshotJson(it) }
            }.onFailure { Log.w(TAG, "Local snapshot restore skipped", it) }
        }
    }

    private fun persistSnapshotAsync(context: Context) {
        val json = runCatching { encodeSnapshotJson() }.getOrNull() ?: return
        ioScope.launch {
            runCatching {
                AppDatabase.get(context).stateSnapshotDao()
                    .put(StateSnapshotEntity(SNAPSHOT_KEY, json, System.currentTimeMillis()))
            }
        }
    }

    private fun encodeSnapshotJson(): String {
        val root = JSONObject()
        val usersArr = JSONArray()
        users.values.forEach { s ->
            usersArr.put(
                JSONObject().apply {
                    put("uid", s.uid)
                    put("name", s.name)
                    put("initials", s.initials)
                    put("program", s.program)
                    put("year", s.year)
                    put("email", s.email)
                    put("bio", s.bio)
                    put("avatarUri", s.avatarUri.orEmpty())
                    put("suspended", s.suspended)
                    put("suspendedReason", s.suspendedReason)
                    put("warningCount", s.warningCount)
                },
            )
        }
        root.put("users", usersArr)

        val listingsArr = JSONArray()
        listings.forEach { l ->
            listingsArr.put(
                JSONObject().apply {
                    put("id", l.id)
                    put("title", l.title)
                    put("price", l.price)
                    put("program", l.program)
                    put("targetPrograms", JSONArray(l.targetPrograms))
                    put("condition", l.condition)
                    put("type", l.type)
                    put("description", l.description)
                    put("ownerUid", l.ownerUid)
                    put("sellerName", l.seller.name)
                    put("sellerProgram", l.seller.program)
                    put("meetup", l.meetup)
                    put("meetupNote", l.meetupNote)
                    put("wants", l.wants.orEmpty())
                    put("maxDays", l.maxDays)
                    put("photoUris", JSONArray(l.photoUris))
                    put("availabilityJson", availabilityToJson(l.availability))
                    put("status", l.status)
                    put("aiCheckStatus", l.aiCheckStatus)
                    put("reportCount", l.reportCount)
                    put("reportedByUids", JSONArray(l.reportedByUids.toList()))
                    put("createdAt", l.createdAt)
                },
            )
        }
        root.put("listings", listingsArr)

        val reqArr = JSONArray()
        requests.forEach { r ->
            reqArr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("listingId", r.listingId)
                    put("ownerUid", r.ownerUid)
                    put("requesterUid", r.requesterUid)
                    put("requesterName", r.from.name)
                    put("requesterProgram", r.from.program)
                    put("sentMillis", r.sentMillis)
                    put("meetup", r.meetup)
                    put("meetupNote", r.meetupNote)
                    put("meetMillis", r.meetMillis)
                    put("message", r.message)
                    put("days", r.days)
                    put("offerId", r.offerId.orEmpty())
                    put("agreedPrice", r.agreedPrice)
                    put("handoverCode", r.handoverCode)
                    put("status", r.status)
                    put("acceptedAtMillis", r.acceptedAtMillis ?: 0L)
                },
            )
        }
        root.put("requests", reqArr)

        val rentalsObj = JSONObject()
        rentals.forEach { (lid, r) ->
            rentalsObj.put(
                lid,
                JSONObject().apply {
                    put("id", r.id)
                    put("lenderUid", r.lenderUid)
                    put("borrowerUid", r.borrowerUid)
                    put("dueMillis", r.dueMillis)
                    put("startMillis", r.startMillis)
                    put("returnPending", r.returnPending)
                    put("reportedMillis", r.reportedMillis ?: 0L)
                    put("days", r.days)
                    put("proof", r.proof)
                },
            )
        }
        root.put("rentals", rentalsObj)

        val exArr = JSONArray()
        exchanges.forEach { e ->
            exArr.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("title", e.title)
                    put("price", e.price)
                    put("kind", e.kind)
                    put("finishedMillis", e.finishedMillis)
                    put("meetup", e.meetup)
                    put("onTime", e.onTime)
                    put("proof", e.proof)
                    put("listingId", e.listingId.orEmpty())
                    put("giverUid", e.giverUid)
                    put("receiverUid", e.receiverUid)
                    put("giverRating", e.giverRating)
                    put("giverNote", e.giverNote)
                    put("receiverRating", e.receiverRating)
                    put("receiverNote", e.receiverNote)
                },
            )
        }
        root.put("exchanges", exArr)

        val threadsArr = JSONArray()
        threads.values.forEach { t ->
            val unreadObj = JSONObject()
            t.unreadByUid.forEach { (k, v) -> unreadObj.put(k, v) }
            threadsArr.put(
                JSONObject().apply {
                    put("threadId", t.threadId)
                    put("listingId", t.listingId)
                    put("ownerUid", t.ownerUid)
                    put("buyerUid", t.buyerUid)
                    put("ownerName", t.ownerName)
                    put("buyerName", t.buyerName)
                    put("ownerProgram", t.ownerProgram)
                    put("buyerProgram", t.buyerProgram)
                    put("lastMessagePreview", t.lastMessagePreview)
                    put("lastMessageMillis", t.lastMessageMillis)
                    put("lastSenderUid", t.lastSenderUid)
                    put("unreadByUid", unreadObj)
                },
            )
        }
        root.put("threads", threadsArr)

        val msgsObj = JSONObject()
        messagesByThread.forEach { (tid, list) ->
            val arr = JSONArray()
            list.forEach { m ->
                arr.put(
                    JSONObject().apply {
                        put("id", m.id)
                        put("senderUid", m.senderUid)
                        put("text", m.text.orEmpty())
                        put("photoUri", m.photoUri.orEmpty())
                        put("createdAt", m.createdAt)
                        put("systemTitle", m.systemTitle.orEmpty())
                        put("systemDetail", m.systemDetail.orEmpty())
                        put("meetupPlace", m.meetupPlace.orEmpty())
                        put("meetupMillis", m.meetupMillis ?: 0L)
                        m.proposal?.let { p ->
                            put(
                                "proposal",
                                JSONObject().apply {
                                    put("proposalId", p.proposalId)
                                    put("proposalType", p.proposalType)
                                    put("proposalStatus", p.proposalStatus)
                                    put("proposerUid", p.proposerUid)
                                    put("proposerName", p.proposerName)
                                    put("listingId", p.listingId)
                                    put("requestId", p.requestId.orEmpty())
                                    put("oldMeetup", p.oldMeetup.orEmpty())
                                    put("oldMeetMillis", p.oldMeetMillis ?: 0L)
                                    put("newMeetup", p.newMeetup.orEmpty())
                                    put("newMeetupNote", p.newMeetupNote)
                                    put("newMeetMillis", p.newMeetMillis ?: 0L)
                                    put("extraDays", p.extraDays)
                                    put("extraCostPesos", p.extraCostPesos)
                                    put("newDueMillis", p.newDueMillis ?: 0L)
                                    put("note", p.note)
                                    put("resolvedByUid", p.resolvedByUid.orEmpty())
                                    put("resolvedAt", p.resolvedAt ?: 0L)
                                },
                            )
                        }
                    },
                )
            }
            msgsObj.put(tid, arr)
        }
        root.put("messages", msgsObj)

        val notifsArr = JSONArray()
        notifications.forEach { n ->
            notifsArr.put(
                JSONObject().apply {
                    put("id", n.id)
                    put("recipientUid", n.recipientUid)
                    put("type", n.type)
                    put("title", n.title)
                    put("body", n.body)
                    put("targetType", n.targetType)
                    put("targetId", n.targetId)
                    put("extraArg", n.extraArg.orEmpty())
                    put("read", n.read)
                    put("createdAt", n.createdAt)
                },
            )
        }
        root.put("notifications", notifsArr)

        root.put("savedByUid", mapOfSetsToJson(savedByUid))
        root.put("blockedByUid", mapOfSetsToJson(blockedByUid))
        root.put("reportedListingsByUid", mapOfSetsToJson(reportedListingsByUid))
        root.put("receivedByUid", mapOfSetsToJson(receivedByUid))
        root.put("swappedInByUid", mapOfSetsToJson(swappedInByUid))
        return root.toString()
    }

    private fun restoreSnapshotJson(raw: String) {
        val root = JSONObject(raw)
        root.optJSONArray("users")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val uid = o.optString("uid")
                if (uid.isNotBlank()) {
                    users[uid] = Seller(
                        name = o.optString("name", "UC Student"),
                        initials = o.optString("initials", "UC"),
                        program = o.optString("program", "CITCS"),
                        year = o.optString("year", "1st Year"),
                        rating = "New",
                        onTime = "\u2013",
                        completed = 0,
                        lastMessage = "",
                        online = true,
                        uid = uid,
                        email = o.optString("email"),
                        bio = o.optString("bio"),
                        avatarUri = o.optString("avatarUri").takeIf { it.isNotBlank() },
                        suspended = o.optBoolean("suspended"),
                        suspendedReason = o.optString("suspendedReason"),
                        warningCount = o.optInt("warningCount"),
                    )
                }
            }
        }
        root.optJSONArray("listings")?.let { arr ->
            listings.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ownerUid = o.optString("ownerUid")
                val seller = users[ownerUid] ?: Seller(
                    name = o.optString("sellerName", "UC Student"),
                    initials = UserState.UserProfile(name = o.optString("sellerName")).initials,
                    program = o.optString("sellerProgram", "CITCS"),
                    year = "UCian",
                    rating = "New",
                    onTime = "\u2013",
                    completed = 0,
                    lastMessage = "",
                    online = true,
                    uid = ownerUid,
                )
                val targetPrograms = jsonArrayToStrings(o.optJSONArray("targetPrograms"))
                val createdAt = o.optLong("createdAt", System.currentTimeMillis())
                listings.add(
                    Listing(
                        id = o.getString("id"),
                        title = o.optString("title"),
                        price = o.optInt("price"),
                        program = targetPrograms.firstOrNull() ?: "All colleges",
                        condition = o.optInt("condition", 1),
                        type = o.optInt("type", 0),
                        description = o.optString("description"),
                        seller = seller,
                        meetup = o.optString("meetup", Listings.MEETUP_POINTS.first()),
                        postedDaysAgo = ((System.currentTimeMillis() - createdAt) / Rentals.DAY_MS).toInt().coerceAtLeast(0),
                        availability = availabilityFromJson(o.optString("availabilityJson")),
                        wants = o.optString("wants").takeIf { it.isNotBlank() },
                        maxDays = o.optInt("maxDays", 14).coerceIn(1, 120),
                        photoUris = jsonArrayToStrings(o.optJSONArray("photoUris")),
                        targetPrograms = targetPrograms,
                        meetupNote = o.optString("meetupNote"),
                        ownerUid = ownerUid,
                        status = o.optString("status", ListingStatus.OPEN),
                        aiCheckStatus = o.optString("aiCheckStatus", AiCheckStatus.APPROVED),
                        reportCount = o.optInt("reportCount"),
                        reportedByUids = jsonArrayToStrings(o.optJSONArray("reportedByUids")).toSet(),
                        createdAt = createdAt,
                    ),
                )
            }
        }
        root.optJSONArray("requests")?.let { arr ->
            requests.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val reqUid = o.optString("requesterUid")
                val from = users[reqUid] ?: Seller(
                    name = o.optString("requesterName", "UC Student"),
                    initials = UserState.UserProfile(name = o.optString("requesterName")).initials,
                    program = o.optString("requesterProgram", "CITCS"),
                    year = "UCian",
                    rating = "New",
                    onTime = "\u2013",
                    completed = 0,
                    lastMessage = "",
                    online = true,
                    uid = reqUid,
                )
                requests.add(
                    Request(
                        id = o.getString("id"),
                        listingId = o.optString("listingId"),
                        from = from,
                        sentMillis = o.optLong("sentMillis"),
                        meetup = o.optString("meetup"),
                        meetMillis = o.optLong("meetMillis"),
                        message = o.optString("message"),
                        days = o.optInt("days"),
                        offerId = o.optString("offerId").takeIf { it.isNotBlank() },
                        ownerUid = o.optString("ownerUid"),
                        requesterUid = reqUid,
                        agreedPrice = o.optInt("agreedPrice"),
                        meetupNote = o.optString("meetupNote"),
                        handoverCode = o.optString("handoverCode"),
                        status = o.optInt("status"),
                        acceptedAtMillis = o.optLong("acceptedAtMillis").takeIf { it > 0L },
                    ),
                )
            }
        }
        root.optJSONObject("rentals")?.let { obj ->
            rentals.clear()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val lid = keys.next()
                val o = obj.getJSONObject(lid)
                val lenderUid = o.optString("lenderUid")
                val borrowerUid = o.optString("borrowerUid")
                rentals[lid] = UserState.Rental(
                    id = o.optString("id"),
                    partner = users[borrowerUid] ?: users[lenderUid] ?: UserState.me(),
                    dueMillis = o.optLong("dueMillis"),
                    lending = lenderUid == activeUid,
                    returnPending = o.optBoolean("returnPending"),
                    startMillis = o.optLong("startMillis"),
                    reportedMillis = o.optLong("reportedMillis").takeIf { it > 0L },
                    proof = o.optInt("proof"),
                    days = o.optInt("days", 1),
                    lenderUid = lenderUid,
                    borrowerUid = borrowerUid,
                )
            }
        }
        root.optJSONArray("exchanges")?.let { arr ->
            exchanges.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                exchanges.add(
                    Exchange(
                        id = o.getString("id"),
                        title = o.optString("title"),
                        price = o.optInt("price"),
                        kind = o.optInt("kind"),
                        partner = users[o.optString("receiverUid")],
                        finishedMillis = o.optLong("finishedMillis"),
                        meetup = o.optString("meetup"),
                        theirRating = 0,
                        yourRating = 0,
                        onTime = o.optBoolean("onTime", true),
                        proof = o.optInt("proof"),
                        listingId = o.optString("listingId").takeIf { it.isNotBlank() },
                        giverUid = o.optString("giverUid"),
                        receiverUid = o.optString("receiverUid"),
                        giverRating = o.optInt("giverRating"),
                        giverNote = o.optString("giverNote"),
                        receiverRating = o.optInt("receiverRating"),
                        receiverNote = o.optString("receiverNote"),
                    ),
                )
            }
        }
        root.optJSONArray("threads")?.let { arr ->
            threads.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val unreadMap = LinkedHashMap<String, Boolean>()
                o.optJSONObject("unreadByUid")?.let { uo ->
                    val uKeys = uo.keys()
                    while (uKeys.hasNext()) {
                        val k = uKeys.next()
                        unreadMap[k] = uo.optBoolean(k)
                    }
                }
                val t = ChatThread(
                    threadId = o.getString("threadId"),
                    listingId = o.optString("listingId"),
                    ownerUid = o.optString("ownerUid"),
                    buyerUid = o.optString("buyerUid"),
                    ownerName = o.optString("ownerName"),
                    buyerName = o.optString("buyerName"),
                    ownerProgram = o.optString("ownerProgram"),
                    buyerProgram = o.optString("buyerProgram"),
                    lastMessagePreview = o.optString("lastMessagePreview"),
                    lastMessageMillis = o.optLong("lastMessageMillis"),
                    lastSenderUid = o.optString("lastSenderUid"),
                    unreadByUid = unreadMap,
                )
                threads[t.threadId] = t
            }
        }
        root.optJSONObject("messages")?.let { obj ->
            messagesByThread.clear()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val tid = keys.next()
                val arr = obj.getJSONArray(tid)
                val list = mutableListOf<UserState.ChatMessage>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val pObj = o.optJSONObject("proposal")
                    val proposal = pObj?.let { p ->
                        ProposalPayload(
                            proposalId = p.optString("proposalId"),
                            proposalType = p.optString("proposalType", ProposalType.MEETUP_CHANGE),
                            proposalStatus = p.optString("proposalStatus", ProposalStatus.PENDING),
                            proposerUid = p.optString("proposerUid"),
                            proposerName = p.optString("proposerName"),
                            listingId = p.optString("listingId"),
                            requestId = p.optString("requestId").takeIf { it.isNotBlank() },
                            oldMeetup = p.optString("oldMeetup").takeIf { it.isNotBlank() },
                            oldMeetMillis = p.optLong("oldMeetMillis").takeIf { it > 0L },
                            newMeetup = p.optString("newMeetup").takeIf { it.isNotBlank() },
                            newMeetupNote = p.optString("newMeetupNote"),
                            newMeetMillis = p.optLong("newMeetMillis").takeIf { it > 0L },
                            extraDays = p.optInt("extraDays"),
                            extraCostPesos = p.optInt("extraCostPesos"),
                            newDueMillis = p.optLong("newDueMillis").takeIf { it > 0L },
                            note = p.optString("note"),
                            resolvedByUid = p.optString("resolvedByUid").takeIf { it.isNotBlank() },
                            resolvedAt = p.optLong("resolvedAt").takeIf { it > 0L },
                        )
                    }
                    list.add(
                        UserState.ChatMessage(
                            id = o.optString("id"),
                            senderUid = o.optString("senderUid"),
                            text = o.optString("text").takeIf { it.isNotBlank() },
                            photoUri = o.optString("photoUri").takeIf { it.isNotBlank() },
                            createdAt = o.optLong("createdAt"),
                            systemTitle = o.optString("systemTitle").takeIf { it.isNotBlank() },
                            systemDetail = o.optString("systemDetail").takeIf { it.isNotBlank() },
                            meetupPlace = o.optString("meetupPlace").takeIf { it.isNotBlank() },
                            meetupMillis = o.optLong("meetupMillis").takeIf { it > 0L },
                            proposal = proposal,
                        ),
                    )
                }
                messagesByThread[tid] = list
            }
        }
        root.optJSONArray("notifications")?.let { arr ->
            notifications.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                notifications.add(
                    AppNotification(
                        id = o.getString("id"),
                        recipientUid = o.optString("recipientUid"),
                        type = o.optString("type"),
                        title = o.optString("title"),
                        body = o.optString("body"),
                        targetType = o.optString("targetType"),
                        targetId = o.optString("targetId"),
                        extraArg = o.optString("extraArg").takeIf { it.isNotBlank() },
                        read = o.optBoolean("read"),
                        createdAt = o.optLong("createdAt"),
                    ),
                )
            }
        }
        jsonToMapOfSets(root.optJSONObject("savedByUid"), savedByUid)
        jsonToMapOfSets(root.optJSONObject("blockedByUid"), blockedByUid)
        jsonToMapOfSets(root.optJSONObject("reportedListingsByUid"), reportedListingsByUid)
        jsonToMapOfSets(root.optJSONObject("receivedByUid"), receivedByUid)
        jsonToMapOfSets(root.optJSONObject("swappedInByUid"), swappedInByUid)
    }

    // =========================================================================
    // Test Helpers (Strictly for Unit/Robolectric Tests — NEVER exposed in UI)
    // =========================================================================

    fun resetAllForTests() {
        detachAllFirestoreListeners()
        initialized = false
        activeUid = ""
        profile = UserState.UserProfile()
        settings = UserState.SettingsState()
        defaultMeetupIndex = 0
        draft = null
        users.clear()
        listings.clear()
        requests.clear()
        rentals.clear()
        exchanges.clear()
        threads.clear()
        messagesByThread.clear()
        notifications.clear()
        announcements.clear()
        issuesBySubjectAndReporter.clear()
        savedByUid.clear()
        blockedByUid.clear()
        reportedListingsByUid.clear()
        receivedByUid.clear()
        swappedInByUid.clear()
        lateCancelsByUid.clear()
        ratingsByUserAndExchange.clear()
        ratedAtByUserAndExchange.clear()
        moderationLogs.clear()
        notifiedIds.clear()
        initialNotifSnapshotDone = false
        initialAnnounceSnapshotDone = false
        appContext?.let { ctx ->
            SecureSessionStore.clearAll(ctx)
        }
        AppDatabase.resetForTests()
    }

    internal fun seedVerifiedTestSession(
        context: Context,
        email: String = "juan.delacruz@students.uc-bcf.edu.ph",
        name: String = "Juan Dela Cruz",
        program: String = "CITCS",
        year: String = "3rd Year",
    ): UserState.UserProfile {
        ensureInitialized(context)
        val uid = "test_${email.substringBefore('@').replace(Regex("[^a-z0-9]"), "_")}"
        val userProfile = UserState.UserProfile(
            uid = uid,
            name = name,
            email = email,
            program = program,
            year = year,
            emailVerified = true,
        )
        activateSession(context, userProfile)
        return userProfile
    }

    internal fun registerTestStudent(seller: Seller) {
        users[seller.uid] = seller
    }

    internal fun addTestListing(listing: Listing) {
        users.putIfAbsent(listing.ownerUid, listing.seller)
        listings.add(listing)
        bumpState(broadcast = false)
    }

    // =========================================================================
    // Conversion & Utility Helpers
    // =========================================================================

    private fun registerSellerFromEntity(entity: LocalAccountEntity) {
        users[entity.uid] = Seller(
            name = entity.name,
            initials = UserState.UserProfile(name = entity.name).initials,
            program = entity.program,
            year = entity.year,
            rating = "New",
            onTime = "\u2013",
            completed = 0,
            lastMessage = "",
            online = entity.showActiveStatus,
            uid = entity.uid,
            email = entity.email,
            bio = entity.bio,
            avatarUri = entity.avatarRef,
            showProgram = entity.showProgram,
            suspended = entity.suspended,
            suspendedReason = entity.suspendedReason,
            warningCount = entity.warningCount,
        )
    }

    private fun entityToProfile(entity: LocalAccountEntity): UserState.UserProfile =
        UserState.UserProfile(
            uid = entity.uid,
            name = entity.name,
            email = entity.email,
            program = entity.program,
            year = entity.year,
            bio = entity.bio,
            avatarUri = entity.avatarRef,
            emailVerified = entity.emailVerified,
            suspended = entity.suspended,
            suspendedReason = entity.suspendedReason,
            warningCount = entity.warningCount,
        )

    private fun draftToEntity(uid: String, d: UserState.ListingDraft): DraftEntity = DraftEntity(
        ownerUid = uid,
        title = d.title,
        targetProgramsCsv = d.targetPrograms.joinToString(","),
        condition = d.condition,
        listingType = d.listingType,
        price = d.price,
        wants = d.wants,
        maxDays = d.maxDays,
        description = d.description,
        meetup = d.meetup,
        meetupNote = d.meetupNote,
        availabilityJson = availabilityToJson(d.availability),
        photoUrisJson = JSONArray(d.photoUris).toString(),
    )

    private fun entityToDraft(e: DraftEntity): UserState.ListingDraft = UserState.ListingDraft(
        title = e.title,
        targetPrograms = e.targetProgramsCsv.split(',').filter { it in Listings.PROGRAMS },
        condition = e.condition,
        listingType = e.listingType,
        price = e.price,
        wants = e.wants,
        maxDays = e.maxDays.coerceIn(1, 120),
        description = e.description,
        meetup = e.meetup,
        meetupNote = e.meetupNote,
        availability = availabilityFromJson(e.availabilityJson),
        photoUris = runCatching { jsonArrayToStrings(JSONArray(e.photoUrisJson)) }.getOrDefault(emptyList()),
    )

    internal fun availabilityToJson(availability: Availability): String {
        val arr = JSONArray()
        availability.times.forEach { ft ->
            arr.put(
                JSONObject().apply {
                    put("days", JSONArray(ft.days.sorted()))
                    put("from", ft.from)
                    put("to", ft.to)
                },
            )
        }
        return arr.toString()
    }

    internal fun availabilityFromJson(raw: String?): Availability {
        if (raw.isNullOrBlank()) return Availability.OFFICE_HOURS
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<FreeTime>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val dArr = o.getJSONArray("days")
                val days = LinkedHashSet<Int>()
                for (j in 0 until dArr.length()) days.add(dArr.getInt(j))
                list.add(FreeTime(days, o.getInt("from"), o.getInt("to")))
            }
            if (list.isEmpty()) Availability.OFFICE_HOURS else Availability(list)
        } catch (_: Exception) {
            Availability.OFFICE_HOURS
        }
    }

    private fun jsonArrayToStrings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return List(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }
    }

    private fun mapOfSetsToJson(map: Map<String, Set<String>>): JSONObject = JSONObject().apply {
        map.forEach { (k, v) -> put(k, JSONArray(v.toList())) }
    }

    private fun jsonToMapOfSets(obj: JSONObject?, target: MutableMap<String, MutableSet<String>>) {
        target.clear()
        if (obj == null) return
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            target[k] = jsonArrayToStrings(obj.optJSONArray(k)).toMutableSet()
        }
    }

    private fun friendlyAuthError(e: FirebaseAuthException): String = when (e.errorCode) {
        "ERROR_EMAIL_ALREADY_IN_USE" -> "An account with this UC email already exists. Try signing in."
        "ERROR_WEAK_PASSWORD" -> "Choose a stronger password (at least 8 characters with letters and numbers)."
        "ERROR_TOO_MANY_REQUESTS" -> "Too many attempts. Please wait a minute and try again."
        "ERROR_NETWORK_REQUEST_FAILED" -> "Network error. Check your internet connection."
        else -> "Invalid email or password."
    }
}
