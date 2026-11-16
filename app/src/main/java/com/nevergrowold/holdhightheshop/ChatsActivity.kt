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

    /** 0 = All, 1 = Buying & borrowing, 2 = Selling & lending. */
    private var roleFilter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_chats)
        BarInsets.apply(this, hideBottomOnKeyboard = true)

        rows = listOf(R.id.chatRow1, R.id.chatRow2, R.id.chatRow3, R.id.chatRow4).map { findViewById(it) }
        dividers = listOf(R.id.chatDivider1, R.id.chatDivider2, R.id.chatDivider3).map { findViewById(it) }
        search = findViewById(R.id.inputChatSearch)

        // Tag each conversation with what you are doing in it.
        val badges = listOf(R.id.chatBadge1, R.id.chatBadge2, R.id.chatBadge3, R.id.chatBadge4)
        Listings.SELLERS.forEachIndexed { index, seller ->
            val listing = Listings.byId(Listings.CHAT_LISTINGS[seller])
            styleBadge(findViewById(badges[index]), Listings.role(this, listing), Listings.isMine(listing))
        }
        bindRoleChips()

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = filter()
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
            listOf(findViewById(R.id.chatFilterAll), findViewById(R.id.chatFilterGetting), findViewById(R.id.chatFilterGiving)),
            roleFilter
        ) {
            roleFilter = it
            filter()
        }
    }

    /**
     * Shows only the conversations that match the role chip and whose name,
     * program, item or last message contain the search text.
     */
    private fun filter() {
        val q = search.text.toString().trim().lowercase()
        val visible = rows.mapIndexed { index, row ->
            val giving = Listings.isMine(Listings.byId(Listings.CHAT_LISTINGS[Listings.SELLERS[index]]))
            val roleOk = roleFilter == 0 || (roleFilter == 2) == giving
            val match = roleOk && (q.isEmpty() || textOf(row).lowercase().contains(q))
            row.isVisible = match
            match
        }
        // A divider sits under each row except the last; keep it only between visible rows.
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
