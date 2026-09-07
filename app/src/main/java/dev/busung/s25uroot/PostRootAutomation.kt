package dev.busung.s25uroot

import android.app.Application
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class PostRootResult(
    val softRebootStarted: Boolean = false,
    val shizukuStarted: Boolean = false,
    val detail: String = "",
)

/**
 * Post-root userspace automation. This object is deliberately isolated from the
 * exploit path: callers invoke it only after KernelSU has been verified.
 *
 * An already-running Shizuku Binder is the preferred post-root shell bridge.
 * When the Binder is absent, the app-authenticated v0266 root-helper daemon is
 * tried next so the Shizuku starter does not depend on Wireless ADB. Local ADB
 * remains compatibility-only fallback. Module activation/zygote refresh remains
 * owned by one detached root keeper and the app never replays KernelSU lifecycle
 * stages.
 */
internal object PostRootAutomation {
    suspend fun run(
        context: Context,
        softReboot: Boolean,
        startShizuku: Boolean,
        onLog: (String) -> Unit = {},
    ): PostRootResult = withContext(Dispatchers.IO) {
        if (!softReboot && !startShizuku) return@withContext PostRootResult()

        // Root is now verified. The pre-root coordinator no longer owns Shizuku
        // startup for this boot. Stopping it also cancels any narrow ADB window
        // before the root-capable path proceeds, eliminating an RMG-vs-RMG race.
        if (startShizuku) {
            ShizukuBootService.stop(context)
        }

        // The user's thedjchi/Shizuku fork commonly has its Binder available
        // before Root My Galaxy runs. In that case it is already a shell/root
        // transport and Wireless ADB must not be touched.
        if (ShizukuController.pingUntilRunning(SHIZUKU_ALREADY_RUNNING_PROBE_MILLIS)) {
            onLog("[+] Shizuku Binder already available; skipping Wireless ADB bootstrap")
            val shizukuRoot = rootShellFromShizuku(onLog)
            if (shizukuRoot != null) {
                grantWriteSecureSettingsIfNeeded(context, shizukuRoot, onLog)
                return@withContext finishPostRoot(
                    context = context,
                    rootShell = shizukuRoot,
                    softReboot = softReboot,
                    shizukuStarted = true,
                    onLog = onLog,
                )
            }

            onLog("[!] Shizuku Binder is alive but cannot obtain KernelSU shell root; trying app root helper")
        }

        val helperRoot = rootShellFromAppHelper(context, onLog)
        if (helperRoot != null) {
            grantWriteSecureSettingsIfNeeded(context, helperRoot, onLog)

            var shizukuStarted = ShizukuController.pingUntilRunning(500)
            if (startShizuku && !shizukuStarted) {
                shizukuStarted = startShizukuWithRootOrIntent(context, helperRoot, onLog)
                if (!shizukuStarted && !softReboot) {
                    return@withContext PostRootResult(detail = "No Shizuku startup method produced a Binder")
                }
            } else if (shizukuStarted) {
                onLog("[+] Shizuku Binder became available before helper startup; no restart needed")
            }

            val preferredRoot = if (shizukuStarted) {
                rootShellFromShizuku(onLog) ?: helperRoot
            } else {
                helperRoot
            }
            return@withContext finishPostRoot(
                context = context,
                rootShell = preferredRoot,
                softReboot = softReboot,
                shizukuStarted = shizukuStarted,
                onLog = onLog,
            )
        }

        if (ShizukuController.isRunning()) {
            val detail = "Shizuku Binder is available but no root-capable post-root bridge is usable"
            onLog("[-] $detail; Wireless ADB fallback suppressed")
            return@withContext PostRootResult(
                shizukuStarted = true,
                detail = detail,
            )
        }

        onLog("[!] App-authenticated root helper unavailable; trying local ADB fallback")
        val session = openLocalAdbFallback(context, onLog)
            ?: return@withContext PostRootResult(
                shizukuStarted = ShizukuController.isRunning(),
                detail = context.getString(R.string.postroot_adb_not_paired),
            )

        try {
            val adbRoot: (String) -> LocalAdbClient.ShellResult = { command ->
                session.shell("su -c ${shellQuote(command)} 2>&1")
            }
            val rootCheck = adbRoot("id")
            if (rootCheck.exitCode != 0 || !rootCheck.output.contains("uid=0")) {
                val detail = "KernelSU shell root unavailable: ${rootCheck.output.trim().takeLast(180)}"
                onLog("[-] $detail")
                return@withContext PostRootResult(detail = detail)
            }
            onLog("[+] KernelSU --allow-shell verified over local ADB fallback")

            grantWriteSecureSettingsIfNeeded(context, adbRoot, onLog)

            var shizukuStarted = ShizukuController.pingUntilRunning(500)
            if (startShizuku && !shizukuStarted) {
                shizukuStarted = startShizukuWithRootOrIntent(context, adbRoot, onLog)
                if (!shizukuStarted && !softReboot) {
                    return@withContext PostRootResult(detail = "No Shizuku startup method produced a Binder")
                }
            } else if (shizukuStarted) {
                onLog("[+] Shizuku Binder became available before fallback startup; no restart needed")
            }

            return@withContext finishPostRoot(
                context = context,
                rootShell = adbRoot,
                softReboot = softReboot,
                shizukuStarted = shizukuStarted,
                onLog = onLog,
            )
        } finally {
            runCatching { session.close() }
            // Security invariant: RMG never leaves Wireless Debugging enabled
            // after its local-ADB fallback, regardless of its entry state.
            TemporaryWirelessAdb.forceDisable(context, onLog)
        }
    }

