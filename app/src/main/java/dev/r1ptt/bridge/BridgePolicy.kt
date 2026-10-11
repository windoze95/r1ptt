package dev.r1ptt.bridge

import android.os.PowerManager
import dev.r1ptt.messages.SmsIntent
import org.json.JSONObject
import java.security.MessageDigest

object BridgePolicy {
    const val VERSION = 1
    const val TTL_SECONDS = 300L
    const val RETENTION_SECONDS = 86400L
    const val METERED_BYTES_PER_DAY = 1_048_576L
    val terminal = setOf("cancelled", "expired", "failed", "unresolved", "revoked", "draft", "explained", "clarify", "chat", "handoff")
    private val draft = Regex("^(?:please\\s+)?(?:draft|write|compose)\\b.*\\b(?:text|sms|message)\\b", RegexOption.IGNORE_CASE)
    private val sensitive = Regex("\\b(?:otp|one[ -]?time|verification|authentication|security code|passcode|password|recovery|reset|2fa|two[ -]factor|sign[ -]?in|log[ -]?in|secret|api[ _-]?key)\\b|\\b\\d{4,8}\\b", RegexOption.IGNORE_CASE)
    // A recipient's phone number is not a code: "405-555-0123" must not trip the 4–8 digit rule.
    private val phoneNumber = Regex("(?<![\\d+])(?:\\+?1[\\s.-]?)?(?:\\(\\d{3}\\)|\\d{3})[\\s.-]?\\d{3}[\\s.-]?\\d{4}(?!\\d)|\\+\\d{8,15}(?!\\d)|(?<![\\d+])\\d{3}[\\s.-]\\d{4}(?![\\d.-]?\\d)")
    fun draftRequest(text: String) = draft.containsMatchIn(text.trim())
    /** "Tell me…", "send me five tips": ordinary chat stays with the chat model, not the relay. */
    fun request(text: String) = draftRequest(text) || (SmsIntent.directed(text) && !SmsIntent.toUser(text))
    fun sensitive(text: String) = sensitive.containsMatchIn(text)
    /** The owner's own command may name a phone number; codes and credentials still stay local. */
    fun sensitiveCommand(text: String) = sensitive(phoneNumber.replace(text, " "))
    fun destination(number: String): Boolean = number.matches(Regex("\\+1[2-9]\\d{2}[2-9]\\d{6}")) &&
        supportedUsNumber.matches(number.substring(2)) && number.substring(5, 8) != "976"

    /** UTF-8 length-prefixed fields; independent of JSON escaping and key order. */
    fun digest(frozen: JSONObject): String {
        val bytes = listOf("recipient", "body", "sim", "parts", "attempt").joinToString("") { key ->
            val value = frozen.get(key).toString(); "${value.toByteArray(Charsets.UTF_8).size}:$value"
        }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

data class RelayPower(
    val enabled: Boolean, val paused: Boolean, val docked: Boolean, val plugged: Boolean,
    val interactive: Boolean, val deliberateAirplane: Boolean, val powerSave: Boolean,
    val batteryPercent: Int, val thermalStatus: Int,
) {
    val safe get() = enabled && !paused && !deliberateAirplane && !powerSave && batteryPercent >= 15 &&
        thermalStatus < PowerManager.THERMAL_STATUS_MODERATE
    val holdRadios get() = safe && docked && plugged
    val sync get() = safe && (interactive || holdRadios)
}
