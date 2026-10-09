package dev.r1ptt.update

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class UpdateGateTest {
    @Test fun installCannotStartWhileVoiceIsBusyOrScreenIsNotForeground() {
        val gate = UpdateGate()
        assertFalse(gate.begin(true, true))
        assertFalse(gate.begin(false, false))
        assertFalse(gate.installing)
        assertTrue(gate.begin(true, false))
        assertTrue(gate.installing)
    }
    @Test fun onlyOneInstallHandoffIsAdmittedUntilCompletionOrCancellation() {
        val gate = UpdateGate()
        assertTrue(gate.begin(true, false))
        assertFalse(gate.begin(true, false))
        gate.finish()
        assertFalse(gate.installing)
        assertTrue(gate.begin(true, false))
    }
    @Test fun updateTransportOnlyAcceptsHttpsRepositoryAndAssetHosts() {
        for (url in listOf(ReleaseManifest.METADATA_URL,
            "https://github.com/windoze95/robotOS/releases/download/v0.2.0/robotOS.apk",
            "https://release-assets.githubusercontent.com/a?signature=fixture", "https://objects.githubusercontent.com/a"))
            assertTrue(url, UpdateHttp.allowed(url.toHttpUrl()))
        for (url in listOf("http://github.com/windoze95/robotOS/releases/a", "https://evil.example/a",
            "https://github.com.evil.example/windoze95/robotOS/releases/a", "https://github.com/other/repo/releases/a",
            "https://user:password@github.com/windoze95/robotOS/releases/a",
            "https://github.com:444/windoze95/robotOS/releases/a", "https://github.com/windoze95/robotOS/releases/a#fragment",
            "https://github.com/windoze95/robotOS/releases/../../../evil"))
            assertFalse(url, UpdateHttp.allowed(url.toHttpUrl()))
    }

    @Test fun staleInterruptedAndWrongActionCallbacksCannotReleaseOrConfirmANewInstall() {
        for ((expected, received, action) in listOf(Triple(-1, 4, true), Triple(5, 4, true),
            Triple(5, -2, true), Triple(5, 5, false))) {
            for (status in listOf(-1, 0, 1)) assertEquals(InstallResult.IGNORE,
                InstallResultPolicy.classify(expected, received, action, status, true))
        }
    }
    @Test fun onlyMatchingSuccessIsSuccessfulAndPendingActionMustHaveAnIntent() {
        assertEquals(InstallResult.CONFIRM, InstallResultPolicy.classify(5, 5, true, -1, true))
        assertEquals(InstallResult.FAILURE, InstallResultPolicy.classify(5, 5, true, -1, false))
        assertEquals(InstallResult.SUCCESS, InstallResultPolicy.classify(5, 5, true, 0, false))
        for (status in listOf(-2, 1, 2, 3, 4, 5, 6, 7, 8, Int.MAX_VALUE))
            assertEquals(InstallResult.FAILURE, InstallResultPolicy.classify(5, 5, true, status, false))
    }
}