    private suspend fun openLocalAdbFallback(
        context: Context,
        onLog: (String) -> Unit,
    ): WirelessAdbSession? {
        if (!AppPreferences.adbPaired(context)) {
            if (Application.getProcessName() == context.packageName) {
                runCatching {
                    context.startActivity(
                        Intent(context, AdbPairingSetupActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { error ->
                    onLog("[!] Unable to open Wireless ADB pairing setup: ${error.message ?: error.javaClass.simpleName}")
                }
            }
            onLog("[!] ${context.getString(R.string.postroot_adb_not_paired)}")
            return null
        }

        if (!TemporaryWirelessAdb.begin(context, onLog)) return null
        delay(LOCAL_ADB_ENABLE_SETTLE_MILLIS)

        return runCatching { WirelessAdbSession.open(context, LOCAL_ADB_DISCOVERY_TIMEOUT_MILLIS) }
            .onSuccess { onLog("[+] Local Wireless ADB fallback connected") }
            .getOrElse { error ->
                val detail = error.message ?: error.javaClass.simpleName
                if (looksLikePairingLoss(detail)) AppPreferences.setAdbPaired(context, false)
                onLog("[-] Local Wireless ADB fallback failed: $detail")
                TemporaryWirelessAdb.forceDisable(context, onLog)
                null
            }
    }

    private fun rootShellFromShizuku(
        onLog: (String) -> Unit,
    ): ((String) -> LocalAdbClient.ShellResult)? {
        val direct = runCatching { ShizukuController.shell("id") }.getOrNull()
        if (direct != null && direct.exitCode == 0 && direct.output.contains("uid=0")) {
            onLog("[+] Shizuku server already runs as root")
            return { command -> ShizukuController.shell(command) }
        }

        val elevated = runCatching { ShizukuController.shell("su -c id") }.getOrNull()
        if (elevated != null && elevated.exitCode == 0 && elevated.output.contains("uid=0")) {
            onLog("[+] KernelSU --allow-shell verified through existing Shizuku Binder")
            return { command ->
                ShizukuController.shell("su -c ${shellQuote(command)}")
            }
        }
        return null
    }

    private fun rootShellFromAppHelper(
        context: Context,
        onLog: (String) -> Unit,
    ): ((String) -> LocalAdbClient.ShellResult)? {
        val rootCheck = RootHelperShell.shell(context, "id")
        if (rootCheck.exitCode == 0 && rootCheck.output.contains("uid=0")) {
            onLog("[+] RMG app-authenticated root helper verified; Wireless ADB is not required")
            return { command -> RootHelperShell.shell(context, command) }
        }
        val detail = rootCheck.output.trim().takeLast(180)
        if (detail.isNotBlank()) {
            onLog("[!] RMG root helper unavailable for post-root commands: $detail")
        }
        return null
    }

    private fun grantWriteSecureSettingsIfNeeded(
        context: Context,
        rootShell: (String) -> LocalAdbClient.ShellResult,
        onLog: (String) -> Unit,
    ) {
        if (AdbPairing.hasWriteSecureSettings(context)) return
        val grant = rootShell(
            "pm grant ${shellQuote(context.packageName)} android.permission.WRITE_SECURE_SETTINGS",
        )
        if (grant.exitCode == 0) {
            onLog("[+] WRITE_SECURE_SETTINGS granted for future background automation")
        } else {
            onLog("[!] WRITE_SECURE_SETTINGS grant failed: ${grant.output.trim().takeLast(180)}")
        }
    }

    private suspend fun startShizukuWithRootOrIntent(
        context: Context,
        rootShell: (String) -> LocalAdbClient.ShellResult,
        onLog: (String) -> Unit,
    ): Boolean {
        val shellOutcome = ShizukuStarter.start(
            context = context,
            shell = rootShell,
            binderTimeoutMillis = SHIZUKU_BINDER_TIMEOUT_MILLIS,
            onLog = onLog,
        )
        if (shellOutcome.started) {
            onLog("[+] Shizuku root-mode startup completed via ${shellOutcome.method ?: "compatible starter"}")
            return true
        }

        val intentOutcome = ShizukuIntentStarter.start(
            context = context,
            binderTimeoutMillis = SHIZUKU_BINDER_TIMEOUT_MILLIS,
            onLog = onLog,
        )
        if (intentOutcome.started) return true

        val detail = listOf(shellOutcome.detail, intentOutcome.detail)
            .filter { it.isNotBlank() }
            .joinToString("; ")
            .ifBlank { "no compatible Shizuku starter produced a Binder" }
        onLog("[-] ${context.getString(R.string.postroot_shizuku_failed, detail.takeLast(180))}")
        return false
    }

    private fun finishPostRoot(
        context: Context,
        rootShell: (String) -> LocalAdbClient.ShellResult,
        softReboot: Boolean,
        shizukuStarted: Boolean,
        onLog: (String) -> Unit,
    ): PostRootResult {
        if (!softReboot) {
            return PostRootResult(
                shizukuStarted = shizukuStarted,
                detail = "post-root automation complete",
            )
        }

        val bootId = AutoRootSupport.currentBootToken()
        if (bootId.isNullOrBlank()) {
            return PostRootResult(
                shizukuStarted = shizukuStarted,
                detail = "kernel boot id unavailable before module refresh",
            )
        }

        val keeper = PostRootModuleKeeper.launch(
            rootShell = rootShell,
            expectedBootId = bootId,
            onLog = onLog,
        )
        if (!keeper.accepted) {
            onLog("[-] Module keeper launch failed: ${keeper.detail}")
            return PostRootResult(
                shizukuStarted = shizukuStarted,
                detail = keeper.detail,
            )
        }

        if (keeper.alreadyDone) {
            onLog("[+] Module refresh marker already satisfied for boot_id=$bootId")
        } else {
            onLog("[*] Module keeper confirmed running for boot_id=$bootId")
        }
        return PostRootResult(
            softRebootStarted = true,
            shizukuStarted = shizukuStarted,
            detail = "post-root automation accepted",
        )
    }

    private fun looksLikePairingLoss(message: String): Boolean {
        val lower = message.lowercase()
        return "certificate_unknown" in lower ||
            "certificate unknown" in lower ||
            ("pairing" in lower && "revoked" in lower) ||
            LocalAdbClient.PAIRING_LOST_MARKER.lowercase() in lower
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private const val SHIZUKU_ALREADY_RUNNING_PROBE_MILLIS = 750L
    private const val SHIZUKU_BINDER_TIMEOUT_MILLIS = 12_000L
    private const val LOCAL_ADB_ENABLE_SETTLE_MILLIS = 800L
    private const val LOCAL_ADB_DISCOVERY_TIMEOUT_MILLIS = 15_000L
}
