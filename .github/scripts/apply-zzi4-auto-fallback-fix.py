from pathlib import Path
import re

INSTALL = Path("app/src/main/java/dev/busung/s25uroot/InstallViewModel.kt")
AUTO = Path("app/src/main/java/dev/busung/s25uroot/AutoRootRunner.kt")

text = INSTALL.read_text()
new_execute = '''    private suspend fun executeExploit(payload: File, profileId: String) {
        val zzi4Hybrid = profileId == TRACEFS_ZZI4_PROFILE
        val shizuku = shizukuEnabled()
        val tracefsFirst = zzi4Hybrid && shizuku
        if (zzi4Hybrid) {
            if (tracefsFirst) {
                appendLog(
                    "[*] exploit route=auto tracefs_first=true " +
                        "p0_fallback=enabled attempts=$TRACEFS_EXPLOIT_ATTEMPTS",
                )
            } else {
                appendLog(
                    "[!] tracefs unavailable; using legacy physical-P0 route " +
                        "attempts=$EXPLOIT_ATTEMPTS",
                )
            }
        }
        val logFile = if (shizuku) File(SHIZUKU_LOG_PATH) else File(app.filesDir, "exploit.log")
        if (shizuku) {
            ShizukuController.exec(arrayOf("rm", "-f", SHIZUKU_LOG_PATH)).waitFor()
        } else {
            logFile.delete()
        }
        val helper = helperFile()
        if (!shizuku) {
            require(helper.canExecute()) { app.getString(R.string.error_helper_unavailable) }
        }
        val logPrefix = mutableState.value.log
        val bootToken = currentBootToken()
        val process = if (shizuku) {
            val stagedPayload = shizukuStage(payload, SHIZUKU_PAYLOAD_PATH, "755")
            ShizukuController.exec(
                arrayOf(
                    helper.absolutePath,
                    "--run-payload",
                    stagedPayload.absolutePath,
                    helper.absolutePath,
                    SHIZUKU_LOG_PATH,
                ),
                shizukuEnvironment(bootToken, helper.absolutePath, tracefsFirst),
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
            processBuilder.environment().apply {
                put("EXPLOIT_ATTEMPTS", EXPLOIT_ATTEMPTS)
                put("EXPLOIT_ATTEMPT_TIMEOUT_SEC", EXPLOIT_ATTEMPT_TIMEOUT_SEC)
                put("P0_ATTEMPT_TIMEOUT_SEC", P0_ATTEMPT_TIMEOUT_SEC)
                cachedP0Offset(bootToken)?.let { put(P0_OFFSET_ENV, it) }
            }
            processBuilder.start()
        }
        val captured = StringBuilder()
        val readLog: () -> String = if (shizuku) {
            { drainProcessOutput(process, captured) }
        } else {
            { drainProcessOutput(process, captured); logFile.readTextIfPresent() }
        }

        try {
            val startedAt = SystemClock.elapsedRealtime()
            var lastProgressAt = startedAt
            var lastRawLog = ""
            while (process.isAlive) {
                val rawLog = readLog()
                if (rawLog != lastRawLog) {
                    cacheP0Offset(bootToken, rawLog)
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
                delay(if (shizuku) SHIZUKU_LOG_POLL_INTERVAL else LOG_POLL_INTERVAL)
            }

            val exitCode = process.waitFor()
            val rawLog = readLog()
            cacheP0Offset(bootToken, rawLog)
            publishExploitLog(logPrefix, rawLog)
            if (tracefsFirst && rawLog.contains("slide-kaslr-ok source=physical")) {
                appendLog("[!] tracefs KASLR unavailable; legacy physical-P0 fallback was used")
            }
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

'''
pat = re.compile(
    r"    private suspend fun executeExploit\(payload: File, profileId: String\) \{.*?\n    private fun drainProcessOutput",
    re.S,
)
text, n = pat.subn(new_execute + "    private fun drainProcessOutput", text, count=1)
if n != 1:
    raise SystemExit(f"InstallViewModel executeExploit replacement count={n}")

