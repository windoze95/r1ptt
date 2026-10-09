package dev.r1ptt.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.r1ptt.App
import dev.r1ptt.R
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class UpdateActivity : Activity() {
    private val updates get() = (application as App).updates
    private var ui: kotlinx.coroutines.CoroutineScope? = null
    private lateinit var status: TextView
    private lateinit var check: Button
    private lateinit var download: Button
    private lateinit var install: Button
    private lateinit var confirm: Button
    private lateinit var cancel: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(getColor(R.color.bg)); addView(root) })
        fun label(text: String) = TextView(this).apply {
            this.text = text; textSize = 16f; setTextColor(getColor(R.color.fg)); root.addView(this)
        }
        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text; isAllCaps = false; setOnClickListener { action() }; root.addView(this)
        }
        label("robotOS ${updates.installedVersion()}")
        status = label("")
        check = button("Check for updates") { updates.check() }
        download = button("Download update") { updates.download() }
        install = button("Install update") {
            if (!packageManager.canRequestPackageInstalls()) {
                AlertDialog.Builder(this).setTitle("Allow robotOS update requests?")
                    .setMessage("Android requires you to allow this source before robotOS can request an update. You will still confirm each installation. You can turn this off again in Android settings.")
                    .setPositiveButton("Open Android settings") { _, _ ->
                        try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                        catch (_: Exception) { status.setText(R.string.update_permission_hint) }
                    }.setNegativeButton("Cancel", null).show()
            } else {
                AlertDialog.Builder(this).setTitle("Install the verified update?")
                    .setMessage("Finish your conversation first. Voice will pause while Android confirms and installs the update. Your conversations and settings stay in place.")
                    .setPositiveButton("Continue") { _, _ -> updates.install() }
                    .setNegativeButton("Cancel", null).show()
            }
        }
        confirm = button("Continue to Android confirmation") {
            updates.confirmation()?.let {
                try { @Suppress("DEPRECATION") startActivityForResult(it, CONFIRM_INSTALL) }
                catch (_: Exception) { updates.confirmationReturned(cancelled = true) }
            }
        }
        cancel = button("Cancel update") { updates.cancel() }
        button("Back") { updates.cancel(); finish() }
    }

    override fun onResume() {
        super.onResume()
        updates.foreground(true)
        ui = MainScope().also { scope -> scope.launch { updates.state.collect { s ->
            status.text = s.message
            val working = s.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING) || updates.installing
            check.isEnabled = !working
            download.isEnabled = s.phase == UpdatePhase.AVAILABLE && !working
            install.isEnabled = s.phase == UpdatePhase.READY && !working
            confirm.isEnabled = s.phase == UpdatePhase.CONFIRM && updates.installing
            cancel.isEnabled = working
        } } }
    }

    override fun onPause() {
        ui?.cancel(); ui = null
        updates.foreground(false)
        super.onPause()
    }

    @Deprecated("Uses platform activity result for PackageInstaller's confirmation UI")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CONFIRM_INSTALL) updates.confirmationReturned(resultCode != RESULT_OK)
    }

    companion object { private const val CONFIRM_INSTALL = 1 }
}
