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

            // A Binder can theoretically be alive in a context that cannot use
            // KernelSU --allow-shell. The app-authenticated helper is the only
            // additional root bridge attempted in this state; ADB stays off.
            onLog("[!] Shizuku Binder is alive but cannot obtain KernelSU shell root; trying app root helper")
        }

        val helperRoot = rootShellFromAppHelper(context, onLog)
        if (helperRoot != null) {
            grantWriteSecureSettingsIfNeeded(context, helperRoot, onLog)

            var shizukuStarted = ShizukuController.pingUntilRunning(500)
            if (startShizuku && !shizukuStarted) {
                val start = startShizukuWithRoot(context, helperRoot, onLog)
                if (start.exitCode != 0) {
                    val detail = start.output.trim().ifBlank {
                        "root-mode Shizuku starter exit ${start.exitCode}"
                    }
                    onLog("[-] ${context.getString(R.string.postroot_shizuku_failed, detail.takeLast(180))}")
                    if (!softReboot) {
                        return@withContext PostRootResult(detail = detail)
                    }
                } else {
                    val output = start.output.trim()
                    if (output.isNotBlank()) onLog("[*] $output")
                    shizukuStarted = ShizukuController.pingUntilRunning(SHIZUKU_BINDER_TIMEOUT_MILLIS)
                    if (shizukuStarted) {
                        onLog("[+] Shizuku root-mode starter completed through RMG root helper; Binder is available")
                    } else {
                        onLog("[!] Shizuku root-mode starter returned successfully but Binder did not appear")
                    }
                }
            } else if (shizukuStarted) {
                onLog("[+] Shizuku Binder became available before helper startup; no restart needed")
            }

            // Once the Binder is live, prefer it for subsequent post-root work.
            // If it is not usable as a root bridge, keep the app-authenticated
            // helper rather than opening Wireless ADB.
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

        // Binder-first is a hard invariant. If Shizuku is alive but neither its
        // own shell nor the RMG helper can provide root, report that condition;
        // do not open Wireless ADB behind an already-available Binder.
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
                val start = startShizukuWithRoot(context, adbRoot, onLog)
                if (start.exitCode != 0) {
                    val detail = start.output.trim().ifBlank { "root-mode Shizuku starter exit ${start.exitCode}" }
                    onLog("[-] ${context.getString(R.string.postroot_shizuku_failed, detail.takeLast(180))}")
                    if (!softReboot) {
                        return@withContext PostRootResult(detail = detail)
                    }
                } else {
                    val output = start.output.trim()
                    if (output.isNotBlank()) onLog("[*] $output")
                    shizukuStarted = ShizukuController.pingUntilRunning(SHIZUKU_BINDER_TIMEOUT_MILLIS)
                    if (shizukuStarted) {
                        onLog("[+] Shizuku root-mode starter completed and Binder is available")
                    } else {
                        onLog("[!] Shizuku root-mode starter returned successfully but Binder did not appear")
                    }
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
        }
    }

    private suspend fun openLocalAdbFallback(
        context: Context,
        onLog: (String) -> Unit,
    ): WirelessAdbSession? {
        if (!AppPreferences.adbPaired(context)) {
            // Local ADB is fallback-only. Pairing is requested only when no
            // usable Shizuku Binder or app-authenticated root helper exists.
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

        if (!AdbPairing.isWirelessAdbEnabled(context)) {
            if (!AdbPairing.enableWirelessAdb(context)) {
                onLog("[-] WRITE_SECURE_SETTINGS is not granted; cannot enable Wireless Debugging fallback")
                return null
            }
            onLog("[*] Wireless Debugging enabled only for local ADB fallback")
            delay(1_500)
        }

        return runCatching { WirelessAdbSession.open(context, 45_000) }
            .onSuccess { onLog("[+] Local Wireless ADB fallback connected") }
            .getOrElse { error ->
                val detail = error.message ?: error.javaClass.simpleName
                if (looksLikePairingLoss(detail)) AppPreferences.setAdbPaired(context, false)
                onLog("[-] Local Wireless ADB fallback failed: $detail")
                null
            }
    }

    /**
     * Return a root-command executor backed by an existing Shizuku Binder.
     * Shizuku may itself be running as root or as adb shell; in shell mode the
     * v0266 --allow-shell policy supplies the one required `su -c` hop.
     */
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

    /**
     * Use the same starter policy as early boot: current native libshizuku.so
     * first, then the documented legacy start.sh only when needed. This keeps
     * post-root recovery compatible without making Wireless ADB a dependency.
     */
    private suspend fun startShizukuWithRoot(
        context: Context,
        rootShell: (String) -> LocalAdbClient.ShellResult,
        onLog: (String) -> Unit,
    ): LocalAdbClient.ShellResult {
        val outcome = ShizukuStarter.start(
            context = context,
            shell = rootShell,
            binderTimeoutMillis = SHIZUKU_BINDER_TIMEOUT_MILLIS,
            onLog = onLog,
        )
        return if (outcome.started) {
            LocalAdbClient.ShellResult(
                0,
                "Shizuku started via ${outcome.method ?: "compatible starter"}",
            )
        } else {
            LocalAdbClient.ShellResult(
                44,
                outcome.detail.ifBlank { "no compatible Shizuku starter produced a Binder" },
            )
        }
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
            "pairing" in lower && "revoked" in lower ||
            LocalAdbClient.PAIRING_LOST_MARKER.lowercase() in lower
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private const val SHIZUKU_ALREADY_RUNNING_PROBE_MILLIS = 750L
    private const val SHIZUKU_BINDER_TIMEOUT_MILLIS = 12_000L
}
