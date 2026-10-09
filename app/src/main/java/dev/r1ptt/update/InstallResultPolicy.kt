package dev.r1ptt.update

enum class InstallResult { IGNORE, CONFIRM, SUCCESS, FAILURE }

/** PackageInstaller's documented status values: -1 requires user action; 0 is success. */
object InstallResultPolicy {
    fun classify(expectedSession: Int, receivedSession: Int, expectedAction: Boolean,
                 status: Int, hasConfirmation: Boolean): InstallResult {
        if (!expectedAction || expectedSession < 0 || receivedSession != expectedSession) return InstallResult.IGNORE
        return when (status) {
            -1 -> if (hasConfirmation) InstallResult.CONFIRM else InstallResult.FAILURE
            0 -> InstallResult.SUCCESS
            else -> InstallResult.FAILURE // unknown/new/error statuses never imply success
        }
    }
}
