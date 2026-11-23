package com.nevergrowold.holdhightheshop

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Private listing report screen with duplicate-report guard, immediate reporter hide,
 * 3-distinct-student auto UNDER_REVIEW escalation, and unsaved changes guard.
 */
class ReportListingActivity : BaseActivity() {

    private lateinit var listing: Listing
    private var reason = -1
    private var dirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_report_listing)
        BarInsets.apply(this)

        listing = Listings.byId(intent.getStringExtra(EXTRA_LISTING))
        findViewById<TextView>(R.id.reportItem).text = listing.title
        findViewById<TextView>(R.id.reportListedBy).text =
            getString(R.string.report_listed_by_format, Listings.sellerOf(listing).name)

        Choice.rows(
            listOf(
                findViewById(R.id.reason1), findViewById(R.id.reason2), findViewById(R.id.reason3),
                findViewById(R.id.reason4), findViewById(R.id.reason5), findViewById(R.id.reason6),
            ),
            listOf(
                findViewById(R.id.reason1Icon), findViewById(R.id.reason2Icon), findViewById(R.id.reason3Icon),
                findViewById(R.id.reason4Icon), findViewById(R.id.reason5Icon), findViewById(R.id.reason6Icon),
            ),
            reason,
        ) {
            reason = it
            dirty = true
        }

        val input = findViewById<EditText>(R.id.inputReportDetails)
        val counter = findViewById<TextView>(R.id.counterReport)
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                dirty = true
                counter.text = getString(R.string.counter_format, s?.length ?: 0)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        dirty = false

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmLeaveIfDirty()
        })
    }

    override fun goBack(view: View) {
        Motion.tick(view)
        confirmLeaveIfDirty()
    }

    private fun confirmLeaveIfDirty() {
        if (!dirty) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.discard_changes_title)
            .setMessage(R.string.discard_changes_message)
            .setNegativeButton(R.string.btn_keep_editing, null)
            .setPositiveButton(R.string.btn_discard) { _, _ -> finish() }
            .show()
    }

    fun submitReport(view: View) {
        if (reason < 0) {
            Motion.shake(findViewById(R.id.reason1))
            Toast.makeText(this, R.string.toast_select_reason, Toast.LENGTH_SHORT).show()
            return
        }
        hideKeyboard(view)
        Motion.confirm(view)
        val details = findViewById<EditText>(R.id.inputReportDetails).text.toString().trim()
        val issue = UserState.report(
            subject = listing.id,
            reason = REASON_STRINGS[reason],
            partner = Listings.sellerOf(listing),
            details = details,
            isListingReport = true,
        )
        dirty = false
        Toast.makeText(this, getString(R.string.toast_report_submitted, issue.ref), Toast.LENGTH_SHORT).show()
        startActivity(
            Intent(this, ReportReceivedActivity::class.java)
                .putExtra(EXTRA_LISTING, listing.id)
                .putExtra(ReportReceivedActivity.EXTRA_REFERENCE, issue.ref),
        )
        finish()
    }

    companion object {
        private val REASON_STRINGS = listOf(
            R.string.reason1,
            R.string.reason2,
            R.string.reason3,
            R.string.reason4,
            R.string.reason5,
            R.string.reason6,
        )
    }
}
