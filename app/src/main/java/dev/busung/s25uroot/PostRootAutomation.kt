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
 * Wireless ADB and Shizuku are never prerequisites for acquiring root.
 *
 * Module activation/zygote refresh has exactly one root-side owner. The app no
 * longer replays KernelSU post-fs-data/services/boot-completed and never kills
 * zygote itself; it only launches PostRootModuleKeeper and then gets out of the
 * way before that keeper performs the guarded one-time respawn.
 */
internal object PostRootAutomation {
    suspend fun run(
        context: Context,
        softReboot: Boolean,
        startShizuku: Boolean,
        onLog: (String) -> Unit = {},
    ): PostRootResult = withContext(Dispatchers.IO) {
        if (!softReboot && !startShizuku) return@withContext PostRootResult()

        if (!AppPreferences.adbPaired(context)) {
            // Pairing needs POST_NOTIFICATIONS because the pairing code is entered
            // through the foreground-service notification. Never start that service
            // blindly from Auto Root: on Android 13+ a fresh install would have no
            // visible RemoteInput if notification permission has not been granted.
            // A manual install runs in the main process with a visible activity, so
            // hand off to a tiny permission activity there. Auto Root simply records
            // that one-time pairing is still required.
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
            val detail = context.getString(R.string.postroot_adb_not_paired)
            onLog("[!] $detail")
            return@withContext PostRootResult(detail = detail)
        }

        if (!AdbPairing.isWirelessAdbEnabled(context)) {
            if (!AdbPairing.enableWirelessAdb(context)) {
                val detail = "WRITE_SECURE_SETTINGS is not granted; cannot enable Wireless Debugging"
                onLog("[-] $detail")
                return@withContext PostRootResult(detail = detail)
            }
            onLog("[+] Wireless Debugging enabled locally")
            delay(1_500)
        }

        val session = runCatching { WirelessAdbSession.open(context, 45_000) }
            .getOrElse { error ->
                val detail = error.message ?: error.javaClass.simpleName
                if (looksLikePairingLoss(detail)) AppPreferences.setAdbPaired(context, false)
                onLog("[-] Local Wireless ADB connection failed: $detail")
                return@withContext PostRootResult(detail = detail)
            }

        onLog("[+] ${context.getString(R.string.postroot_adb_connected)}")
        var rebootStarted = false
        var shizukuStarted = false

        try {
            val su = session.shell("su -c id 2>&1")
            if (su.exitCode != 0 || !su.output.contains("uid=0")) {
                val detail = "KernelSU shell root unavailable: ${su.output.trim().takeLast(180)}"
                onLog("[-] $detail")
                return@withContext PostRootResult(detail = detail)
            }
            onLog("[+] KernelSU --allow-shell verified over local ADB")

            // One-time self-grant. Once this succeeds, the app can re-enable
            // Wireless Debugging on subsequent full boots before local ADB is
            // reachable, which removes the Tasker bootstrap dependency.
            if (!AdbPairing.hasWriteSecureSettings(context)) {
                val grant = session.shell(
                    "su -c 'pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS' 2>&1",
                )
                if (grant.exitCode == 0) {
                    onLog("[+] WRITE_SECURE_SETTINGS granted for future boots")
                } else {
                    onLog("[!] WRITE_SECURE_SETTINGS grant failed: ${grant.output.trim().takeLast(180)}")
                }
            }

            // Start Shizuku before the optional zygote refresh. The Shizuku
            // server is shell-owned app_process, not an app zygote child; doing
            // this first avoids making its bootstrap depend on reconnecting the
            // app after the framework refresh.
            if (startShizuku) {
                val start = startShizuku(session)
                if (start.exitCode != 0) {
                    val detail = start.output.trim().ifBlank { "start.sh exit ${start.exitCode}" }
                    onLog("[-] ${context.getString(R.string.postroot_shizuku_failed, detail.takeLast(180))}")
                    if (!softReboot) {
                        return@withContext PostRootResult(detail = detail)
                    }
                } else {
                    val output = start.output.trim()
                    if (output.isNotBlank()) onLog("[*] $output")
                    shizukuStarted = ShizukuController.pingUntilRunning(SHIZUKU_BINDER_TIMEOUT_MILLIS)
                    if (shizukuStarted) {
                        onLog("[+] ${context.getString(R.string.postroot_shizuku_started)}")
                    } else {
                        onLog("[!] Shizuku start.sh returned successfully but Binder did not appear")
                    }
                }
            }

            if (softReboot) {
                val bootId = AutoRootSupport.currentBootToken()
                if (bootId.isNullOrBlank()) {
                    return@withContext PostRootResult(
                        shizukuStarted = shizukuStarted,
                        detail = "kernel boot id unavailable before module refresh",
                    )
                }

                // The keeper is the only owner of module-readiness checks and
                // zygote respawn. It is detached from the app so the Android
                // processes may die during the refresh without losing the job or
                // launching a competing second restart.
                val keeper = PostRootModuleKeeper.launch(
                    adb = session,
                    expectedBootId = bootId,
                    onLog = onLog,
                )
                if (!keeper.accepted) {
                    return@withContext PostRootResult(
                        shizukuStarted = shizukuStarted,
                        detail = keeper.detail,
                    )
                }
                rebootStarted = true
                if (keeper.alreadyDone) {
                    onLog("[+] Module refresh marker already satisfied for boot_id=$bootId")
                } else {
                    onLog("[*] Module keeper owns the pending zygote refresh for boot_id=$bootId")
                }
            }
        } finally {
            runCatching { session.close() }
        }

        PostRootResult(
            softRebootStarted = rebootStarted,
            shizukuStarted = shizukuStarted,
            detail = "post-root automation accepted",
        )
    }

    private fun startShizuku(adb: WirelessAdbSession): LocalAdbClient.ShellResult =
        adb.shell(
            "script=''; " +
                "for p in " +
                "'/sdcard/Android/data/moe.shizuku.privileged.api/start.sh' " +
                "'/storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh'; do " +
                "[ -f \"\$p\" ] && script=\"\$p\" && break; done; " +
                "[ -n \"\$script\" ] || { echo 'Shizuku start.sh not found' >&2; exit 44; }; " +
                "sh \"\$script\"",
        )

    private fun looksLikePairingLoss(message: String): Boolean {
        val lower = message.lowercase()
        return "certificate_unknown" in lower ||
            "certificate unknown" in lower ||
            "pairing" in lower && "revoked" in lower ||
            LocalAdbClient.PAIRING_LOST_MARKER.lowercase() in lower
    }

    private const val SHIZUKU_BINDER_TIMEOUT_MILLIS = 12_000L
}
