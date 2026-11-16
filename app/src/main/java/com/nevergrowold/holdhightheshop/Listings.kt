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
)

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
    val wants: String? = null
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

        /** Meetup points are open daily from 7:00 AM to 7:00 PM. */
        const val CAMPUS_OPENS = 7 * 60
        const val CAMPUS_CLOSES = 19 * 60

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
 * A finished exchange, shown under Profile > Completed and on its
 * Transaction details screen.
 *
 * @param kind one of the Exchange.SOLD ... SWAPPED constants.
 * @param partner the other student, or null for a listing you closed yourself.
 * @param theirRating stars the other student gave you, 0 if not rated yet.
 * @param yourRating stars you gave them, 0 if not rated yet.
 */
data class Exchange(
    val id: String,
    val title: String,
    val price: Int,
    val kind: Int,
    val partner: Seller?,
    val date: String,
    val meetup: String,
    val theirRating: Int,
    val yourRating: Int
) {
    /** You gave the item (sold, lent or swapped) rather than received it. */
    val giving get() = kind == SOLD || kind == LENT || kind == SWAPPED

    companion object {
        const val SOLD = 0
        const val BOUGHT = 1
        const val LENT = 2
        const val BORROWED = 3
        const val SWAPPED = 4
    }
}

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
            "Size M shirt and jogging pants. Washed and ironed. Available to rent for the semester.",
            aira, "UC Canteen Area", 3,
            Availability(FreeTime(setOf(0, 1, 2), 10 * 60, 15 * 60))
        ),
        Listing(
            "item5", "White Lab Gown", 380, "CONAHS", 1, 1,
            "Size S, long sleeves. Clean and without stains. Rent for a week or the whole term.",
            janelle, "UC Library Lobby", 4,
            Availability(FreeTime(setOf(1, 3), 9 * 60, 12 * 60), FreeTime(setOf(6), 14 * 60, 17 * 60))
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
            "Size M, white. Rent it for your kitchen lab days.",
            aira, "UC Canteen Area", 9
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
            "LED desk lamp with an adjustable arm. Rent it for the week of your plates.",
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
    fun myActive(): List<Listing> = ALL.filter { isMine(it) && !UserState.isClosed(it.id) }

    /** Mika's finished exchanges, shown under Profile > Completed. */
    private val COMPLETED = listOf(
        Exchange("ex1", "Calculus Textbook", 600, Exchange.SOLD, janelle, "Nov 12", "UC Library Lobby", 5, 5),
        Exchange("ex2", "UC Type A Uniform", 500, Exchange.SOLD, aira, "Nov 9", "UC Main Gate Security Desk", 5, 4),
        Exchange("ex3", "Lab Goggles", 150, Exchange.BOUGHT, miguel, "Nov 5", "CITCS Building Entrance", 4, 4),
        Exchange("ex4", "Graphing Calculator", 400, Exchange.LENT, miguel, "Nov 1", "UC Main Gate Security Desk", 5, 5),
        Exchange("ex5", "Intro to Programming", 450, Exchange.SWAPPED, paolo, "Oct 28", "CITCS Building Entrance", 5, 5),
        Exchange("ex6", "Nursing Shoes", 700, Exchange.SOLD, janelle, "Oct 24", "UC Library Lobby", 4, 5),
        Exchange("ex7", "Accounting Workbook", 300, Exchange.BOUGHT, aira, "Oct 20", "UC Canteen Area", 5, 4),
        Exchange("ex8", "Stethoscope", 950, Exchange.SOLD, janelle, "Oct 15", "UC Library Lobby", 5, 5),
        Exchange("ex9", "Scientific Calculator", 800, Exchange.SOLD, miguel, "Oct 11", "UC Main Gate Security Desk", 4, 0),
        Exchange("ex10", "UC Lanyard and ID Case", 80, Exchange.SOLD, paolo, "Oct 6", "UC Canteen Area", 5, 5),
        Exchange("ex11", "Physics Reviewer", 250, Exchange.BOUGHT, paolo, "Oct 2", "UC Library Lobby", 0, 5),
        Exchange("ex12", "Engineering Ruler Set", 200, Exchange.SOLD, miguel, "Sep 27", "CITCS Building Entrance", 5, 4),
        Exchange("ex13", "Business Math Book", 350, Exchange.SWAPPED, aira, "Sep 22", "UC Canteen Area", 5, 5),
        Exchange("ex14", "White Lab Gown", 120, Exchange.BORROWED, janelle, "Sep 18", "UC Library Lobby", 5, 5)
    )

    /** Profile > Completed: anything you closed this session, then the history. */
    fun completed(): List<Exchange> = ALL.filter { isMine(it) && UserState.isClosed(it.id) }.map {
        val kind = when (it.type) {
            1 -> Exchange.LENT
            2 -> Exchange.SWAPPED
            else -> Exchange.SOLD
        }
        Exchange("closed-${it.id}", it.title, it.price, kind, null, "Today", it.meetup, 0, 0)
    } + COMPLETED

    fun exchange(id: String?): Exchange = completed().firstOrNull { it.id == id } ?: COMPLETED.first()

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
     * Your own listings live under Profile > Active instead.
     */
    fun open(): List<Listing> = ALL.filter { !isMine(it) && !UserState.isClosed(it.id) }

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
 * Which listings the student saved or reserved during this session.
 * Kept in memory only: it resets when the app is closed, which is fine for
 * a front-end prototype. The backend will own this later.
 */
object UserState {

    private val saved = mutableSetOf("item3", "item5")
    /** Listing id to meetup summary. Starts with the sample reservations. */
    private val reserved = mutableMapOf(
        "item2" to "UC Main Gate Security Desk \u2022 Tomorrow, 1:30 PM",
        "item5" to "UC Library Lobby \u2022 Return by Friday",
        "item14" to "Your Calculus Reviewer for their Arduino Starter Kit"
    )
    private val closed = mutableSetOf<String>()

    /** Settings > Default meetup point. Used to prefill new listings. */
    var defaultMeetup = 0

    fun saved(): List<Listing> = Listings.ALL.filter { it.id in saved }

    fun isSaved(id: String) = id in saved

    fun setSaved(id: String, on: Boolean) {
        if (on) saved.add(id) else saved.remove(id)
    }

    fun isReserved(id: String) = id in reserved

    fun reservation(id: String): String? = reserved[id]

    /** Listings the student reserved from others, oldest first. */
    fun reservedListings(): List<Listing> = reserved.keys.map { Listings.byId(it) }

    fun setReserved(id: String, detail: String) {
        reserved[id] = detail
    }

    fun cancelReservation(id: String) {
        reserved.remove(id)
    }

    /** Own listings marked as sold, rented out or swapped. */
    fun isClosed(id: String) = id in closed

    fun close(id: String) {
        closed.add(id)
    }
}
