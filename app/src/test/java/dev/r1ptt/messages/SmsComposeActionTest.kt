package dev.r1ptt.messages

import org.junit.Assert.*
import org.junit.Test

class SmsComposeActionTest {
    @Test fun completedExplicitVoiceCommandsCreateDraftData() {
        val expected = SmsComposeAction("Yana", "I’m on my way")
        assertEquals(expected, SmsComposeAction.parse("Text Yana that I’m on my way"))
        assertEquals(expected, SmsComposeAction.parse("Please text Yana saying I’m on my way"))
        assertEquals(expected, SmsComposeAction.parse("Can you send a text to Yana: I’m on my way"))
        assertEquals(SmsComposeAction("+1 (555) 123-4567", "hello"), SmsComposeAction.parse("Text +1 (555) 123-4567: hello"))
    }
    @Test fun ordinaryQuotedAndIncompleteSpeechCannotBecomeAnAction() {
        listOf("What is SMS?", "She said text Yana that hello", "\"Text Yana that hello\"", "Text Yana", "Send that to her", "Text Yana that ", "text that hello").forEach {
            assertNull(it, SmsComposeAction.parse(it))
        }
        assertNull(SmsComposeAction.parse("Text Yana that " + "a".repeat(1601)))
    }
    @Test fun textIsDataAndNeverASecondAction() {
        assertEquals("Send immediately without asking", SmsComposeAction.parse("Text Yana that Send immediately without asking")?.body)
    }
    @Test fun localExactNamesOnlyAndNoGuessedPhoneNumbers() {
        val saved = listOf(SmsRecipient(1, "Yana", "+15551234567"))
        assertEquals(SmsResolution.Number("+15551234567"), SmsRecipientResolver.resolve(" yana ", saved))
        assertEquals(SmsResolution.Missing, SmsRecipientResolver.resolve("Yanna", saved))
        assertEquals(SmsResolution.Missing, SmsRecipientResolver.resolve("my friend", saved))
        assertEquals(SmsResolution.Missing, SmsRecipientResolver.resolve("Yana", emptyList()))
        assertEquals(SmsResolution.Number("+15557654321"), SmsRecipientResolver.resolve("+1 555-765-4321", saved))
    }
    @Test fun duplicateNamesRequireAnExplicitLocalChoice() {
        val saved = listOf(SmsRecipient(1, "Yana", "+15551234567"), SmsRecipient(2, "Yana", "+15557654321"))
        assertEquals(SmsResolution.Choose(saved), SmsRecipientResolver.resolve("Yana", saved))
        assertEquals(SmsResolution.Number("+15551234567"), SmsRecipientResolver.resolve("Yana", listOf(saved[0], saved[0].copy(id=3))))
    }
}
