package dev.r1ptt.bridge

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import dev.r1ptt.App
import dev.r1ptt.data.BridgeConfig
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

/** Private queue/status screen. Enabling availability never enables assistant SMS sending. */
class BridgeActivity : Activity() {
    private val app get() = application as App
    private var scope: CoroutineScope? = null
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var rows: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        render()
    }
    override fun onResume() {
        super.onResume()
        RelayService.start(this)
        scope = MainScope().also { scope ->
            scope.launch { app.bridge.status.collect { updateStatus() } }
            scope.launch { while (isActive) { refresh(); delay(2000) } }
        }
    }
    override fun onPause() { scope?.cancel(); scope = null; super.onPause() }

    private fun render() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 8, 12, 16) }
        setContentView(ScrollView(this).apply { addView(root) })
        root.addView(button("‹ Messages / settings") { finish() })
        root.addView(label("SMS relay", 21f))
        status = label("", 14f).also { root.addView(it) }
        val cfg = app.store.value.bridge
        root.addView(label(if (cfg.valid()) "Bridge: ${java.net.URI(cfg.activeUrl).host}" else "Provision a bridge enrollment to enable relay. Your existing PTT texts still work.", 14f))
        root.addView(label("Tailscale is the primary connection. Switch to WireGuard when needed; Android runs one VPN at a time.", 14f))
        root.addView(button("Open Tailscale") { openVpn("com.tailscale.ipn") })
        root.addView(button("Open WireGuard") { openVpn("com.wireguard.android") })
        root.addView(button("Android VPN settings") { runCatching { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) } })
        if (cfg.wireguardUrl.isNotBlank()) root.addView(button(if (cfg.useWireguard) "Use Tailscale connection" else "Use WireGuard fallback") {
            change { it.copy(useWireguard = !it.useWireguard) }
            openVpn(if (app.store.value.bridge.useWireguard) "com.wireguard.android" else "com.tailscale.ipn")
        })
        root.addView(button(if (cfg.enabled) "Disable relay" else "Enable relay") {
            if (!cfg.valid()) {
                AlertDialog.Builder(this).setTitle("Relay setup")
                    .setMessage("Import the private per-device enrollment JSON using the existing robotOS configuration import. The R1 needs an HTTPS bridge address; the Hermes API key stays on the server.")
                    .setPositiveButton("Close", null).show()
            } else if (cfg.enabled) change { it.copy(enabled = false) }
            else AlertDialog.Builder(this).setTitle("Enable SMS relay?")
                .setMessage("Explicit PTT SMS commands will go to this bridge's restricted Hermes profile. Clear sends go directly to your SIM; draft requests remain unsent. Incoming texts are shared only when you select one. Relay copies expire after 24 hours when cleanup runs; IDs remain for replay protection. Your Messages history and model provider have separate retention.")
                .setPositiveButton("Enable") { _, _ -> change { it.copy(enabled = true, paused = false) } }
                .setNegativeButton("Cancel", null).show()
        })
        if (cfg.enabled) {
            root.addView(button(if (cfg.paused) "Resume relay" else "Pause relay") { change { it.copy(paused = !it.paused) } })
            root.addView(button(if (cfg.docked) "Turn off docked mode" else "Stay available while plugged in") {
                if (cfg.docked) change { it.copy(docked = false) }
                else AlertDialog.Builder(this).setTitle("Docked SMS relay")
                    .setMessage("While externally powered, keep the modem and Wi-Fi available with the screen and microphone off. Unplugging returns to the normal three-minute radio sleep. This uses your existing carrier plan and does not enable automatic replies.")
                    .setPositiveButton("Enable docked mode") { _, _ -> change { it.copy(docked = true) } }
                    .setNegativeButton("Cancel", null).show()
            })
        }
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { root.addView(it) }
        updateStatus()
    }
    private fun change(block: (BridgeConfig) -> BridgeConfig) {
        try {
            app.bridge.availability(false, "Updating relay settings")
            app.store.update { it.copy(bridge = block(it.bridge)) }
            RelayService.start(this)
            render()
        } catch (_: Exception) { Toast.makeText(this, "Relay settings could not be saved.", Toast.LENGTH_LONG).show() }
    }
    private fun updateStatus() {
        val last = app.bridge.lastContact
        val cm = getSystemService(ConnectivityManager::class.java)
        val vpn = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        status.text = "${app.bridge.status.value}\n${if (app.store.value.bridge.useWireguard) "WireGuard fallback selected" else "Tailscale selected"} · ${if (vpn) "VPN active" else "VPN not active"}\nLast contact: ${if (last == 0L) "never" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last))}\nRelay mobile data today: ${app.bridge.meteredBytes / 1024} KiB / 1024 KiB"
    }
    private fun openVpn(name: String) {
        val launch = packageManager.getLaunchIntentForPackage(name)
        if (launch != null) startActivity(launch)
        else Toast.makeText(this, "Install the VPN client first.", Toast.LENGTH_LONG).show()
    }
    private suspend fun refresh() {
        val jobs = withContext(Dispatchers.IO) { app.bridge.journal.jobs() }
        rows.removeAllViews()
        if (jobs.isEmpty()) rows.addView(label("No relay requests. PTT sends continue through your current assistant until relay is enabled.", 14f))
        jobs.forEach { job ->
            rows.addView(label("${job.id.take(8)} · ${state(job)}", 15f))
            if (job.state == "explained") rows.addView(label(job.result.orEmpty(), 15f))
            if (job.state !in BridgePolicy.terminal && job.state != "stopping") rows.addView(button("Cancel request") {
                scope?.launch { app.bridge.cancel(job.id); refresh() }
            })
        }
        updateStatus()
    }
    private fun state(job: BridgeJob): String = when (job.state) {
        "queued" -> "Saved · waiting for connection"
        "running" -> "Hermes working"
        "ready", "frozen" -> "Owner-authorized · waiting"
        "stopping" -> "Stopping · dispatch prevented locally"
        "handoff" -> "Android attempt recorded · check Messages for receipts"
        "draft" -> "Draft ready in Messages · unsent"
        "explained" -> "Selected message explanation"
        "clarify" -> "Needs a clear recipient and send request"
        "chat" -> "No SMS requested"
        "unresolved" -> "Server result unavailable · not repeated"
        else -> job.state.replaceFirstChar { it.uppercase() }
    }
    private fun label(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; setPadding(0, 6, 0, 6) }
    private fun button(value: String, clicked: () -> Unit) = Button(this).apply { text = value; isAllCaps = false; setOnClickListener { clicked() } }
}
