package dev.busung.s25uroot

import android.content.Context
import java.io.File

/**
 * Resolves and starts Shizuku without coupling Root My Galaxy to one packaging
 * generation. Modern Shizuku (including thedjchi/Shizuku) uses the native
 * libshizuku.so starter. Older/manual distributions may expose start.sh on
 * shared storage. The native starter is always preferred; legacy paths are
 * compatibility-only fallbacks and their absence is not an error.
 *
 * All RMG shell-based starts are serialized across app processes and re-check
 * the Binder immediately before every launch. This closes the edge case where
 * the boot service and post-root automation both observed "not running" before
 * one of them had time to publish the Binder.
 */
internal object ShizukuStarter {
    internal data class Outcome(
        val started: Boolean,
        val method: String? = null,
        val detail: String = "",
    )

    suspend fun start(
        context: Context,
        shell: (String) -> LocalAdbClient.ShellResult,
        binderTimeoutMillis: Long,
        onLog: (String) -> Unit = {},
    ): Outcome = ShizukuStartCoordinator.withStartLock(context) {
        startLocked(
            context = context,
            shell = shell,
            binderTimeoutMillis = binderTimeoutMillis,
            onLog = onLog,
        )
    }

    private suspend fun startLocked(
        context: Context,
        shell: (String) -> LocalAdbClient.ShellResult,
        binderTimeoutMillis: Long,
        onLog: (String) -> Unit,
    ): Outcome {
        if (ShizukuController.pingUntilRunning(BINDER_RACE_PROBE_MILLIS)) {
            onLog("[+] Shizuku Binder already available; no starter selected")
            return Outcome(started = true, method = "existing-binder")
        }

        val nativeCommand = nativeCommand(context)
        if (nativeCommand != null) {
            val nativeProbe = shell("test -f ${shellQuote(nativeCommand.starterPath)}")
            if (nativeProbe.exitCode == 0) {
                if (ShizukuController.pingUntilRunning(BINDER_RACE_PROBE_MILLIS)) {
                    onLog("[+] Shizuku Binder appeared before native-lib launch; skipped duplicate starter")
                    return Outcome(started = true, method = "existing-binder")
                }

                onLog("[*] Shizuku startup selected: native-lib")
                val result = shell("${nativeCommand.command} 2>&1")
                if (result.exitCode == 0) {
                    if (ShizukuController.pingUntilRunning(binderTimeoutMillis)) {
                        onLog("[+] Shizuku started via native-lib; Binder is available")
                        return Outcome(started = true, method = "native-lib")
                    }
                    onLog("[!] Shizuku native-lib starter returned successfully but Binder did not appear; checking legacy fallback")
                } else {
                    val detail = result.output.trim().takeLast(240)
                    onLog("[!] Shizuku native-lib starter rc=${result.exitCode}${if (detail.isBlank()) "" else ": $detail"}; checking legacy fallback")
                }
            } else {
                onLog("[*] Shizuku native-lib starter is not present; checking legacy fallback")
            }
        } else {
            onLog("[*] Shizuku native-lib paths are unavailable; checking legacy fallback")
        }

        if (ShizukuController.pingUntilRunning(BINDER_RACE_PROBE_MILLIS)) {
            onLog("[+] Shizuku Binder appeared before legacy fallback; skipped duplicate starter")
            return Outcome(started = true, method = "existing-binder")
        }

        val legacyPath = firstLegacyScript(shell)
        if (legacyPath != null) {
            if (ShizukuController.pingUntilRunning(BINDER_RACE_PROBE_MILLIS)) {
                onLog("[+] Shizuku Binder appeared before legacy start.sh launch; skipped duplicate starter")
                return Outcome(started = true, method = "existing-binder")
            }

            onLog("[*] Shizuku startup selected: legacy-start.sh")
            val result = shell("sh ${shellQuote(legacyPath)} 2>&1")
            if (result.exitCode == 0) {
                if (ShizukuController.pingUntilRunning(binderTimeoutMillis)) {
                    onLog("[+] Shizuku started via legacy-start.sh; Binder is available")
                    return Outcome(started = true, method = "legacy-start.sh")
                }
                val detail = "legacy start.sh returned successfully but Binder did not appear"
                onLog("[!] $detail")
                return Outcome(started = false, method = "legacy-start.sh", detail = detail)
            }

            val output = result.output.trim().takeLast(240)
            val detail = "legacy start.sh failed rc=${result.exitCode}${if (output.isBlank()) "" else ": $output"}"
            onLog("[!] $detail")
            return Outcome(started = false, method = "legacy-start.sh", detail = detail)
        }

        // Missing legacy scripts are normal on current Shizuku builds. Only the
        // aggregate inability to start the service is reported as the outcome.
        val detail = "no compatible Shizuku shell starter produced a Binder"
        onLog("[!] $detail (legacy start.sh not present; skipped)")
        return Outcome(started = false, detail = detail)
    }

    private fun nativeCommand(context: Context): NativeCommand? {
        val appInfo = runCatching {
            context.packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0)
        }.getOrNull() ?: return null

        val nativeLibraryDir = appInfo.nativeLibraryDir?.takeIf { it.isNotBlank() } ?: return null
        val sourceDir = appInfo.sourceDir?.takeIf { it.isNotBlank() } ?: return null
        val starterPath = File(nativeLibraryDir, "libshizuku.so").absolutePath
        return NativeCommand(
            starterPath = starterPath,
            command = "${shellQuote(starterPath)} --apk=${shellQuote(sourceDir)}",
        )
    }

    private fun firstLegacyScript(
        shell: (String) -> LocalAdbClient.ShellResult,
    ): String? {
        for (path in LEGACY_START_PATHS) {
            if (shell("test -f ${shellQuote(path)}").exitCode == 0) return path
        }
        return null
    }

    private data class NativeCommand(
        val starterPath: String,
        val command: String,
    )

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val BINDER_RACE_PROBE_MILLIS = 500L
    private val LEGACY_START_PATHS = arrayOf(
        "/storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh",
        "/sdcard/Android/data/moe.shizuku.privileged.api/start.sh",
    )
}
