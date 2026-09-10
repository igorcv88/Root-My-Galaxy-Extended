from pathlib import Path

SRC = Path('app/src/main/java/dev/busung/s25uroot/InstallViewModel.kt')
TEST = Path('app/src/test/java/dev/busung/s25uroot/ManualShellTransportPolicyTest.kt')
text = SRC.read_text()


def replace_between(source: str, start: str, end: str, replacement: str) -> str:
    a = source.index(start)
    b = source.index(end, a)
    return source[:a] + replacement.rstrip() + '\n\n' + source[b:]

needle = 'private data class CommandResult(val code: Int, val output: String)\n'
insert = '''private data class CommandResult(val code: Int, val output: String)

internal enum class ManualRunTransport {
    App,
    Shizuku,
    LocalAdb,
}

internal fun chooseManualRunTransport(
    shellRequired: Boolean,
    shizukuRequested: Boolean,
    shizukuUsable: Boolean,
    localAdbPaired: Boolean,
): ManualRunTransport? {
    if (shizukuRequested && shizukuUsable) return ManualRunTransport.Shizuku
    if (shellRequired) {
        return if (localAdbPaired) ManualRunTransport.LocalAdb else null
    }
    return if (shizukuRequested) null else ManualRunTransport.App
}
'''
if text.count(needle) != 1:
    raise SystemExit('CommandResult insertion point changed')
text = text.replace(needle, insert, 1)

old_fields = '''    @Volatile
    private var activeRunShizuku: Boolean? = null
'''
new_fields = '''    @Volatile
    private var activeRunTransport: ManualRunTransport? = null
    private var activeLocalAdbSession: WirelessAdbSession? = null
'''
if text.count(old_fields) != 1:
    raise SystemExit('activeRunShizuku field changed')
text = text.replace(old_fields, new_fields, 1)

