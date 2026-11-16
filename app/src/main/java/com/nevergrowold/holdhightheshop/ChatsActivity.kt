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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_chats)
        BarInsets.apply(this, hideBottomOnKeyboard = true)

        rows = listOf(R.id.chatRow1, R.id.chatRow2, R.id.chatRow3, R.id.chatRow4).map { findViewById(it) }
        dividers = listOf(R.id.chatDivider1, R.id.chatDivider2, R.id.chatDivider3).map { findViewById(it) }
        search = findViewById(R.id.inputChatSearch)

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
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

    /** Shows only the conversations whose name, program or last message match. */
    private fun filter(query: String) {
        val q = query.trim().lowercase()
        val visible = rows.map { row ->
            val match = q.isEmpty() || textOf(row).lowercase().contains(q)
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
