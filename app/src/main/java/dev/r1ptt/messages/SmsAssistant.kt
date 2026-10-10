package dev.r1ptt.messages

import android.content.Intent
import dev.r1ptt.App

/** A bounded local compose action, not a remote send tool. Never exposes the recipient book to AI. */
class SmsAssistant(private val app: App) {
    private val prefs = app.getSharedPreferences("messages", 0)
    var enabled: Boolean
        get() = prefs.getBoolean("assistantDrafts", false)
        set(value) { prefs.edit().putBoolean("assistantDrafts", value).apply() }

    fun command(text: String): SmsComposeAction? = if (enabled) SmsComposeAction.parse(text) else null

    fun open(action: SmsComposeAction): Boolean = runCatching {
        app.startActivity(Intent(app, MessagesActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(RECIPIENT, action.recipient).putExtra(BODY, action.body))
    }.isSuccess

    fun instructions(): String = if (!enabled) "" else
        " robotOS can prepare an SMS draft locally when the user says 'Text NAME that MESSAGE' or 'Text NUMBER: MESSAGE'. " +
            "For these requests, say to review the draft on the device. You cannot send texts, read the inbox, resolve contacts, or confirm delivery. " +
            "Never claim that a text was sent. The user must confirm the recipient and message with the Send SMS button. " +
            "For missing recipient or message, ask for an explicit name or number and the full message."

    companion object {
        const val RECIPIENT = "dev.r1ptt.sms.recipient"
        const val BODY = "dev.r1ptt.sms.body"
    }
}