install_fn = r'''    fun install(profileId: String? = null) {
        if (installJob?.isActive == true || mutableState.value.phase == InstallPhase.Installed) return
        discoveryJob?.cancel()
        installJob = viewModelScope.launch(Dispatchers.IO) {
            mutableState.value = InstallUiState(
                phase = InstallPhase.Checking,
                probeOutput = mutableState.value.probeOutput,
            )
            startHistory()
            try {
                setPhase(InstallPhase.Checking, app.getString(R.string.status_checking_github))
                val profile = if (profileId == null) {
                    repository.resolveTarget(DeviceSnapshot.current())
                } else {
                    repository.resolveTarget(profileId)
                }
                appendLog(app.getString(R.string.log_profile, profile.profileId))
                updateHistoryProfile(profile.profileId)

                activeRunTransport = selectRunTransport(profile)
                appendLog(
                    "[*] Manual transport=" + when (runTransport()) {
                        ManualRunTransport.App -> "app"
                        ManualRunTransport.Shizuku -> "shell-shizuku"
                        ManualRunTransport.LocalAdb -> "shell-local-adb"
                    },
                )

                setPhase(InstallPhase.Downloading, app.getString(R.string.status_downloading_payload))
                val payloads = repository.download(profile) { appendLog("[*] $it") }
                appendLog(app.getString(R.string.log_download_verified))

                if (isExactCzg3(profile)) {
                    val minimumUptime = AppPreferences.czg3BootMinUptimeSeconds(app)
                    setPhase(
                        InstallPhase.Checking,
                        app.getString(R.string.status_waiting_boot_uptime, minimumUptime),
                    )
                    DiagnosticUptime.waitUntil(minimumUptime)
                }

                runExploitAndKernelSu(payloads)

                if (payloads.source == PayloadSource.Online) {
                    runCatching { KnownGoodPayloadStore.publish(app, payloads) }
                        .onSuccess { appendLog("[+] Offline payload cache updated") }
                        .onFailure { error ->
                            appendLog(
                                "[!] Root succeeded, but offline payload cache was not updated: " +
                                    (error.message ?: error.javaClass.simpleName),
                            )
                        }
                }

                setPhase(InstallPhase.Installed, app.getString(R.string.status_ksu_active))
                appendLog(app.getString(R.string.log_install_complete))
                checkpointHistorySuccess()

                val restartZygote = AppPreferences.restartZygoteAfterRoot(app)
                val startShizuku = AppPreferences.autoStartShizukuAfterRoot(app)
                if (restartZygote || startShizuku) {
                    if (restartZygote) {
                        mutableState.value = mutableState.value.copy(
                            message = app.getString(R.string.zygote_restart_starting),
                        )
                    }
                    try {
                        if (startShizuku) {
                            val postRoot = PostRootAutomation.run(
                                context = app,
                                softReboot = false,
                                startShizuku = true,
                                onLog = ::appendLog,
                            )
                            if (!postRoot.shizukuStarted && postRoot.detail.isNotBlank()) {
                                appendLog("[!] Post-root Shizuku automation: ${postRoot.detail.take(200)}")
                            }
                        }

                        if (restartZygote) {
                            val restart = RootRecoveryActions.restartZygote(app)
                            if (!restart.accepted) {
                                val message = app.getString(
                                    R.string.zygote_restart_failed,
                                    restart.detail.take(160),
                                )
                                mutableState.value = mutableState.value.copy(message = message)
                                appendLog("[!] $message")
                            } else {
                                appendLog("[+] ${restart.detail}")
                                finishHistory(InstallRunResult.Succeeded)
                                return@launch
                            }
                        }
                    } catch (error: Throwable) {
                        appendLog(
                            "[!] Post-root automation failed: " +
                                (error.message ?: error.javaClass.simpleName),
                        )
                    }
                }

                finishHistory(InstallRunResult.Succeeded)
            } catch (error: Throwable) {
                appendLog("[-] ${error.message ?: error.javaClass.simpleName}")
                setPhase(InstallPhase.Failed, app.getString(R.string.status_install_failed))
                finishHistory(InstallRunResult.Failed)
            } finally {
                activeLocalAdbSession = null
                activeRunTransport = null
            }
        }
    }

    private suspend fun selectRunTransport(profile: TargetProfile): ManualRunTransport {
        val requestedShizuku = AppPreferences.shizukuMode(app)
        var shizukuUsable = false
        if (requestedShizuku) {
            appendLog(app.getString(R.string.log_shizuku_prepare))
            val running = ShizukuController.isRunning() || ShizukuController.pingUntilRunning()
            if (running) {
                shizukuUsable = ShizukuController.isGranted() || ShizukuController.requestPermission()
            }
            if (shizukuUsable) {
                appendLog(app.getString(R.string.log_shizuku_permission))
            } else if (profile.routePolicy.prefersShellTransport) {
                appendLog("[!] Shizuku is unavailable; using paired local ADB for this shell-required target")
            }
        }

        return chooseManualRunTransport(
            shellRequired = profile.routePolicy.prefersShellTransport,
            shizukuRequested = requestedShizuku,
            shizukuUsable = shizukuUsable,
            localAdbPaired = AppPreferences.adbPaired(app),
        ) ?: if (profile.routePolicy.prefersShellTransport) {
            error("This target requires shell transport, but neither Shizuku nor the paired local ADB key is usable")
        } else {
            error(app.getString(R.string.error_shizuku_unavailable))
        }
    }

    private suspend fun runExploitAndKernelSu(payloads: VerifiedPayloads) {
        if (runTransport() != ManualRunTransport.LocalAdb) {
            setPhase(InstallPhase.Exploiting, app.getString(R.string.status_exploit_running))
            executeExploit(payloads.exploit, payloads.profile.routePolicy)
            setPhase(InstallPhase.LoadingKernelSu, app.getString(R.string.status_ksu_loading))
            installKernelSu(payloads)
            return
        }

        TemporaryWirelessAdb.use(
            context = app,
            settleMillis = LOCAL_ADB_SETTLE_MILLIS,
            onLog = ::appendLog,
        ) {
            WirelessAdbSession.open(
                app,
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
                appendLog("[+] Manual Local ADB shell transport ready: u:r:shell:s0")
                activeLocalAdbSession = session
                try {
                    setPhase(InstallPhase.Exploiting, app.getString(R.string.status_exploit_running))
                    executeExploit(payloads.exploit, payloads.profile.routePolicy)
                    setPhase(InstallPhase.LoadingKernelSu, app.getString(R.string.status_ksu_loading))
                    installKernelSu(payloads)
                } finally {
                    activeLocalAdbSession = null
                }
            }
        }
    }'''
text = replace_between(text, '    fun install(profileId: String? = null) {', '    private suspend fun executeExploit(', install_fn)

