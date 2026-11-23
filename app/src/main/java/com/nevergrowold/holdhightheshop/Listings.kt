package com.nevergrowold.holdhightheshop

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import com.nevergrowold.holdhightheshop.data.AiCheckStatus
import com.nevergrowold.holdhightheshop.data.AppNotification
import com.nevergrowold.holdhightheshop.data.AppRepository
import com.nevergrowold.holdhightheshop.data.CampusAnnouncement
import com.nevergrowold.holdhightheshop.data.ChatThread
import com.nevergrowold.holdhightheshop.data.ListingStatus
import com.nevergrowold.holdhightheshop.data.NotificationTarget
import com.nevergrowold.holdhightheshop.data.NotificationType
import com.nevergrowold.holdhightheshop.data.ProposalPayload
import com.nevergrowold.holdhightheshop.data.ProposalStatus
import com.nevergrowold.holdhightheshop.data.ProposalType
import com.nevergrowold.holdhightheshop.security.UcEmailValidator
import java.text.NumberFormat
import java.util.Locale

/**
 * Core campus marketplace domain models, formatting helpers, and reactive
 * facade over [AppRepository] (backed by Firebase Auth + Cloud Firestore when
 * `google-services.json` is configured, and Room SQLite for offline/local persistence).
 */
data class Seller(
    val name: String,
    val initials: String,
    val program: String,
    val year: String,
    val rating: String,
    val onTime: String,
    val completed: Int,
    val lastMessage: String,
    val online: Boolean,
    val uid: String = name.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_"),
    val email: String = "",
    val bio: String = "",
    val avatarUri: String? = null,
    val showProgram: Boolean = true,
    val suspended: Boolean = false,
    val suspendedReason: String = "",
    val warningCount: Int = 0,
) {
    /** "Paolo", for buttons and short messages. */
    val first: String get() = name.trim().substringBefore(' ').ifEmpty { "UCian" }
}

data class Listing(
    val id: String,
    val title: String,
    /** Pesos (or pesos per day for Lend; 0 = FREE giveaway for Sell/Lend). */
    val price: Int,
    /** Primary college display code (or "All colleges" when [targetPrograms] is empty). */
    val program: String,
    /** 0 = Like new, 1 = Good, 2 = Fair. */
    val condition: Int,
    /** 0 = Sell, 1 = Lend, 2 = Swap. */
    val type: Int,
    val description: String,
    val seller: Seller,
    val meetup: String,
    /** Lower is newer; derived from [createdAt] when live. */
    val postedDaysAgo: Int = 0,
    /** When the seller is free to meet. Reserving only offers these times. */
    val availability: Availability = Availability.OFFICE_HOURS,
    /** Swap listings: what the seller would like in exchange. */
    val wants: String? = null,
    /** Lend listings: longest allowed loan in days (1..120). */
    val maxDays: Int = 14,
    /** 1..10 photo refs (`media://<id>` or local `content://` URI). Slot 0 is Cover. */
    val photoUris: List<String> = emptyList(),
    /** Multi-select UC college tags. Empty list means applicable to ALL colleges. */
    val targetPrograms: List<String> = if (program in Listings.PROGRAMS) listOf(program) else emptyList(),
    /** Optional specific indoor spot / floor note (e.g. "3rd Floor lobby near M304"). */
    val meetupNote: String = "",
    /** Owner Firebase UID (or local account UID). */
    val ownerUid: String = seller.uid,
    /** Lifecycle status: OPEN, PAUSED, RESERVED, ON_LOAN, CLOSED, UNDER_REVIEW, REMOVED_BY_ADMIN. */
    val status: String = ListingStatus.OPEN,
    /** Moderation state: APPROVED or PENDING_REVIEW (when Gemini was offline/unconfigured). */
    val aiCheckStatus: String = AiCheckStatus.APPROVED,
    val reportCount: Int = 0,
    val reportedByUids: Set<String> = emptySet(),
    val createdAt: Long = System.currentTimeMillis() - postedDaysAgo * Rentals.DAY_MS,
) {
    val isRent: Boolean get() = type == 1
    val isSwap: Boolean get() = type == 2
    val isFree: Boolean get() = !isSwap && price == 0
    val isForAllColleges: Boolean get() = targetPrograms.isEmpty()

    /** Matches Market filter: null = All, FILTER_ALL_COLLEGES = strictly universal, college = tagged OR universal. */
    fun matchesProgramFilter(selectedFilter: String?): Boolean = when {
        selectedFilter.isNullOrBlank() -> true
        selectedFilter == Listings.FILTER_ALL_COLLEGES -> targetPrograms.isEmpty()
        else -> selectedFilter in targetPrograms || targetPrograms.isEmpty()
    }

    /** True when tagged specifically for [selectedFilter] (used to rank college-specific items above universal items). */
    fun isExplicitlyTaggedFor(selectedFilter: String?): Boolean =
        !selectedFilter.isNullOrBlank() && selectedFilter in targetPrograms
}

