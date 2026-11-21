package com.nevergrowold.holdhightheshop

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible

class ChatsActivity : BaseActivity() {

    private lateinit var rows: List<View>
    private lateinit var dividers: List<View>
    private lateinit var search: EditText

    /** 0 = All, 1 = Buying & borrowing, 2 = Selling & lending, 3 = Swaps. */
    private var roleFilter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_chats)
        BarInsets.apply(this, hideBottomOnKeyboard = true)

        rows = listOf(R.id.chatRow1, R.id.chatRow2, R.id.chatRow3, R.id.chatRow4, R.id.chatRow5).map { findViewById(it) }
        dividers = listOf(R.id.chatDivider1, R.id.chatDivider2, R.id.chatDivider3, R.id.chatDivider4).map { findViewById(it) }
        search = findViewById(R.id.inputChatSearch)

        // Tag each conversation with what you are doing in it.
        val badges = listOf(R.id.chatBadge1, R.id.chatBadge2, R.id.chatBadge3, R.id.chatBadge4, R.id.chatBadge5)
        Listings.SELLERS.forEachIndexed { index, seller ->
            val listing = Listings.byId(Listings.CHAT_LISTINGS[seller])
            styleBadge(findViewById(badges[index]), Listings.role(this, listing), Listings.isMine(listing), listing.isSwap)
        }
        bindRoleChips()

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

    /** Search icon in the header, and the close button inside the search bar. */
    fun toggleSearch(view: View) {
        Motion.tick(view)
        val content = findViewById<ViewGroup>(R.id.chatsContent)
        Motion.layoutTransition(content, durationMs = 200L)
        val bar = findViewById<View>(R.id.chatSearchBar)
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
                findViewById(R.id.chatFilterAll),
                findViewById(R.id.chatFilterGetting),
                findViewById(R.id.chatFilterGiving),
                findViewById(R.id.chatFilterSwaps),
            ),
            roleFilter,
        ) {
            roleFilter = it
            filter(animate = true)
        }
    }

    /**
     * Shows only the conversations that match the role chip and whose name,
     * program, item or last message contain the search text.
     */
    private fun filter(animate: Boolean) {
        if (animate) Motion.layoutTransition(findViewById(R.id.chatsContent), durationMs = 210L)
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
        findViewById<View>(R.id.chatsEmpty).isVisible = visible.none { it }
    }

    private fun textOf(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString(" ") { textOf(view.getChildAt(it)) }
        else -> ""
    }

    /** Opens a conversation. The row's tag is the person's index in the list. */
    fun openThread(view: View) {
        val index = (view.tag as? String)?.toIntOrNull() ?: 0
        openChatWith(Listings.SELLERS[index])
    }
}
