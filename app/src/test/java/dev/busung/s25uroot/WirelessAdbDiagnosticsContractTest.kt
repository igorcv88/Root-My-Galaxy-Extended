package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessAdbDiagnosticsContractTest {
    @Test
    fun diagnosticsRequireRealTlsAdbVerification() {
        val source = File("src/main/java/dev/busung/s25uroot/WirelessAdbDiagnostics.kt").readText()

        assertTrue(source.contains("discoverConnectPort"))
        assertTrue(source.contains("LocalAdbClient.shellOnce"))
        assertTrue(source.contains("command = \"id\""))
        assertTrue(source.contains("WirelessAdbAuthState.Valid"))
        assertTrue(source.contains("LocalAdbClient.isPairingLostError"))
        assertTrue(source.contains("AppPreferences.setAdbPaired(context, false)"))
    }

    @Test
    fun rePairCanBypassHistoricalPairingFlag() {
        val source = File("src/main/java/dev/busung/s25uroot/AdbPairingSetupActivity.kt").readText()

        assertTrue(source.contains("EXTRA_FORCE_REPAIR"))
        assertTrue(source.contains("forceRepair"))
        assertTrue(source.contains("AppPreferences.setAdbPaired(this, false)"))
        assertTrue(source.contains("pairingIntent"))
    }

    @Test
    fun staleFlagCannotSilentlyGenerateNewUnpairedKey() {
        val source = File("src/main/java/dev/busung/s25uroot/WirelessAdbSession.kt").readText()
        val keyGuard = source.indexOf("AdbCredentialStore.hasStoredKey(context)")
        val keyManager = source.indexOf("AdbKeyManager(context)")

        assertTrue(keyGuard >= 0)
        assertTrue(keyManager > keyGuard)
        assertTrue(source.contains("AppPreferences.setAdbPaired(context, false)"))
        assertTrue(source.contains("ADB_CREDENTIAL_MISSING"))
    }

    @Test
    fun diagnosticsUiExposesRecoveryWithoutPrivateKeyMaterial() {
        val ui = File("src/main/java/dev/busung/s25uroot/ShizukuBootSettingsCard.kt").readText()
        val credentials = File("src/main/java/dev/busung/s25uroot/AdbCredentialStore.kt").readText()

        assertTrue(ui.contains("WirelessAdbDiagnostics.testConnection"))
        assertTrue(ui.contains("pairingIntent"))
        assertTrue(ui.contains("forgetLocalCredential"))
        assertTrue(ui.contains("WIRELESS_DEBUGGING_SETTINGS"))
        assertTrue(credentials.contains("SHA-256"))
        assertFalse(ui.contains("adb_private.der"))
        assertFalse(credentials.contains("readBytes()).toString"))
    }
}