/**
 * One block of free time, e.g. Mon, Wed, Fri from 1:00 to 4:00 PM.
 *
 * @param days 0 = Monday ... 6 = Sunday.
 * @param from start, in minutes after midnight.
 * @param to end, in minutes after midnight.
 */
data class FreeTime(val days: Set<Int>, val from: Int, val to: Int) {

    /** "Mon, Wed, Fri • 1:00–4:00 PM" */
    fun label(): String =
        "${Availability.daysLabel(days)} \u2022 " +
            "${Availability.time(from, withMeridiem = (from < 720) != (to < 720))}\u2013${Availability.time(to)}"
}

/**
 * When a seller can meet, set on the Create listing form as one or more
 * blocks of free time. Reserving only offers days and times inside them.
 */
data class Availability(val times: List<FreeTime>) {

    constructor(vararg times: FreeTime) : this(times.toList())

    val days: Set<Int> get() = times.flatMap { it.days }.toSet()

    fun isFreeOn(weekday: Int) = times.any { weekday in it.days }

    fun isFreeAt(weekday: Int, minutes: Int) = times.any { weekday in it.days && minutes in it.from..it.to }

    fun slots(weekday: Int): List<Int> = times.filter { weekday in it.days }
        .flatMap { block -> (block.from..block.to - 30 step 60).toList().ifEmpty { listOf(block.from) } }
        .distinct()
        .sorted()

    fun label(): String = times.joinToString("; ") { it.label() }

    fun labelFor(weekday: Int): String = times.filter { weekday in it.days }.joinToString(", ") {
        "${time(it.from, withMeridiem = (it.from < 720) != (it.to < 720))}\u2013${time(it.to)}"
    }

    companion object {
        val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        const val DEFAULT_START = 9 * 60
        val OFFICE_HOURS = Availability(FreeTime(setOf(0, 1, 2, 3, 4), 9 * 60, 16 * 60))

        fun daysLabel(days: Set<Int>): String = when (days) {
            (0..6).toSet() -> "Every day"
            (0..4).toSet() -> "Mon\u2013Fri"
            setOf(5, 6) -> "Sat\u2013Sun"
            else -> days.sorted().joinToString(", ") { DAY_NAMES[it] }
        }

        fun time(minutes: Int, withMeridiem: Boolean = true): String {
            val h = minutes / 60
            val hour12 = if (h % 12 == 0) 12 else h % 12
            val base = "%d:%02d".format(Locale.US, hour12, minutes % 60)
            return if (withMeridiem) "$base ${if (h < 12) "AM" else "PM"}" else base
        }

        fun weekdayOf(calendarDay: Int) = (calendarDay + 5) % 7
    }
}

/** A college, shown as a Home tile, a Market chip and a Create listing tag option. */
data class Program(val code: String, val icon: Int)

data class DealEvent(val text: Int, val millis: Long, val arg: String? = null)

data class Exchange(
    val id: String,
    val title: String,
    val price: Int,
    val kind: Int,
    val partner: Seller?,
    val finishedMillis: Long,
    val meetup: String,
    val theirRating: Int,
    val yourRating: Int,
    val onTime: Boolean = true,
    val proof: Int = PROOF_CODE,
    val listingId: String? = null,
    val events: List<DealEvent> = emptyList(),
    val giverUid: String = "",
    val receiverUid: String = "",
    val giverRating: Int = 0,
    val giverNote: String = "",
    val receiverRating: Int = 0,
    val receiverNote: String = "",
) {
    val giving: Boolean get() = kind == SOLD || kind == LENT || kind == SWAPPED
    val isLoan: Boolean get() = kind == LENT || kind == BORROWED

    companion object {
        const val SOLD = 0
        const val BOUGHT = 1
        const val LENT = 2
        const val BORROWED = 3
        const val SWAPPED = 4

        const val PROOF_CODE = 0
        const val PROOF_MANUAL = 1
        const val PROOF_RECEIVED = 2
    }
}

/**
 * A deal request (Buy / Lend / Swap) on a listing.
 * Snapshots [agreedPrice] at request time to prevent price bait-and-switch,
 * and carries a cryptographically random [handoverCode] once accepted.
 */
data class Request(
    val id: String,
    val listingId: String,
    val from: Seller,
    val sentMillis: Long,
    val meetup: String,
    val meetMillis: Long,
    val message: String,
    val days: Int = 0,
    val offerId: String? = null,
    val ownerUid: String = "",
    val requesterUid: String = from.uid,
    val agreedPrice: Int = 0,
    val meetupNote: String = "",
    val handoverCode: String = "",
    val status: Int = Deals.PENDING,
    val acceptedAtMillis: Long? = null,
) {
    val expiresMillis: Long get() = sentMillis + Deals.ANSWER_WITHIN_MS
}