execute_fn = r'''    private suspend fun executeExploit(payload: File, policy: ExploitRoutePolicy) {
        val transport = runTransport()
        val useShell = transport != ManualRunTransport.App
        appendLog(
            policy.describe(
                if (useShell) ExploitRoutePolicy.SHELL_TRANSPORT else ExploitRoutePolicy.APP_TRANSPORT,
            ),
        )

        val logPrefix = mutableState.value.log
        val bootToken = currentBootToken()
        if (transport == ManualRunTransport.LocalAdb) {
            executeExploitViaLocalAdb(payload, policy, bootToken, logPrefix)
            appendLog(app.getString(R.string.log_bootstrap_root))
            return
        }

        val logFile = if (transport == ManualRunTransport.Shizuku) {
            File(SHIZUKU_LOG_PATH)
        } else {
            File(app.filesDir, "exploit.log")
        }
        if (transport == ManualRunTransport.Shizuku) {
            ShizukuController.exec(arrayOf("rm", "-f", SHIZUKU_LOG_PATH)).waitFor()
        } else {
            logFile.delete()
        }

        val helper = helperFile()
        if (transport == ManualRunTransport.App) {
            require(helper.canExecute()) { app.getString(R.string.error_helper_unavailable) }
        }
        val process = if (transport == ManualRunTransport.Shizuku) {
            val stagedPayload = shizukuStage(payload, SHIZUKU_PAYLOAD_PATH, "755")
            ShizukuController.exec(
                arrayOf(
                    helper.absolutePath,
                    "--run-payload",
                    stagedPayload.absolutePath,
                    helper.absolutePath,
                    SHIZUKU_LOG_PATH,
                ),
                shizukuEnvironment(bootToken, helper.absolutePath, policy),
                "/data/local/tmp",
            )
        } else {
            val processBuilder = ProcessBuilder(
                helper.absolutePath,
                "--run-payload",
                payload.absolutePath,
                helper.absolutePath,
                logFile.absolutePath,
            ).redirectErrorStream(true)
            processBuilder.environment().putAll(policy.environment(cachedP0Offset(bootToken)))
            processBuilder.start()
        }

        val captured = StringBuilder()
        fun readLog(): String {
            drainProcessOutput(process, captured)
            return if (transport == ManualRunTransport.Shizuku) captured.toString() else logFile.readTextIfPresent()
        }

        try {
            val startedAt = SystemClock.elapsedRealtime()
            var lastProgressAt = startedAt
            var lastRawLog = ""
            while (process.isAlive) {
                val rawLog = readLog()
                if (rawLog != lastRawLog) {
                    if (policy.p0OffsetCache) cacheP0Offset(bootToken, rawLog)
                    publishExploitLog(logPrefix, rawLog)
                    lastRawLog = rawLog
                    lastProgressAt = SystemClock.elapsedRealtime()
                }
                val now = SystemClock.elapsedRealtime()
                require(now - lastProgressAt < EXPLOIT_STALL_MILLIS) {
                    app.getString(R.string.error_exploit_stalled)
                }
                require(now - startedAt < EXPLOIT_TOTAL_MILLIS) {
                    app.getString(R.string.error_exploit_timeout)
                }
                delay(
                    if (transport == ManualRunTransport.Shizuku) {
                        SHIZUKU_LOG_POLL_INTERVAL
                    } else {
                        LOG_POLL_INTERVAL
                    },
                )
            }

            val exitCode = process.waitFor()
            val rawLog = readLog()
            if (policy.p0OffsetCache) cacheP0Offset(bootToken, rawLog)
            publishExploitLog(logPrefix, rawLog)
            val earlyOutput = captured.toString().trim()
            require(exitCode == 0) {
                app.getString(
                    R.string.error_payload_exit,
                    exitCode,
                    earlyOutput.takeIf(String::isNotBlank)?.let { " ($it)" } ?: "",
                )
            }
            require(rawLog.contains("exploit completed") && rawLog.contains("done=1 root=1")) {
                app.getString(R.string.error_success_marker)
            }
        } finally {
            if (process.isAlive) {
                process.destroy()
                delay(500.milliseconds)
                if (process.isAlive) process.destroyForcibly()
            }
        }
        appendLog(app.getString(R.string.log_bootstrap_root))
    }

    private suspend fun executeExploitViaLocalAdb(
        payload: File,
        policy: ExploitRoutePolicy,
        bootToken: String?,
        logPrefix: String,
    ) {
        val session = requireNotNull(activeLocalAdbSession) {
            "Manual Local ADB session disappeared before exploit launch"
        }
        session.remove(SHIZUKU_LOG_PATH)
        session.push(nativeHelperFile(), SHIZUKU_HELPER_PATH, executable = true)
        session.push(payload, SHIZUKU_PAYLOAD_PATH, executable = true)

        val env = localAdbEnvironment(bootToken, SHIZUKU_HELPER_PATH, policy)
        val command = buildString {
            append("cd /data/local/tmp && ")
            if (env.isNotBlank()) {
                append(env)
                append(' ')
            }
            append(shellQuote(SHIZUKU_HELPER_PATH))
            append(" --run-payload ")
            append(shellQuote(SHIZUKU_PAYLOAD_PATH))
            append(' ')
            append(shellQuote(SHIZUKU_HELPER_PATH))
            append(' ')
            append(shellQuote(SHIZUKU_LOG_PATH))
        }
        appendLog("[*] Launching Manual exploit through paired local ADB shell")
        val streamed = session.runStreaming(
            command = command,
            overallTimeoutMs = EXPLOIT_TOTAL_MILLIS,
            stallTimeoutMs = EXPLOIT_STALL_MILLIS,
            onOutput = { snapshot -> publishExploitLog(logPrefix, snapshot) },
        )
        val remoteLog = session.readLog(SHIZUKU_LOG_PATH)
        val rawLog = remoteLog.ifBlank { streamed }
        publishExploitLog(logPrefix, rawLog)
        if (policy.p0OffsetCache) cacheP0Offset(bootToken, rawLog)
        require(rawLog.contains("exploit completed") && rawLog.contains("done=1 root=1")) {
            app.getString(R.string.error_success_marker)
        }
    }'''
