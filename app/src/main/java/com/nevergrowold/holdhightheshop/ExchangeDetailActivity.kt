package com.nevergrowold.holdhightheshop

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible

/**
 * Transaction details for a finished exchange, opened from a row in
 * Profile > Completed. Not in the Figma flow; follows the same cards,
 * labels and buttons as the listing detail screen.
 */
class ExchangeDetailActivity : BaseActivity() {

    private lateinit var exchange: Exchange

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_exchange_detail)
        BarInsets.apply(this)

        exchange = Listings.exchange(intent.getStringExtra(EXTRA_EXCHANGE))
        val partner = exchange.partner

        styleBadge(findViewById(R.id.exBadge), ExchangeText.badge(this, exchange), exchange.giving)
        findViewById<TextView>(R.id.exTitle).text = exchange.title
        findViewById<TextView>(R.id.exPrice).text = Listings.price(exchange.price)
        findViewById<TextView>(R.id.exDate).text = getString(R.string.ex_completed_on, exchange.date)

        findViewById<TextView>(R.id.exReference).text = "HTS-%05d".format(23000 + exchange.id.hashCode().mod(1000))
        findViewById<TextView>(R.id.exMeetup).text = exchange.meetup
        findViewById<TextView>(R.id.exPayment).text = when (exchange.kind) {
            Exchange.SWAPPED -> getString(R.string.ex_no_payment)
            Exchange.LENT, Exchange.BORROWED -> getString(R.string.ex_rent_fee, Listings.price(exchange.price))
            else -> getString(R.string.ex_paid_in_person, Listings.price(exchange.price))
        }

        // A listing you closed yourself has nobody attached yet.
        listOf(R.id.exWithLabel, R.id.exPartnerCard, R.id.bottomNav, R.id.exRatings)
            .forEach { findViewById<View>(it).isVisible = partner != null }
        findViewById<View>(R.id.exNoPartnerNote).isVisible = partner == null
        if (partner == null) return

        val first = partner.name.substringBefore(' ')
        findViewById<TextView>(R.id.exPartnerInitials).text = partner.initials
        findViewById<TextView>(R.id.exPartnerName).text = partner.name
        findViewById<TextView>(R.id.exPartnerMeta).text = getString(R.string.seller_meta_format, partner.program, partner.year)
        findViewById<TextView>(R.id.exTheirLabel).text = getString(R.string.ex_their_rating, first)
        findViewById<TextView>(R.id.exYourLabel).text = getString(R.string.ex_your_rating, first)
        stars(findViewById(R.id.exTheirStars), exchange.theirRating)
        stars(findViewById(R.id.exYourStars), exchange.yourRating)
        findViewById<TextView>(R.id.exMessage).text = getString(R.string.btn_message_seller, first)
    }

    /** Five stars, the first [count] filled; "Not rated yet" when zero. */
    private fun stars(row: LinearLayout, count: Int) {
        row.removeAllViews()
        if (count == 0) {
            row.addView(TextView(this).apply {
                setText(R.string.ex_not_rated)
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
            })
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

    /** "With" card: the other student's profile. */
    fun openPartner(view: View) {
        exchange.partner?.let { openSeller(it) }
    }

    fun messagePartner(view: View) {
        exchange.partner?.let { openChatWith(it) }
    }

    companion object {
        const val EXTRA_EXCHANGE = "exchange"
    }
}

/** Shared wording for exchanges, used by the Completed list and the details screen. */
object ExchangeText {

    fun summary(context: Context, exchange: Exchange): String {
        val who = exchange.partner?.name ?: return context.getString(
            R.string.ex_summary_format, context.getString(R.string.ex_closed_by_you), exchange.date
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
        return context.getString(R.string.ex_summary_format, action, exchange.date)
    }

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