data class Issue(
    val ref: String,
    val subject: String,
    val reason: Int,
    val partner: Seller?,
    val millis: Long,
    val details: String = "",
    val reporterUid: String = "",
    val status: String = "OPEN",
)

object Listings {

    const val FILTER_ALL_COLLEGES = "__ALL_COLLEGES__"
    const val MAX_PRICE = 50_000
    const val MIN_PHOTOS = 1
    const val MAX_PHOTOS = 10

    /** UC colleges, in the order they appear across the app. */
    val COLLEGES = listOf(
        Program("CITCS", R.drawable.ic_prog_citcs),
        Program("COA", R.drawable.ic_prog_coa),
        Program("COE", R.drawable.ic_prog_coe),
        Program("CAFA", R.drawable.ic_prog_cafa),
        Program("CTE", R.drawable.ic_prog_cte),
        Program("CONAHS", R.drawable.ic_prog_conahs),
        Program("CBA", R.drawable.ic_prog_cba),
        Program("CHTM", R.drawable.ic_prog_chtm),
        Program("CAS", R.drawable.ic_prog_cas),
        Program("CCJE", R.drawable.ic_prog_ccje),
        Program("COL", R.drawable.ic_prog_col),
    )

    val PROGRAMS = COLLEGES.map { it.code }

    val MEETUP_POINTS = listOf(
        "UC Main Gate Security Desk",
        "UC Library Lobby",
        "CITCS Building Entrance",
        "UC Canteen Area",
    )

    /** Practical indoor/campus landmark descriptions replacing the old placeholder map. */
    val MEETUP_LANDMARKS = mapOf(
        "UC Main Gate Security Desk" to "Ground floor, Governor Pack Rd. entrance beside the security post — staffed & CCTV-covered.",
        "UC Library Lobby" to "Main library entrance lobby — well-lit, quiet, and covered from rain.",
        "CITCS Building Entrance" to "Main lobby entrance of the CITCS wing — high foot traffic between classes.",
        "UC Canteen Area" to "Campus dining hall entrance — bright, busy, and easy to spot between breaks.",
    )

    /** Day-unit loan durations from 1 day up to a full semester (120 days). */
    val RENT_DAYS = listOf(1, 2, 3, 5, 7, 14, 30, 60, 120)

    /** Quick loan return extension presets (in extra days). */
    val EXTENSION_DAYS = listOf(1, 2, 3, 7)

    const val DEFAULT_ID = "item2"

    val ME: Seller get() = UserState.me()

    val SELLERS: List<Seller> get() = AppRepository.allUsers()

    val ALL: List<Listing> get() = AppRepository.allListings()

    val REQUESTS: List<Request> get() = AppRepository.allRequests()

    fun isMine(listing: Listing): Boolean {
        val myUid = UserState.currentUid
        return myUid.isNotEmpty() && listing.ownerUid == myUid
    }

    fun sellerOf(listing: Listing): Seller =
        if (isMine(listing)) ME else AppRepository.userByUid(listing.ownerUid) ?: listing.seller

    fun nextListingId(): String = AppRepository.nextListingId()

    fun referenceCode(listingId: String): String =
        "HTS-%05d".format(Locale.US, 24000 + (listingId.hashCode() and 0x7fffffff) % 1000)

    fun addListing(listing: Listing): Listing = AppRepository.createListingLocalOrSync(listing)

    fun updateListing(updated: Listing): Boolean = AppRepository.updateListingLocalOrSync(updated)

    fun deleteListing(listingId: String): Boolean = AppRepository.deleteListingLocalOrSync(listingId)

    fun reset() = AppRepository.resetAllForTests()

    fun roleKicker(listing: Listing): Int = when {
        listing.type == 2 -> R.string.kicker_swap
        isMine(listing) && listing.type == 1 -> R.string.kicker_you_lending
        isMine(listing) -> R.string.kicker_you_selling
        listing.type == 1 -> R.string.kicker_you_borrowing
        else -> R.string.kicker_you_buying
    }

    fun role(context: Context, listing: Listing): String = context.getString(
        when {
            listing.type == 2 -> R.string.role_swapping
            isMine(listing) && listing.type == 1 -> R.string.role_lending
            isMine(listing) -> R.string.role_selling
            listing.type == 1 -> R.string.role_borrowing
            else -> R.string.role_buying
        },
    )

    fun myActive(): List<Listing> = ALL.filter {
        isMine(it) &&
            it.status in setOf(ListingStatus.OPEN, ListingStatus.PAUSED, ListingStatus.RESERVED, ListingStatus.UNDER_REVIEW) &&
            UserState.rental(it.id) == null
    }

