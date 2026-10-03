package dev.r1ptt.power

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import dev.r1ptt.sys.AdminReceiver
import dev.r1ptt.sys.Root

/**
 * Screen and CPU-wake decisions. The screen itself is kept on by the launcher window while a turn
 * is active (FLAG_KEEP_SCREEN_ON) and otherwise left to the short system timeout.
 */
class ScreenPolicy(ctx: Context) {
    private val pm = ctx.getSystemService(PowerManager::class.java)
    private val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(ctx, AdminReceiver::class.java)
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var screenOn = pm.isInteractive
    @Volatile private var lastOnAt = 0L
    @Volatile private var offAtPress = false

    fun onScreenOn() {
        screenOn = true
        lastOnAt = SystemClock.elapsedRealtime()
    }

    fun onScreenOff() {
        screenOn = false
    }

    fun isScreenOn(): Boolean = pm.isInteractive

    /**
     * Called on every press. The root button reader usually delivers the press before Android has
     * finished waking the screen, so this still sees "off" for the press that woke it.
     */
    fun notePress() {
        offAtPress = !screenOn || !pm.isInteractive
    }

    /**
     * Whether the tap that just ended was the one that woke the screen (so it shouldn't also
     * put it back to sleep). Errs toward "yes": a missed sleep is better than an un-wakeable screen.
     */
    fun tapWasWake(): Boolean = offAtPress || SystemClock.elapsedRealtime() - lastOnAt < 1000

    /** Screen off now: device admin's lockNow() if provisioned, else the SLEEP key via root. */
    fun sleepNow() {
        val viaAdmin = dpm.isAdminActive(admin) && runCatching { dpm.lockNow() }.isSuccess
        if (!viaAdmin) Root.async("input keyevent 223")
    }

    /** Keeps the CPU up for one turn even if the screen goes off; bounded so a bug can't drain the battery. */
    fun holdAwake() {
        val wl = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "r1ptt:turn")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        wl.acquire(3 * 60_000L)
    }

    fun release() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }
}
