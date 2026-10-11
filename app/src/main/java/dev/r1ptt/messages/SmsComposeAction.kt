package dev.r1ptt.messages

import java.util.Locale

data class SmsComposeAction(val recipient: String, val body: String) {
    companion object {
        // Require both recipient and explicit body. Contextual requests such as "send that to her"
        // cannot safely choose either from AI history and are intentionally not interpreted here.
        private val command = Regex(
            "^(?:please\\s+|(?:can|could) you\\s+)?(?:text|sms|send (?:a )?text to)\\s+(.{1,80}?)(?:\\s+(?:that|saying)\\s+|\\s*:\\s*)(.+)$",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        fun parse(text: String): SmsComposeAction? {
            val match = command.matchEntire(text.trim()) ?: return null
            val recipient = match.groupValues[1].trim()
            val body = match.groupValues[2].trim()
            if (recipient.isBlank() || body.isBlank() || body.length > SmsRecord.MAX_DRAFT_CHARS) return null
            return SmsComposeAction(recipient, body)
        }
    }
}

data class SmsRecipient(val id: Long, val name: String, val number: String)
sealed interface SmsResolution {
    data class Number(val number: String) : SmsResolution
    data class Choose(val matches: List<SmsRecipient>) : SmsResolution
    data object Missing : SmsResolution
}

object SmsRecipientResolver {
    private fun key(name: String) = name.trim().trimEnd('.', ',', '!', '?').replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    /** [region] is the SMS SIM's country; it only decides whether a ten-digit number is a +1 number. */
    fun resolve(request: String, saved: List<SmsRecipient>, region: String? = null): SmsResolution {
        SmsRecipientText.number(request, region)?.let { return SmsResolution.Number(it) }
        fun named(name: String) = saved.filter { key(it.name) == key(name) && SmsAddress.normalize(it.number) == it.number }
            .distinctBy { it.number }
        // "Text my mom" names the saved "Mom"; an exact saved "My Mom" still wins.
        val matches = named(request).ifEmpty { Regex("^\\s*my\\s+(.+)$", RegexOption.IGNORE_CASE).find(request)?.let { named(it.groupValues[1]) }.orEmpty() }
        return when (matches.size) {
            0 -> SmsResolution.Missing
            1 -> SmsResolution.Number(matches.single().number)
            else -> SmsResolution.Choose(matches)
        }
    }
}