    /** Own listings eligible to offer in a swap: OPEN, not paused/under review, not promised to anyone. */
    fun offerable(): List<Listing> = myActive().filter {
        it.status == ListingStatus.OPEN && UserState.accepted(it.id) == null
    }

    fun completed(): List<Exchange> = AppRepository.completedForCurrentUser()

    fun exchange(id: String?): Exchange? = completed().firstOrNull { it.id == id } ?: completed().firstOrNull()

    fun byIdOrNull(id: String?): Listing? = if (id.isNullOrBlank()) null else ALL.firstOrNull { it.id == id }

    fun byId(id: String?): Listing = byIdOrNull(id) ?: ALL.firstOrNull() ?: fallbackPlaceholderListing()

    fun sellerByUid(uid: String?): Seller? = if (uid.isNullOrBlank()) null else AppRepository.userByUid(uid)

    fun sellerByName(name: String?): Seller {
        if (name.isNullOrBlank()) return SELLERS.firstOrNull() ?: ME
        val needle = name.trim()
        return SELLERS.firstOrNull { it.uid.equals(needle, ignoreCase = true) }
            ?: SELLERS.firstOrNull { it.name.equals(needle, ignoreCase = true) }
            ?: SELLERS.firstOrNull { it.first.equals(needle, ignoreCase = true) }
            ?: SELLERS.firstOrNull()
            ?: ME
    }

    fun bySeller(seller: Seller): List<Listing> = ALL.filter {
        (it.ownerUid == seller.uid || sellerOf(it).name == seller.name) &&
            it.status == ListingStatus.OPEN &&
            !UserState.isHiddenForMe(it)
    }

    /**
     * What Campus Market displays:
     * - Other students' OPEN listings only (excludes own, paused, closed, under_review, removed_by_admin)
     * - Excludes listings from blocked users or already reported by the current student
     * - Excludes items the user currently reserved/borrowed/bought/swapped
     */
    fun open(): List<Listing> = ALL.filter { listing ->
        !isMine(listing) &&
            listing.status == ListingStatus.OPEN &&
            !UserState.isHiddenForMe(listing) &&
            !UserState.isTaken(listing.id)
    }

    /**
     * Compact label for product cards and listing details:
     * - empty tags -> "All colleges"
     * - 1 tag -> "CITCS"
     * - 2 tags -> "CITCS · COE"
     * - 3+ tags -> "CITCS · COE +N"
     */
    fun targetProgramsLabel(context: Context, listing: Listing): String {
        val tags = listing.targetPrograms.filter { it in PROGRAMS }
        return when {
            tags.isEmpty() -> context.getString(R.string.tag_all_colleges)
            tags.size == 1 -> tags[0]
            tags.size == 2 -> "${tags[0]} \u00B7 ${tags[1]}"
            else -> "${tags[0]} \u00B7 ${tags[1]} +${tags.size - 2}"
        }
    }

