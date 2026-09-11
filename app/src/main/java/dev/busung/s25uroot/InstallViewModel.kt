package dev.busung.s25uroot

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class InstallPhase {
    Checking,
    Ready,
    Downloading,
    Exploiting,
    LoadingKernelSu,
    Installed,
    Failed,
}

data class InstallUiState(
    val phase: InstallPhase = InstallPhase.Checking,
    val message: String = "",
    val probeOutput: String = "",
    val log: String = "",
) {
    val busy: Boolean
        get() = phase in setOf(
            InstallPhase.Checking,
            InstallPhase.Downloading,
            InstallPhase.Exploiting,
            InstallPhase.LoadingKernelSu,
        )
}

data class TargetCatalogUiState(
    val loading: Boolean = false,
    val profiles: List<TargetProfile> = emptyList(),
    val error: String? = null,
)

private data class CommandResult(val code: Int, val output: String)

internal fun remoteArtifactMatches(
    output: String,
    expectedSha256: String,
    expectedSize: Long,
): Boolean {
    val fields = output.trim().split(Regex("\\s+"))
    return fields.size >= 2 &&
        fields[0].equals(expectedSha256, ignoreCase = true) &&
        fields[1].toLongOrNull() == expectedSize
}

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
    if (shellRequired) {
        if (localAdbPaired) return ManualRunTransport.LocalAdb
        return if (shizukuRequested && shizukuUsable) ManualRunTransport.Shizuku else null
    }
    if (shizukuRequested && shizukuUsable) return ManualRunTransport.Shizuku
    return if (shizukuRequested) null else ManualRunTransport.App
}

/**
 * Payloads are truncated to a fixed release size, so a rebuild of a target --
 * or a different target padded to the same size -- has exactly the length of
 * whatever is already staged, and would keep running in its place.
 */
internal fun stagedFileIsCurrent(staged: File, source: File): Boolean {
    if (!staged.exists()) return false
    val stagedDigest = sha256OrNull(staged) ?: return false
    return stagedDigest == sha256OrNull(source)
}

private fun sha256OrNull(file: File): String? = runCatching {
    file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}.getOrNull()

class InstallViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val repository = PayloadRepository(application)
    private val historyStore = InstallHistoryStore(application)
    private val mutableState = MutableStateFlow(InstallUiState())
    private val mutableHistory = MutableStateFlow(historyStore.closeInterruptedRuns())
    private val mutableTargetCatalog = MutableStateFlow(TargetCatalogUiState())
    private var discoveryJob: Job? = null
    private var installJob: Job? = null
    private var activeHistoryEntry: InstallHistoryEntry? = null

    @Volatile
    private var activeRunTransport: ManualRunTransport? = null
    private var activeLocalAdbSession: WirelessAdbSession? = null
    val state: StateFlow<InstallUiState> = mutableState.asStateFlow()
    val history: StateFlow<List<InstallHistoryEntry>> = mutableHistory.asStateFlow()
    val targetCatalog: StateFlow<TargetCatalogUiState> = mutableTargetCatalog.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (installJob?.isActive == true) return
        mutableHistory.value = historyStore.load()
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch(Dispatchers.IO) {
            val probe = NativeProbe.run()
            if (detectInstalled()) {
                mutableState.value = InstallUiState(
                    phase = InstallPhase.Installed,
                    message = app.getString(R.string.status_ksu_active),
                    probeOutput = probe,
                    log = probe,
                )
            } else {
                // Keep ordinary refresh local. Online target discovery happens only
                // when Manual Online or the explicit Advanced target picker asks
                // for it, so opening Manual Offline never performs hidden network I/O.
                mutableState.value = InstallUiState(
                    phase = InstallPhase.Ready,
                    message = app.getString(R.string.status_not_installed),
                    probeOutput = probe,
                    log = probe,
                )
            }
        }
    }

    fun deleteHistoryEntries(ids: Collection<String>) {
        val runningId = activeHistoryEntry?.id
        val toDelete = ids.filterNot { it == runningId }
        if (toDelete.isEmpty()) return
        toDelete.forEach(historyStore::delete)
        mutableHistory.value = mutableHistory.value.filterNot { it.id in toDelete }
    }

    fun loadTargetCatalog() {
        if (mutableTargetCatalog.value.loading) return
        viewModelScope.launch(Dispatchers.IO) {
            mutableTargetCatalog.value = TargetCatalogUiState(loading = true)
            mutableTargetCatalog.value = try {
                TargetCatalogUiState(
                    profiles = repository.loadTargets().sortedWith(
                        compareBy(
                            TargetProfile::displayName,
                            TargetProfile::profileId,
                        ),
                    ),
                )
            } catch (error: Throwable) {
                TargetCatalogUiState(error = error.message ?: error.javaClass.simpleName)
            }
        }
    }

    fun install(profileId: String? = null) {
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
                        val requireZzi4Runtime =
                            restartZygote && profile.profileId == Zzi4PostRootRuntime.PROFILE_ID
                        val postRoot = if (startShizuku || requireZzi4Runtime) {
                            PostRootAutomation.run(
                                context = app,
                                softReboot = false,
                                startShizuku = startShizuku,
                                prepareZzi4Modules = requireZzi4Runtime,
                                onLog = ::appendLog,
                            )
                        } else {
                            null
                        }
                        if (startShizuku && postRoot != null &&
                            !postRoot.shizukuStarted && postRoot.detail.isNotBlank()
                        ) {
                            appendLog("[!] Post-root Shizuku automation: ${postRoot.detail.take(200)}")
                        }

                        if (restartZygote && requireZzi4Runtime && postRoot?.zzi4RuntimeReady != true) {
                            appendLog(
                                "[!] Zygote restart skipped: " +
                                    (postRoot?.detail ?: "ZZI4 post-root runtime could not be verified"),
                            )
                        } else if (
                            restartZygote && requireZzi4Runtime &&
                            postRoot?.zzi4RuntimeApplicable == true &&
                            !postRoot.zzi4RestartNeeded
                        ) {
                            appendLog(
                                "[+] Zygote restart not needed: LSPosed is already mapped in system_server",
                            )
                        } else if (restartZygote) {
                            val restart = RootRecoveryActions.restartZygote(
                                app,
                                oncePerBoot = requireZzi4Runtime,
                            )
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
        val shellRequired = profile.routePolicy.prefersShellTransport
        val localAdbPaired = AppPreferences.adbPaired(app)

        // ZZI4 was hardware-validated through the paired Local ADB shell. Keep
        // that launch path deterministic even when a Shizuku binder happens to
        // be alive after boot; the exploit is scheduler-sensitive despite both
        // transports reporting uid=2000 / u:r:shell:s0.
        if (profile.profileId == "pa3q-S938BXXUCZZI4" && shellRequired && localAdbPaired) {
            appendLog("[*] ZZI4 Manual transport pinned to paired Local ADB shell")
            return ManualRunTransport.LocalAdb
        }

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
            } else if (shellRequired) {
                appendLog("[!] Shizuku is unavailable; using paired local ADB for this shell-required target")
            }
        }

        return chooseManualRunTransport(
            shellRequired = shellRequired,
            shizukuRequested = requestedShizuku,
            shizukuUsable = shizukuUsable,
            localAdbPaired = localAdbPaired,
        ) ?: if (shellRequired) {
            error("This target requires shell transport, but neither Shizuku nor the paired local ADB key is usable")
        } else {
            error(app.getString(R.string.error_shizuku_unavailable))
        }
    }

    private suspend fun runExploitAndKernelSu(payloads: VerifiedPayloads) {
        if (runTransport() != ManualRunTransport.LocalAdb) {
            preStageKernelSuForAutoLateLoad(payloads)
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
                    preStageKernelSuForAutoLateLoad(payloads)
                    setPhase(InstallPhase.Exploiting, app.getString(R.string.status_exploit_running))
                    executeExploit(payloads.exploit, payloads.profile.routePolicy)
                    setPhase(InstallPhase.LoadingKernelSu, app.getString(R.string.status_ksu_loading))
                    installKernelSu(payloads)
                } finally {
                    activeLocalAdbSession = null
                }
            }
        }
    }

    private fun preStageKernelSuForAutoLateLoad(payloads: VerifiedPayloads) {
        if (payloads.profile.profileId != "pa3q-S938BXXUCZZI4") return

        val artifact = payloads.profile.kernelSu.artifact
        val expectedSha256 = artifact.sha256
        val expectedSize = artifact.size
        val verifyCommand =
            "set -e; " +
                "h=$(/system/bin/toybox sha256sum ${shellQuote(SHIZUKU_KSUD_PATH)} | " +
                "/system/bin/toybox awk '{print \$1}'); " +
                "s=$(/system/bin/toybox wc -c < ${shellQuote(SHIZUKU_KSUD_PATH)}); " +
                "printf '%s %s\\n' \"\$h\" \"\$s\""

        val verification = when (runTransport()) {
            ManualRunTransport.LocalAdb -> {
                val session = requireNotNull(activeLocalAdbSession) {
                    "Manual Local ADB session disappeared before KernelSU pre-stage"
                }
                session.remove(SHIZUKU_KSUD_STAGE_PATH)
                session.push(payloads.kernelSu, SHIZUKU_KSUD_PATH, executable = true)
                session.shell(verifyCommand)
            }
            ManualRunTransport.Shizuku -> {
                val cleanup = ShizukuController.shell(
                    "rm -f ${shellQuote(SHIZUKU_KSUD_STAGE_PATH)}",
                )
                require(cleanup.exitCode == 0) {
                    "Unable to clear stale KernelSU stage before ZZI4 exploit: ${cleanup.output}"
                }
                ShizukuController.writeFile(
                    SHIZUKU_KSUD_PATH,
                    "755",
                    payloads.kernelSu.inputStream(),
                )
                ShizukuController.shell(verifyCommand)
            }
            ManualRunTransport.App -> {
                error("ZZI4 KernelSU pre-stage requires a real shell transport")
            }
        }

        require(
            verification.exitCode == 0 &&
                remoteArtifactMatches(verification.output, expectedSha256, expectedSize),
        ) {
            "ZZI4 KernelSU pre-stage verification failed: expected=" +
                "$expectedSha256/$expectedSize remote=${verification.output.takeLast(240)}"
        }
        appendLog(
            "[+] ZZI4 pre-exploit KernelSU bootstrap verified " +
                "sha256=$expectedSha256 size=$expectedSize",
        )
    }

    private suspend fun executeExploit(payload: File, policy: ExploitRoutePolicy) {
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
    }

    private fun drainProcessOutput(process: Process, buffer: StringBuilder): String {
        return try {
            drainStream(process.inputStream, buffer)
            drainStream(process.errorStream, buffer)
            buffer.toString()
        } catch (_: Throwable) {
            buffer.toString()
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

    private fun publishExploitLog(prefix: String, rawLog: String) {
        mutableState.value = mutableState.value.copy(
            log = listOf(prefix, stripAnsi(rawLog))
                .filter(String::isNotBlank)
                .joinToString("\n"),
        )
        updateHistoryLog()
    }

    private suspend fun installKernelSu(payloads: VerifiedPayloads) {
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
    }

    private fun detectInstalled(): Boolean {
        val bootToken = currentBootToken()
        if (KernelSuRuntime.isControlActive(app)) {
            if (bootToken != null) {
                runCatching { AutoRootSupport.markVerifiedForBoot(app, bootToken) }
            }
            return true
        }
        if (bootToken == null) return false
        val receipt = app.getSharedPreferences(INSTALL_RECEIPT, Application.MODE_PRIVATE)
        return receipt.getString(RECEIPT_BOOT_TOKEN, null) == bootToken &&
            receipt.getBoolean(RECEIPT_VERIFIED, false)
    }

    private fun storeInstallReceipt() {
        val bootToken = currentBootToken() ?: error(app.getString(R.string.error_boot_id))
        val stored = app.getSharedPreferences(INSTALL_RECEIPT, Application.MODE_PRIVATE)
            .edit()
            .putString(RECEIPT_BOOT_TOKEN, bootToken)
            .putBoolean(RECEIPT_VERIFIED, true)
            .commit()
        require(stored) { app.getString(R.string.error_receipt) }
    }

    private fun currentBootToken(): String? = runCatching {
        File("/proc/sys/kernel/random/boot_id")
            .readText(Charsets.US_ASCII)
            .trim()
            .takeIf(String::isNotBlank)
    }.getOrNull()

    private fun cachedP0Offset(bootToken: String?): String? {
        if (bootToken == null) return null
        val stored = app.getSharedPreferences(P0_CACHE, Application.MODE_PRIVATE)
        if (stored.getString(P0_CACHE_BOOT_TOKEN, null) != bootToken) return null
        return stored.getString(P0_CACHE_OFFSET, null)
    }

    private fun cacheP0Offset(bootToken: String?, log: String) {
        if (bootToken == null) return
        val match = P0_OFFSET_PATTERN.findAll(log).lastOrNull() ?: return
        val offset = match.groupValues[1].toLongOrNull(16) ?: return
        if (offset !in 0..P0_OFFSET_MAX || offset and P0_OFFSET_MASK != 0L) return
        val value = "0x${offset.toString(16)}"
        val stored = app.getSharedPreferences(P0_CACHE, Application.MODE_PRIVATE)
        if (stored.getString(P0_CACHE_BOOT_TOKEN, null) == bootToken &&
            stored.getString(P0_CACHE_OFFSET, null) == value
        ) return
        stored.edit()
            .putString(P0_CACHE_BOOT_TOKEN, bootToken)
            .putString(P0_CACHE_OFFSET, value)
            .apply()
    }

    private fun helperFile(): File = when (runTransport()) {
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

    private fun shizukuStage(source: File, target: String, mode: String): File {
        val staged = File(target)
        if (stagedFileIsCurrent(staged, source)) return staged
        try {
            ShizukuController.writeFile(target, mode, source.inputStream())
        } catch (error: Throwable) {
            throw IllegalStateException(
                app.getString(R.string.error_shizuku_stage, target, error.message.orEmpty()),
                error,
            )
        }
        return staged
    }

    private fun shizukuEnvironment(
        bootToken: String?,
        helperPath: String,
        policy: ExploitRoutePolicy,
    ): Array<String> = buildList {
        add("CVE43499_ROOT_HELPER=$helperPath")
        policy.environment(cachedP0Offset(bootToken)).forEach { (key, value) ->
            add("$key=$value")
        }
    }.toTypedArray()

    private fun localAdbEnvironment(
        bootToken: String?,
        helperPath: String,
        policy: ExploitRoutePolicy,
    ): String = buildList {
        add("CVE43499_ROOT_HELPER=${shellQuote(helperPath)}")
        policy.environment(cachedP0Offset(bootToken)).forEach { (key, value) ->
            add("$key=${shellQuote(value)}")
        }
    }.joinToString(" ")

    /**
     * Runs the bootstrap helper for a short management command. Unlike the
     * exploit run there is no log file to poll, so output is drained inline
     * and a hard deadline guards against a helper that never exits — without
     * this, a hung `--late-load` leaves the install stuck in LoadingKernelSu
     * indefinitely.
     */
    private suspend fun runHelper(vararg arguments: String): CommandResult {
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
    }

    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"

    private fun setPhase(phase: InstallPhase, message: String) {
        mutableState.value = mutableState.value.copy(phase = phase, message = message)
        appendLog("[*] $message")
    }

    private fun appendLog(line: String) {
        val cleanLine = stripAnsi(line).trim()
        if (cleanLine.isBlank()) return
        mutableState.value = mutableState.value.copy(
            log = (mutableState.value.log + "\n" + cleanLine).trim(),
        )
        updateHistoryLog()
    }

    private fun startHistory() {
        val entry = historyStore.create()
        activeHistoryEntry = entry
        publishHistory(entry)
    }

    private fun updateHistory(transform: (InstallHistoryEntry) -> InstallHistoryEntry) {
        val entry = activeHistoryEntry ?: return
        val updated = transform(entry)
        activeHistoryEntry = updated
        historyStore.save(updated)
        publishHistory(updated)
    }

    private fun updateHistoryLog() =
        updateHistory { it.copy(log = mutableState.value.log) }

    private fun updateHistoryProfile(profileId: String) =
        updateHistory { it.copy(profileId = profileId) }

    private fun checkpointHistorySuccess() {
        updateHistory { entry ->
            entry.copy(
                completedAtMillis = System.currentTimeMillis(),
                result = InstallRunResult.Succeeded,
                log = mutableState.value.log,
            )
        }
    }

    private fun finishHistory(result: InstallRunResult) {
        updateHistory { entry ->
            entry.copy(
                completedAtMillis = entry.completedAtMillis ?: System.currentTimeMillis(),
                result = result,
                log = mutableState.value.log,
            )
        }
        activeHistoryEntry = null
    }

    private fun publishHistory(entry: InstallHistoryEntry) {
        mutableHistory.value = (mutableHistory.value.filterNot { it.id == entry.id } + entry)
            .sortedByDescending(InstallHistoryEntry::startedAtMillis)
    }

    private fun File.readTextIfPresent(): String = if (exists()) readText() else ""

    companion object {
        private const val EXPLOIT_STALL_MILLIS = 90_000L
        private const val EXPLOIT_TOTAL_MILLIS = 900_000L
        private const val HELPER_TIMEOUT_MILLIS = 120_000L
        private const val AUTO_LATE_LOAD_WAIT_MILLIS = 8_000L
        private const val INSTALL_RECEIPT = "install_receipt"
        private const val RECEIPT_BOOT_TOKEN = "kernel_boot_id"
        private const val RECEIPT_VERIFIED = "verified"
        private const val P0_CACHE = "p0_cache"
        private const val P0_CACHE_BOOT_TOKEN = "kernel_boot_id"
        private const val P0_CACHE_OFFSET = "offset"
        private const val P0_OFFSET_MAX = 0x1f0000L
        private const val P0_OFFSET_MASK = 0xffffL
        private const val SHIZUKU_LOG_PATH = "/data/local/tmp/ksu-exploit.log"
        private const val SHIZUKU_HELPER_PATH = "/data/local/tmp/ksu-helper"
        private const val SHIZUKU_PAYLOAD_PATH = "/data/local/tmp/ksu-payload"
        private const val SHIZUKU_KSUD_PATH = "/data/local/tmp/ksud-s25u-kdp"
        private const val SHIZUKU_KSUD_STAGE_PATH = "/data/local/tmp/.ksud-stage"
        private const val KSUD_REFRESH_PATH = "/data/local/tmp/.ksud-refresh"
        private const val KSUD_STAGE_REFRESH_PATH = "/data/local/tmp/.ksud-stage-refresh"
        private const val LOCAL_ADB_SETTLE_MILLIS = 800L
        private const val LOCAL_ADB_PORT_DISCOVERY_TIMEOUT_MILLIS = 30_000L
        private val LOG_POLL_INTERVAL = 250.milliseconds
        private val HELPER_POLL_INTERVAL = 250.milliseconds
        private val AUTO_LATE_LOAD_POLL_INTERVAL = 400.milliseconds
        private val SHIZUKU_LOG_POLL_INTERVAL = 1.seconds
        private val ANSI_ESCAPE = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
        private val P0_OFFSET_PATTERN = Regex(
            "slide-kaslr-ok[^\\n]*slide=([0-9a-fA-F]{16})",
        )

        private fun stripAnsi(value: String): String = ANSI_ESCAPE.replace(value, "").replace("\r", "")
    }
}
