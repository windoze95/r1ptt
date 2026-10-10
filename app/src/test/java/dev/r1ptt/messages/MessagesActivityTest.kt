package dev.r1ptt.messages

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import dev.r1ptt.App
import dev.r1ptt.data.Msg
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class, qualifiers = "w320dp-h426dp-mdpi")
class MessagesActivityTest {
    private lateinit var app: App
    @Before fun setup() { app = RuntimeEnvironment.getApplication() as App; app.deleteDatabase("messages.db") }
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertTrue("UI condition was not reached", condition())
    }

    @Test fun openingMessagesDoesNotRequestPermissionsAndButtonCannotStartAiDictation() {
        val c = Robolectric.buildActivity(MessagesActivity::class.java).setup()
        val a = c.get()
        await { views(a.window.decorView).filterIsInstance<Button>().any { it.text == "New text" && it.isEnabled } }
        assertNull(shadowOf(a).lastRequestedPermission)
        assertFalse(a.canDictate())
        app.history.add(Msg.USER, "Existing AI conversation")
        val before = app.history.messages
        app.turns.onPress(0); app.turns.onHoldStart(); app.turns.onHoldEnd()
        app.turns.onDoubleTap()
        assertEquals(before, app.history.messages)
        assertFalse(app.turns.busy)
        c.pause()
        assertNull(app.turns.target)
        // First wake press can precede Activity.onResume. It must not open a chat microphone.
        app.turns.onPress(1000); app.turns.onHoldStart(); app.turns.onHoldEnd(); app.turns.onDoubleTap()
        assertFalse(app.turns.busy)
        assertEquals(before, app.history.messages)
        c.stop().destroy()
    }

    @Test fun unknownAssistantRecipientOpensBodyWithEmptyPhoneAndNoSend() {
        val intent = Intent(app, MessagesActivity::class.java).putExtra(SmsAssistant.RECIPIENT, "Yana")
            .putExtra(SmsAssistant.BODY, "Synthetic draft only")
        val c = Robolectric.buildActivity(MessagesActivity::class.java, intent).setup()
        val a = c.get()
        await { views(a.window.decorView).filterIsInstance<EditText>().any { it.contentDescription == "SMS draft" && it.text.toString() == "Synthetic draft only" } }
        val phone = views(a.window.decorView).filterIsInstance<EditText>().single { it.contentDescription == "Recipient phone number" }
        assertEquals("", phone.text.toString())
        assertTrue(a.sendTyped())
        assertNull(shadowOf(a).lastRequestedPermission)
        assertTrue(app.history.messages.isEmpty())
        SmsStore(app).use { assertTrue(it.threads().isEmpty()) }
        c.pause().stop().destroy()
    }

    @Test fun typedAssistantActionOpensOnlyALocalDraftWithoutAddingAiHistory() {
        app.smsAssistant.enabled = true
        app.turns.sendText("Text Yana that I’m on my way")
        shadowOf(Looper.getMainLooper()).idle()
        val opened = shadowOf(app).nextStartedActivity
        assertEquals(MessagesActivity::class.java.name, opened.component?.className)
        assertEquals("Yana", opened.getStringExtra(SmsAssistant.RECIPIENT))
        assertEquals("I’m on my way", opened.getStringExtra(SmsAssistant.BODY))
        assertTrue(app.history.messages.isEmpty())
        SmsStore(app).use { assertTrue(it.threads().isEmpty()) }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun composeFitsSmallScreenAndProducesSyntheticPreview() {
        val intent = Intent(app, MessagesActivity::class.java).putExtra(SmsAssistant.RECIPIENT, "+15551234567")
            .putExtra(SmsAssistant.BODY, "I’m on my way. See you soon!")
        val c = Robolectric.buildActivity(MessagesActivity::class.java, intent).setup()
        val a = c.get()
        await { views(a.window.decorView).filterIsInstance<EditText>().any { it.contentDescription == "SMS draft" } }
        val content = a.findViewById<ViewGroup>(android.R.id.content)
        content.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(426, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, 320, 426)
        val review = views(content).filterIsInstance<Button>().single { it.text == "Review text" }
        assertTrue(review.bottom <= 426)
        assertTrue(review.width >= 250)
        val bitmap = Bitmap.createBitmap(320, 426, Bitmap.Config.ARGB_8888)
        content.draw(Canvas(bitmap))
        val preview = File("build/messages-ui/compose.png").apply { requireNotNull(parentFile).mkdirs() }
        preview.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        c.pause().stop().destroy()
    }
}