old_env = '''    private fun shizukuEnvironment(
        bootToken: String?,
        helperPath: String,
        strictTracefs: Boolean,
    ): Array<String> = buildList {
        add(
            "EXPLOIT_ATTEMPTS=" +
                if (strictTracefs) TRACEFS_EXPLOIT_ATTEMPTS else EXPLOIT_ATTEMPTS,
        )
        add("EXPLOIT_ATTEMPT_TIMEOUT_SEC=$EXPLOIT_ATTEMPT_TIMEOUT_SEC")
        add("CVE43499_ROOT_HELPER=$helperPath")
        if (strictTracefs) {
            add("SLIDE_SOURCE=tracefs")
        } else {
            add("P0_ATTEMPT_TIMEOUT_SEC=$P0_ATTEMPT_TIMEOUT_SEC")
            cachedP0Offset(bootToken)?.let { add("$P0_OFFSET_ENV=$it") }
        }
    }.toTypedArray()
'''
new_env = '''    private fun shizukuEnvironment(
        bootToken: String?,
        helperPath: String,
        tracefsFirst: Boolean,
    ): Array<String> = buildList {
        add(
            "EXPLOIT_ATTEMPTS=" +
                if (tracefsFirst) TRACEFS_EXPLOIT_ATTEMPTS else EXPLOIT_ATTEMPTS,
        )
        add("EXPLOIT_ATTEMPT_TIMEOUT_SEC=$EXPLOIT_ATTEMPT_TIMEOUT_SEC")
        add("CVE43499_ROOT_HELPER=$helperPath")
        if (tracefsFirst) {
            // SamSU-compatible hybrid: deterministic tracefs first, physical P0 fallback.
            add("SLIDE_SOURCE=auto")
            add("P0_ATTEMPT_TIMEOUT_SEC=$TRACEFS_P0_ATTEMPT_TIMEOUT_SEC")
        } else {
            add("P0_ATTEMPT_TIMEOUT_SEC=$P0_ATTEMPT_TIMEOUT_SEC")
            cachedP0Offset(bootToken)?.let { add("$P0_OFFSET_ENV=$it") }
        }
    }.toTypedArray()
'''
if old_env not in text:
    raise SystemExit("InstallViewModel shizukuEnvironment block not found")
text = text.replace(old_env, new_env, 1)
text = text.replace(
    '        private const val P0_ATTEMPT_TIMEOUT_SEC = "45"\n',
    '        private const val P0_ATTEMPT_TIMEOUT_SEC = "45"\n'
    '        private const val TRACEFS_P0_ATTEMPT_TIMEOUT_SEC = "90"\n',
    1,
)
text = text.replace(
    '            "slide-kaslr-ok[^\\\\n]*slide=([0-9a-fA-F]{16})",',
    '            "slide-kaslr-ok source=physical[^\\\\n]*slide=([0-9a-fA-F]{16})",',
    1,
)
INSTALL.write_text(text)

text = AUTO.read_text()
old_run = '''        onStage(AutoRootStage.PreparingExploit)
        onLog("[*] profile=${payloads.profile.profileId} transport=standalone source=offline")

        onStage(AutoRootStage.RunningExploit)
        executeExploit(payloads.exploit, bootToken)
'''
new_run = '''        onStage(AutoRootStage.PreparingExploit)
        val zzi4Hybrid = payloads.profile.profileId == TRACEFS_ZZI4_PROFILE
        val tracefsFirst = zzi4Hybrid && ShizukuController.isRunning() && ShizukuController.isGranted()
        val transport = if (tracefsFirst) "shizuku-auto" else "standalone"
        onLog("[*] profile=${payloads.profile.profileId} transport=$transport source=offline")
        if (zzi4Hybrid) {
            if (tracefsFirst) {
                onLog(
                    "[*] exploit route=auto tracefs_first=true " +
                        "p0_fallback=enabled attempts=$TRACEFS_EXPLOIT_ATTEMPTS",
                )
            } else {
                onLog(
                    "[!] Shizuku tracefs unavailable; Auto Root is using the " +
                        "legacy physical-P0 fallback",
                )
            }
        }

        onStage(AutoRootStage.RunningExploit)
        executeExploit(payloads.exploit, bootToken, tracefsFirst)
'''
if old_run not in text:
    raise SystemExit("AutoRootRunner run block not found")
text = text.replace(old_run, new_run, 1)

