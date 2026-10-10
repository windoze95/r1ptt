package dev.r1ptt

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/** A supported, read-only diagnostic surface. No inbox, recipients, transcripts, or credentials. */
class OutcomesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 12, 16, 12) }
        layout.addView(Button(this).apply { setText(R.string.outcomes_back); setOnClickListener { finish() } })
        layout.addView(TextView(this).apply { setText(R.string.outcomes_description); textSize = 17f })
        val rows = (application as App).outcomes.list().asReversed()
        layout.addView(TextView(this).apply {
            textSize = 15f
            text = if (rows.isEmpty()) "No retained outcomes yet. Earlier turns were not recorded." else rows.joinToString("\n\n") { row ->
                val status = if (row.status == OutcomeStatus.HANDOFF && System.currentTimeMillis() - row.at >= 120_000) OutcomeStatus.SMS_UNKNOWN else row.status
                val label = when (status) {
                    OutcomeStatus.RESOLVING -> "Action incomplete · no result retained"
                    OutcomeStatus.COMPLETED -> "Turn completed · does not imply SMS sent"
                    OutcomeStatus.HANDOFF -> "SMS handed to Android · awaiting result"
                    OutcomeStatus.SMS_SENT -> "SMS sent · delivery unconfirmed"
                    OutcomeStatus.SMS_DELIVERED -> "SMS delivered · carrier confirmed"
                    OutcomeStatus.SMS_UNKNOWN -> "SMS status unknown · do not automatically retry"
                    OutcomeStatus.CLARIFY -> "Clarification needed · no SMS sent"
                    OutcomeStatus.DISABLED -> "Assistant SMS disabled · no SMS sent"
                    OutcomeStatus.CANCELLED -> "Cancelled before completed action"
                    OutcomeStatus.FAILED -> "Action failed · no SMS confirmed"
                    OutcomeStatus.SMS_FAILED -> "Android reported SMS send failure"
                    OutcomeStatus.SMS_PARTLY_SENT -> "SMS partly sent · do not automatically retry"
                    OutcomeStatus.SMS_DELIVERY_FAILED -> "Carrier reported delivery failure"
                }
                "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(row.at))} · ${row.source.name}\n$label" +
                    (if (row.reason == OutcomeReason.NONE) "" else "\nCategory: ${row.reason.name}") + (row.code?.let { " · code $it" } ?: "")
            }
        })
        setContentView(ScrollView(this).apply { addView(layout) })
    }
}
