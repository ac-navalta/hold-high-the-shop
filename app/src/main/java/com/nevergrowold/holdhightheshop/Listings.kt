package com.nevergrowold.holdhightheshop

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import java.text.NumberFormat
import java.util.Locale

/**
 * Stand-in data for the prototype. There is no backend yet, so every screen
 * reads its listings, sellers and history from here. When the real API
 * arrives, only this file should need to change.
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
    val online: Boolean
) {
    /** "Paolo", for buttons and short messages. */
    val first get() = name.substringBefore(' ')
}

data class Listing(
    val id: String,
    val title: String,
    val price: Int,
    val program: String,
    /** 0 = Like new, 1 = Good, 2 = Fair. Same order as the create form. */
    val condition: Int,
    /** 0 = Sell, 1 = Rent, 2 = Swap. Same order as the create form. */
    val type: Int,
    val description: String,
    val seller: Seller,
    val meetup: String,
    /** Lower is newer. Used by the "Newest" sort. */
    val postedDaysAgo: Int,
    /** When the seller is free to meet. Reserving only offers these times. */
    val availability: Availability = Availability.OFFICE_HOURS,
    /** Swap listings: what the seller would like in exchange. */
    val wants: String? = null,
    /** Rent listings: the longest a borrower can keep it, in weeks. */
    val maxWeeks: Int = 4
) {
    val isRent get() = type == 1
    val isSwap get() = type == 2
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

    /** Every weekday covered by at least one block. */
    val days: Set<Int> get() = times.flatMap { it.days }.toSet()

    fun isFreeOn(weekday: Int) = times.any { weekday in it.days }

    fun isFreeAt(weekday: Int, minutes: Int) = times.any { weekday in it.days && minutes in it.from..it.to }

    /** Hourly start times on [weekday], at least 30 minutes before a block ends. */
    fun slots(weekday: Int): List<Int> = times.filter { weekday in it.days }
        .flatMap { block -> (block.from..block.to - 30 step 60).toList().ifEmpty { listOf(block.from) } }
        .distinct()
        .sorted()

    /** All blocks, e.g. "Mon, Wed, Fri • 1:00–4:00 PM; Sat • 9:00 AM–12:00 PM". */
    fun label(): String = times.joinToString("; ") { it.label() }

    /** Free time on one weekday, e.g. "1:00–4:00 PM". */
    fun labelFor(weekday: Int): String = times.filter { weekday in it.days }.joinToString(", ") {
        "${time(it.from, withMeridiem = (it.from < 720) != (it.to < 720))}\u2013${time(it.to)}"
    }

    companion object {
        val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

        /** Where the clock picker starts when a day has no free time to suggest. */
        const val DEFAULT_START = 9 * 60

        val OFFICE_HOURS = Availability(FreeTime(setOf(0, 1, 2, 3, 4), 9 * 60, 16 * 60))

        fun daysLabel(days: Set<Int>): String = when (days) {
            (0..6).toSet() -> "Every day"
            (0..4).toSet() -> "Mon\u2013Fri"
            setOf(5, 6) -> "Sat\u2013Sun"
            else -> days.sorted().joinToString(", ") { DAY_NAMES[it] }
        }

        /** "1:30 PM", or "1:30" when the AM/PM is shown once for a range. */
        fun time(minutes: Int, withMeridiem: Boolean = true): String {
            val h = minutes / 60
            val hour12 = if (h % 12 == 0) 12 else h % 12
            val base = "%d:%02d".format(hour12, minutes % 60)
            return if (withMeridiem) "$base ${if (h < 12) "AM" else "PM"}" else base
        }

        /** java.util.Calendar day of week to 0 = Monday ... 6 = Sunday. */
        fun weekdayOf(calendarDay: Int) = (calendarDay + 5) % 7
    }
}

/** A college, shown as a Home tile, a Market chip and a Create listing option. */
data class Program(val code: String, val icon: Int)

/**
 * One step in a deal's history, shown on Transaction details, e.g.
 * "Miguel sent a request" at 2:14 PM. [text] is a string resource; [arg]
 * fills its %1$s, usually the other student's first name.
 */
data class DealEvent(val text: Int, val millis: Long, val arg: String? = null)

/**
 * A finished exchange, shown under Profile > Completed and on its
 * Transaction details screen.
 *
 * @param kind one of the Exchange.SOLD ... SWAPPED constants.
 * @param partner the other student, or null for a listing you closed yourself.
 * @param finishedMillis when the item changed hands (or came back, for loans).
 * @param theirRating stars the other student gave you, 0 if not rated yet.
 * @param yourRating stars you gave them, 0 if not rated yet. A rating given
 *        this session lives in [UserState] instead; see [Reliability.yourStars].
 * @param onTime you kept to the meetup or the return date.
 * @param proof how the handover was confirmed: one of the PROOF_ constants.
 * @param listingId the listing this came from, when it is one in [Listings.ALL].
 * @param events the deal's history; sample exchanges get a made-up one.
 */
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
    val events: List<DealEvent> = emptyList()
) {
    /** You gave the item (sold, lent or swapped) rather than received it. */
    val giving get() = kind == SOLD || kind == LENT || kind == SWAPPED

    val isLoan get() = kind == LENT || kind == BORROWED

    companion object {
        const val SOLD = 0
        const val BOUGHT = 1
        const val LENT = 2
        const val BORROWED = 3
        const val SWAPPED = 4

        /** The giver typed in the receiver's handover code. */
        const val PROOF_CODE = 0
        /** The giver confirmed without a code (no signal, forgot the app...). */
        const val PROOF_MANUAL = 1
        /** You confirmed you received it; the giver checks your code on their side. */
        const val PROOF_RECEIVED = 2
    }
}

/**
 * Someone asking for one of Mika's listings: to buy it, borrow it, or swap
 * one of their own items for it. The other student never acts in the
 * prototype, so every request starts as sample data and only Mika answers.
 *
 * @param meetMillis the meetup they proposed; accepting agrees to it.
 * @param weeks loans only: how long they want to borrow it.
 * @param offerId swaps only: their listing they are offering.
 */