new_auto_execute = '''    private suspend fun executeExploit(
        payload: File,
        bootToken: String,
        tracefsFirst: Boolean,
    ) {
        val appLogFile = File(context.filesDir, "autoroot-exploit.log")
        appLogFile.delete()

        val nativeHelper = helperFile()
        require(nativeHelper.canExecute()) { context.getString(R.string.error_helper_unavailable) }

        val captured = StringBuilder()
        val process: Process
        val readLog: () -> String
        if (tracefsFirst) {
            val stagedHelper = stageForShizuku(nativeHelper, SHIZUKU_HELPER_PATH)
            val stagedPayload = stageForShizuku(payload, SHIZUKU_PAYLOAD_PATH)
            ShizukuController.exec(arrayOf("rm", "-f", SHIZUKU_LOG_PATH)).waitFor()
            val environment = arrayOf(
                "EXPLOIT_ATTEMPTS=$TRACEFS_EXPLOIT_ATTEMPTS",
                "EXPLOIT_ATTEMPT_TIMEOUT_SEC=$EXPLOIT_ATTEMPT_TIMEOUT_SEC",
                "P0_ATTEMPT_TIMEOUT_SEC=$TRACEFS_P0_ATTEMPT_TIMEOUT_SEC",
                "SLIDE_SOURCE=auto",
                "CVE43499_ROOT_HELPER=${stagedHelper.absolutePath}",
            )
            process = ShizukuController.exec(
                arrayOf(
                    stagedHelper.absolutePath,
                    "--run-payload",
                    stagedPayload.absolutePath,
                    stagedHelper.absolutePath,
                    SHIZUKU_LOG_PATH,
                ),
                environment,
                "/data/local/tmp",
            )
            readLog = { drainProcessOutput(process, captured); captured.toString() }
        } else {
            process = ProcessBuilder(
                nativeHelper.absolutePath,
                "--run-payload",
                payload.absolutePath,
                nativeHelper.absolutePath,
                appLogFile.absolutePath,
            ).redirectErrorStream(true).apply {
                environment().apply {
                    put("EXPLOIT_ATTEMPTS", EXPLOIT_ATTEMPTS)
                    put("P0_ATTEMPT_TIMEOUT_SEC", P0_ATTEMPT_TIMEOUT_SEC)
                    put("EXPLOIT_ATTEMPT_TIMEOUT_SEC", EXPLOIT_ATTEMPT_TIMEOUT_SEC)
                    cachedP0Offset(bootToken)?.let { put(P0_OFFSET_ENV, it) }
                }
            }.start()
            readLog = { drainProcessOutput(process, captured); appLogFile.readTextIfPresent() }
        }

        val originalThreadPriority = runCatching {
            Process.getThreadPriority(Process.myTid())
        }.getOrDefault(Process.THREAD_PRIORITY_DEFAULT)
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }

        try {
            val startedAt = SystemClock.elapsedRealtime()
            var lastProgressAt = startedAt
            var lastRawLog = ""
            while (process.isAlive) {
                val rawLog = readLog()
                if (rawLog != lastRawLog) {
                    publishExploitLog(rawLog)
                    cacheP0Offset(bootToken, rawLog)
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
                delay(LOG_POLL_INTERVAL)
            }

            val exitCode = process.waitFor()
            val rawLog = readLog()
            publishExploitLog(rawLog)
            cacheP0Offset(bootToken, rawLog)
            if (tracefsFirst && rawLog.contains("slide-kaslr-ok source=physical")) {
                onLog("[!] tracefs KASLR unavailable; legacy physical-P0 fallback was used")
            }
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

    private fun stageForShizuku(source: File, target: String): File {
        ShizukuController.writeFile(target, "755", source.inputStream())
        return File(target)
    }

'''
pat = re.compile(
    r"    private suspend fun executeExploit\(payload: File, bootToken: String\) \{.*?\n    private suspend fun stageKernelSuRequired",
    re.S,
)
text, n = pat.subn(new_auto_execute + "    private suspend fun stageKernelSuRequired", text, count=1)
if n != 1:
    raise SystemExit(f"AutoRootRunner executeExploit replacement count={n}")

text = text.replace(
    '        private const val EXPLOIT_ATTEMPTS = "24"\n',
    '        private const val TRACEFS_ZZI4_PROFILE = "pa3q-S938BXXUCZZI4"\n'
    '        private const val TRACEFS_EXPLOIT_ATTEMPTS = "4"\n'
    '        private const val EXPLOIT_ATTEMPTS = "24"\n',
    1,
)
text = text.replace(
    '        private const val P0_ATTEMPT_TIMEOUT_SEC = "45"\n',
    '        private const val P0_ATTEMPT_TIMEOUT_SEC = "45"\n'
    '        private const val TRACEFS_P0_ATTEMPT_TIMEOUT_SEC = "90"\n',
    1,
)
text = text.replace(
    '        private const val KSUD_PATH = "/data/local/tmp/ksud-s25u-kdp"\n',
    '        private const val SHIZUKU_LOG_PATH = "/data/local/tmp/autoroot-exploit.log"\n'
    '        private const val SHIZUKU_HELPER_PATH = "/data/local/tmp/autoroot-ksu-helper"\n'
    '        private const val SHIZUKU_PAYLOAD_PATH = "/data/local/tmp/autoroot-ksu-payload"\n'
    '        private const val KSUD_PATH = "/data/local/tmp/ksud-s25u-kdp"\n',
    1,
)
text = text.replace(
    '            "slide-kaslr-ok[^\\\\n]*slide=([0-9a-fA-F]{16})",',
    '            "slide-kaslr-ok source=physical[^\\\\n]*slide=([0-9a-fA-F]{16})",',
    1,
)
AUTO.write_text(text)
