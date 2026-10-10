package dev.r1ptt.messages

import android.content.Intent
import dev.r1ptt.App
import dev.r1ptt.OutcomeSource
import dev.r1ptt.OutcomeStatus
import dev.r1ptt.OutcomeReason
import java.util.concurrent.atomic.AtomicBoolean

/** Only completed explicit user commands enter here. No model-output, inbox or Intent send entry. */
class SmsAssistant(private val app: App, private val sms: SmsController = app.sms) {
    private val prefs = app.getSharedPreferences("messages", 0)
    var enabled: Boolean
        // A previous draft-only opt-in must never silently authorize direct sending.
        get() = prefs.getBoolean("assistantSend", false)
        set(value) { prefs.edit().putBoolean("assistantSend", value).apply() }

    class Request internal constructor(val action: SmsComposeAction, val outcomeId: String) {
        private val consumed = AtomicBoolean()
        internal fun claim() = consumed.compareAndSet(false, true)
    }

    fun command(text: String): SmsComposeAction? = if (enabled) SmsComposeAction.parse(text) else null
    fun request(text: String): Request? = command(text)?.let { request(it, app.outcomes.begin(OutcomeSource.SMS)) }
    fun request(action: SmsComposeAction, outcomeId: String): Request = Request(action, outcomeId)

    /** Main-thread completion only. A stale callback or repeated completion cannot send again. */
    fun execute(request: Request, current: () -> Boolean, report: (String) -> Unit) {
        if (!request.claim()) return
        if (!enabled || !current()) { app.outcomes.finishIfOpen(request.outcomeId, OutcomeStatus.CANCELLED); return }
        val action = request.action
        fun rejected(message: String, reason: OutcomeReason) {
            app.outcomes.update(request.outcomeId, OutcomeStatus.FAILED, reason)
            report(message)
        }
        sms.recipients { saved, error ->
            if (!enabled || !current()) { app.outcomes.finishIfOpen(request.outcomeId, OutcomeStatus.CANCELLED); return@recipients }
            if (saved == null) { rejected("${error ?: "Recipients unavailable."} No text was sent.", OutcomeReason.UNAVAILABLE); return@recipients }
            when (val recipient = SmsRecipientResolver.resolve(action.recipient, saved)) {
                is SmsResolution.Number -> {
                    val prepared = try { sms.prepare(SmsDraft(recipient.number, action.body), request.outcomeId) }
                    catch (e: IllegalArgumentException) { rejected("${e.message} No text was sent.", OutcomeReason.INVALID_ACTION); return@recipients }
                    catch (e: IllegalStateException) { rejected("${e.message} No text was sent.", OutcomeReason.UNAVAILABLE); return@recipients }
                    catch (_: Exception) { rejected("SMS is unavailable. No text was sent.", OutcomeReason.UNAVAILABLE); return@recipients }
                    sms.send(prepared, clearDraft = false) { consumed, message ->
                        if (!consumed) app.outcomes.update(request.outcomeId, OutcomeStatus.FAILED, OutcomeReason.UNAVAILABLE)
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
                else -> {
                    app.outcomes.update(request.outcomeId, OutcomeStatus.CLARIFY, OutcomeReason.RECIPIENT)
                    report(if (open(action)) "Recipient needs clarification in Messages. No text was sent."
                        else "Couldn't open Messages for recipient clarification. No text was sent.")
                }
            }
        }
    }

    private fun open(action: SmsComposeAction): Boolean = runCatching {
        app.startActivity(Intent(app, MessagesActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(RECIPIENT, action.recipient).putExtra(BODY, action.body))
    }.isSuccess

    fun instructions(): String = (if (enabled)
        " robotOS can send an explicit SMS request directly when one recipient and the exact message are provided. Assistant SMS sending is enabled. "
        else " Assistant SMS sending is disabled. It can be enabled in Messages → Options. ") +
        "This conversation response does not itself execute device actions. " +
        "Never promise to send, claim you sent, or claim delivery. Native robotOS actions and their results are handled separately before this response path. " +
        "If asked to send a text here, explain that no text was sent and ask for one recipient and the exact message."

    companion object {
        const val RECIPIENT = "dev.r1ptt.sms.recipient"
        const val BODY = "dev.r1ptt.sms.body"
        const val THREAD = "dev.r1ptt.sms.thread"
    }
}