text = replace_between(text, '    private suspend fun executeExploit(', '    private fun drainProcessOutput(', execute_fn)

ksu_fn = r'''    private suspend fun installKernelSu(payloads: VerifiedPayloads) {
        val bootToken = currentBootToken() ?: error(app.getString(R.string.error_boot_id))
        val autoLoaded = waitForAutoLateLoad(bootToken)

        val stage = runHelper("-c", kernelSuStageCommand(payloads))
        require(stage.code == 0) { app.getString(R.string.error_ksu_stage, stage.output) }
        appendLog(app.getString(R.string.log_ksu_staged))

        if (autoLoaded) {
            appendLog("[+] KernelSU auto-late-load verified; skipped duplicate late-load")
        } else {
            val lateLoad = runHelper("--late-load")
            require(lateLoad.code == 0) {
                app.getString(R.string.error_ksu_verify, lateLoad.code, lateLoad.output)
            }
            if (lateLoad.output.isNotBlank()) appendLog(lateLoad.output)
        }

        val verification = runCatching { runHelper("--ksu-info") }.getOrNull()
        val nativeActive = NativeProbe.isKernelSuActive()
        val rootProof = if (verification?.code == 0 || nativeActive) {
            null
        } else {
            runCatching { runPostRoot("id") }.getOrNull()
        }
        require(
            verification?.code == 0 || nativeActive ||
                (rootProof?.code == 0 && rootProof.output.contains("uid=0")),
        ) {
            app.getString(
                R.string.error_ksu_verify,
                verification?.code ?: rootProof?.code ?: -1,
                verification?.output ?: rootProof?.output ?: "KernelSU control channel is not active",
            )
        }
        if (verification?.code == 0 && verification.output.isNotBlank()) appendLog(verification.output)

        val global = runPostRoot(KernelSuGlobalReadiness.command(bootToken))
        require(global.code == 0) {
            app.getString(
                R.string.error_ksu_verify,
                global.code,
                global.output.ifBlank { "KernelSU late-load global readiness is not satisfied" },
            )
        }
        if (global.output.isNotBlank()) appendLog(global.output)
        storeInstallReceipt()
        appendLog(app.getString(R.string.log_ksu_control_verified))
    }

    private suspend fun waitForAutoLateLoad(bootToken: String): Boolean {
        val deadline = SystemClock.elapsedRealtime() + AUTO_LATE_LOAD_WAIT_MILLIS
        var lastOutput = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            val probe = runCatching { runHelper("--ksu-info") }.getOrNull()
            val nativeActive = NativeProbe.isKernelSuActive()
            var controlActive = probe?.code == 0 || nativeActive
            if (!controlActive) {
                val rootProof = runCatching { runPostRoot("id") }.getOrNull()
                if (rootProof != null) {
                    if (rootProof.output.isNotBlank()) lastOutput = rootProof.output
                    controlActive = rootProof.code == 0 && rootProof.output.contains("uid=0")
                }
            }
            if (controlActive) {
                val global = runCatching {
                    runPostRoot(KernelSuGlobalReadiness.command(bootToken))
                }.getOrNull()
                if (global != null) {
                    lastOutput = global.output
                    if (global.code == 0) {
                        if (probe?.code == 0 && probe.output.isNotBlank()) appendLog(probe.output)
                        if (global.output.isNotBlank()) appendLog(global.output)
                        return true
                    }
                }
            } else if (probe != null && probe.output.isNotBlank()) {
                lastOutput = probe.output
            }
            delay(AUTO_LATE_LOAD_POLL_INTERVAL)
        }
        if (lastOutput.isNotBlank()) {
            appendLog("[*] auto-late-load readiness probe: ${lastOutput.takeLast(320)}")
        }
        return false
    }

    private fun kernelSuStageCommand(payloads: VerifiedPayloads): String {
        val source = shellQuote(payloads.kernelSu.absolutePath)
        return "set -e; " +
            "tmp='$KSUD_REFRESH_PATH'; rm -f \"\$tmp\"; " +
            "/system/bin/cp $source \"\$tmp\"; /system/bin/chmod 755 \"\$tmp\"; " +
            "/system/bin/mv -f \"\$tmp\" $SHIZUKU_KSUD_PATH; " +
            "tmp='$KSUD_STAGE_REFRESH_PATH'; rm -f \"\$tmp\"; " +
            "/system/bin/cp $source \"\$tmp\"; /system/bin/chmod 755 \"\$tmp\"; " +
            "/system/bin/mv -f \"\$tmp\" $SHIZUKU_KSUD_STAGE_PATH"
    }

    private fun runPostRoot(command: String): CommandResult = when (runTransport()) {
        ManualRunTransport.Shizuku -> {
            val result = ShizukuController.shell("su -c ${shellQuote(command)}")
            CommandResult(result.exitCode, stripAnsi(result.output.trim()))
        }
        ManualRunTransport.LocalAdb -> {
            val session = requireNotNull(activeLocalAdbSession) {
                "Manual Local ADB session disappeared during KernelSU handoff"
            }
            val result = session.shell("su -c ${shellQuote(command)} 2>&1")
            CommandResult(result.exitCode, stripAnsi(result.output.trim()))
        }
        ManualRunTransport.App -> {
            val result = RootHelperShell.shell(app, command)
            CommandResult(result.exitCode, stripAnsi(result.output.trim()))
        }
    }'''
