package dev.r1ptt.sys

import android.app.admin.DeviceAdminReceiver

/**
 * Device-admin hook whose only policy is force-lock: DevicePolicyManager.lockNow() turns the
 * screen off instantly when the button is tapped. Activated by tools/provision.sh.
 */
class AdminReceiver : DeviceAdminReceiver()