    /**
     * Price label:
     * - Swap -> "Swap" (smaller amber)
     * - Free (price == 0) -> "FREE" (Sell) or "FREE / day" (Lend)
     * - Lend -> "₱50 / day" (smaller grey "/ day")
     * - Sell -> "₱850"
     */
    fun priceLabel(context: Context, listing: Listing): CharSequence {
        fun sp(value: Float) = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics,
        ).toInt()
        return when {
            listing.isSwap -> SpannableString(context.getString(R.string.price_swap)).apply {
                setSpan(AbsoluteSizeSpan(sp(15f)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(context.getColor(R.color.swap_text)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            listing.isRent && listing.price == 0 -> {
                val free = context.getString(R.string.price_free)
                SpannableString(context.getString(R.string.price_rent_day_format, free)).apply {
                    setSpan(AbsoluteSizeSpan(sp(12f)), free.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(ForegroundColorSpan(context.getColor(R.color.text_secondary)), free.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            listing.price == 0 -> context.getString(R.string.price_free)
            listing.isRent -> {
                val amount = price(listing.price)
                SpannableString(context.getString(R.string.price_rent_day_format, amount)).apply {
                    setSpan(AbsoluteSizeSpan(sp(12f)), amount.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(
                        ForegroundColorSpan(context.getColor(R.color.text_secondary)),
                        amount.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            else -> price(listing.price)
        }
    }

    /** Natural day/week/month/semester label for day-unit lending (1..120 days). */
    fun daysLabel(context: Context, days: Int): String = when (days) {
        7 -> context.getString(R.string.rent_1_week)
        14 -> context.getString(R.string.rent_2_weeks)
        30 -> context.getString(R.string.rent_1_month)
        60 -> context.getString(R.string.rent_2_months)
        120 -> context.getString(R.string.rent_semester)
        else -> context.resources.getQuantityString(R.plurals.rent_days, days.coerceAtLeast(1), days.coerceAtLeast(1))
    }

    fun price(value: Int): String =
        if (value <= 0) "FREE" else "₱" + NumberFormat.getIntegerInstance(Locale.US).format(value)

    fun conditionLabel(context: Context, condition: Int): String = context.getString(
        when (condition) {
            0 -> R.string.cond_like_new
            1 -> R.string.cond_good
            else -> R.string.cond_fair
        },
    )

    private fun fallbackPlaceholderListing(): Listing = Listing(
        id = DEFAULT_ID,
        title = "Campus Item",
        price = 0,
        program = "All colleges",
        condition = 1,
        type = 0,
        description = "",
        seller = ME,
        meetup = MEETUP_POINTS.first(),
        targetPrograms = emptyList(),
        status = ListingStatus.CLOSED,
    )
}

/**
 * Current student's active session state, settings, deals, loans, chats, and
 * notifications—delegating directly to [AppRepository] for full Room SQLite
 * and Firebase Auth/Firestore persistence.
 */
object UserState {

    data class UserProfile(
        val uid: String = "",
        val name: String = "UC Student",
        val email: String = "",
        val program: String = "CITCS",
        val year: String = "1st Year",
        val bio: String = "",
        val avatarUri: String? = null,
        val emailVerified: Boolean = false,
        val suspended: Boolean = false,
        val suspendedReason: String = "",
        val warningCount: Int = 0,
    ) {
        val first: String get() = name.trim().substringBefore(' ').ifEmpty { "UCian" }
        val initials: String
            get() {
                val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                return when {
                    parts.isEmpty() -> "UC"
                    parts.size == 1 -> parts[0].take(2).uppercase(Locale.US)
                    else -> (parts.first().take(1) + parts.last().take(1)).uppercase(Locale.US)
                }
            }
    }

    data class SettingsState(
        val notifyMessages: Boolean = true,
        val notifyReservations: Boolean = true,
        val notifyReminders: Boolean = true,
        val notifyPriceDrops: Boolean = false,
        val showProgram: Boolean = true,
        val showActiveStatus: Boolean = true,
    )

    data class ListingDraft(
        val title: String,
        val targetPrograms: List<String>,
        val condition: Int,
        val listingType: Int,
        val price: String,
        val wants: String,
        val maxDays: Int,
        val description: String,
        val meetup: Int,
        val meetupNote: String,
        val availability: Availability,
        val photoUris: List<String>,
    )

    data class ChatMessage(
        val id: String = "msg_${System.currentTimeMillis()}_${UcEmailValidator.generateHandoverCode()}",
        val senderUid: String = "",
        val text: String? = null,
        val photoUri: String? = null,
        val outgoing: Boolean = true,
        val timeLabel: String = "Just now",
        val createdAt: Long = System.currentTimeMillis(),
        val systemTitle: String? = null,
        val systemDetail: String? = null,
        val meetupPlace: String? = null,
        val meetupMillis: Long? = null,
        val proposal: ProposalPayload? = null,
    )

    data class Reservation(
        val requestId: String,
        val summary: String,
        val days: Int = 0,
        val meetMillis: Long? = null,
        val meetup: String = "",
        val meetupNote: String = "",
        val offerId: String? = null,
        val madeMillis: Long = System.currentTimeMillis(),
        val status: Int = Deals.PENDING,
        val handoverCode: String = "",
        val agreedPrice: Int = 0,
    ) {
        val isAccepted: Boolean get() = status == Deals.ACCEPTED
        val isPending: Boolean get() = status == Deals.PENDING
    }

    data class Rental(
        val id: String = "",
        val partner: Seller,
        val dueMillis: Long,
        val lending: Boolean,
        val returnPending: Boolean = false,
        val startMillis: Long = System.currentTimeMillis(),
        val reportedMillis: Long? = null,
        val events: List<DealEvent> = emptyList(),
        val proof: Int = Exchange.PROOF_CODE,
        val days: Int = 1,
        val lenderUid: String = "",
        val borrowerUid: String = "",
    )

    val currentUid: String get() = AppRepository.currentUid()
    val isSignedIn: Boolean get() = AppRepository.isSignedIn()
    val isVerifiedSignedIn: Boolean get() = AppRepository.isVerifiedSignedIn()
    val isSuspended: Boolean get() = profile.suspended
    val profile: UserProfile get() = AppRepository.currentProfile()
    val settings: SettingsState get() = AppRepository.currentSettings()
    var defaultMeetup: Int
        get() = AppRepository.currentDefaultMeetup()
        set(value) = AppRepository.setDefaultMeetupInMemory(value)

    var draft: ListingDraft?
        get() = AppRepository.currentDraft()
        set(value) = AppRepository.setDraftLocal(value)

    fun me(): Seller {
        val p = profile
        return Seller(
            name = p.name,
            initials = p.initials,
            program = p.program,
            year = p.year,
            rating = Reliability.rating(),
            onTime = Reliability.onTime(),
            completed = Reliability.completed(),
            lastMessage = "",
            online = settings.showActiveStatus,
            uid = p.uid,
            email = p.email,
            bio = p.bio,
            avatarUri = p.avatarUri,
            showProgram = settings.showProgram,
            suspended = p.suspended,
            suspendedReason = p.suspendedReason,
            warningCount = p.warningCount,
        )
    }

    fun ensureLoaded(context: Context) = AppRepository.ensureInitialized(context)

    fun updateProfile(
        context: Context,
        name: String,
        program: String,
        year: String,
        bio: String,
        avatarUri: String? = profile.avatarUri,
    ) = AppRepository.updateProfile(context, name, program, year, bio, avatarUri)

    fun updateSettings(context: Context, next: SettingsState) = AppRepository.updateSettings(context, next)

    fun updateDefaultMeetup(context: Context, index: Int) = AppRepository.updateDefaultMeetup(context, index)

    // ---- Notifications & Announcements ----

    fun notifications(): List<AppNotification> = AppRepository.notificationsForCurrentUser()

    fun announcements(): List<CampusAnnouncement> = AppRepository.announcements()

    val hasUnreadNotifications: Boolean get() = notifications().any { !it.read }

    fun markNotificationRead(id: String) = AppRepository.markNotificationRead(id)

    fun markAllNotificationsRead() = AppRepository.markAllNotificationsRead()

    // ---- Saved & Blocked ----

    fun saved(): List<Listing> = AppRepository.savedListings()

    fun isSaved(id: String): Boolean = AppRepository.isSaved(id)

    fun setSaved(id: String, on: Boolean) = AppRepository.setSaved(id, on)

    fun blockedUids(): Set<String> = AppRepository.blockedUids()

    fun isBlocked(uid: String): Boolean = uid in blockedUids()

    fun blockUser(uid: String) = AppRepository.blockUser(uid)

    fun unblockUser(uid: String) = AppRepository.unblockUser(uid)

    fun isHiddenForMe(listing: Listing): Boolean =
        listing.ownerUid in blockedUids() ||
            listing.id in AppRepository.reportedListingIdsByMe() ||
            listing.status == ListingStatus.UNDER_REVIEW ||
            listing.status == ListingStatus.REMOVED_BY_ADMIN

    // ---- Per-Listing Chats & Proposals ----

    fun chatThreads(): List<ChatThread> = AppRepository.chatThreadsForCurrentUser()

    fun threadFor(listingId: String, partnerUid: String): ChatThread =
        AppRepository.getOrCreateThread(listingId, partnerUid)

    fun chatMessages(threadId: String): List<ChatMessage> = AppRepository.chatMessages(threadId)

    fun appendChatMessage(threadId: String, message: ChatMessage) = AppRepository.sendChatMessage(threadId, message)

    fun markChatRead(threadId: String) = AppRepository.markThreadRead(threadId)

    fun proposeMeetupChange(
        threadId: String,
        listing: Listing,
        request: Request,
        newMeetup: String,
        newMeetupNote: String,
        newMeetMillis: Long,
        note: String,
    ): ProposalPayload = AppRepository.proposeMeetupChange(
        threadId, listing, request, newMeetup, newMeetupNote, newMeetMillis, note,
    )

    fun proposeReturnExtension(
        threadId: String,
        listing: Listing,
        rental: Rental,
        extraDays: Int,
        note: String,
    ): ProposalPayload = AppRepository.proposeReturnExtension(
        threadId, listing, rental, extraDays, note,
    )

    fun respondToProposal(threadId: String, proposalId: String, nextStatus: String): Boolean =
        AppRepository.respondToProposal(threadId, proposalId, nextStatus)

    // ---- Reservations & Requests ----

    fun isReserved(listingId: String): Boolean = reservation(listingId) != null

    fun reservation(listingId: String): Reservation? = AppRepository.myReservationFor(listingId)

    fun reservedListings(): List<Listing> = AppRepository.myReservedListings()

    fun submitRequest(
        listing: Listing,
        meetup: String,
        meetupNote: String,
        meetMillis: Long,
        days: Int = 0,
        offerId: String? = null,
        message: String = "",
    ): Request = AppRepository.submitRequest(listing, meetup, meetupNote, meetMillis, days, offerId, message)

    fun cancelReservation(listingId: String): Boolean = AppRepository.cancelMyReservation(listingId)

    fun reportNoShow(listing: Listing, theyMissed: Boolean) = AppRepository.reportBuyerSideNoShow(listing, theyMissed)

    fun rental(listingId: String): Rental? = AppRepository.activeRentalFor(listingId)

    fun borrowing(): List<Pair<Listing, Rental>> = AppRepository.myBorrowing()

    fun lending(): List<Pair<Listing, Rental>> = AppRepository.myLending()

    fun lend(listing: Listing, partner: Seller, days: Int) = AppRepository.lendDirectly(listing, partner, days)

    fun reportReturned(listing: Listing) = AppRepository.reportReturned(listing)

    fun rejectReturn(listing: Listing) = AppRepository.rejectReturn(listing)

    fun markReturned(listing: Listing, keepOpen: Boolean = true) = AppRepository.confirmReturned(listing, keepOpen)

    fun requests(listingId: String): List<Request> = AppRepository.requestsForListing(listingId)

    fun status(request: Request): Int = AppRepository.effectiveRequestStatus(request)

    fun pending(listingId: String): List<Request> = requests(listingId).filter { status(it) == Deals.PENDING }

    fun accepted(listingId: String): Request? = AppRepository.acceptedRequestForListing(listingId)

    fun acceptedAt(request: Request): Long = request.acceptedAtMillis ?: request.sentMillis

    fun requestFrom(partnerUid: String, listingId: String): Request? =
        AppRepository.latestRequestBetween(partnerUid, listingId)

    fun offersUsing(listingId: String): List<Listing> = AppRepository.myOffersUsing(listingId)

    fun accept(request: Request): List<Listing> = AppRepository.acceptRequest(request)

    fun decline(request: Request) = AppRepository.declineRequest(request)

    fun cancelAccepted(listingId: String): Boolean = AppRepository.cancelAcceptedDeal(listingId)

    fun reportOwnerNoShow(listing: Listing, theyMissed: Boolean) = AppRepository.reportOwnerSideNoShow(listing, theyMissed)

    fun handOver(listing: Listing, proof: Int) = AppRepository.completeHandover(listing, proof)

    fun close(id: String) = AppRepository.closeListingByOwner(id)

    fun setPaused(id: String, paused: Boolean) = AppRepository.setListingPaused(id, paused)

    fun isTaken(id: String): Boolean = AppRepository.isTakenForCurrentUser(id)

    fun hasReceived(id: String): Boolean = AppRepository.hasReceivedListing(id)

    fun gotInSwap(id: String): Boolean = AppRepository.gotInSwap(id)

    fun incomingOffer(id: String): Request? = AppRepository.incomingSwapOffer(id)

    fun isClosed(id: String): Boolean {
        val status = Listings.byIdOrNull(id)?.status ?: return false
        return status == ListingStatus.CLOSED || status == ListingStatus.REMOVED_BY_ADMIN
    }

    fun isPaused(id: String): Boolean = Listings.byIdOrNull(id)?.status == ListingStatus.PAUSED

    // ---- Ratings & Reports ----

    fun rate(exchangeId: String, stars: Int, note: String) = AppRepository.rateExchange(exchangeId, stars, note)

    fun ratedAt(exchangeId: String): Long = AppRepository.ratedAt(exchangeId)

    fun rating(exchangeId: String): Pair<Int, String>? = AppRepository.myRatingFor(exchangeId)

    fun lateCancels(): Int = AppRepository.myLateCancels()

    fun report(subject: String, reason: Int, partner: Seller?, details: String = "", isListingReport: Boolean = false): Issue =
        AppRepository.submitReport(subject, reason, partner, details, isListingReport)

    fun issue(subject: String): Issue? = AppRepository.myIssueFor(subject)

    fun reset() = AppRepository.resetAllForTests()
}

object Deals {
    const val PENDING = 0
    const val WAITLIST = 1
    const val ACCEPTED = 2
    const val DECLINED = 3
    const val EXPIRED = 4
    const val COMPLETED = 5

    private const val HOUR = 60 * 60 * 1000L
    const val ANSWER_WITHIN_MS = 24 * HOUR
    const val LATE_CANCEL_MS = 2 * HOUR
    const val NO_SHOW_AFTER_MS = 2 * HOUR
    const val RATING_WINDOW_MS = 7 * 24 * HOUR

    fun isLate(meetMillis: Long?) = meetMillis != null && meetMillis - System.currentTimeMillis() < LATE_CANCEL_MS

    fun meetupMissed(meetMillis: Long?) = meetMillis != null && System.currentTimeMillis() > meetMillis + NO_SHOW_AFTER_MS

    fun timeline(exchange: Exchange): List<DealEvent> {
        if (exchange.events.isNotEmpty()) return exchange.events
        val first = exchange.partner?.first ?: return listOf(DealEvent(R.string.ev_closed_by_you, exchange.finishedMillis))
        val end = exchange.finishedMillis
        val start = end - (if (exchange.isLoan) 4 else 1) * Rentals.DAY_MS
        val asked = when {
            exchange.giving -> DealEvent(if (exchange.kind == Exchange.SWAPPED) R.string.ev_they_offered else R.string.ev_they_requested, start, first)
            else -> DealEvent(if (exchange.kind == Exchange.SWAPPED) R.string.ev_you_offered else R.string.ev_you_reserved, start)
        }
        val handover = DealEvent(
            if (exchange.proof == Exchange.PROOF_MANUAL) R.string.ev_handover_manual else R.string.ev_handover_code,
            if (exchange.isLoan) start + Rentals.DAY_MS else end,
        )
        return listOfNotNull(
            asked,
            DealEvent(if (exchange.giving) R.string.ev_you_accepted else R.string.ev_they_accepted, start + 2 * HOUR, first),
            handover,
            if (exchange.isLoan) DealEvent(R.string.ev_return_confirmed, end) else null,
        )
    }
}

/**
 * Dynamic reliability stats derived strictly from real completed exchanges.
 * Brand-new student accounts show "New" (or "–"), "–", and 0 completed until
 * they finish real transactions!
 */
object Reliability {

    private fun deals() = Listings.completed().filter { it.partner != null }

    fun yourStars(exchange: Exchange): Int = UserState.rating(exchange.id)?.first ?: exchange.yourRating

    fun ratingClosesAt(exchange: Exchange) = exchange.finishedMillis + Deals.RATING_WINDOW_MS

    fun ratingOpen(exchange: Exchange) = System.currentTimeMillis() < ratingClosesAt(exchange)

    fun canRate(exchange: Exchange) = exchange.partner != null && yourStars(exchange) == 0 && ratingOpen(exchange)

    fun theirVisible(exchange: Exchange) = yourStars(exchange) > 0 || !ratingOpen(exchange)

    fun rating(): String {
        val stars = deals().filter { it.theirRating > 0 && theirVisible(it) }.map { it.theirRating }
        return if (stars.isEmpty()) "New" else "%.1f".format(Locale.US, stars.average())
    }

    fun onTime(): String {
        val all = deals()
        val total = all.size + UserState.lateCancels()
        if (total == 0) return "\u2013"
        return "${Math.round(all.count { it.onTime } * 100.0 / total)}%"
    }

    fun completed(): Int = deals().size
}

/** Dates and countdowns for day-unit rentals and campus meetups. */
object Rentals {

    const val DAY_MS = 24L * 60 * 60 * 1000
    const val WEEK_MS = 7 * DAY_MS

    fun at(days: Int, minutes: Int): Long = java.util.Calendar.getInstance().apply {
        add(java.util.Calendar.DAY_OF_MONTH, days)
        set(java.util.Calendar.HOUR_OF_DAY, minutes / 60)
        set(java.util.Calendar.MINUTE, minutes % 60)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun whenLabel(context: Context, millis: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        val time = Availability.time(cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE))
        val day = when (daysUntil(millis)) {
            0 -> context.getString(R.string.day_today_label)
            1 -> context.getString(R.string.day_tomorrow_label)
            -1 -> context.getString(R.string.day_yesterday_label)
            else -> dateLabel(millis)
        }
        return "$day, $time"
    }

    fun shortDate(context: Context, millis: Long): String =
        if (daysUntil(millis) == 0) context.getString(R.string.day_today_label)
        else java.text.SimpleDateFormat("MMM d", Locale.US).format(millis)

    fun timeLeft(context: Context, millis: Long): String {
        val minutes = ((millis - System.currentTimeMillis()) / 60000).coerceAtLeast(1)
        return if (minutes >= 60) context.getString(R.string.hours_short, (minutes / 60).toInt())
        else context.getString(R.string.minutes_short, minutes.toInt())
    }

    fun dateLabel(millis: Long): String =
        java.text.SimpleDateFormat("EEE, MMM d", Locale.US).format(millis)

    fun daysUntil(millis: Long): Int {
        fun midnight(t: Long) = java.util.Calendar.getInstance().apply {
            timeInMillis = t
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        return Math.round((midnight(millis) - midnight(System.currentTimeMillis())).toDouble() / DAY_MS).toInt()
    }

    fun countdown(context: Context, millis: Long): String {
        val days = daysUntil(millis)
        return when {
            days > 1 -> context.resources.getQuantityString(R.plurals.days_left, days, days)
            days == 1 -> context.getString(R.string.due_tomorrow)
            days == 0 -> context.getString(R.string.due_today)
            else -> context.resources.getQuantityString(R.plurals.overdue_by, -days, -days)
        }
    }

    fun isOverdue(millis: Long) = daysUntil(millis) < 0

    fun isDueSoon(millis: Long) = daysUntil(millis) <= 3
}