text = replace_between(text, '    private suspend fun installKernelSu(', '    private fun detectInstalled()', ksu_fn)

helper_block = r'''    private fun helperFile(): File = when (runTransport()) {
        ManualRunTransport.Shizuku -> shizukuStage(nativeHelperFile(), SHIZUKU_HELPER_PATH, "755")
        ManualRunTransport.LocalAdb -> File(SHIZUKU_HELPER_PATH)
        ManualRunTransport.App -> nativeHelperFile()
    }

    private fun nativeHelperFile() = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")

    private fun runTransport(): ManualRunTransport =
        activeRunTransport ?: if (AppPreferences.shizukuMode(app)) {
            ManualRunTransport.Shizuku
        } else {
            ManualRunTransport.App
        }
'''
text = replace_between(text, '    private fun helperFile(): File =', '    private fun shizukuStage(', helper_block)

local_env = r'''    private fun localAdbEnvironment(
        bootToken: String?,
        helperPath: String,
        policy: ExploitRoutePolicy,
    ): String = buildList {
        add("CVE43499_ROOT_HELPER=${shellQuote(helperPath)}")
        policy.environment(cachedP0Offset(bootToken)).forEach { (key, value) ->
            add("$key=${shellQuote(value)}")
        }
    }.joinToString(" ")

'''
marker = '    /**\n     * Runs the bootstrap helper for a short management command.'
if marker not in text:
    raise SystemExit('runHelper documentation marker changed')
text = text.replace(marker, local_env + marker, 1)

