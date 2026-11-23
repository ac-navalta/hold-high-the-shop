package com.nevergrowold.holdhightheshop

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.dynamicanimation.animation.DynamicAnimation

/**
 * Campus Market tab hosted inside [MainActivity].
 *
 * College chip bar order:
 * - Index 0: `All` (everything open)
 * - Index 1: `All colleges` (`Listings.FILTER_ALL_COLLEGES` -> universal untagged items)
 * - Index 2..12: The 11 UC Colleges (`CITCS`..`COL`). Selecting a college shows items
 *   explicitly tagged for that college FIRST, followed by `All colleges` items!
 */
internal class MarketTab(
    private val activity: MainActivity,
    val root: View,
) {
    private val header: View = root.findViewById(R.id.headerArea)
    private val headerBaseTop: Int = header.paddingTop
    val fab: View = root.findViewById(R.id.fabPost)

    private val search: EditText = root.findViewById(R.id.inputSearch)
    private val grid: LinearLayout = root.findViewById(R.id.grid)
    private val chipScroll: HorizontalScrollView = root.findViewById(R.id.chipScroll)
    private val chips: List<TextView>

    /** Chip filter codes matching [chips] 1:1. `null` = All. */
    private val chipFilters: List<String?> =
        listOf(null, Listings.FILTER_ALL_COLLEGES) + Listings.PROGRAMS

    private var selectedChipIndex = 0
    private var sort = 0
    private var filters = MarketActivity.Filters()
    private var lastOpenSig: List<String> = emptyList()

    init {
        val chipRow = root.findViewById<LinearLayout>(R.id.chipRow)
        val labels = listOf(
            activity.getString(R.string.chip_all),
            activity.getString(R.string.tag_all_colleges),
        ) + Listings.PROGRAMS

        chips = labels.map { label ->
            val chip = LayoutInflater.from(activity).inflate(R.layout.item_market_chip, chipRow, false) as TextView
            chip.text = label
            chipRow.addView(chip)
            chip
        }

        root.findViewById<View>(R.id.searchIcon).setOnClickListener { showKeyboard(search) }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                Motion.setVisiblePop(root.findViewById(R.id.btnClearSearch), !s.isNullOrEmpty())
                render(animate = false)
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

        bindChips()
        render(animate = false)
        Motion.hideOnScroll(root.findViewById(R.id.marketScroll), fab)
    }

    fun applyTopInset(top: Int) {
        header.updatePadding(top = headerBaseTop + top)
    }

    fun selectProgram(programCode: String?) {
        selectedChipIndex = when {
            programCode.isNullOrBlank() -> 0
            programCode == Listings.FILTER_ALL_COLLEGES -> 1
            else -> chipFilters.indexOf(programCode).coerceAtLeast(0)
        }
        bindChips()
        scrollSelectedChipIntoView(smooth = true)
        render(animate = true)
    }

    fun setSearchQuery(query: String) {
        search.setText(query)
        search.setSelection(search.text.length)
        render(animate = true)
    }

    private fun openSignature(list: List<Listing>): List<String> =
        list.map { "${it.id}:${it.title}:${it.price}:${it.condition}:${it.type}:${it.targetPrograms}:${it.photoUris.firstOrNull()}" }

    fun refresh() {
        val currentSig = openSignature(Listings.open())
        if (currentSig != lastOpenSig) render(animate = false)
    }

    fun onHide() {
        if (search.hasFocus()) {
            hideKeyboard(search)
            search.clearFocus()
        }
    }

    private fun bindChips() {
        Choice.chips(chips, selectedChipIndex) {
            selectedChipIndex = it
            scrollSelectedChipIntoView(smooth = true)
            render(animate = true)
        }
    }

    private fun scrollSelectedChipIntoView(smooth: Boolean) {
        val chip = chips.getOrNull(selectedChipIndex) ?: return
        chipScroll.post {
            val targetX = (chip.left + chip.width / 2) - (chipScroll.width / 2)
            val clamped = targetX.coerceAtLeast(0)
            if (smooth && Motion.enabled(activity)) chipScroll.smoothScrollTo(clamped, 0)
            else chipScroll.scrollTo(clamped, 0)
        }
    }

    private fun render(animate: Boolean = true) {
        val query = search.text.toString().trim().lowercase()
        val selectedFilter = chipFilters.getOrNull(selectedChipIndex)
        val openListings = Listings.open()
        lastOpenSig = openSignature(openListings)

        var items = openListings.filter { listing ->
            listing.matchesProgramFilter(selectedFilter) &&
                filters.matches(listing) &&
                (query.isEmpty() ||
                    listing.title.lowercase().contains(query) ||
                    listing.program.lowercase().contains(query) ||
                    listing.targetPrograms.any { it.lowercase().contains(query) } ||
                    listing.description.lowercase().contains(query) ||
                    listing.meetup.lowercase().contains(query) ||
                    listing.seller.name.lowercase().contains(query))
        }

        // When filtering by a specific college, rank explicitly tagged listings ahead of "All colleges" items.
        val isSpecificCollege = !selectedFilter.isNullOrBlank() && selectedFilter != Listings.FILTER_ALL_COLLEGES
        items = when (sort) {
            1 -> if (isSpecificCollege) {
                items.sortedWith(compareByDescending<Listing> { it.isExplicitlyTaggedFor(selectedFilter) }.thenBy { it.price })
            } else {
                items.sortedBy { it.price }
            }
            2 -> if (isSpecificCollege) {
                items.sortedWith(compareByDescending<Listing> { it.isExplicitlyTaggedFor(selectedFilter) }.thenByDescending { it.price })
            } else {
                items.sortedByDescending { it.price }
            }
            else -> if (isSpecificCollege) {
                items.sortedWith(compareByDescending<Listing> { it.isExplicitlyTaggedFor(selectedFilter) }.thenByDescending { it.createdAt })
            } else {
                items.sortedByDescending { it.createdAt }
            }
        }

        grid.removeAllViews()
        val cards = mutableListOf<View>()
        items.chunked(2).forEach { pair ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
            }
            pair.forEachIndexed { index, listing -> row.addView(card(listing, index).also { cards += it }) }
            if (grid.childCount > 0) {
                row.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = activity.resources.getDimensionPixelSize(R.dimen.grid_gap) }
            }
            if (pair.size == 1) {
                row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            }
            grid.addView(row)
        }

        if (animate) Motion.staggerIn(cards, riseDp = 10f, gapMs = 22L, subtleScale = true)

        Motion.rollText(
            root.findViewById(R.id.itemsCount),
            activity.resources.getQuantityString(R.plurals.items_found, items.size, items.size),
        )

        val empty = root.findViewById<View>(R.id.emptyState)
        val wasEmpty = empty.isVisible
        empty.isVisible = items.isEmpty()
        if (items.isEmpty() && !wasEmpty && animate && Motion.enabled(activity)) {
            val dp = activity.resources.displayMetrics.density
            empty.alpha = 0f
            empty.scaleX = 0.95f
            empty.scaleY = 0.95f
            empty.translationY = 10f * dp
            Motion.spring(empty, DynamicAnimation.ALPHA, 1f, Motion.Effects.DEFAULT)
            Motion.spring(empty, DynamicAnimation.TRANSLATION_Y, 0f, Motion.Spatial.RESPONSIVE)
            Motion.springScale(empty, 1f, Motion.Spatial.RESPONSIVE)
        }

        Motion.setVisiblePop(root.findViewById(R.id.filterDot), filters.isActive)
        root.findViewById<TextView>(R.id.sortLabel).text = sortOptions()[sort]
    }

    private fun card(listing: Listing, column: Int): View {
        val card = activity.listingCard(grid, listing)
        val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val half = activity.resources.getDimensionPixelSize(R.dimen.half_gap)
        if (column == 0) params.marginEnd = half else params.marginStart = half
        card.layoutParams = params
        return card
    }

    private fun sortOptions() = listOf(
        activity.getString(R.string.sort_newest),
        activity.getString(R.string.sort_price_low),
        activity.getString(R.string.sort_price_high),
    )

    fun openSort(view: View) {
        Dropdown.show(view, sortOptions(), sort, widthDp = 200, alignEnd = true, rotateChevron = true) {
            if (sort != it) {
                sort = it
                render(animate = true)
            }
        }
    }

    fun clearSearch(view: View) {
        Motion.tick(view)
        search.setText("")
    }

    fun clearAllFilters(view: View) {
        Motion.tick(view)
        search.setText("")
        selectedChipIndex = 0
        filters = MarketActivity.Filters()
        bindChips()
        scrollSelectedChipIntoView(smooth = true)
        render(animate = true)
    }

    fun openFilters(view: View) {
        var draft = filters
        val selectedFilter = chipFilters.getOrNull(selectedChipIndex)
        Sheets.show(activity, R.layout.sheet_filters) { sheet, dialog ->
            val conditions = listOf<TextView>(
                sheet.findViewById(R.id.fCond0),
                sheet.findViewById(R.id.fCond1),
                sheet.findViewById(R.id.fCond2),
            )
            val types = listOf<TextView>(
                sheet.findViewById(R.id.fType0),
                sheet.findViewById(R.id.fType1),
                sheet.findViewById(R.id.fType2),
            )
            val prices = listOf<TextView>(
                sheet.findViewById(R.id.fPrice0),
                sheet.findViewById(R.id.fPrice1),
                sheet.findViewById(R.id.fPrice2),
                sheet.findViewById(R.id.fPrice3),
            )
            val apply = sheet.findViewById<TextView>(R.id.filterApply)

            fun refreshDraft() {
                Choice.toggles(conditions, draft.conditions) { draft = draft.copy(conditions = it); refreshDraft() }
                Choice.toggles(types, draft.types) { draft = draft.copy(types = it); refreshDraft() }
                Choice.chips(prices, draft.price) { draft = draft.copy(price = it); refreshDraft() }
                val count = Listings.open().count {
                    draft.matches(it) && it.matchesProgramFilter(selectedFilter)
                }
                Motion.rollText(apply, activity.resources.getQuantityString(R.plurals.filters_show_count, count, count))
            }
            refreshDraft()

            sheet.findViewById<View>(R.id.filterReset).setOnClickListener { resetBtn ->
                Motion.tick(resetBtn)
                draft = MarketActivity.Filters()
                refreshDraft()
            }
            apply.setOnClickListener {
                Motion.confirm(it)
                filters = draft
                render(animate = true)
                dialog.dismiss()
            }
        }
    }
}
