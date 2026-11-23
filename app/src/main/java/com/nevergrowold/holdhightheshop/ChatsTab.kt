package com.nevergrowold.holdhightheshop

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Dynamic per-listing Chats tab hosted inside [MainActivity].
 * Renders real conversations for the signed-in student with role filters,
 * live search, unread dots, and per-listing context badges.
 */
internal class ChatsTab(
    private val activity: MainActivity,
    val root: View,
) {
    private val header: View = root.findViewById(R.id.headerArea)
    private val headerBaseTop: Int = header.paddingTop
    private val listContainer: LinearLayout = root.findViewById(R.id.chatsList)
    private val search: EditText = root.findViewById(R.id.inputChatSearch)

    /** 0 = All, 1 = Buying & borrowing, 2 = Selling & lending, 3 = Swaps. */
    private var roleFilter = 0

    init {
        bindRoleChips()
        refresh()

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = render(animate = false)
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

    fun refresh() = render(animate = false)

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
            render(animate = true)
        }
    }

    private fun render(animate: Boolean) {
        if (animate) Motion.layoutTransition(root.findViewById(R.id.chatsContent), durationMs = 200L)
        val myUid = UserState.currentUid
        val q = search.text.toString().trim().lowercase()
        val allThreads = UserState.chatThreads()

        val filtered = allThreads.filter { thread ->
            val listing = Listings.byIdOrNull(thread.listingId)
            val isMine = thread.ownerUid == myUid
            val isSwap = listing?.isSwap == true
            val roleOk = when (roleFilter) {
                1 -> !isSwap && !isMine
                2 -> !isSwap && isMine
                3 -> isSwap
                else -> true
            }
            val partnerName = thread.partnerName(myUid)
            val listingTitle = listing?.title.orEmpty()
            val match = roleOk && (
                q.isEmpty() ||
                    partnerName.lowercase().contains(q) ||
                    listingTitle.lowercase().contains(q) ||
                    thread.lastMessagePreview.lowercase().contains(q)
                )
            match
        }

        listContainer.removeAllViews()
        filtered.forEachIndexed { idx, thread ->
            val row = LayoutInflater.from(activity).inflate(R.layout.item_chat_row, listContainer, false)
            val partnerUid = thread.partnerUid(myUid)
            val partnerName = thread.partnerName(myUid).ifBlank { "UC Student" }
            val partnerProgram = thread.partnerProgram(myUid).ifBlank { "UC" }
            val partnerSeller = Listings.sellerByUid(partnerUid)
            val listing = Listings.byIdOrNull(thread.listingId)
            val unread = thread.isUnreadFor(myUid)

            row.findViewById<TextView>(R.id.chatInitials).text =
                partnerSeller?.initials ?: UserState.UserProfile(name = partnerName).initials
            row.findViewById<View>(R.id.chatOnlineDot).isVisible = partnerSeller?.online == true
            row.findViewById<TextView>(R.id.chatPartnerName).text = partnerName

            val badge = row.findViewById<TextView>(R.id.chatRoleBadge)
            if (listing != null) {
                activity.styleBadge(badge, Listings.role(activity, listing), Listings.isMine(listing), listing.isSwap)
            } else {
                badge.isVisible = false
            }

            row.findViewById<TextView>(R.id.chatListingMeta).text =
                if (listing != null) "$partnerProgram \u2022 ${listing.title}" else partnerProgram

            row.findViewById<TextView>(R.id.chatLastPreview).apply {
                text = thread.lastMessagePreview.ifBlank { "Tap to start chatting" }
                setTextColor(activity.getColor(if (unread) R.color.text_primary else R.color.text_secondary))
            }
            row.findViewById<TextView>(R.id.chatTime).text = formatThreadTime(thread.lastMessageMillis)
            row.findViewById<View>(R.id.chatUnreadDot).isVisible = unread
            row.findViewById<View>(R.id.chatRowDivider).isVisible = idx < filtered.lastIndex

            row.findViewById<View>(R.id.chatRowBody).setOnClickListener {
                activity.openChatThread(thread.threadId, thread.listingId)
            }
            listContainer.addView(row)
        }

        val empty = root.findViewById<TextView>(R.id.chatsEmpty)
        empty.isVisible = filtered.isEmpty()
        empty.setText(if (allThreads.isEmpty()) R.string.chats_empty_new else R.string.chats_empty)
    }

    private fun formatThreadTime(millis: Long): String {
        val diff = System.currentTimeMillis() - millis
        return when {
            diff < 60_000L -> activity.getString(R.string.msg_sent_now)
            Rentals.daysUntil(millis) == 0 -> SimpleDateFormat("h:mm a", Locale.US).format(millis)
            Rentals.daysUntil(millis) == -1 -> activity.getString(R.string.day_yesterday_label)
            else -> SimpleDateFormat("MMM d", Locale.US).format(millis)
        }
    }

    /** Legacy XML click hook kept safe for compatibility. */
    fun openThread(view: View) {
        val threadId = view.tag as? String ?: return
        activity.openChatThread(threadId)
    }
}
