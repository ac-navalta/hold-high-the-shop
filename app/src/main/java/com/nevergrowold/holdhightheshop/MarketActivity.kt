package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible

/**
 * Campus Market. The grid is built from [Listings] so the search box,
 * program chips, filters sheet and sort menu can all narrow or reorder it.
 */
class MarketActivity : BaseActivity() {

    private lateinit var search: EditText
    private lateinit var grid: LinearLayout
    private lateinit var chips: List<TextView>

    /** -1 means "All". Otherwise an index into [Listings.PROGRAMS]. */
    private var program = -1
    private var sort = 0
    private var filters = Filters()

    /** What the Filters sheet can narrow by. Empty sets mean "any". */
    data class Filters(
        val conditions: Set<Int> = emptySet(),
        val types: Set<Int> = emptySet(),
        val price: Int = 0
    ) {
        val isActive get() = conditions.isNotEmpty() || types.isNotEmpty() || price != 0

        fun matches(listing: Listing): Boolean =
            (conditions.isEmpty() || listing.condition in conditions) &&
                (types.isEmpty() || listing.type in types) &&
                when (price) {
                    1 -> listing.price < 500
                    2 -> listing.price in 500..1000
                    3 -> listing.price > 1000
                    else -> true
                }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_market)
        BarInsets.apply(this, hideBottomOnKeyboard = true)

        search = findViewById(R.id.inputSearch)
        grid = findViewById(R.id.grid)
        chips = listOf(
            findViewById(R.id.chip1), findViewById(R.id.chip2), findViewById(R.id.chip3),
            findViewById(R.id.chip4), findViewById(R.id.chip5)
        )

        findViewById<View>(R.id.searchIcon).setOnClickListener { showKeyboard(search) }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                findViewById<View>(R.id.btnClearSearch).isVisible = !s.isNullOrEmpty()
                render()
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        search.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(v)
                v.clearFocus()
                true
            } else {
                false
            }
        }

        program = programIndex(intent.getStringExtra(EXTRA_PROGRAM))
        bindChips()
        render()
    }

    /** Arriving from a program tile on Home while the market is already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(EXTRA_PROGRAM)) {
            program = programIndex(intent.getStringExtra(EXTRA_PROGRAM))
            bindChips()
            render()
        }
    }

    private fun programIndex(name: String?): Int {
        // Chip order on screen: All, CITCS, CONAHS, CEA, CBA.
        return Listings.PROGRAMS.indexOf(name)
    }

    private fun bindChips() {
        Choice.chips(chips, program + 1) {
            program = it - 1
            render()
        }
    }

    /** Rebuilds the grid from the current search, chip, filters and sort. */
    private fun render() {
        val query = search.text.toString().trim().lowercase()
        val programName = Listings.PROGRAMS.getOrNull(program)

        var items = Listings.ALL.filter { listing ->
            (programName == null || listing.program == programName) &&
                filters.matches(listing) &&
                (query.isEmpty() ||
                    listing.title.lowercase().contains(query) ||
                    listing.program.lowercase().contains(query) ||
                    listing.description.lowercase().contains(query))
        }
        items = when (sort) {
            1 -> items.sortedBy { it.price }
            2 -> items.sortedByDescending { it.price }
            else -> items.sortedBy { it.postedDaysAgo }
        }

        grid.removeAllViews()
        items.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
            }
            pair.forEachIndexed { index, listing -> row.addView(card(listing, index)) }
            if (pair.size == 1) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            }
            grid.addView(row)
        }

        val narrowed = query.isNotEmpty() || programName != null || filters.isActive
        findViewById<TextView>(R.id.itemsCount).text = if (narrowed) {
            resources.getQuantityString(R.plurals.items_found, items.size, items.size)
        } else {
            getString(R.string.items_near_you)
        }
        findViewById<View>(R.id.emptyState).isVisible = items.isEmpty()
        findViewById<View>(R.id.filterDot).isVisible = filters.isActive
        findViewById<TextView>(R.id.sortLabel).text = sortOptions()[sort]
    }

    private fun card(listing: Listing, column: Int): View {
        val card = LayoutInflater.from(this).inflate(R.layout.item_listing_card, grid, false)
        card.findViewById<TextView>(R.id.cardPrice).text = Listings.price(listing.price)
        card.findViewById<TextView>(R.id.cardTitle).text = listing.title
        card.findViewById<TextView>(R.id.cardProgram).text = listing.program
        card.findViewById<TextView>(R.id.cardCondition).text = Listings.conditionLabel(this, listing.condition)
        card.setOnClickListener { openListingById(listing.id) }
        val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        if (column == 0) params.marginEnd = dp(this, 4) else params.marginStart = dp(this, 4)
        card.layoutParams = params
        return card
    }

    private fun sortOptions() = listOf(
        getString(R.string.sort_newest),
        getString(R.string.sort_price_low),
        getString(R.string.sort_price_high)
    )

    /** "Newest" dropdown. */
    fun openSort(view: View) {
        Dropdown.show(view, sortOptions(), sort, widthDp = 200, alignEnd = true) {
            sort = it
            render()
        }
    }

    fun clearSearch(view: View) {
        search.setText("")
    }

    fun clearAllFilters(view: View) {
        search.setText("")
        program = -1
        filters = Filters()
        bindChips()
        render()
    }

    /** Filters sheet: condition, listing type and price range. */
    fun openFilters(view: View) {
        hideKeyboard(view)
        var draft = filters
        Sheets.show(this, R.layout.sheet_filters) { sheet, dialog ->
            val conditions = listOf<TextView>(sheet.findViewById(R.id.fCond0), sheet.findViewById(R.id.fCond1), sheet.findViewById(R.id.fCond2))
            val types = listOf<TextView>(sheet.findViewById(R.id.fType0), sheet.findViewById(R.id.fType1), sheet.findViewById(R.id.fType2))
            val prices = listOf<TextView>(
                sheet.findViewById(R.id.fPrice0), sheet.findViewById(R.id.fPrice1),
                sheet.findViewById(R.id.fPrice2), sheet.findViewById(R.id.fPrice3)
            )
            val apply = sheet.findViewById<TextView>(R.id.filterApply)

            fun refresh() {
                Choice.toggles(conditions, draft.conditions) { draft = draft.copy(conditions = it); refresh() }
                Choice.toggles(types, draft.types) { draft = draft.copy(types = it); refresh() }
                Choice.chips(prices, draft.price) { draft = draft.copy(price = it); refresh() }
                val count = Listings.ALL.count {
                    draft.matches(it) && (program < 0 || it.program == Listings.PROGRAMS[program])
                }
                apply.text = resources.getQuantityString(R.plurals.filters_show_count, count, count)
            }
            refresh()

            sheet.findViewById<View>(R.id.filterReset).setOnClickListener {
                draft = Filters()
                refresh()
            }
            apply.setOnClickListener {
                filters = draft
                render()
                dialog.dismiss()
            }
        }
    }
}
