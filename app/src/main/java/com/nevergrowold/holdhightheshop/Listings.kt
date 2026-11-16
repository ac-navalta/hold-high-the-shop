package com.nevergrowold.holdhightheshop

import android.content.Context
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
    val postedDaysAgo: Int
)

/** A college, shown as a Home tile, a Market chip and a Create listing option. */
data class Program(val code: String, val icon: Int)

/** A finished exchange, shown under Profile > Completed. */
data class Exchange(val title: String, val price: Int, val summary: String)

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

    val SELLERS = listOf(paolo, janelle, miguel, aira)

    val ALL = listOf(
        Listing(
            "item1", "Engineering Drawing Set", 850, "COE", 0, 0,
            "Complete drafting set with T-square, triangles and compass. Barely used, still in the original case.",
            ME, "UC Main Gate Security Desk", 1
        ),
        Listing(
            "item2", "Casio fx-991EX", 1200, "CITCS", 1, 0,
            "Fully working scientific calculator. Used for two semesters only. Comes with original hard case and manual.",
            paolo, "UC Main Gate Security Desk", 0
        ),
        Listing(
            "item3", "Anatomy & Physiology", 650, "CONAHS", 1, 0,
            "Latest edition. A few highlighted pages but no torn or missing pages.",
            janelle, "UC Library Lobby", 2
        ),
        Listing(
            "item4", "UC PE Uniform", 450, "CBA", 0, 1,
            "Size M shirt and jogging pants. Washed and ironed. Available to rent for the semester.",
            aira, "UC Canteen Area", 3
        ),
        Listing(
            "item5", "White Lab Gown", 380, "CONAHS", 1, 1,
            "Size S, long sleeves. Clean and without stains. Rent for a week or the whole term.",
            janelle, "UC Library Lobby", 4
        ),
        Listing(
            "item6", "Data Structures Book", 520, "CITCS", 2, 2,
            "Some notes in pencil on the early chapters. Open to swapping for an Algorithms book.",
            paolo, "CITCS Building Entrance", 5
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
            janelle, "UC Library Lobby", 8
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
        )
    )

    fun isMine(listing: Listing) = listing.seller == ME

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
    val COMPLETED = listOf(
        Exchange("Calculus Textbook", 600, "Sold to Janelle Garcia • Nov 12"),
        Exchange("UC Type A Uniform", 500, "Sold to Aira Villanueva • Nov 9"),
        Exchange("Lab Goggles", 150, "Bought from Miguel Santos • Nov 5"),
        Exchange("Drafting Table Lamp", 400, "Rented to Miguel Santos • Nov 1"),
        Exchange("Intro to Programming", 450, "Swapped with Paolo Mendoza • Oct 28"),
        Exchange("Nursing Shoes", 700, "Sold to Janelle Garcia • Oct 24"),
        Exchange("Accounting Workbook", 300, "Bought from Aira Villanueva • Oct 20"),
        Exchange("Stethoscope", 950, "Sold to Janelle Garcia • Oct 15"),
        Exchange("Scientific Calculator", 800, "Sold to Miguel Santos • Oct 11"),
        Exchange("UC Lanyard and ID Case", 80, "Sold to Paolo Mendoza • Oct 6"),
        Exchange("Physics Reviewer", 250, "Bought from Paolo Mendoza • Oct 2"),
        Exchange("Engineering Ruler Set", 200, "Sold to Miguel Santos • Sep 27"),
        Exchange("Business Math Book", 350, "Swapped with Aira Villanueva • Sep 22"),
        Exchange("Lab Gown (Size M)", 400, "Sold to Janelle Garcia • Sep 18")
    )

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

    /** What the market shows: everything not yet sold, rented out or swapped. */
    fun open(): List<Listing> = ALL.filter { !UserState.isClosed(it.id) }

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
        "item5" to "UC Library Lobby \u2022 Return by Friday"
    )
    private val closed = mutableSetOf<String>()

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
