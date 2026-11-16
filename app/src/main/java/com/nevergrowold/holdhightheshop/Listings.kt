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

/** A finished exchange, shown under Profile > Completed. */
data class Exchange(val title: String, val price: Int, val summary: String)

object Listings {

    val PROGRAMS = listOf("CITCS", "CONAHS", "CEA", "CBA")

    private val paolo = Seller(
        "Paolo Mendoza", "PM", "CITCS", "3rd Year", "4.6", "92%", 14,
        "Yes, available pa! Meet tomorrow?", true
    )
    private val janelle = Seller(
        "Janelle Garcia", "JG", "CONAHS", "2nd Year", "4.9", "98%", 21,
        "Thank you ulit, Mika!", false
    )
    private val miguel = Seller(
        "Miguel Santos", "MS", "CEA", "4th Year", "4.4", "88%", 9,
        "Can I see more photos of the gown?", true
    )
    private val aira = Seller(
        "Aira Villanueva", "AV", "CBA", "1st Year", "4.8", "95%", 6,
        "Transaction confirmed", false
    )

    val SELLERS = listOf(paolo, janelle, miguel, aira)

    val ALL = listOf(
        Listing(
            "item1", "Engineering Drawing Set", 850, "CEA", 0, 0,
            "Complete drafting set with T-square, triangles and compass. Barely used, still in the original case.",
            miguel, "UC Main Gate Security Desk", 1
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
        )
    )

    /** Mika's own listings, shown under Profile > Active. */
    val MY_ACTIVE = listOf("item1", "item2", "item4")

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
 * Remembers which listings the student saved or reserved. Kept in
 * SharedPreferences so the state survives moving between screens and
 * restarting the app, which is enough for usability testing.
 */
object UserState {

    private const val PREFS = "user_state"
    private const val KEY_SAVED = "saved"
    private const val KEY_RESERVED = "reserved"
    private val DEFAULT_SAVED = setOf("item3", "item5")

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun savedIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SAVED, DEFAULT_SAVED) ?: DEFAULT_SAVED

    fun saved(context: Context): List<Listing> {
        val ids = savedIds(context)
        return Listings.ALL.filter { it.id in ids }
    }

    fun isSaved(context: Context, id: String) = id in savedIds(context)

    fun setSaved(context: Context, id: String, saved: Boolean) {
        val ids = savedIds(context).toMutableSet()
        if (saved) ids.add(id) else ids.remove(id)
        prefs(context).edit().putStringSet(KEY_SAVED, ids).apply()
    }

    fun isReserved(context: Context, id: String) =
        id in (prefs(context).getStringSet(KEY_RESERVED, emptySet()) ?: emptySet())

    fun setReserved(context: Context, id: String) {
        val ids = (prefs(context).getStringSet(KEY_RESERVED, emptySet()) ?: emptySet()).toMutableSet()
        ids.add(id)
        prefs(context).edit().putStringSet(KEY_RESERVED, ids).apply()
    }
}
