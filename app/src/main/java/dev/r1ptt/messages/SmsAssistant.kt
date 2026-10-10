package dev.r1ptt.messages

import android.content.Intent
import dev.r1ptt.App
import java.util.concurrent.atomic.AtomicBoolean

/** Only completed explicit user commands enter here. No model-output, inbox or Intent send entry. */
class SmsAssistant(private val app: App, private val sms: SmsController = app.sms) {
    private val prefs = app.getSharedPreferences("messages", 0)
    var enabled: Boolean
        // A previous draft-only opt-in must never silently authorize direct sending.
        get() = prefs.getBoolean("assistantSend", false)
        set(value) { prefs.edit().putBoolean("assistantSend", value).apply() }

    class Request internal constructor(val action: SmsComposeAction) {
        private val consumed = AtomicBoolean()
        internal fun claim() = consumed.compareAndSet(false, true)
    }

    fun command(text: String): SmsComposeAction? = if (enabled) SmsComposeAction.parse(text) else null
    fun request(text: String): Request? = command(text)?.let(::Request)

    /** Main-thread completion only. A stale callback or repeated completion cannot send again. */
    fun execute(request: Request, current: () -> Boolean, report: (String) -> Unit) {
        if (!request.claim() || !enabled || !current()) return
        val action = request.action
        sms.recipients { saved, error ->
            if (!enabled || !current()) return@recipients
            if (saved == null) { report("${error ?: "Recipients unavailable."} No text was sent."); return@recipients }
            when (val recipient = SmsRecipientResolver.resolve(action.recipient, saved)) {
                is SmsResolution.Number -> {
                    val prepared = try { sms.prepare(SmsDraft(recipient.number, action.body)) }
                    catch (e: IllegalArgumentException) { report("${e.message} No text was sent."); return@recipients }
                    catch (e: IllegalStateException) { report("${e.message} No text was sent."); return@recipients }
                    catch (_: Exception) { report("SMS is unavailable. No text was sent."); return@recipients }
                    sms.send(prepared, clearDraft = false) { consumed, message ->
                        if (current()) {
                            report(message)
                            // This Intent only displays the durable attempt; reopening it never sends.
                            if (consumed) runCatching {
                                app.startActivity(Intent(app, MessagesActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(THREAD, prepared.record.peer))
                            }.onFailure { report("$message Open Messages to check the result.") }
                        }
                    }
                }
                else -> report(if (open(action)) "Recipient needs clarification in Messages. No text was sent."
                    else "Couldn't open Messages for recipient clarification. No text was sent.")
            }
        }
    }

    private fun open(action: SmsComposeAction): Boolean = runCatching {
        app.startActivity(Intent(app, MessagesActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(RECIPIENT, action.recipient).putExtra(BODY, action.body))
    }.isSuccess

    fun instructions(): String = if (!enabled) "" else
        " robotOS sends an SMS locally after a completed explicit user command such as 'Text NAME that MESSAGE' or 'Text NUMBER: MESSAGE', without a review step. " +
            "The device resolves exact saved names; missing or ambiguous recipients need clarification. You cannot read texts or contacts or invoke this action from your replies. " +
            "For these commands, say that robotOS will attempt the text after this voice turn finishes and show its actual status in Messages. " +
            "Do not claim sent or delivered: only the device's Android callbacks establish those states. " +
            "For missing recipient or message, ask for an explicit name or number and the full message."

    companion object {
        const val RECIPIENT = "dev.r1ptt.sms.recipient"
        const val BODY = "dev.r1ptt.sms.body"
        const val THREAD = "dev.r1ptt.sms.thread"
    }
}
