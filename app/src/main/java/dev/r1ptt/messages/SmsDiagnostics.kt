package dev.r1ptt.messages

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/** Local, non-secret evidence. Never reads a phone number, subscriber identity, inbox, or location. */
object SmsDiagnostics {
    fun errorName(code: Int): String = SmsManager::class.java.fields.firstOrNull {
        it.name.startsWith("RESULT_") && it.type == Int::class.javaPrimitiveType && runCatching { it.getInt(null) == code }.getOrDefault(false)
    }?.name ?: "UNKNOWN_ANDROID_RESULT"

    fun advice(code: Int): String = when (code) {
        0 -> "This is not a valid SMS failure result. Version 0.3.0 lost callback results after goAsync(), recording 0 even for successful sends. The original Android result cannot be recovered; check with the recipient and do not automatically resend."
        SmsManager.RESULT_NO_DEFAULT_SMS_APP -> "Android reported no default SMS app. Choose robotOS as the default in Options. This attempt will not be retried."
        SmsManager.RESULT_ERROR_RADIO_OFF, SmsManager.RESULT_RADIO_NOT_AVAILABLE, SmsManager.RESULT_RIL_RADIO_NOT_AVAILABLE ->
            "The radio was unavailable. Wake the device and wait for cellular service before reviewing a new attempt."
        SmsManager.RESULT_ERROR_NO_SERVICE, SmsManager.RESULT_RIL_NETWORK_NOT_READY ->
            "SMS service was unavailable. Check SIM registration and carrier SMS provisioning; working mobile data is not proof of SMS service."
        SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> "The SIM fixed-dialing restriction blocked this destination. Check with the SIM owner or carrier."
        SmsManager.RESULT_INVALID_SMSC_ADDRESS, SmsManager.RESULT_RIL_INVALID_SMSC_ADDRESS -> "Android reported an invalid SMS service center. Ask the carrier to verify SMS provisioning; robotOS does not change the SMSC."
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Android returned a generic failure. A radio error, when supplied, may help the carrier identify it. This alone does not identify a plan restriction."
        else -> "Keep this result for troubleshooting. It does not establish whether the carrier plan includes SMS. Review any new attempt explicitly; there is no automatic retry."
    }

    fun attempt(record: SmsRecord): String = buildString {
        append("Recorded subscription: ${record.subscriptionId}\n")
        record.parts.forEachIndexed { index, part ->
            append("Part ${index + 1}: ${part.sent.name}; delivery ${part.delivery.name}\n")
            part.error?.let { code ->
                append("Result: $code · ${errorName(code)}\n")
                append("Source: ${part.failureSource ?: "legacy result; callback/exception source was not retained"}\n")
                append("Radio error: ${part.radioError?.toString() ?: "not retained or not supplied"}\n")
                append(advice(code)); append("\n")
            }
        }
        append("\nAt dispatch:\n${record.sendEvidence ?: "Not recorded by the earlier app version."}")
    }

    @Suppress("DEPRECATION")
    fun current(context: Context, cellular: Boolean): String {
        val sub = SubscriptionManager.getDefaultSmsSubscriptionId()
        val tm = context.getSystemService(TelephonyManager::class.java)?.createForSubscriptionId(sub)
        fun permission(name: String) = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
        fun value(get: () -> Any?): String = runCatching { get()?.toString() ?: "unavailable" }.getOrDefault("unavailable")
        return buildString {
            append("Default SMS subscription: $sub\n")
            append("SIM slot: ${SubscriptionManager.getSlotIndex(sub).let { if (it < 0) "unavailable" else (it + 1).toString() }}\n")
            append("Carrier: ${value { tm?.networkOperatorName?.takeIf { it.isNotBlank() } }}\n")
            append("Device SMS capability: ${value { tm?.isSmsCapable }}\n")
            append("SIM state: ${value { tm?.simState }} (5 = ready)\n")
            append("Airplane mode: ${Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0}\n")
            append("robotOS cellular enabled: $cellular\n")
            append("SEND_SMS: ${permission(Manifest.permission.SEND_SMS)}\nRECEIVE_SMS: ${permission(Manifest.permission.RECEIVE_SMS)}\n")
            append("Default SMS role: ${SmsRole.held(context)}\n")
            append("SIM readiness is not network or SMS-service readiness.\n")
            append("Carrier plan SMS entitlement: not reported by Android. Data service does not confirm SMS provisioning.")
        }
    }
}