run_helper = r'''    private suspend fun runHelper(vararg arguments: String): CommandResult {
        if (runTransport() == ManualRunTransport.LocalAdb) {
            val session = requireNotNull(activeLocalAdbSession) {
                "Manual Local ADB session disappeared during bootstrap handoff"
            }
            val command = buildString {
                append(shellQuote(SHIZUKU_HELPER_PATH))
                arguments.forEach { argument ->
                    append(' ')
                    append(shellQuote(argument))
                }
            }
            val result = session.shell("$command 2>&1")
            return CommandResult(result.exitCode, stripAnsi(result.output.trim()))
        }

        val helper = helperFile()
        val process = if (runTransport() == ManualRunTransport.Shizuku) {
            ShizukuController.exec(arrayOf(helper.absolutePath) + arguments)
        } else {
            ProcessBuilder(listOf(helper.absolutePath) + arguments)
                .redirectErrorStream(true)
                .start()
        }
        val captured = StringBuilder()
        val startedAt = SystemClock.elapsedRealtime()
        try {
            while (process.isAlive) {
                drainProcessOutput(process, captured)
                require(SystemClock.elapsedRealtime() - startedAt < HELPER_TIMEOUT_MILLIS) {
                    app.getString(
                        R.string.error_helper_timeout,
                        captured.toString().trim().takeIf(String::isNotBlank)
                            ?.let { ": $it" } ?: "",
                    )
                }
                delay(HELPER_POLL_INTERVAL)
            }
            drainProcessOutput(process, captured)
            val exitCode = process.waitFor()
            return CommandResult(exitCode, stripAnsi(captured.toString().trim()))
        } finally {
            if (process.isAlive) {
                process.destroy()
                delay(500.milliseconds)
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }'''
text = replace_between(text, '    private suspend fun runHelper(vararg arguments: String): CommandResult {', '    private fun shellQuote(', run_helper)

const_needle = '''        private const val SHIZUKU_KSUD_STAGE_PATH = "/data/local/tmp/.ksud-stage"\n'''
const_repl = '''        private const val SHIZUKU_KSUD_STAGE_PATH = "/data/local/tmp/.ksud-stage"\n        private const val KSUD_REFRESH_PATH = "/data/local/tmp/.ksud-refresh"\n        private const val KSUD_STAGE_REFRESH_PATH = "/data/local/tmp/.ksud-stage-refresh"\n        private const val LOCAL_ADB_SETTLE_MILLIS = 800L\n        private const val LOCAL_ADB_PORT_DISCOVERY_TIMEOUT_MILLIS = 30_000L\n'''
if text.count(const_needle) != 1:
    raise SystemExit('KernelSU constant insertion point changed')
text = text.replace(const_needle, const_repl, 1)

if 'activeRunShizuku' in text or 'shizukuEnabled()' in text:
    raise SystemExit('stale boolean transport logic remains')
if 'ManualRunTransport.LocalAdb' not in text or 'TemporaryWirelessAdb.use' not in text:
    raise SystemExit('Local ADB transport was not installed')

SRC.write_text(text)

TEST.write_text(r'''package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualShellTransportPolicyTest {
    @Test
    fun shellRequiredStandaloneUsesPairedLocalAdb() {
        assertEquals(
            ManualRunTransport.LocalAdb,
            chooseManualRunTransport(
                shellRequired = true,
                shizukuRequested = false,
                shizukuUsable = false,
                localAdbPaired = true,
            ),
        )
    }

    @Test
    fun shellRequiredNeverFallsBackToAppDomain() {
        assertNull(
            chooseManualRunTransport(
                shellRequired = true,
                shizukuRequested = false,
                shizukuUsable = false,
                localAdbPaired = false,
            ),
        )
    }

    @Test
    fun usableShizukuStillWinsWhenRequested() {
        assertEquals(
            ManualRunTransport.Shizuku,
            chooseManualRunTransport(
                shellRequired = true,
                shizukuRequested = true,
                shizukuUsable = true,
                localAdbPaired = true,
            ),
        )
    }

    @Test
    fun legacyStandaloneKeepsAppTransport() {
        assertEquals(
            ManualRunTransport.App,
            chooseManualRunTransport(
                shellRequired = false,
                shizukuRequested = false,
                shizukuUsable = false,
                localAdbPaired = true,
            ),
        )
    }
}
''')

print('Manual shell transport patch applied')
