package com.nevergrowold.holdhightheshop

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updatePadding

/**
 * Chats tab page hosted inside [MainActivity].
 */
internal class ChatsTab(
    private val activity: MainActivity,
    val root: View,
) {
    private val header: View = root.findViewById(R.id.headerArea)
    private val headerBaseTop: Int = header.paddingTop
    private val rows: List<View> =
        listOf(R.id.chatRow1, R.id.chatRow2, R.id.chatRow3, R.id.chatRow4, R.id.chatRow5).map { root.findViewById(it) }
    private val dividers: List<View> =
        listOf(R.id.chatDivider1, R.id.chatDivider2, R.id.chatDivider3, R.id.chatDivider4).map { root.findViewById(it) }
    private val search: EditText = root.findViewById(R.id.inputChatSearch)

    /** 0 = All, 1 = Buying & borrowing, 2 = Selling & lending, 3 = Swaps. */
    private var roleFilter = 0

    init {
        val badges = listOf(R.id.chatBadge1, R.id.chatBadge2, R.id.chatBadge3, R.id.chatBadge4, R.id.chatBadge5)
        Listings.SELLERS.forEachIndexed { index, seller ->
            val listing = Listings.byId(Listings.CHAT_LISTINGS[seller])
            activity.styleBadge(
                root.findViewById(badges[index]),
                Listings.role(activity, listing),
                Listings.isMine(listing),
                listing.isSwap,
            )
        }
        bindRoleChips()
        refresh()

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = filter(animate = false)
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        search.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(v)
                true
            } else {
                false
            }
        }
    }

    fun applyTopInset(top: Int) {
        header.updatePadding(top = headerBaseTop + top)
    }

    /** Updates last-message previews, timestamps, and unread indicators after chatting. */
    fun refresh() {
        val lastIds = listOf(R.id.chatLast1, R.id.chatLast2, R.id.chatLast3, R.id.chatLast4, R.id.chatLast5)
        val timeIds = listOf(R.id.chatTime1, R.id.chatTime2, R.id.chatTime3, R.id.chatTime4, R.id.chatTime5)
        val unreadViews = mapOf(0 to R.id.chatUnread1, 2 to R.id.chatUnread3)

        Listings.SELLERS.forEachIndexed { index, seller ->
            val (preview, timeOverride) = UserState.lastChatPreview(seller)
            val lastView = root.findViewById<TextView>(lastIds[index])
            lastView.text = preview
            val unread = UserState.isChatUnread(index, seller.name)
            lastView.setTextColor(activity.getColor(if (unread) R.color.text_primary else R.color.text_secondary))
            unreadViews[index]?.let { dotId ->
                root.findViewById<View>(dotId)?.isVisible = unread
            }
            if (timeOverride != null) {
                root.findViewById<TextView>(timeIds[index]).text = timeOverride
            }
        }
        filter(animate = false)
    }

    fun onHide() {
        if (search.hasFocus()) {
            hideKeyboard(search)
            search.clearFocus()
        }
    }

    fun toggleSearch(view: View) {
        Motion.tick(view)
        val content = root.findViewById<ViewGroup>(R.id.chatsContent)
        Motion.layoutTransition(content, durationMs = 200L)
        val bar = root.findViewById<View>(R.id.chatSearchBar)
        if (bar.isVisible) {
            search.setText("")
            hideKeyboard(search)
            bar.isVisible = false
        } else {
            bar.isVisible = true
            showKeyboard(search)
        }
    }

    private fun bindRoleChips() {
        Choice.chips(
            listOf(
                root.findViewById(R.id.chatFilterAll),
                root.findViewById(R.id.chatFilterGetting),
                root.findViewById(R.id.chatFilterGiving),
                root.findViewById(R.id.chatFilterSwaps),
            ),
            roleFilter,
        ) {
            roleFilter = it
            filter(animate = true)
        }
    }

    private fun filter(animate: Boolean) {
        if (animate) Motion.layoutTransition(root.findViewById(R.id.chatsContent), durationMs = 210L)
        val q = search.text.toString().trim().lowercase()
        val visible = rows.mapIndexed { index, row ->
            val listing = Listings.byId(Listings.CHAT_LISTINGS[Listings.SELLERS[index]])
            val roleOk = when (roleFilter) {
                1 -> !listing.isSwap && !Listings.isMine(listing)
                2 -> !listing.isSwap && Listings.isMine(listing)
                3 -> listing.isSwap
                else -> true
            }
            val match = roleOk && (q.isEmpty() || textOf(row).lowercase().contains(q))
            row.isVisible = match
            match
        }
        dividers.forEachIndexed { index, divider ->
            divider.isVisible = visible[index] && visible.drop(index + 1).any { it }
        }
        root.findViewById<View>(R.id.chatsEmpty).isVisible = visible.none { it }
    }

    private fun textOf(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString(" ") { textOf(view.getChildAt(it)) }
        else -> ""
    }

    fun openThread(view: View) {
        val index = (view.tag as? String)?.toIntOrNull() ?: 0
        activity.openChatWith(Listings.SELLERS[index])
    }
}