data class Request(
    val id: String,
    val listingId: String,
    val from: Seller,
    val sentMillis: Long,
    val meetup: String,
    val meetMillis: Long,
    val message: String,
    val weeks: Int = 0,
    val offerId: String? = null
) {
    val expiresMillis get() = sentMillis + Deals.ANSWER_WITHIN_MS
}

/**
 * A problem raised with student support: a no-show, an item not returned,
 * or something wrong with a finished exchange. Stays "Under review" in the
 * prototype; the support team works on the backend later.
 *
 * @param subject the exchange or listing id it is about.
 * @param reason a string resource naming the problem.
 */
data class Issue(
    val ref: String,
    val subject: String,
    val reason: Int,
    val partner: Seller?,
    val millis: Long,
    val details: String = ""
)

object Listings {

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
        Program("COL", R.drawable.ic_prog_col)
    )

    val PROGRAMS = COLLEGES.map { it.code }

    /** The signed-in student. */
    val ME = Seller(
        "Mika Dela Cruz", "MD", "CITCS", "3rd Year", "4.6", "92%", 14,
        "", true
    )

    private val paolo = Seller(
        "Paolo Mendoza", "PM", "CITCS", "3rd Year", "4.6", "92%", 14,
        "Yes, available pa! Meet tomorrow?", true
    )
    private val janelle = Seller(
        "Janelle Garcia", "JG", "CONAHS", "2nd Year", "4.9", "98%", 21,
        "Thank you ulit, Mika!", false
    )
    private val miguel = Seller(
        "Miguel Santos", "MS", "COE", "4th Year", "4.4", "88%", 9,
        "Can I see more photos of the drawing set?", true
    )
    private val aira = Seller(
        "Aira Villanueva", "AV", "CBA", "1st Year", "4.8", "95%", 6,
        "Transaction confirmed", false
    )

    private val carlo = Seller(
        "Carlo Reyes", "CR", "COE", "2nd Year", "4.7", "94%", 11,
        "Deal! Your calculus reviewer for my Arduino kit?", true
    )

    /** Everyone Mika chats with, in Chats list order. */
    val SELLERS = listOf(paolo, janelle, miguel, aira, carlo)

    /**
     * What each conversation in Chats is about, in list order. Paolo and
     * Janelle are giving to Mika; Miguel and Aira are getting from her.
     */
    val CHAT_LISTINGS = mapOf(
        paolo to "item2",
        janelle to "item5",
        miguel to "item1",
        aira to "mine2",
        carlo to "item14"
    )

    val ALL = listOf(
        Listing(
            "item1", "Engineering Drawing Set", 850, "COE", 0, 0,
            "Complete drafting set with T-square, triangles and compass. Barely used, still in the original case.",
            ME, "UC Main Gate Security Desk", 1
        ),
        Listing(
            "item2", "Casio fx-991EX", 1200, "CITCS", 1, 0,
            "Fully working scientific calculator. Used for two semesters only. Comes with original hard case and manual.",
            paolo, "UC Main Gate Security Desk", 0,
            Availability(FreeTime(setOf(0, 2, 4), 13 * 60, 16 * 60), FreeTime(setOf(5), 9 * 60, 12 * 60))
        ),
        Listing(
            "item3", "Anatomy & Physiology", 650, "CONAHS", 1, 0,
            "Latest edition. A few highlighted pages but no torn or missing pages.",
            janelle, "UC Library Lobby", 2,
            Availability(FreeTime(setOf(1, 3), 9 * 60, 12 * 60))
        ),
        Listing(
            "item4", "UC PE Uniform", 450, "CBA", 0, 1,
            "Size M shirt and jogging pants. Washed and ironed. Available to borrow for the semester.",
            aira, "UC Canteen Area", 3,
            Availability(FreeTime(setOf(0, 1, 2), 10 * 60, 15 * 60)),
            maxWeeks = 16
        ),
        Listing(
            "item5", "White Lab Gown", 380, "CONAHS", 1, 1,
            "Size S, long sleeves. Clean and without stains. Borrow it for a week or the whole term.",
            janelle, "UC Library Lobby", 4,
            Availability(FreeTime(setOf(1, 3), 9 * 60, 12 * 60), FreeTime(setOf(6), 14 * 60, 17 * 60)),
            maxWeeks = 2
        ),
        Listing(
            "item6", "Data Structures Book", 520, "CITCS", 2, 2,
            "Some notes in pencil on the early chapters. Open to swapping for an Algorithms book.",
            paolo, "CITCS Building Entrance", 5,
            Availability(FreeTime(setOf(0, 2, 4), 13 * 60, 16 * 60), FreeTime(setOf(5), 9 * 60, 12 * 60)),
            wants = "An Algorithms or Discrete Math book"
        ),
        Listing(
            "item7", "Fundamentals of Accounting", 400, "COA", 1, 0,
            "Volumes 1 and 2. Answers to the exercises are not written in.",
            aira, "UC Library Lobby", 6
        ),
        Listing(
            "item8", "Sketching Pencil Set", 250, "CAFA", 0, 0,
            "12 graphite pencils, 2H to 8B, plus a kneaded eraser. Used once.",
            miguel, "UC Canteen Area", 7
        ),
        Listing(
            "item9", "Lesson Plan Binder", 150, "CTE", 1, 2,
            "Sample lesson plans and templates from practice teaching. Swap for any CTE reviewer.",
            janelle, "UC Library Lobby", 8,
            wants = "Any CTE board exam reviewer"
        ),
        Listing(
            "item10", "Chef Uniform and Toque", 600, "CHTM", 1, 1,
            "Size M, white. Borrow it for your kitchen lab days.",
            aira, "UC Canteen Area", 9,
            maxWeeks = 1
        ),
        Listing(
            "item11", "General Chemistry Lab Manual", 200, "CAS", 2, 0,
            "Some pages filled in with pencil. Still usable for the procedures.",
            paolo, "UC Library Lobby", 10
        ),
        Listing(
            "item12", "CCJE Type B Uniform", 550, "CCJE", 1, 0,
            "Size L polo and slacks. Badge not included.",
            miguel, "UC Main Gate Security Desk", 11
        ),
        Listing(
            "item13", "The 1987 Constitution, Annotated", 900, "COL", 0, 0,
            "Hardbound, latest printing. Like new with no highlights.",
            janelle, "UC Library Lobby", 12
        ),
        Listing(
            "mine2", "Drafting Table Lamp", 150, "CAFA", 1, 1,
            "LED desk lamp with an adjustable arm. Borrow it for the week of your plates.",
            ME, "CITCS Building Entrance", 3
        ),
        Listing(
            "mine3", "Introduction to Psychology", 300, "CAS", 1, 0,
            "Used for one semester. Clean pages, a few sticky notes inside.",
            ME, "UC Library Lobby", 6
        ),
        Listing(
            "item14", "Arduino Starter Kit", 900, "COE", 1, 2,
            "Uno board, breadboard, jumper wires and a box of sensors. All parts tested.",
            carlo, "CITCS Building Entrance", 2,
            Availability(FreeTime(setOf(0, 1, 3), 10 * 60, 14 * 60)),
            wants = "A Calculus or Physics reviewer"
        ),
        Listing(
            "mine4", "Calculus Reviewer", 350, "CAS", 1, 2,
            "Complete reviewer with worked examples for Calculus 1 and 2. A few notes in pencil.",
            ME, "UC Library Lobby", 4,
            wants = "An electronics or Arduino kit"
        ),
        Listing(
            "mine5", "Safety Goggles", 40, "COE", 1, 1,
            "Clear anti-fog goggles for chem and shop labs. Cleaned after every use.",
            ME, "CITCS Building Entrance", 1,
            maxWeeks = 4
        ),
        Listing(
            "mine6", "Drafting Triangles Set", 30, "CAFA", 1, 1,
            "45\u00B0 and 30/60\u00B0 triangles with a protractor. Borrow it for your plates.",
            ME, "UC Library Lobby", 9,
            maxWeeks = 2
        )
    )

    fun isMine(listing: Listing) = listing.seller == ME

    /** Kicker for a chat or listing strip, e.g. YOU'RE BORROWING. */
    fun roleKicker(listing: Listing): Int = when {
        listing.type == 2 -> R.string.kicker_swap
        isMine(listing) && listing.type == 1 -> R.string.kicker_you_lending
        isMine(listing) -> R.string.kicker_you_selling
        listing.type == 1 -> R.string.kicker_you_borrowing
        else -> R.string.kicker_you_buying
    }

    /**
     * What the student is doing with a listing, for the role badge:
     * giving (selling / lending) on their own, getting (buying / borrowing)
     * on someone else's.
     */
    fun role(context: Context, listing: Listing): String = context.getString(
        when {
            listing.type == 2 -> R.string.role_swapping
            isMine(listing) && listing.type == 1 -> R.string.role_lending
            isMine(listing) -> R.string.role_selling
            listing.type == 1 -> R.string.role_borrowing
            else -> R.string.role_buying
        }
    )

    /** Mika's own listings that are still open, shown under Profile > Active. */
    fun myActive(): List<Listing> = ALL.filter {
        isMine(it) && !UserState.isClosed(it.id) && UserState.rental(it.id) == null
    }

    /** Own listings you can still offer in a swap: open, and not promised to anyone. */
    fun offerable(): List<Listing> = myActive().filter { UserState.accepted(it.id) == null }

    private fun daysAgo(days: Int) = System.currentTimeMillis() - days * Rentals.DAY_MS

    /**
     * Mika's finished exchanges before this session, newest first. Dated
     * from today so the 7-day rating window always makes sense: Calculus
     * Textbook is recent and still waiting for Mika's rating, while the
     * Scientific Calculator's window has closed. Accounting Workbook was a
     * late meetup, so it counts against her on-time rate.
     */
    private val COMPLETED by lazy {
        listOf(
            Exchange("ex1", "Calculus Textbook", 600, Exchange.SOLD, janelle, daysAgo(4), "UC Library Lobby", 5, 0),
            Exchange("ex2", "UC Type A Uniform", 500, Exchange.SOLD, aira, daysAgo(7), "UC Main Gate Security Desk", 5, 4),
            Exchange("ex3", "Lab Goggles", 150, Exchange.BOUGHT, miguel, daysAgo(11), "CITCS Building Entrance", 4, 4),
            Exchange("ex4", "Graphing Calculator", 400, Exchange.LENT, miguel, daysAgo(15), "UC Main Gate Security Desk", 5, 5),
            Exchange("ex5", "Intro to Programming", 450, Exchange.SWAPPED, paolo, daysAgo(19), "CITCS Building Entrance", 5, 5),
            Exchange("ex6", "Nursing Shoes", 700, Exchange.SOLD, janelle, daysAgo(23), "UC Library Lobby", 4, 5),
            Exchange(
                "ex7", "Accounting Workbook", 300, Exchange.BOUGHT, aira, daysAgo(27), "UC Canteen Area", 5, 4,
                onTime = false
            ),
            Exchange("ex8", "Stethoscope", 950, Exchange.SOLD, janelle, daysAgo(32), "UC Library Lobby", 5, 5),
            Exchange("ex9", "Scientific Calculator", 800, Exchange.SOLD, miguel, daysAgo(36), "UC Main Gate Security Desk", 4, 0),
            Exchange("ex10", "UC Lanyard and ID Case", 80, Exchange.SOLD, paolo, daysAgo(41), "UC Canteen Area", 5, 5),
            Exchange("ex11", "Physics Reviewer", 250, Exchange.BOUGHT, paolo, daysAgo(45), "UC Library Lobby", 0, 5),
            Exchange("ex12", "Engineering Ruler Set", 200, Exchange.SOLD, miguel, daysAgo(50), "CITCS Building Entrance", 5, 4),
            Exchange(
                "ex13", "Business Math Book", 350, Exchange.SWAPPED, aira, daysAgo(55), "UC Canteen Area", 5, 5,
                proof = Exchange.PROOF_MANUAL
            ),
            Exchange("ex14", "White Lab Gown", 120, Exchange.BORROWED, janelle, daysAgo(59), "UC Library Lobby", 5, 5)
        )
    }

    /**
     * Profile > Completed: exchanges finished this session, then listings you
     * closed yourself (sold elsewhere, no partner on record), then history.
     */
    fun completed(): List<Exchange> {
        val closedByYou = ALL.filter { isMine(it) && UserState.isClosedByYou(it.id) }.map {
            val kind = when (it.type) {
                1 -> Exchange.LENT
                2 -> Exchange.SWAPPED
                else -> Exchange.SOLD
            }
            Exchange(
                "closed-${it.id}", it.title, it.price, kind, null, UserState.closedAt(it.id), it.meetup, 0, 0,
                listingId = it.id
            )
        }
        return UserState.finished() + closedByYou + COMPLETED
    }

    fun exchange(id: String?): Exchange = completed().firstOrNull { it.id == id } ?: COMPLETED.first()

    /**
     * Requests on Mika's listings, as they stood when the app opened.
     * - Engineering Drawing Set: two open requests and one that expired
     *   because nobody answered within 24 hours.
     * - Introduction to Psychology: Carlo's request is already accepted
     *   (meetup later today), Paolo waits on the waitlist.
     * - Safety Goggles: Miguel wants to borrow them for two weeks.
     * - Calculus Reviewer: Paolo offers his Chemistry lab manual. Mika has
     *   also offered this reviewer to Carlo, so accepting Paolo withdraws that.
     */
    val REQUESTS: List<Request> by lazy {
        val now = System.currentTimeMillis()
        val hour = 60 * 60 * 1000L
        listOf(
            Request(
                "rq1", "item1", miguel, now - 3 * hour, "UC Main Gate Security Desk",
                Rentals.at(1, 14 * 60), "Hi! Is this still available? I can meet tomorrow after class."
            ),
            Request(
                "rq2", "item1", aira, now - 1 * hour, "UC Library Lobby",
                Rentals.at(2, 10 * 60), "I need it for my plates next week. Pwede sa library?"
            ),
            Request(
                "rq3", "item1", janelle, now - 30 * hour, "UC Library Lobby",
                Rentals.at(0, 11 * 60), "Hello, reserving this for my cousin in COE."
            ),
            Request(
                "rq4", "mine3", carlo, now - 20 * hour, "CITCS Building Entrance",
                (now + 3 * hour) / (30 * 60 * 1000L) * (30 * 60 * 1000L), "Thanks for accepting! See you at the CITCS entrance."
            ),
            Request(
                "rq5", "mine3", paolo, now - 6 * hour, "UC Library Lobby",
                Rentals.at(1, 15 * 60), "If it falls through, I'll take it."
            ),
            Request(
                "rq6", "mine5", miguel, now - 2 * hour, "CITCS Building Entrance",
                Rentals.at(1, 11 * 60), "For my chem lab next week and the one after.", weeks = 2
            ),
            Request(
                "rq7", "mine4", paolo, now - 5 * hour, "CITCS Building Entrance",
                Rentals.at(2, 13 * 60), "Not electronics, but my lab manual is complete and clean.",
                offerId = "item11"
            )
        )
    }

    val MEETUP_POINTS = listOf(
        "UC Main Gate Security Desk",
        "UC Library Lobby",
        "CITCS Building Entrance",
        "UC Canteen Area"
    )

    const val DEFAULT_ID = "item2"

    fun byId(id: String?): Listing = ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == DEFAULT_ID }

    fun sellerByName(name: String?): Seller = SELLERS.firstOrNull { it.name == name } ?: paolo

    fun bySeller(seller: Seller): List<Listing> = ALL.filter { it.seller == seller }

    /**
     * What the market shows: other students' listings that are still open.
     * Your own listings live under Profile > Active instead, and anything you
     * reserved, borrowed, bought or offered a swap for is hidden too.
     */
    fun open(): List<Listing> = ALL.filter {
        !isMine(it) && !UserState.isClosed(it.id) && !UserState.isTaken(it.id)
    }

    /**
     * Price as shown on a listing: "₱850", "₱380 / week" for rent, "Swap" for
     * swap. Only the amount keeps the big price size; "/ week" is small and
     * grey, and "Swap" is a smaller amber word, whatever size the price is.
     */
    fun priceLabel(context: Context, listing: Listing): CharSequence {
        fun sp(value: Float) = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics
        ).toInt()
        return when {
            listing.isSwap -> SpannableString(context.getString(R.string.price_swap)).apply {
                setSpan(AbsoluteSizeSpan(sp(15f)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(context.getColor(R.color.swap_text)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            listing.isRent -> {
                val amount = price(listing.price)
                SpannableString(context.getString(R.string.price_rent_format, amount)).apply {
                    setSpan(AbsoluteSizeSpan(sp(12f)), amount.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(
                        ForegroundColorSpan(context.getColor(R.color.text_secondary)),
                        amount.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            else -> price(listing.price)
        }
    }

    /** Rental lengths offered on the forms, in weeks. */
    val RENT_WEEKS = listOf(1, 2, 4, 8, 16)

    fun weeksLabel(context: Context, weeks: Int): String = when (weeks) {
        4 -> context.getString(R.string.rent_1_month)
        8 -> context.getString(R.string.rent_2_months)
        16 -> context.getString(R.string.rent_semester)
        else -> context.resources.getQuantityString(R.plurals.rent_weeks, weeks, weeks)
    }

    fun price(value: Int): String =
        "₱" + NumberFormat.getIntegerInstance(Locale.US).format(value)

    fun conditionLabel(context: Context, condition: Int): String = context.getString(
        when (condition) {
            0 -> R.string.cond_like_new
            1 -> R.string.cond_good
            else -> R.string.cond_fair
        }
    )
}

/**
 * Everything the student did during this session: saved items,
 * reservations, loans, answers to requests, finished exchanges, ratings
 * and reported problems. Kept in memory only, so it resets when the app is
 * closed, which is fine for a front-end prototype.
 *
 * Each function here stands in for one backend call. In the real app the
 * server makes these state changes (and checks they are allowed), so the
 * screens only ever ask for an action and redraw from the result.
 */
object UserState {

    private val saved = mutableSetOf("item3", "item5")
    var notificationsRead: Boolean = false

    /**
     * A reservation or swap offer Mika made on someone else's listing.
     * Mika's reservations count as accepted straight away, since the other
     * student never answers in the prototype.
     *
     * @param weeks loans only: how long she is borrowing it.
     * @param meetMillis the agreed meetup, or null when none is set yet.
     * @param offerId swaps only: Mika's listing she is offering.
     * @param madeMillis when she reserved or offered.
     */
    data class Reservation(
        val summary: String,
        val weeks: Int = 0,
        val meetMillis: Long? = null,
        val offerId: String? = null,
        val madeMillis: Long = System.currentTimeMillis()
    )

    /**
     * An item that is out on loan. [lending] is true when Mika lent it to
     * [partner]; false when she borrowed it from them.
     */
    data class Rental(
        val partner: Seller,
        val dueMillis: Long,
        val lending: Boolean,
        /** The borrower says they gave it back; the lender has not confirmed yet. */
        val returnPending: Boolean = false,
        val startMillis: Long = System.currentTimeMillis(),
        /** When the borrower said it was returned. */
        val reportedMillis: Long? = null,
        /** History up to the pickup, carried into the finished exchange. */
        val events: List<DealEvent> = emptyList(),
        val proof: Int = Exchange.PROOF_CODE
    )

    private val reserved: MutableMap<String, Reservation> by lazy {
        val now = System.currentTimeMillis()
        mutableMapOf(
            "item2" to Reservation(
                "UC Main Gate Security Desk • Tomorrow, 1:30 PM",
                meetMillis = Rentals.at(1, 13 * 60 + 30), madeMillis = now - 2 * HOUR
            ),
            "item14" to Reservation(
                "Your Calculus Reviewer for their Arduino Starter Kit",
                offerId = "mine4", madeMillis = now - 26 * HOUR
            ),
            // The meetup was yesterday and nobody confirmed it: "Did it happen?"
            "item7" to Reservation(
                "UC Library Lobby • Yesterday, 10:00 AM",
                meetMillis = Rentals.at(-1, 10 * 60), madeMillis = now - 3 * Rentals.DAY_MS
            )
        )
    }

    /** Sample loans, dated from today so the countdowns always make sense. */
    private val rentals: MutableMap<String, Rental> by lazy {
        val now = System.currentTimeMillis()
        mutableMapOf(
            "item5" to Rental(
                Listings.byId("item5").seller, now + 4 * Rentals.DAY_MS, lending = false,
                startMillis = now - 3 * Rentals.DAY_MS
            ),
            "item10" to Rental(
                Listings.byId("item10").seller, now - 1 * Rentals.DAY_MS, lending = false,
                startMillis = now - 8 * Rentals.DAY_MS
            ),
            // Aira already tapped "I've returned it", so Mika has a return to confirm.
            "mine2" to Rental(
                Listings.sellerByName("Aira Villanueva"), now + 2 * Rentals.DAY_MS,
                lending = true, returnPending = true, startMillis = now - 5 * Rentals.DAY_MS,
                reportedMillis = now - 4 * HOUR
            ),
            // Paolo is three days late: Mika can report a problem.
            "mine6" to Rental(
                Listings.sellerByName("Paolo Mendoza"), now - 3 * Rentals.DAY_MS,
                lending = true, startMillis = now - 17 * Rentals.DAY_MS
            )
        )
    }

    /** Own listings taken off the market, with when. */
    private val closed = mutableMapOf<String, Long>()

    /** Closed by "Mark as sold" rather than by a deal in the app. */
    private val closedByYou = mutableSetOf<String>()

    /** Exchanges finished this session, newest first. */
    private val finished = mutableListOf<Exchange>()

    /** Other people's listings Mika bought, borrowed or swapped for: gone from the market. */
    private val taken = mutableSetOf<String>()

    /** Mika's answers to requests on her listings. Unanswered ones are not in here. */
    private val answers: MutableMap<String, Int> by lazy { mutableMapOf("rq4" to Deals.ACCEPTED) }

    private val acceptedAt: MutableMap<String, Long> by lazy {
        mutableMapOf("rq4" to System.currentTimeMillis() - 19 * HOUR)
    }

    /** Cancellations close to the meetup, and meetups Mika missed. Both lower on-time. */
    private var lateCancels = 0

    /** Stars and an optional note Mika gave, by exchange id. */
    private val ratings = mutableMapOf<String, Pair<Int, String>>()

    private val ratedAt = mutableMapOf<String, Long>()

    /** Problems reported to student support, by the exchange or listing they are about. */
    private val issues = mutableMapOf<String, Issue>()

    /** Settings > Default meetup point. Used to prefill new listings. */
    var defaultMeetup = 0

    // ---- Saved -------------------------------------------------------------

    fun saved(): List<Listing> = Listings.ALL.filter { it.id in saved }

    fun isSaved(id: String) = id in saved

    fun setSaved(id: String, on: Boolean) {
        if (on) saved.add(id) else saved.remove(id)
    }

    // ---- Mika's reservations and swap offers ------------------------------

    fun isReserved(id: String) = id in reserved

    fun reservation(id: String): Reservation? = reserved[id]

    /** Listings the student reserved from others, oldest first. */
    fun reservedListings(): List<Listing> = reserved.keys.map { Listings.byId(it) }

    fun setReserved(id: String, detail: String, weeks: Int = 0, meetMillis: Long? = null, offerId: String? = null) {
        reserved[id] = Reservation(detail, weeks, meetMillis, offerId)
    }

    /**
     * Cancels or withdraws a reservation. Returns true when it was late
     * (under two hours before the meetup, or after it), which counts
     * against Mika's on-time rate.
     */
    fun cancelReservation(id: String): Boolean {
        val reservation = reserved.remove(id) ?: return false
        val late = Deals.isLate(reservation.meetMillis)
        if (late) lateCancels++
        return late
    }

    /**
     * "Did the meetup happen?" answered with a no. When the other student
     * missed it, a no-show goes to student support; when Mika missed it,
     * it counts against her on-time rate. Either way the reservation ends
     * and the item goes back on the market.
     */
    fun reportNoShow(listing: Listing, theyMissed: Boolean) {
        reserved.remove(listing.id) ?: return
        if (theyMissed) {
            report(listing.id, R.string.problem_no_show, listing.seller)
        } else {
            lateCancels++
        }
    }

    // ---- Loans --------------------------------------------------------------

    fun rental(id: String): Rental? = rentals[id]

    /** Things Mika borrowed, soonest due first. */
    fun borrowing(): List<Pair<Listing, Rental>> =
        rentals.filter { !it.value.lending }.map { Listings.byId(it.key) to it.value }.sortedBy { it.second.dueMillis }

    /** Mika's things that are out on loan, soonest due first. */
    fun lending(): List<Pair<Listing, Rental>> =
        rentals.filter { it.value.lending }.map { Listings.byId(it.key) to it.value }.sortedBy { it.second.dueMillis }

    /** Own lend listing handed over without a request in the app ("Lend it out"). */
    fun lend(listing: Listing, partner: Seller, weeks: Int) {
        val now = System.currentTimeMillis()
        declineOpen(listing.id)
        rentals[listing.id] = Rental(
            partner, now + weeks * Rentals.WEEK_MS, lending = true, startMillis = now,
            events = listOf(DealEvent(R.string.ev_lent_out, now, partner.first)), proof = Exchange.PROOF_MANUAL
        )
    }

    /**
     * Borrower: "I've returned it". The loan stays open until the lender
     * confirms, because only they know the item really came back.
     */
    fun reportReturned(listing: Listing) {
        rentals[listing.id]?.let {
            rentals[listing.id] = it.copy(returnPending = true, reportedMillis = System.currentTimeMillis())
        }
    }

    /** Lender: "Not received". The loan carries on as before. */
    fun rejectReturn(listing: Listing) {
        rentals[listing.id]?.let { rentals[listing.id] = it.copy(returnPending = false, reportedMillis = null) }
    }

    /**
     * Lender confirms the item is back (with or without the borrower's
     * report). The loan moves to Completed. A lent item can be lent again;
     * a borrowed one stays off the market.
     */
    fun markReturned(listing: Listing) {
        val rental = rentals.remove(listing.id) ?: return
        val now = System.currentTimeMillis()
        val returned = rental.reportedMillis ?: now
        val events = rental.events.ifEmpty {
            listOf(DealEvent(R.string.ev_picked_up, rental.startMillis))
        } + listOfNotNull(
            rental.reportedMillis?.let {
                if (rental.lending) DealEvent(R.string.ev_they_returned, it, rental.partner.first)
                else DealEvent(R.string.ev_you_returned, it)
            },
            DealEvent(R.string.ev_return_confirmed, now)
        )
        finished.add(
            0, Exchange(
                "done-${listing.id}-${finished.size}", listing.title, listing.price,
                if (rental.lending) Exchange.LENT else Exchange.BORROWED,
                rental.partner, now, listing.meetup, 0, 0,
                // Mika's on-time rate is about her own returns, not her borrowers'.
                onTime = rental.lending || returned < rental.dueMillis + Rentals.DAY_MS,
                proof = rental.proof, listingId = listing.id, events = events
            )
        )
    }

    // ---- Receiving: Mika got the item she reserved -------------------------

    /**
     * Reservation picked up / swap done. A loan starts its countdown; a
     * purchase or swap goes straight to Completed. In a swap Mika's own
     * item is gone too, so any requests on it are declined.
     */
    fun markReceived(listing: Listing) {
        val reservation = reserved.remove(listing.id) ?: return
        val now = System.currentTimeMillis()
        taken.add(listing.id)
        val start = DealEvent(if (listing.isSwap) R.string.ev_you_offered else R.string.ev_you_reserved, reservation.madeMillis)
        if (listing.isRent) {
            val weeks = reservation.weeks.coerceAtLeast(1)
            rentals[listing.id] = Rental(
                listing.seller, now + weeks * Rentals.WEEK_MS, lending = false, startMillis = now,
                events = listOf(start, DealEvent(R.string.ev_you_picked_up, now)), proof = Exchange.PROOF_RECEIVED
            )
            return
        }
        reservation.offerId?.let {
            closed[it] = now
            declineOpen(it)
        }
        finished.add(
            0, Exchange(
                "done-${listing.id}-${finished.size}", listing.title, listing.price,
                if (listing.isSwap) Exchange.SWAPPED else Exchange.BOUGHT,
                listing.seller, now, listing.meetup, 0, 0,
                proof = Exchange.PROOF_RECEIVED, listingId = listing.id,
                events = listOf(start, DealEvent(if (listing.isSwap) R.string.ev_swap_done else R.string.ev_you_got_it, now))
            )
        )
    }

    // ---- Requests on Mika's listings ---------------------------------------

    /** Every request on a listing that has not been declined or completed, oldest first. */
    fun requests(listingId: String): List<Request> = Listings.REQUESTS.filter {
        it.listingId == listingId && answers[it.id].let { a -> a == null || a == Deals.ACCEPTED }
    }

    /** One of the Deals.PENDING ... EXPIRED states. */
    fun status(request: Request): Int = answers[request.id] ?: when {
        accepted(request.listingId) != null -> Deals.WAITLIST
        System.currentTimeMillis() > request.expiresMillis -> Deals.EXPIRED
        else -> Deals.PENDING
    }

    /** Requests still waiting for Mika's answer. */
    fun pending(listingId: String): List<Request> = requests(listingId).filter { status(it) == Deals.PENDING }

    /** The request Mika accepted on this listing, if any. Its meetup is on. */
    fun accepted(listingId: String): Request? =
        Listings.REQUESTS.firstOrNull { it.listingId == listingId && answers[it.id] == Deals.ACCEPTED }

    fun acceptedAt(request: Request): Long = acceptedAt[request.id] ?: request.sentMillis

    /** The latest request this student sent on this listing, for the chat thread. */
    fun requestFrom(seller: Seller, listingId: String): Request? =
        Listings.REQUESTS.lastOrNull { it.from == seller && it.listingId == listingId }

    /**
     * Swap offers Mika made that give away [listingId]. Accepting a request
     * on that listing withdraws them, since one item cannot go to two people.
     */
    fun offersUsing(listingId: String): List<Listing> =
        reserved.filter { it.value.offerId == listingId }.keys.map { Listings.byId(it) }

    /**
     * Accepts a request: the item is promised to them and everyone else
     * waits on the waitlist. Returns the swap offers that were withdrawn.
     */
    fun accept(request: Request): List<Listing> {
        val withdrawn = offersUsing(request.listingId)
        withdrawn.forEach { reserved.remove(it.id) }
        answers[request.id] = Deals.ACCEPTED
        acceptedAt[request.id] = System.currentTimeMillis()
        return withdrawn
    }

    fun decline(request: Request) {
        answers[request.id] = Deals.DECLINED
    }

    /**
     * Calls off an accepted request. Waitlisted requests are open again.
     * Returns true when it was late, which counts against on-time.
     */
    fun cancelAccepted(listingId: String): Boolean {
        val request = accepted(listingId) ?: return false
        answers[request.id] = Deals.DECLINED
        val late = Deals.isLate(request.meetMillis)
        if (late) lateCancels++
        return late
    }

    /** "Did the meetup happen?" on your own listing, answered with a no. */
    fun reportOwnerNoShow(listing: Listing, theyMissed: Boolean) {
        val request = accepted(listing.id) ?: return
        answers[request.id] = Deals.DECLINED
        if (theyMissed) report(listing.id, R.string.problem_no_show, request.from) else lateCancels++
    }

    /**
     * Mika handed the item over to the student she accepted. [proof] says
     * whether she checked their code. A sale or swap goes to Completed; a
     * loan starts. Every other request on the listing is declined.
     */
    fun handOver(listing: Listing, proof: Int) {
        val request = accepted(listing.id) ?: return
        val now = System.currentTimeMillis()
        answers[request.id] = Deals.COMPLETED
        declineOpen(listing.id)
        val events = listOf(
            DealEvent(if (request.offerId != null) R.string.ev_they_offered else R.string.ev_they_requested, request.sentMillis, request.from.first),
            DealEvent(R.string.ev_you_accepted, acceptedAt(request)),
            DealEvent(if (proof == Exchange.PROOF_CODE) R.string.ev_handover_code else R.string.ev_handover_manual, now)
        )
        if (listing.isRent) {
            rentals[listing.id] = Rental(
                request.from, now + request.weeks.coerceAtLeast(1) * Rentals.WEEK_MS, lending = true,
                startMillis = now, events = events, proof = proof
            )
            return
        }
        closed[listing.id] = now
        request.offerId?.let { taken.add(it) }
        finished.add(
            0, Exchange(
                "done-${listing.id}-${finished.size}", listing.title, listing.price,
                if (listing.isSwap) Exchange.SWAPPED else Exchange.SOLD,
                request.from, now, request.meetup, 0, 0,
                proof = proof, listingId = listing.id, events = events
            )
        )
    }

    /** The item is gone, so nobody else can have it: open and waitlisted requests are declined. */
    private fun declineOpen(listingId: String) {
        Listings.REQUESTS.filter { it.listingId == listingId && it.id !in answers }.forEach { answers[it.id] = Deals.DECLINED }
    }

    // ---- Finished ------------------------------------------------------------

    fun finished(): List<Exchange> = finished

    /**
     * Not shown in the market: anything Mika reserved, borrowed, bought or
     * swapped for, and items offered to her in a swap she accepted.
     */
    fun isTaken(id: String) = id in reserved || id in taken || rentals[id]?.lending == false ||
        Listings.REQUESTS.any { it.offerId == id && answers[it.id] == Deals.ACCEPTED }

    /** Mika bought, borrowed or swapped for this, and the exchange is done. */
    fun hasReceived(id: String) = id in taken

    /** Someone else's item Mika got by accepting their swap offer on her own listing. */
    fun gotInSwap(id: String) = Listings.REQUESTS.any { it.offerId == id && answers[it.id] == Deals.COMPLETED }

    /**
     * Someone else's item coming to Mika because she accepted their swap
     * offer on her own listing. The handover has not happened yet.
     */
    fun incomingOffer(id: String): Request? =
        Listings.REQUESTS.firstOrNull { it.offerId == id && answers[it.id] == Deals.ACCEPTED }

    /** Own listings sold, swapped or closed. */
    fun isClosed(id: String) = id in closed

    /** Closed with "Mark as sold" / "Mark as swapped", not through a request. */
    fun isClosedByYou(id: String) = id in closedByYou

    fun closedAt(id: String): Long = closed[id] ?: System.currentTimeMillis()

    /** "Mark as sold": taken off the market by hand. Waiting requests are declined. */
    fun close(id: String) {
        closed[id] = System.currentTimeMillis()
        closedByYou.add(id)
        declineOpen(id)
    }

    // ---- Ratings, reliability and problems -----------------------------------

    fun rate(exchangeId: String, stars: Int, note: String) {
        ratings[exchangeId] = stars to note
        ratedAt[exchangeId] = System.currentTimeMillis()
    }

    fun ratedAt(exchangeId: String): Long = ratedAt[exchangeId] ?: System.currentTimeMillis()

    fun rating(exchangeId: String): Pair<Int, String>? = ratings[exchangeId]

    fun lateCancels() = lateCancels

    /** Sends a problem to student support. Returns the new reference. */
    fun report(subject: String, reason: Int, partner: Seller?, details: String = ""): Issue {
        val issue = Issue(
            "HTS-R%04d".format(4100 + issues.size + 1), subject, reason, partner,
            System.currentTimeMillis(), details
        )
        issues[subject] = issue
        return issue
    }

    fun issue(subject: String): Issue? = issues[subject]

    private const val HOUR = 60 * 60 * 1000L
}

/**
 * Rules for deals that the backend will enforce later: how long a request
 * stays open, when a cancellation counts as late, when a meetup counts as
 * missed, and the handover codes.
 */
object Deals {

    /** Request states. Only Mika's answers are stored; the rest are worked out. */
    const val PENDING = 0
    const val WAITLIST = 1
    const val ACCEPTED = 2
    const val DECLINED = 3
    const val EXPIRED = 4
    const val COMPLETED = 5

    private const val HOUR = 60 * 60 * 1000L

    /** An unanswered request closes after a day. */
    const val ANSWER_WITHIN_MS = 24 * HOUR

    /** Cancelling less than this before the meetup counts against on-time. */
    const val LATE_CANCEL_MS = 2 * HOUR

    /** A meetup this long in the past without a handover asks "Did it happen?". */
    const val NO_SHOW_AFTER_MS = 2 * HOUR

    /** Both students can rate for a week after the exchange. */
    const val RATING_WINDOW_MS = 7 * 24 * HOUR

    fun isLate(meetMillis: Long?) = meetMillis != null && meetMillis - System.currentTimeMillis() < LATE_CANCEL_MS

    fun meetupMissed(meetMillis: Long?) = meetMillis != null && System.currentTimeMillis() > meetMillis + NO_SHOW_AFTER_MS

    /**
     * The 4-digit code the receiver shows at the meetup. The giver types it
     * in to prove they met. Made up from the deal id here; the server will
     * issue a random one per deal.
     */
    fun code(dealId: String): String = "%04d".format(1000 + (dealId.hashCode() and 0x7fffffff) % 9000)

    /** Mika's code for something she reserved. */
    fun myCode(listingId: String) = code("me-$listingId")

    /**
     * Sample exchanges have no recorded history, so build a believable one
     * from the finish date: requested two days before, accepted two hours
     * later, then the handover (and the return, for loans).
     */
    fun timeline(exchange: Exchange): List<DealEvent> {
        if (exchange.events.isNotEmpty()) return exchange.events
        val first = exchange.partner?.first ?: return listOf(DealEvent(R.string.ev_closed_by_you, exchange.finishedMillis))
        val end = exchange.finishedMillis
        val start = end - (if (exchange.isLoan) 9 else 2) * Rentals.DAY_MS
        val asked = when {
            exchange.giving -> DealEvent(if (exchange.kind == Exchange.SWAPPED) R.string.ev_they_offered else R.string.ev_they_requested, start, first)
            else -> DealEvent(if (exchange.kind == Exchange.SWAPPED) R.string.ev_you_offered else R.string.ev_you_reserved, start)
        }
        val handover = DealEvent(
            if (exchange.proof == Exchange.PROOF_MANUAL) R.string.ev_handover_manual else R.string.ev_handover_code,
            if (exchange.isLoan) start + 2 * Rentals.DAY_MS else end
        )
        return listOfNotNull(
            asked,
            DealEvent(if (exchange.giving) R.string.ev_you_accepted else R.string.ev_they_accepted, start + 2 * HOUR, first),
            handover,
            if (exchange.isLoan) DealEvent(R.string.ev_return_confirmed, end) else null
        )
    }
}

/**
 * Mika's reliability card: rating, on-time rate and completed count. Worked
 * out from her history instead of typed in, which is why the card says it
 * cannot be edited. The server will compute these for everyone.
 */
object Reliability {

    /** Exchanges with another student; ones you closed yourself do not count. */
    private fun deals() = Listings.completed().filter { it.partner != null }

    /** Stars Mika gave, including a rating given this session. */
    fun yourStars(exchange: Exchange): Int = UserState.rating(exchange.id)?.first ?: exchange.yourRating

    fun ratingClosesAt(exchange: Exchange) = exchange.finishedMillis + Deals.RATING_WINDOW_MS

    fun ratingOpen(exchange: Exchange) = System.currentTimeMillis() < ratingClosesAt(exchange)

    fun canRate(exchange: Exchange) = exchange.partner != null && yourStars(exchange) == 0 && ratingOpen(exchange)

    /**
     * Ratings are blind: you see theirs once you have rated, or once the
     * week is over, so nobody rates in revenge.
     */
    fun theirVisible(exchange: Exchange) = yourStars(exchange) > 0 || !ratingOpen(exchange)

    /** "4.8", from every rating Mika can see. */
    fun rating(): String {
        val stars = deals().filter { it.theirRating > 0 && theirVisible(it) }.map { it.theirRating }
        return if (stars.isEmpty()) "–" else "%.1f".format(java.util.Locale.US, stars.average())
    }

    /** "93%": exchanges where Mika was on time, out of all of them plus late cancels and missed meetups. */
    fun onTime(): String {
        val all = deals()
        val total = all.size + UserState.lateCancels()
        if (total == 0) return "–"
        return "${Math.round(all.count { it.onTime } * 100.0 / total)}%"
    }

    fun completed(): Int = deals().size
}

/** Dates and countdowns for rentals: return dates, "3 days left", "Overdue". */
object Rentals {

    const val DAY_MS = 24L * 60 * 60 * 1000
    const val WEEK_MS = 7 * DAY_MS

    /** Today plus [days] (negative for the past) at [minutes] after midnight. */
    fun at(days: Int, minutes: Int): Long = java.util.Calendar.getInstance().apply {
        add(java.util.Calendar.DAY_OF_MONTH, days)
        set(java.util.Calendar.HOUR_OF_DAY, minutes / 60); set(java.util.Calendar.MINUTE, minutes % 60)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** "Today, 4:00 PM", "Tomorrow, 2:00 PM", "Yesterday, 10:00 AM" or "Wed, Nov 18, 2:00 PM". */
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

    /** "Nov 12", or "Today" for exchanges finished today. */
    fun shortDate(context: Context, millis: Long): String =
        if (daysUntil(millis) == 0) context.getString(R.string.day_today_label)
        else java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(millis)

    /** "21 h" or "40 min" until [millis], for request deadlines. */
    fun timeLeft(context: Context, millis: Long): String {
        val minutes = ((millis - System.currentTimeMillis()) / 60000).coerceAtLeast(1)
        return if (minutes >= 60) context.getString(R.string.hours_short, (minutes / 60).toInt())
        else context.getString(R.string.minutes_short, minutes.toInt())
    }

    /** "Fri, Nov 20" */
    fun dateLabel(millis: Long): String =
        java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.US).format(millis)

    /** Whole days from today to [millis]; negative when it has passed. */
    fun daysUntil(millis: Long): Int {
        fun midnight(t: Long) = java.util.Calendar.getInstance().apply {
            timeInMillis = t
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        return Math.round((midnight(millis) - midnight(System.currentTimeMillis())).toDouble() / DAY_MS).toInt()
    }

    /** "4 days left", "Due tomorrow", "Due today" or "Overdue by 2 days". */
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

    /** Due within three days, or overdue: worth a reminder. */
    fun isDueSoon(millis: Long) = daysUntil(millis) <= 3
}
