package dev.r1ptt.bridge

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import dev.r1ptt.App
import dev.r1ptt.R
import kotlinx.coroutines.*

/** A separate, content-free remoteMessaging service. It never opens audio or a live voice session. */
class RelayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as App
    private var loop: Job? = null
    private var registered = false
    private var foreground = false
    private var thermal: PowerManager.OnThermalStatusChangedListener? = null
    private var lastConfig: dev.r1ptt.data.BridgeConfig? = null
    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = recompute()
    }

    override fun onCreate() {
        super.onCreate()
        startNotice()
        if (!foreground) { stopSelf(); return }
        registerReceiver(events, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED); addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }, RECEIVER_NOT_EXPORTED)
        registered = true
        thermal = PowerManager.OnThermalStatusChangedListener { recompute() }.also { getSystemService(PowerManager::class.java).addThermalStatusListener(mainExecutor, it) }
        scope.launch {
            app.store.flow.collect { cfg ->
                if (lastConfig != cfg.bridge) { app.bridge.interrupt(); loop?.cancel(); loop = null; lastConfig = cfg.bridge }
                recompute()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startNotice()
        if (intent?.action == PAUSE) {
            // Stop dispatch immediately, even if secure settings persistence fails.
            app.bridge.availability(false, "Relay paused")
            runCatching { app.store.update { it.copy(bridge = it.bridge.copy(paused = true)) } }
            stopSelf()
        } else {
            // A new owner command must interrupt the idle heartbeat wait before its TTL expires.
            app.bridge.interrupt(); loop?.cancel(); loop = null
            recompute()
        }
        return START_STICKY
    }

    private fun recompute() {
        if (!foreground) return
        val cfg = app.store.value.bridge
        if (!cfg.enabled || !cfg.valid() || cfg.paused) { stopSelf(); return }
        val power = getSystemService(PowerManager::class.java)
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val state = RelayPower(cfg.enabled, cfg.paused, cfg.docked,
            (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0, power.isInteractive,
            app.radio.deliberateAirplane, power.isPowerSaveMode, if (scale > 0) level * 100 / scale else -1, power.currentThermalStatus)
        app.radio.relay(state.holdRadios)
        app.bridge.availability(state.sync, when {
            state.deliberateAirplane -> "Relay paused · airplane mode"
            !state.safe -> "Relay paused · power or temperature"
            else -> "Pocket mode · sync on wake"
        })
        if (!state.sync) { loop?.cancel(); loop = null; return }
        if (loop?.isActive == true) return
        loop = scope.launch {
            var waitMs = 2000L
            while (isActive && app.bridge.allowed) {
                // Bounded wake lock for one sync pass only; never held while waiting or on battery sleep.
                val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "robotOS:relay-sync")
                lock.acquire(30_000)
                val pending = try { withTimeoutOrNull(25_000) { app.bridge.sync() } ?: true }
                finally { if (lock.isHeld) lock.release() }
                waitMs = if (pending) (waitMs * 2).coerceAtMost(60_000) else 15 * 60_000
                delay(waitMs)
            }
        }
    }

    override fun onDestroy() {
        app.bridge.availability(false, if (app.store.value.bridge.paused) "Relay paused" else "Relay stopped · open SMS relay to resume")
        app.radio.relay(false)
        if (registered) unregisterReceiver(events)
        thermal?.let { getSystemService(PowerManager::class.java).removeThermalStatusListener(it) }
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun startNotice() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sms-relay", "SMS relay", NotificationManager.IMPORTANCE_LOW).apply { lockscreenVisibility = Notification.VISIBILITY_SECRET })
        val open = PendingIntent.getActivity(this, 0, Intent(this, BridgeActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 1, Intent(this, RelayService::class.java).setAction(PAUSE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notice = Notification.Builder(this, "sms-relay").setSmallIcon(R.drawable.ic_messages).setContentTitle("robotOS SMS relay")
            .setContentText("Selected messages only · tap for status").setContentIntent(open).setVisibility(Notification.VISIBILITY_SECRET)
            .addAction(Notification.Action.Builder(null, "Pause", pause).build()).setOngoing(true).build()
        foreground = runCatching { startForeground(205, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING); true }.getOrDefault(false)
    }
    companion object {
        private const val PAUSE = "dev.r1ptt.RELAY_PAUSE"
        fun start(context: Context) {
            val cfg = (context.applicationContext as App).store.value.bridge
            if (cfg.enabled && !cfg.paused && cfg.valid()) runCatching { context.startForegroundService(Intent(context, RelayService::class.java)) }
        }
    }
}

/** Re-evaluate external power at boot/power connect; this never enables a previously disabled relay. */
class RelayBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_POWER_CONNECTED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val app = context.applicationContext as App
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (app.store.value.bridge.docked && (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0) RelayService.start(context)
    }
}
