package dev.busung.s25uroot

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
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
            if (startShizuku) {
                runCatching {
                    ContextCompat.startForegroundService(context, AdbPairingService.startIntent(context))
                }
                onLog("[!] ${context.getString(R.string.postroot_adb_not_paired)}")
            }
            return@withContext PostRootResult(
                detail = context.getString(R.string.postroot_adb_not_paired),
            )
        }

        // WRITE_SECURE_SETTINGS is granted best-effort by the root helper when
        // bootstrap root lands. It persists across reboots, so future boots can
        // enable Wireless Debugging without Tasker or a PC.
        if (!AdbPairing.isWirelessAdbEnabled(context)) {
            if (!AdbPairing.enableWirelessAdb(context)) {
                val detail = "WRITE_SECURE_SETTINGS is not granted; cannot enable Wireless Debugging"
                onLog("[-] $detail")
                return@withContext PostRootResult(detail = detail)
            }
            onLog("[+] Wireless Debugging enabled locally")
            delay(1_500)
        }

        var session = runCatching { WirelessAdbSession.open(context, 45_000) }
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

            if (softReboot) {
                val lifecycle = applyKernelSuLifecycle(session, onLog)
                if (!lifecycle.first) {
                    return@withContext PostRootResult(detail = lifecycle.second)
                }
                rebootStarted = true
                onLog("[+] ${context.getString(R.string.postroot_soft_reboot_started)}")

                // Restarting zygote may invalidate framework-side connections.
                // Close cleanly and reconnect after the new userspace settles.
                session.close()
                delay(8_000)
                if (startShizuku) {
                    session = runCatching { WirelessAdbSession.open(context, 45_000) }
                        .getOrElse { error ->
                            val detail = "Wireless ADB did not return after zygote restart: " +
                                (error.message ?: error.javaClass.simpleName)
                            onLog("[-] $detail")
                            return@withContext PostRootResult(
                                softRebootStarted = true,
                                detail = detail,
                            )
                        }
                    onLog("[+] Local Wireless ADB reconnected after userspace restart")
                }
            }

            if (startShizuku) {
                val start = startShizuku(session)
                if (start.exitCode != 0) {
                    val detail = start.output.trim().ifBlank { "start.sh exit ${start.exitCode}" }
                    onLog("[-] ${context.getString(R.string.postroot_shizuku_failed, detail.takeLast(180))}")
                    return@withContext PostRootResult(
                        softRebootStarted = rebootStarted,
                        detail = detail,
                    )
                }
                onLog(start.output.trim().takeIf(String::isNotBlank)?.let { "[*] $it" }.orEmpty())

                shizukuStarted = ShizukuController.pingUntilRunning(SHIZUKU_BINDER_TIMEOUT_MILLIS)
                if (shizukuStarted) {
                    onLog("[+] ${context.getString(R.string.postroot_shizuku_started)}")
                } else {
                    val detail = "Shizuku start.sh returned successfully but Binder did not appear"
                    onLog("[-] $detail")
                    return@withContext PostRootResult(
                        softRebootStarted = rebootStarted,
                        detail = detail,
                    )
                }
            }
        } finally {
            runCatching { session.close() }
        }

        PostRootResult(
            softRebootStarted = rebootStarted,
            shizukuStarted = shizukuStarted,
            detail = "post-root automation complete",
        )
    }

    /** HyperRamzey's device-tested userspace activation route. */
    private suspend fun applyKernelSuLifecycle(
        adb: WirelessAdbSession,
        onLog: (String) -> Unit,
    ): Pair<Boolean, String> {
        suspend fun stage(name: String, waitMillis: Long): Pair<Boolean, String> {
            val command =
                "su -c 'setsid sh -c \"timeout 30 /data/adb/ksud $name " +
                    "> /data/local/tmp/ksud-$name.log 2>&1 < /dev/null\" & echo ${name}_bg'"
            val result = adb.shell(command)
            if (result.exitCode != 0) {
                return false to "ksud $name launch failed: ${result.output.trim().takeLast(180)}"
            }
            onLog("[*] ksud $name launched")
            delay(waitMillis)
            return true to ""
        }

        stage("post-fs-data", 12_000).let { if (!it.first) return it }
        stage("services", 5_000).let { if (!it.first) return it }
        stage("boot-completed", 3_000).let { if (!it.first) return it }

        val kill = adb.shell(
            "su -c 'for p in \$(pidof zygote64) \$(pidof zygote); do " +
                "kill -9 \$p 2>/dev/null; done; echo zygote-killed'",
        )
        if (kill.exitCode != 0 || !kill.output.contains("zygote-killed")) {
            return false to "zygote restart failed: ${kill.output.trim().takeLast(180)}"
        }
        return true to ""
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
            "pairing" in lower && "revoked" in lower
    }

    private const val SHIZUKU_BINDER_TIMEOUT_MILLIS = 12_000L
}
