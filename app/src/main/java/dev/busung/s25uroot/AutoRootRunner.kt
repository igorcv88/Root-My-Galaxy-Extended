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

internal enum class AutoRootShellTransport {
    Shizuku,
    LocalAdb,
}

private data class AutoRootCommandResult(val code: Int, val output: String)

/**
 * Auto Root executes the exact last-known-good offline payload from a fresh
 * executor. Legacy targets use the app transport. A target whose route policy
 * prefers shell is fail-closed onto a real `u:r:shell:s0` transport: Shizuku
 * when its client Binder is usable, otherwise the already-paired local Wireless
 * ADB transport. It never degrades a shell-required target to untrusted-app/P0.
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
    suspend fun run(
        payloads: VerifiedPayloads,
        bootToken: String,
        shellTransport: AutoRootShellTransport? = null,
        beforeExploit: () -> Unit = {},
    ) {
        require(payloads.source == PayloadSource.Offline) {
            "Auto Root requires the last-known-good offline payload"
        }

        val shellRequired = payloads.profile.routePolicy.prefersShellTransport
        if (shellRequired) {
            requireNotNull(shellTransport) {
                "Shell-required Auto Root has no usable shell transport"
            }
        } else {
            require(shellTransport == null) {
                "Legacy Auto Root must not be forced through a shell transport"
            }
        }

        val transportLabel = when (shellTransport) {
            AutoRootShellTransport.Shizuku -> "shell-shizuku"
            AutoRootShellTransport.LocalAdb -> "shell-local-adb"
            null -> ExploitRoutePolicy.APP_TRANSPORT
        }

        onStage(AutoRootStage.PreparingExploit)
        onLog("[*] profile=${payloads.profile.profileId} transport=$transportLabel source=offline")

        onStage(AutoRootStage.RunningExploit)
        executeExploit(
            payload = payloads.exploit,
            bootToken = bootToken,
            policy = payloads.profile.routePolicy,
            shellTransport = shellTransport,
            beforeExploit = beforeExploit,
        )

        onStage(AutoRootStage.LoadingKernelSu)
        withKernelSuClient(shellTransport) { ksuExec ->
            val autoLoaded = waitForAutoLateLoad(bootToken, ksuExec)

            if (autoLoaded) {
                // Keep the same authenticated client principal that acquired root.
                // A shell-launched v0266 daemon authorizes uid 2000, while legacy
                // standalone boots authorize the app client. Switching principals
                // here makes a healthy auto-late-load look unavailable.
                onLog("[+] KernelSU auto-late-load globally verified; skipped duplicate late-load")
                onStage(AutoRootStage.VerifyingRoot)
                verifyKernelSu(bootToken, ksuExec)
            } else {
                onLog("[!] KernelSU auto-late-load not globally ready; using same-transport client fallback")
                logKernelSuAutoStage(ksuExec)
                stageKernelSuRequired(payloads, ksuExec)
                val lateLoad = ksuExec(arrayOf("--late-load"))
                require(lateLoad.code == 0) {
                    context.getString(R.string.error_ksu_verify, lateLoad.code, lateLoad.output)
                }
                if (lateLoad.output.isNotBlank()) onLog(lateLoad.output)

                onStage(AutoRootStage.VerifyingRoot)
                verifyKernelSu(bootToken, ksuExec)
            }
        }
    }

    private suspend fun verifyKernelSu(
        bootToken: String,
        ksuExec: suspend (Array<out String>) -> AutoRootCommandResult,
    ) {
        val verification = runCatching { ksuExec(arrayOf("--ksu-info")) }.getOrNull()
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

        val global = ksuExec(arrayOf("-c", KernelSuGlobalReadiness.command(bootToken)))
        require(global.code == 0) {
            context.getString(
                R.string.error_ksu_verify,
                global.code,
                global.output.ifBlank { "KernelSU late-load global readiness is not satisfied" },
            )
        }
        if (global.output.isNotBlank()) onLog(global.output)
        onLog("[+] KernelSU control and PID1 mount readiness verified")
    }

    private suspend fun waitForAutoLateLoad(
        bootToken: String,
        ksuExec: suspend (Array<out String>) -> AutoRootCommandResult,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + AUTO_LATE_LOAD_WAIT_MILLIS
        var lastOutput = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            val probe = runCatching { ksuExec(arrayOf("--ksu-info")) }.getOrNull()
            val controlActive = probe?.code == 0 || NativeProbe.isKernelSuActive()
            if (controlActive) {
                val global = runCatching {
                    ksuExec(arrayOf("-c", KernelSuGlobalReadiness.command(bootToken)))
                }.getOrNull()
                if (global != null) {
                    lastOutput = global.output
                    if (global.code == 0) {
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
            ksuExec(arrayOf("-c", KernelSuGlobalReadiness.command(bootToken)))
        }.getOrNull()
        return finalGlobal?.code == 0
    }

    private suspend fun executeExploit(
        payload: File,
        bootToken: String,
        policy: ExploitRoutePolicy,
        shellTransport: AutoRootShellTransport?,
        beforeExploit: () -> Unit,
    ) {
        val useShellTransport = shellTransport != null
        onLog(
            policy.describe(
                if (useShellTransport) {
                    ExploitRoutePolicy.SHELL_TRANSPORT
                } else {
                    ExploitRoutePolicy.APP_TRANSPORT
                },
            ),
        )

        val localLogFile = File(context.filesDir, "autoroot-exploit.log")
        if (!useShellTransport) localLogFile.delete()

        val localHelper = helperFile()
        require(localHelper.canExecute()) { context.getString(R.string.error_helper_unavailable) }

        val originalThreadPriority = runCatching {
            Process.getThreadPriority(Process.myTid())
        }.getOrDefault(Process.THREAD_PRIORITY_DEFAULT)

        if (shellTransport == AutoRootShellTransport.LocalAdb) {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
            try {
                executeExploitViaLocalAdb(
                    payload = payload,
                    localHelper = localHelper,
                    bootToken = bootToken,
                    policy = policy,
                    beforeExploit = beforeExploit,
                )
            } finally {
                runCatching { Process.setThreadPriority(originalThreadPriority) }
            }
            onLog(context.getString(R.string.log_bootstrap_root))
            return
        }

        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
        val process = try {
            val launched = if (shellTransport == AutoRootShellTransport.Shizuku) {
                require(ShizukuController.isGranted()) {
                    "Shizuku shell transport is not authorized"
                }

                val cleanup = ShizukuController.exec(
                    arrayOf("rm", "-f", SHELL_LOG_PATH),
                )
                try {
                    require(cleanup.waitFor() == 0) {
                        "Unable to clear the Auto Root shell log"
                    }
                } finally {
                    if (cleanup.isAlive) cleanup.destroy()
                }

                val stagedHelper = shizukuStage(localHelper, SHELL_HELPER_PATH)
                val stagedPayload = shizukuStage(payload, SHELL_PAYLOAD_PATH)
                beforeExploit()
                ShizukuController.exec(
                    arrayOf(
                        stagedHelper.absolutePath,
                        "--run-payload",
                        stagedPayload.absolutePath,
                        stagedHelper.absolutePath,
                        SHELL_LOG_PATH,
                    ),
                    shizukuEnvironment(bootToken, stagedHelper.absolutePath, policy),
                    "/data/local/tmp",
                )
            } else {
                beforeExploit()
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
                        SHELL_LOG_POLL_INTERVAL
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

    private suspend fun executeExploitViaLocalAdb(
        payload: File,
        localHelper: File,
        bootToken: String,
        policy: ExploitRoutePolicy,
        beforeExploit: () -> Unit,
    ) {
        require(AppPreferences.adbPaired(context)) {
            "Shell-required Auto Root needs either an authorized Shizuku Binder or the paired local ADB key"
        }

        TemporaryWirelessAdb.use(
            context = context,
            settleMillis = LOCAL_ADB_SETTLE_MILLIS,
            onLog = onLog,
        ) {
            WirelessAdbSession.open(
                context,
                portDiscoveryTimeoutMs = LOCAL_ADB_PORT_DISCOVERY_TIMEOUT_MILLIS,
            ).use { session ->
                val identity = session.shell("id")
                require(
                    identity.exitCode == 0 &&
                        identity.output.contains("uid=2000") &&
                        identity.output.contains("u:r:shell:s0"),
                ) {
                    "Local ADB did not provide the required u:r:shell:s0 context: " +
                        identity.output.takeLast(240)
                }
                onLog("[+] Local ADB shell transport ready: u:r:shell:s0")

                session.remove(SHELL_LOG_PATH)
                session.push(localHelper, SHELL_HELPER_PATH, executable = true)
                session.push(payload, SHELL_PAYLOAD_PATH, executable = true)

                val env = localAdbEnvironment(
                    bootToken = bootToken,
                    helperPath = SHELL_HELPER_PATH,
                    policy = policy,
                )
                val command = buildString {
                    append("cd /data/local/tmp && ")
                    if (env.isNotBlank()) {
                        append(env)
                        append(' ')
                    }
                    append(shellQuote(SHELL_HELPER_PATH))
                    append(" --run-payload ")
                    append(shellQuote(SHELL_PAYLOAD_PATH))
                    append(' ')
                    append(shellQuote(SHELL_HELPER_PATH))
                    append(' ')
                    append(shellQuote(SHELL_LOG_PATH))
                }

                // Claim the once-per-boot attempt only after a real shell transport
                // exists and both exact offline artifacts have been staged.
                beforeExploit()
                onLog("[*] Launching exploit through paired local ADB shell")

                val streamed = session.runStreaming(
                    command = command,
                    overallTimeoutMs = EXPLOIT_TOTAL_MILLIS,
                    stallTimeoutMs = EXPLOIT_STALL_MILLIS,
                    onOutput = { snapshot -> publishExploitLog(snapshot) },
                )
                val remoteLog = session.readLog(SHELL_LOG_PATH)
                val rawLog = remoteLog.ifBlank { streamed }
                publishExploitLog(rawLog)
                if (policy.p0OffsetCache) cacheP0Offset(bootToken, rawLog)

                require(rawLog.contains("exploit completed") && rawLog.contains("done=1 root=1")) {
                    context.getString(R.string.error_success_marker)
                }
            }
        }
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

    private fun localAdbEnvironment(
        bootToken: String,
        helperPath: String,
        policy: ExploitRoutePolicy,
    ): String = buildList {
        add("CVE43499_ROOT_HELPER=${shellQuote(helperPath)}")
        policy.environment(cachedP0Offset(bootToken)).forEach { (key, value) ->
            add("$key=${shellQuote(value)}")
        }
    }.joinToString(" ")

    private suspend fun <T> withKernelSuClient(
        shellTransport: AutoRootShellTransport?,
        block: suspend (suspend (Array<out String>) -> AutoRootCommandResult) -> T,
    ): T = when (shellTransport) {
        AutoRootShellTransport.Shizuku -> {
            require(ShizukuController.isGranted()) {
                "Shizuku shell transport disappeared before KernelSU handoff"
            }
            onLog("[*] KernelSU handoff client=shizuku-shell uid=2000")
            block { arguments -> runShizukuHelper(*arguments) }
        }
        AutoRootShellTransport.LocalAdb -> {
            TemporaryWirelessAdb.use(
                context = context,
                settleMillis = LOCAL_ADB_SETTLE_MILLIS,
                onLog = onLog,
            ) {
                WirelessAdbSession.open(
                    context,
                    portDiscoveryTimeoutMs = LOCAL_ADB_PORT_DISCOVERY_TIMEOUT_MILLIS,
                ).use { session ->
                    val identity = session.shell("id")
                    require(
                        identity.exitCode == 0 &&
                            identity.output.contains("uid=2000") &&
                            identity.output.contains("u:r:shell:s0"),
                    ) {
                        "KernelSU handoff local ADB lost u:r:shell:s0: " + identity.output.takeLast(240)
                    }
                    onLog("[*] KernelSU handoff client=local-adb-shell uid=2000")
                    block { arguments -> runLocalAdbHelper(session, *arguments) }
                }
            }
        }
        null -> {
            onLog("[*] KernelSU handoff client=standalone-app")
            block { arguments -> runHelper(*arguments) }
        }
    }

    private suspend fun stageKernelSuRequired(
        payloads: VerifiedPayloads,
        ksuExec: suspend (Array<out String>) -> AutoRootCommandResult,
    ) {
        val stage = ksuExec(arrayOf("-c", kernelSuStageCommand(payloads)))
        require(stage.code == 0) { context.getString(R.string.error_ksu_stage, stage.output) }
        onLog(context.getString(R.string.log_ksu_staged))
    }

    private fun kernelSuStageCommand(payloads: VerifiedPayloads): String {
        // The client only transports this string. The authenticated uid-0 daemon
        // executes it, so the verified app-private source remains readable even
        // when the client itself is shell uid 2000.
        val source = shellQuote(payloads.kernelSu.absolutePath)
        return "set -e; " +
            "tmp='$KSUD_REFRESH_PATH'; rm -f \"\$tmp\"; " +
            "/system/bin/cp $source \"\$tmp\"; /system/bin/chmod 755 \"\$tmp\"; " +
            "/system/bin/mv -f \"\$tmp\" $KSUD_PATH; " +
            "tmp='$KSUD_STAGE_REFRESH_PATH'; rm -f \"\$tmp\"; " +
            "/system/bin/cp $source \"\$tmp\"; /system/bin/chmod 755 \"\$tmp\"; " +
            "/system/bin/mv -f \"\$tmp\" $KSUD_STAGE_PATH"
    }

    private suspend fun logKernelSuAutoStage(
        ksuExec: suspend (Array<out String>) -> AutoRootCommandResult,
    ) {
        val diagnostic = runCatching {
            ksuExec(
                arrayOf(
                    "-c",
                    "cat /data/local/tmp/ksu_auto_stage.log 2>/dev/null || true",
                ),
            )
        }.getOrNull()
        if (diagnostic != null && diagnostic.output.isNotBlank()) {
            onLog("[*] KernelSU UMH stage log: ${diagnostic.output.takeLast(640)}")
        }
    }

    private fun helperFile() =
        File(context.applicationInfo.nativeLibraryDir, "libcve43499root.so")

    private suspend fun runHelper(vararg arguments: String): AutoRootCommandResult {
        val process = ProcessBuilder(listOf(helperFile().absolutePath) + arguments)
            .redirectErrorStream(true)
            .start()
        return awaitHelperProcess(process)
    }

    private suspend fun runShizukuHelper(vararg arguments: String): AutoRootCommandResult {
        val process = ShizukuController.exec(
            arrayOf(SHELL_HELPER_PATH, *arguments),
            dir = "/data/local/tmp",
        )
        return awaitHelperProcess(process)
    }

    private fun runLocalAdbHelper(
        session: WirelessAdbSession,
        vararg arguments: String,
    ): AutoRootCommandResult {
        val command = buildString {
            append(shellQuote(SHELL_HELPER_PATH))
            arguments.forEach { argument ->
                append(' ')
                append(shellQuote(argument))
            }
        }
        val result = session.shell(command)
        return AutoRootCommandResult(result.exitCode, stripAnsi(result.output.trim()))
    }

    private suspend fun awaitHelperProcess(process: java.lang.Process): AutoRootCommandResult {
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
        private const val LOCAL_ADB_SETTLE_MILLIS = 800L
        private const val LOCAL_ADB_PORT_DISCOVERY_TIMEOUT_MILLIS = 30_000L
        private const val P0_CACHE = "p0_cache"
        private const val P0_CACHE_BOOT_TOKEN = "kernel_boot_id"
        private const val P0_CACHE_OFFSET = "offset"
        private const val P0_OFFSET_MAX = 0x1f0000L
        private const val P0_OFFSET_MASK = 0xffffL
        private const val KSUD_PATH = "/data/local/tmp/ksud-s25u-kdp"
        private const val KSUD_STAGE_PATH = "/data/local/tmp/.ksud-stage"
        private const val KSUD_REFRESH_PATH = "/data/local/tmp/.ksud-refresh"
        private const val KSUD_STAGE_REFRESH_PATH = "/data/local/tmp/.ksud-stage-refresh"
        private const val SHELL_LOG_PATH = "/data/local/tmp/autoroot-exploit.log"
        private const val SHELL_HELPER_PATH = "/data/local/tmp/autoroot-helper"
        private const val SHELL_PAYLOAD_PATH = "/data/local/tmp/autoroot-payload"
        private val LOG_POLL_INTERVAL = 250.milliseconds
        private val SHELL_LOG_POLL_INTERVAL = 1_000.milliseconds
        private val HELPER_POLL_INTERVAL = 250.milliseconds
        private val AUTO_LATE_LOAD_POLL_INTERVAL = 400.milliseconds
        private val ANSI_ESCAPE = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
        private val P0_OFFSET_PATTERN = Regex(
            "slide-kaslr-ok[^\\n]*slide=([0-9a-fA-F]{16})",
        )

        private fun stripAnsi(value: String): String = ANSI_ESCAPE.replace(value, "").replace("\r", "")
    }
}
