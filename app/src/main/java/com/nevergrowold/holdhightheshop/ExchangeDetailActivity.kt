package com.nevergrowold.holdhightheshop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible

/**
 * Transaction details for a finished exchange, opened from a row in
 * Profile > Completed. Not in the Figma flow; follows the same cards,
 * labels and buttons as the listing detail screen.
 */
class ExchangeDetailActivity : BaseActivity() {

    private lateinit var exchange: Exchange
    private var referenceCode: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_exchange_detail)
        BarInsets.apply(this)

        val exId = intent.getStringExtra(EXTRA_ID) ?: intent.getStringExtra(EXTRA_EXCHANGE)
        val loaded = Listings.exchange(exId)
        if (loaded == null) {
            finish()
            return
        }
        exchange = loaded
        bind()
    }

    private fun bind() {
        val partner = exchange.partner

        styleBadge(
            findViewById(R.id.exBadge), ExchangeText.badge(this, exchange), exchange.giving,
            exchange.kind == Exchange.SWAPPED
        )
        findViewById<TextView>(R.id.exTitle).text = exchange.title
        findViewById<TextView>(R.id.exPrice).text = Listings.price(exchange.price)
        findViewById<TextView>(R.id.exDate).text = getString(R.string.ex_completed_on, ExchangeText.date(this, exchange))

        referenceCode = "HTS-%05d".format(23000 + exchange.id.hashCode().mod(1000))
        findViewById<TextView>(R.id.exReference).text = referenceCode
        findViewById<TextView>(R.id.exMeetup).text = exchange.meetup
        findViewById<TextView>(R.id.exPayment).text = when (exchange.kind) {
            Exchange.SWAPPED -> getString(R.string.ex_no_payment)
            Exchange.LENT, Exchange.BORROWED -> getString(R.string.ex_rent_fee, Listings.price(exchange.price))
            else -> getString(R.string.ex_paid_in_person, Listings.price(exchange.price))
        }
        findViewById<TextView>(R.id.exHandover).setText(
            when {
                partner == null -> R.string.ex_handover_none
                exchange.proof == Exchange.PROOF_MANUAL -> R.string.ex_handover_manual
                exchange.proof == Exchange.PROOF_RECEIVED -> R.string.ex_handover_received
                else -> R.string.ex_handover_code
            }
        )
        val issue = UserState.issue(exchange.id)
        findViewById<View>(R.id.exProblemRow).isVisible = issue != null
        if (issue != null) findViewById<TextView>(R.id.exProblem).text =
            getString(R.string.ex_problem_value, getString(issue.reason), issue.ref)

        bindTimeline()

        // A listing you closed yourself has nobody attached yet.
        listOf(R.id.exWithLabel, R.id.exPartnerCard, R.id.bottomNav, R.id.exRatings)
            .forEach { findViewById<View>(it).isVisible = partner != null }
        findViewById<View>(R.id.exNoPartnerNote).isVisible = partner == null
        findViewById<View>(R.id.exRateHint).isVisible = partner != null
        if (partner == null) return

        val first = partner.first
        findViewById<TextView>(R.id.exPartnerInitials).text = partner.initials
        findViewById<TextView>(R.id.exPartnerName).text = partner.name
        findViewById<TextView>(R.id.exPartnerMeta).text = getString(R.string.seller_meta_format, partner.program, partner.year)
        findViewById<TextView>(R.id.exTheirLabel).text = getString(R.string.ex_their_rating, first)
        findViewById<TextView>(R.id.exYourLabel).text = getString(R.string.ex_your_rating, first)

        // Blind ratings: theirs shows once you have rated, or once the week is up.
        val yours = Reliability.yourStars(exchange)
        when {
            Reliability.theirVisible(exchange) -> stars(findViewById(R.id.exTheirStars), exchange.theirRating)
            exchange.theirRating > 0 -> note(findViewById(R.id.exTheirStars), getString(R.string.ex_rating_hidden))
            else -> stars(findViewById(R.id.exTheirStars), 0)
        }
        stars(findViewById(R.id.exYourStars), yours)
        findViewById<TextView>(R.id.exRateHint).text = when {
            Reliability.canRate(exchange) -> getString(R.string.ex_rate_by, Rentals.dateLabel(Reliability.ratingClosesAt(exchange)))
            yours > 0 -> UserState.rating(exchange.id)?.second?.takeIf { it.isNotEmpty() }
                ?.let { getString(R.string.ex_your_note, it) } ?: getString(R.string.ex_rating_done)
            else -> getString(R.string.ex_rating_closed)
        }

        val canRate = Reliability.canRate(exchange)
        val message = getString(R.string.btn_message_seller, first)
        findViewById<TextView>(R.id.exMessage).apply { text = message; isVisible = !canRate }
        findViewById<TextView>(R.id.exMessageOutline).apply { text = message; isVisible = canRate }
        findViewById<TextView>(R.id.exRate).apply {
            text = getString(R.string.btn_rate_format, first)
            isVisible = canRate
        }
        findViewById<View>(R.id.exProblemButton).isVisible = issue == null
    }

    /** Every step of the deal, oldest first. */
    private fun bindTimeline() {
        val box = findViewById<LinearLayout>(R.id.exTimeline)
        box.removeAllViews()
        val events = Deals.timeline(exchange).toMutableList()
        UserState.rating(exchange.id)?.let { events += DealEvent(R.string.ev_you_rated, UserState.ratedAt(exchange.id), exchange.partner?.first) }
        UserState.issue(exchange.id)?.let { events += DealEvent(R.string.ev_problem_reported, it.millis, it.ref) }
        events.sortedBy { it.millis }.forEach { event ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_timeline, box, false)
            row.findViewById<TextView>(R.id.timelineText).text =
                if (event.arg != null) getString(event.text, event.arg) else getString(event.text)
            row.findViewById<TextView>(R.id.timelineTime).text = Rentals.whenLabel(this, event.millis)
            box.addView(row)
        }
    }

    /** Five stars, the first [count] filled; "Not rated yet" when zero. */
    private fun stars(row: LinearLayout, count: Int) {
        row.removeAllViews()
        if (count == 0) {
            note(row, getString(R.string.ex_not_rated))
            return
        }
        row.contentDescription = getString(R.string.cd_stars, count)
        repeat(5) { i ->
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_star)
                setColorFilter(getColor(if (i < count) R.color.alert_dot else R.color.divider))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(dp(this@ExchangeDetailActivity, 18), dp(this@ExchangeDetailActivity, 18))
            })
        }
    }

    private fun note(row: LinearLayout, text: String) {
        row.removeAllViews()
        row.contentDescription = null
        row.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
        })
    }

    /** Shares the transaction receipt via implicit ACTION_SEND intent. */
    fun shareReceipt(view: View) {
        Motion.tick(view)
        AppIntents.launch(this, AppIntents.shareReceiptChooser(this, exchange, referenceCode))
    }

    /** Copies the transaction reference code to clipboard with a Toast confirmation. */
    fun copyReference(view: View) {
        Motion.confirm(view)
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.ex_reference), referenceCode))
        Toast.makeText(this, getString(R.string.toast_ref_copied, referenceCode), Toast.LENGTH_SHORT).show()
    }

    /** Opens the Safe Meetup Point bottom sheet (replaces legacy placeholder map). */
    fun openMeetupMap(view: View) {
        Motion.tick(view)
        showSafeMeetupSheet(exchange.meetup)
    }

    /** "With" card: the other student's profile. */
    fun openPartner(view: View) {
        exchange.partner?.let { openSeller(it) }
    }

    fun messagePartner(view: View) {
        exchange.partner?.let { openChatWith(it, exchange.listingId) }
    }

    /** Rate the other student: stars (required) and a note (optional). */
    fun rate(view: View) {
        val partner = exchange.partner ?: return
        var stars = 0
        Sheets.show(this, R.layout.sheet_rate) { sheet, dialog ->
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            sheet.findViewById<TextView>(R.id.rateTitle).text = getString(R.string.rate_title, partner.first)
            sheet.findViewById<TextView>(R.id.rateItem).text = ExchangeText.summary(this, exchange) + " \u2022 " + exchange.title
            sheet.findViewById<TextView>(R.id.rateBlind).text = getString(R.string.rate_blind, partner.first)
            val word = sheet.findViewById<TextView>(R.id.rateWord)
            val words = resources.getStringArray(R.array.rate_words)
            val box = sheet.findViewById<LinearLayout>(R.id.rateStars)
            val icons = (1..5).map { n ->
                ImageView(this).apply {
                    setImageResource(R.drawable.ic_star)
                    contentDescription = getString(R.string.cd_stars, n)
                    val size = dp(this@ExchangeDetailActivity, 44)
                    layoutParams = LinearLayout.LayoutParams(size, size)
                    setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6))
                    background = null
                    isClickable = true
                    isFocusable = true
                    box.addView(this)
                }
            }
            var lastStars = 0
            fun paint(animate: Boolean) {
                val gold = getColor(R.color.alert_dot)
                val muted = getColor(R.color.divider)
                icons.forEachIndexed { i, icon ->
                    val lit = i < stars
                    val wasLit = i < lastStars
                    if (!animate || lit != wasLit) {
                        Motion.iconTint(icon, if (lit) muted else gold, if (lit) gold else muted, animate)
                    }
                    if (animate && lit && Motion.enabled(this)) {
                        icon.postDelayed({
                            if (!icon.isAttachedToWindow) return@postDelayed
                            icon.scaleX = 0.80f
                            icon.scaleY = 0.80f
                            Motion.springScale(icon, 1f, Motion.Spatial.EXPRESSIVE)
                        }, i * 24L)
                    }
                }
                lastStars = stars
                val nextLabel = if (stars == 0) getString(R.string.rate_pick) else words[stars - 1]
                if (animate) Motion.rollText(word, nextLabel) else word.text = nextLabel
                Motion.textColor(word, getColor(if (stars == 0) R.color.text_secondary else R.color.text_primary), animate)
            }
            icons.forEachIndexed { i, icon ->
                icon.setOnClickListener {
                    stars = i + 1
                    Motion.tick(icon)
                    paint(animate = true)
                }
            }
            paint(animate = false)
            val note = sheet.findViewById<EditText>(R.id.rateNote)
            sheet.findViewById<View>(R.id.rateSubmit).setOnClickListener { submitBtn ->
                if (stars == 0) {
                    Motion.shake(box)
                    Toast.makeText(this, R.string.toast_pick_stars, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                hideKeyboard(note)
                Motion.confirm(submitBtn)
                UserState.rate(exchange.id, stars, note.text.toString().trim())
                dialog.dismiss()
                Motion.layoutTransition(findViewById(R.id.main), durationMs = 220L)
                bind()
                notify(
                    if (exchange.theirRating > 0) getString(R.string.toast_rated_revealed, partner.first)
                    else getString(R.string.toast_rated, partner.first)
                )
            }
        }
    }

    /** Flag in the bottom bar: something was wrong with this exchange. */
    fun reportExchangeProblem(view: View) {
        val reasons = listOf(
            R.string.problem_not_as_described, R.string.problem_damaged, R.string.problem_payment, R.string.problem_other
        )
        reportProblem(exchange.id, exchange.title, exchange.partner, reasons) {
            Motion.layoutTransition(findViewById(R.id.main), durationMs = 220L)
            bind()
        }
    }

    companion object {
        const val EXTRA_EXCHANGE = "exchange"
        const val EXTRA_ID = EXTRA_EXCHANGE
    }
}

