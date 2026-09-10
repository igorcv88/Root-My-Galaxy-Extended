package dev.busung.s25uroot

import android.content.Context
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

internal enum class AutoRootStage {
    PreparingExploit,
    RunningExploit,
    LoadingKernelSu,
    VerifyingRoot,
}

private data class AutoRootCommandResult(val code: Int, val output: String)

/**
 * Auto Root executes the exact last-known-good offline payload from a fresh
 * executor. Legacy targets use the app transport; a target whose route policy
 * prefers shell is fail-closed onto Shizuku shell so tracefs is never silently
 * attempted from an untrusted-app SELinux domain.
 *
 * KernelSU is expected to auto-late-load from the UMH root helper as soon as
 * bootstrap root lands. The historical client stage/--late-load path remains a
 * fallback when the persisted /data/local/tmp ksud is unavailable.
 */
internal class AutoRootRunner(
    private val context: Context,
    private val onStage: (AutoRootStage) -> Unit,
    private val onLog: (String) -> Unit = {},
) {
    suspend fun run(payloads: VerifiedPayloads, bootToken: String) {
        require(payloads.source == PayloadSource.Offline) {
            "Auto Root requires the last-known-good offline payload"
        }

        val useShellTransport = payloads.profile.routePolicy.prefersShellTransport
        val transport = if (useShellTransport) {
            ExploitRoutePolicy.SHELL_TRANSPORT
        } else {
            ExploitRoutePolicy.APP_TRANSPORT
        }

        onStage(AutoRootStage.PreparingExploit)
        onLog("[*] profile=${payloads.profile.profileId} transport=$transport source=offline")

        onStage(AutoRootStage.RunningExploit)
        executeExploit(
            payloads.exploit,
            bootToken,
            payloads.profile.routePolicy,
            useShellTransport,
        )

        onStage(AutoRootStage.LoadingKernelSu)
        val autoLoaded = waitForAutoLateLoad(bootToken)

        if (autoLoaded) {
            // Kernel control plus the boot-scoped global readiness invariant prove
            // that blocking late-load/mount stages finished in PID1's namespace.
            // Only now is it safe to persist success and start post-root work.
            onLog("[+] KernelSU auto-late-load globally verified; skipped duplicate late-load")
            onStage(AutoRootStage.VerifyingRoot)
            verifyKernelSu(bootToken)
            return
        }

        // Auto-late-load did not cross the global readiness boundary. Refresh the
        // staged ksud and use the historical client fallback. The patched ksud
        // serializes concurrent late-load callers and suppresses same-boot stage
        // replay, so this fallback cannot race a still-finishing UMH caller.
        onLog("[!] KernelSU auto-late-load not globally ready; using serialized client fallback")
        stageKernelSuRequired(payloads)
        val lateLoad = runHelper("--late-load")
        require(lateLoad.code == 0) {
            context.getString(R.string.error_ksu_verify, lateLoad.code, lateLoad.output)
        }
        if (lateLoad.output.isNotBlank()) onLog(lateLoad.output)

        onStage(AutoRootStage.VerifyingRoot)
        verifyKernelSu(bootToken)
    }

    private suspend fun verifyKernelSu(bootToken: String) {
        val verification = runCatching { runHelper("--ksu-info") }.getOrNull()
        val nativeActive = NativeProbe.isKernelSuActive()
        require(verification?.code == 0 || nativeActive) {
            context.getString(
                R.string.error_ksu_verify,
                verification?.code ?: -1,
                verification?.output ?: "KernelSU control channel is not active",
            )
        }
        if (verification?.code == 0 && verification.output.isNotBlank()) {
            onLog(verification.output)
        }

        val global = KernelSuGlobalReadiness.probe(context, bootToken)
        require(global.exitCode == 0) {
            context.getString(
                R.string.error_ksu_verify,
                global.exitCode,
                global.output.ifBlank { "KernelSU late-load global readiness is not satisfied" },
            )
        }
        if (global.output.isNotBlank()) onLog(global.output)
        onLog("[+] KernelSU control and PID1 mount readiness verified")
    }

    private suspend fun waitForAutoLateLoad(bootToken: String): Boolean {
        val deadline = SystemClock.elapsedRealtime() + AUTO_LATE_LOAD_WAIT_MILLIS
        var lastOutput = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            val probe = runCatching { runHelper("--ksu-info") }.getOrNull()
            val controlActive = probe?.code == 0 || NativeProbe.isKernelSuActive()
            if (controlActive) {
                val global = runCatching {
                    KernelSuGlobalReadiness.probe(context, bootToken)
                }.getOrNull()
                if (global != null) {
                    lastOutput = global.output
                    if (global.exitCode == 0) {
                        if (probe?.code == 0 && probe.output.isNotBlank()) onLog(probe.output)
                        if (global.output.isNotBlank()) onLog(global.output)
                        return true
                    }
                }
            } else if (probe != null && probe.output.isNotBlank()) {
                lastOutput = probe.output
            }
            delay(AUTO_LATE_LOAD_POLL_INTERVAL)
        }
        if (lastOutput.isNotBlank()) {
            onLog("[*] auto-late-load readiness probe: ${lastOutput.takeLast(320)}")
        }

        if (!NativeProbe.isKernelSuActive()) return false
        val finalGlobal = runCatching {
            KernelSuGlobalReadiness.probe(context, bootToken)
        }.getOrNull()
        return finalGlobal?.exitCode == 0
    }

    private suspend fun executeExploit(
        payload: File,
        bootToken: String,
        policy: ExploitRoutePolicy,
        useShellTransport: Boolean,
    ) {
        val transport = if (useShellTransport) {
            ExploitRoutePolicy.SHELL_TRANSPORT
        } else {
            ExploitRoutePolicy.APP_TRANSPORT
        }
        onLog(policy.describe(transport))

        val localLogFile = File(context.filesDir, "autoroot-exploit.log")
        if (!useShellTransport) localLogFile.delete()

        val localHelper = helperFile()
        require(localHelper.canExecute()) { context.getString(R.string.error_helper_unavailable) }

        val originalThreadPriority = runCatching {
            Process.getThreadPriority(Process.myTid())
        }.getOrDefault(Process.THREAD_PRIORITY_DEFAULT)

        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
        val process = try {
            val launched = if (useShellTransport) {
                require(ShizukuController.isGranted()) {
                    "Shizuku shell transport is not authorized"
                }

                val cleanup = ShizukuController.exec(
                    arrayOf("rm", "-f", SHIZUKU_LOG_PATH),
                )
                try {
                    require(cleanup.waitFor() == 0) {
                        "Unable to clear the Auto Root shell log"
                    }
                } finally {
                    if (cleanup.isAlive) cleanup.destroy()
                }

                val stagedHelper = shizukuStage(localHelper, SHIZUKU_HELPER_PATH)
                val stagedPayload = shizukuStage(payload, SHIZUKU_PAYLOAD_PATH)
                ShizukuController.exec(
                    arrayOf(
                        stagedHelper.absolutePath,
                        "--run-payload",
                        stagedPayload.absolutePath,
                        stagedHelper.absolutePath,
                        SHIZUKU_LOG_PATH,
                    ),
                    shizukuEnvironment(bootToken, stagedHelper.absolutePath, policy),
                    "/data/local/tmp",
                )
            } else {
                val processBuilder = ProcessBuilder(
                    localHelper.absolutePath,
                    "--run-payload",
                    payload.absolutePath,
                    localHelper.absolutePath,
                    localLogFile.absolutePath,
                ).redirectErrorStream(true)
                processBuilder.environment().putAll(
                    policy.environment(cachedP0Offset(bootToken)),
                )
                processBuilder.start()
            }

            launched.also {
                val lowered = runCatching {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                }.isSuccess
                if (!lowered) runCatching {
                    Process.setThreadPriority(originalThreadPriority)
                }
            }
        } catch (error: Throwable) {
            runCatching { Process.setThreadPriority(originalThreadPriority) }
            throw error
        }

        val captured = StringBuilder()
        fun readLog(): String {
            drainProcessOutput(process, captured)
            return if (useShellTransport) {
                captured.toString()
            } else {
                localLogFile.readTextIfPresent()
            }
        }

        try {
            val startedAt = SystemClock.elapsedRealtime()
            var lastProgressAt = startedAt
            var lastRawLog = ""
            while (process.isAlive) {
                val rawLog = readLog()
                if (rawLog != lastRawLog) {
                    publishExploitLog(rawLog)
                    lastRawLog = rawLog
                    lastProgressAt = SystemClock.elapsedRealtime()
                }
                val now = SystemClock.elapsedRealtime()
                require(now - lastProgressAt < EXPLOIT_STALL_MILLIS) {
                    context.getString(R.string.error_exploit_stalled)
                }
                require(now - startedAt < EXPLOIT_TOTAL_MILLIS) {
                    context.getString(R.string.error_exploit_timeout)
                }
                delay(
                    if (useShellTransport) {
                        SHIZUKU_LOG_POLL_INTERVAL
                    } else {
                        LOG_POLL_INTERVAL
                    },
                )
            }

            val exitCode = process.waitFor()
            val rawLog = readLog()
            publishExploitLog(rawLog)
            if (policy.p0OffsetCache) cacheP0Offset(bootToken, rawLog)

            val earlyOutput = captured.toString().trim()
            require(exitCode == 0) {
                context.getString(
                    R.string.error_payload_exit,
                    exitCode,
                    earlyOutput.takeIf(String::isNotBlank)?.let { " ($it)" } ?: "",
                )
            }
            require(rawLog.contains("exploit completed") && rawLog.contains("done=1 root=1")) {
                context.getString(R.string.error_success_marker)
            }
        } finally {
            if (process.isAlive) {
                process.destroy()
                delay(500.milliseconds)
                if (process.isAlive) process.destroyForcibly()
            }
            runCatching { Process.setThreadPriority(originalThreadPriority) }
        }
        onLog(context.getString(R.string.log_bootstrap_root))
    }

    private fun shizukuStage(source: File, target: String): File {
        try {
            ShizukuController.writeFile(target, "755", source.inputStream())
        } catch (error: Throwable) {
            throw IllegalStateException(
                "Unable to stage Auto Root shell artifact $target: ${error.message.orEmpty()}",
                error,
            )
        }
        return File(target)
    }

    private fun shizukuEnvironment(
        bootToken: String,
        helperPath: String,
        policy: ExploitRoutePolicy,
    ): Array<String> = buildList {
        add("CVE43499_ROOT_HELPER=$helperPath")
        policy.environment(cachedP0Offset(bootToken)).forEach { (key, value) ->
            add("$key=$value")
        }
    }.toTypedArray()

    private suspend fun stageKernelSuRequired(payloads: VerifiedPayloads) {
        val stage = stageKernelSuAtomically(payloads)
        require(stage.code == 0) { context.getString(R.string.error_ksu_stage, stage.output) }
        onLog(context.getString(R.string.log_ksu_staged))
    }

    private suspend fun stageKernelSuAtomically(payloads: VerifiedPayloads): AutoRootCommandResult {
        val source = shellQuote(payloads.kernelSu.absolutePath)
        val stageCommand =
            "set -e; " +
                "tmp='$KSUD_REFRESH_PATH'; rm -f \"\$tmp\"; " +
                "/system/bin/cp $source \"\$tmp\"; /system/bin/chmod 755 \"\$tmp\"; " +
                "/system/bin/mv -f \"\$tmp\" $KSUD_PATH; " +
                "tmp='$KSUD_STAGE_REFRESH_PATH'; rm -f \"\$tmp\"; " +
                "/system/bin/cp $source \"\$tmp\"; /system/bin/chmod 755 \"\$tmp\"; " +
                "/system/bin/mv -f \"\$tmp\" $KSUD_STAGE_PATH"
        return runHelper("-c", stageCommand)
    }

    private fun helperFile() =
        File(context.applicationInfo.nativeLibraryDir, "libcve43499root.so")

    private suspend fun runHelper(vararg arguments: String): AutoRootCommandResult {
        val process = ProcessBuilder(listOf(helperFile().absolutePath) + arguments)
            .redirectErrorStream(true)
            .start()
        val captured = StringBuilder()
        val startedAt = SystemClock.elapsedRealtime()
        try {
            while (process.isAlive) {
                drainProcessOutput(process, captured)
                require(SystemClock.elapsedRealtime() - startedAt < HELPER_TIMEOUT_MILLIS) {
                    context.getString(
                        R.string.error_helper_timeout,
                        captured.toString().trim().takeIf(String::isNotBlank)
                            ?.let { ": $it" } ?: "",
                    )
                }
                delay(HELPER_POLL_INTERVAL)
            }
            drainProcessOutput(process, captured)
            return AutoRootCommandResult(process.waitFor(), stripAnsi(captured.toString().trim()))
        } finally {
            if (process.isAlive) {
                process.destroy()
                delay(500.milliseconds)
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }

    private fun drainProcessOutput(process: java.lang.Process, buffer: StringBuilder) {
        try {
            drainStream(process.inputStream, buffer)
            drainStream(process.errorStream, buffer)
        } catch (_: Throwable) {
            // The on-disk exploit log remains the source of truth for the payload.
        }
    }

    private fun drainStream(stream: InputStream, buffer: StringBuilder) {
        val data = ByteArray(4096)
        while (stream.available() > 0) {
            val count = stream.read(data)
            if (count <= 0) break
            buffer.append(String(data, 0, count, Charsets.UTF_8))
        }
    }

    private fun publishExploitLog(rawLog: String) {
        val clean = stripAnsi(rawLog).trim()
        if (clean.isNotBlank()) onLog(clean)
    }

    private fun cachedP0Offset(bootToken: String): String? {
        val stored = context.getSharedPreferences(P0_CACHE, Context.MODE_PRIVATE)
        if (stored.getString(P0_CACHE_BOOT_TOKEN, null) != bootToken) return null
        return stored.getString(P0_CACHE_OFFSET, null)
    }

    private fun cacheP0Offset(bootToken: String, log: String) {
        val match = P0_OFFSET_PATTERN.findAll(log).lastOrNull() ?: return
        val offset = match.groupValues[1].toLongOrNull(16) ?: return
        if (offset !in 0..P0_OFFSET_MAX || offset and P0_OFFSET_MASK != 0L) return
        val value = "0x${offset.toString(16)}"
        val stored = context.getSharedPreferences(P0_CACHE, Context.MODE_PRIVATE)
        if (stored.getString(P0_CACHE_BOOT_TOKEN, null) == bootToken &&
            stored.getString(P0_CACHE_OFFSET, null) == value
        ) return
        stored.edit()
            .putString(P0_CACHE_BOOT_TOKEN, bootToken)
            .putString(P0_CACHE_OFFSET, value)
            .apply()
    }

    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"

    private fun File.readTextIfPresent(): String = if (exists()) readText() else ""

    companion object {
        private const val EXPLOIT_STALL_MILLIS = 90_000L
        private const val EXPLOIT_TOTAL_MILLIS = 900_000L
        private const val HELPER_TIMEOUT_MILLIS = 120_000L
        private const val AUTO_LATE_LOAD_WAIT_MILLIS = 8_000L
        private const val P0_CACHE = "p0_cache"
        private const val P0_CACHE_BOOT_TOKEN = "kernel_boot_id"
        private const val P0_CACHE_OFFSET = "offset"
        private const val P0_OFFSET_MAX = 0x1f0000L
        private const val P0_OFFSET_MASK = 0xffffL
        private const val KSUD_PATH = "/data/local/tmp/ksud-s25u-kdp"
        private const val KSUD_STAGE_PATH = "/data/local/tmp/.ksud-stage"
        private const val KSUD_REFRESH_PATH = "/data/local/tmp/.ksud-refresh"
        private const val KSUD_STAGE_REFRESH_PATH = "/data/local/tmp/.ksud-stage-refresh"
        private const val SHIZUKU_LOG_PATH = "/data/local/tmp/autoroot-exploit.log"
        private const val SHIZUKU_HELPER_PATH = "/data/local/tmp/autoroot-helper"
        private const val SHIZUKU_PAYLOAD_PATH = "/data/local/tmp/autoroot-payload"
        private val LOG_POLL_INTERVAL = 250.milliseconds
        private val SHIZUKU_LOG_POLL_INTERVAL = 1_000.milliseconds
        private val HELPER_POLL_INTERVAL = 250.milliseconds
        private val AUTO_LATE_LOAD_POLL_INTERVAL = 400.milliseconds
        private val ANSI_ESCAPE = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
        private val P0_OFFSET_PATTERN = Regex(
            "slide-kaslr-ok[^\\n]*slide=([0-9a-fA-F]{16})",
        )

        private fun stripAnsi(value: String): String = ANSI_ESCAPE.replace(value, "").replace("\r", "")
    }
}
