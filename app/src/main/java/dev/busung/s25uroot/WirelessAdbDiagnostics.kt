package dev.busung.s25uroot

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class WirelessAdbAuthState {
    NoCredential,
    SavedUnverified,
    Valid,
    PairingRejected,
    MdnsUnavailable,
    PermissionRequired,
    ConnectionFailed,
}

internal data class WirelessAdbDiagnosticSnapshot(
    val credentialFlag: Boolean,
    val keyPresent: Boolean,
    val fingerprint: String?,
    val wirelessDebuggingEnabled: Boolean,
    val connectPort: Int? = null,
    val authState: WirelessAdbAuthState,
    val detail: String = "",
)

/**
 * Diagnostics for RMG's own local Wireless ADB identity.
 *
 * A saved preference/key is deliberately treated as "unverified" until a real
 * TLS ADB connection succeeds. This avoids conflating persisted state with the
 * device-side authorization stored by adbd.
 */
internal object WirelessAdbDiagnostics {
    fun passiveSnapshot(context: Context): WirelessAdbDiagnosticSnapshot {
        val keyPresent = AdbCredentialStore.hasStoredKey(context)
        val credentialFlag = AppPreferences.adbPaired(context)
        val state = when {
            !keyPresent -> WirelessAdbAuthState.NoCredential
            else -> WirelessAdbAuthState.SavedUnverified
        }
        val detail = when {
            credentialFlag && !keyPresent -> "Saved pairing flag is stale because the local ADB key is missing"
            !credentialFlag && keyPresent -> "Local ADB key exists, but its current device authorization is unverified"
            keyPresent -> "Credential is saved; run a connection test to verify adbd still accepts it"
            else -> "No local RMG ADB credential is stored"
        }
        return WirelessAdbDiagnosticSnapshot(
            credentialFlag = credentialFlag,
            keyPresent = keyPresent,
            fingerprint = AdbCredentialStore.fingerprint(context),
            wirelessDebuggingEnabled = AdbPairing.isWirelessAdbEnabled(context),
            authState = state,
            detail = detail,
        )
    }

    suspend fun testConnection(context: Context): WirelessAdbDiagnosticSnapshot =
        withContext(Dispatchers.IO) {
            val initial = passiveSnapshot(context)
            if (!initial.keyPresent) {
                AppPreferences.setAdbPaired(context, false)
                return@withContext initial.copy(
                    credentialFlag = false,
                    authState = WirelessAdbAuthState.NoCredential,
                    detail = "No local RMG ADB key is available; pair first",
                )
            }

            if (!AdbPairing.hasWriteSecureSettings(context)) {
                return@withContext initial.copy(
                    authState = WirelessAdbAuthState.PermissionRequired,
                    detail = "WRITE_SECURE_SETTINGS is required to perform the temporary local ADB test",
                )
            }

            var discoveredPort: Int? = null
            try {
                TemporaryWirelessAdb.use(
                    context = context,
                    settleMillis = ENABLE_SETTLE_MILLIS,
                ) {
                    val port = AdbPairing.discoverConnectPort(context, DISCOVERY_TIMEOUT_MILLIS)
                    if (port <= 0) throw ConnectPortNotFoundException()
                    discoveredPort = port

                    val keyManager = AdbKeyManager(context)
                    val result = LocalAdbClient.shellOnce(
                        host = "127.0.0.1",
                        port = port,
                        keyManager = keyManager,
                        command = "id",
                    )
                    if (result.exitCode != 0 || !result.output.contains("uid=")) {
                        throw IOException(
                            "ADB shell verification failed: ${result.output.trim().takeLast(180)}",
                        )
                    }
                }

                AppPreferences.setAdbPaired(context, true)
                passiveSnapshot(context).copy(
                    credentialFlag = true,
                    connectPort = discoveredPort,
                    authState = WirelessAdbAuthState.Valid,
                    detail = "TLS authentication succeeded and local ADB shell returned a valid identity",
                )
            } catch (error: Throwable) {
                val pairingRejected = LocalAdbClient.isPairingLostError(error) ||
                    LocalAdbClient.PAIRING_LOST_MARKER in (error.message ?: "")
                val state = when {
                    pairingRejected -> WirelessAdbAuthState.PairingRejected
                    error is ConnectPortNotFoundException -> WirelessAdbAuthState.MdnsUnavailable
                    else -> WirelessAdbAuthState.ConnectionFailed
                }
                if (pairingRejected) AppPreferences.setAdbPaired(context, false)

                passiveSnapshot(context).copy(
                    credentialFlag = AppPreferences.adbPaired(context),
                    connectPort = discoveredPort,
                    authState = state,
                    detail = when (state) {
                        WirelessAdbAuthState.PairingRejected ->
                            "adbd rejected RMG's TLS certificate; re-pairing is required"
                        WirelessAdbAuthState.MdnsUnavailable ->
                            "Local ADB connect port was not found through service.adb.tls.port or mDNS"
                        else -> error.message ?: error.javaClass.simpleName
                    },
                )
            }
        }

    private class ConnectPortNotFoundException : IOException(
        "Local ADB connect port not found via service.adb.tls.port or mDNS",
    )

    private const val ENABLE_SETTLE_MILLIS = 1_000L
    private const val DISCOVERY_TIMEOUT_MILLIS = 15_000L
}