/** Shared wording for exchanges, used by the Completed list and the details screen. */
object ExchangeText {

    fun summary(context: Context, exchange: Exchange): String {
        val who = exchange.partner?.name ?: return context.getString(
            R.string.ex_summary_format, context.getString(R.string.ex_closed_by_you), date(context, exchange)
        )
        val action = context.getString(
            when (exchange.kind) {
                Exchange.SOLD -> R.string.ex_sold_to
                Exchange.BOUGHT -> R.string.ex_bought_from
                Exchange.LENT -> R.string.ex_lent_to
                Exchange.BORROWED -> R.string.ex_borrowed_from
                else -> R.string.ex_swapped_with
            },
            who
        )
        return context.getString(R.string.ex_summary_format, action, date(context, exchange))
    }

    /** "Nov 12", or "Today". */
    fun date(context: Context, exchange: Exchange): String = Rentals.shortDate(context, exchange.finishedMillis)

    fun badge(context: Context, exchange: Exchange): String = context.getString(
        when (exchange.kind) {
            Exchange.SOLD -> R.string.badge_sold
            Exchange.BOUGHT -> R.string.badge_bought
            Exchange.LENT -> R.string.badge_lent
            Exchange.BORROWED -> R.string.badge_borrowed
            else -> R.string.badge_swapped
        }
    )
}
