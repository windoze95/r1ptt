package dev.r1ptt.data

import dev.r1ptt.input.InputEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BatteryAndInputTest {
    @Test
    fun idleDrainUsesOnlyLongUnpluggedScreenOffPeriods() {
        val h = 3_600_000L
        val lines = listOf(
            BatteryLog.HEADER.trim(),
            "0,0,screen_off,100,false,true",
            "0,${8 * h},screen_on,96,false,false", // 8 h, -4 %
            "0,${8 * h + 60_000},screen_off,96,false,true",
            "0,${8 * h + 120_000},screen_on,96,false,true", // 1 min: ignored
            "0,${9 * h},screen_off,95,true,true", // plugged: ignored
            "0,${10 * h},screen_on,99,true,true",
        )
        val s = BatteryLog.summarize(lines)
        assertTrue(s, s.startsWith("Idle drain: 0.50%/hour over 8.0 h in 1 screen-off periods"))
    }

    @Test
    fun noDataMessage() {
        assertTrue(BatteryLog.summarize(listOf(BatteryLog.HEADER.trim())).startsWith("Not enough"))
    }

    @Test
    fun decodesA64BitInputEvent() {
        val b = ByteBuffer.allocate(InputEvent.SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.putLong(1234).putLong(5678).putShort(1).putShort(116).putInt(1)
        assertEquals(InputEvent(InputEvent.EV_KEY, InputEvent.KEY_POWER, 1), InputEvent.parse(b.array()))
    }

    @Test
    fun findsTheButtonNodeByName() {
        val devices = """
            I: Bus=0019 Vendor=0000 Product=0000 Version=0000
            N: Name="och1970_holl_key"
            P: Phys=
            H: Handlers=kbd event2
            B: EV=3

            I: Bus=0019 Vendor=2454 Product=6500 Version=0010
            N: Name="mtk-kpd"
            P: Phys=
            S: Sysfs=/devices/platform/10010000.kp/input/input0
            H: Handlers=event0 kbd
            B: KEY=1000000 0 0 0
        """.trimIndent()
        assertEquals("/dev/input/event0", InputEvent.findHandler(devices, "mtk-kpd"))
        assertEquals("/dev/input/event2", InputEvent.findHandler(devices, "och1970_holl_key"))
        assertNull(InputEvent.findHandler(devices, "nope"))
    }
}
