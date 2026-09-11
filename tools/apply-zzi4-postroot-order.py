from pathlib import Path

ROOT = Path('.')
POST = ROOT / 'app/src/main/java/dev/busung/s25uroot/PostRootAutomation.kt'
INSTALL = ROOT / 'app/src/main/java/dev/busung/s25uroot/InstallViewModel.kt'
AUTO_SERVICE = ROOT / 'app/src/main/java/dev/busung/s25uroot/AutoRootExecutorService.kt'
AUTO_RUNNER = ROOT / 'app/src/main/java/dev/busung/s25uroot/AutoRootRunner.kt'
RUNTIME = ROOT / 'app/src/main/java/dev/busung/s25uroot/Zzi4PostRootRuntime.kt'
TEST = ROOT / 'app/src/test/java/dev/busung/s25uroot/Zzi4PostRootRuntimeTest.kt'


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding='utf-8')
    if text.count(old) != 1:
        raise SystemExit(f'{label}: expected one anchor, found {text.count(old)}')
    path.write_text(text.replace(old, new, 1), encoding='utf-8')


# Shared target-specific readiness machine. It deliberately does not load the
# external DEFEX validation LKM: production ZZI4 uses the PR #38 KernelSU build.
RUNTIME.write_text(r'''package dev.busung.s25uroot

internal data class Zzi4PostRootRuntimeResult(
    val applicable: Boolean,
    val ready: Boolean,
    val detail: String,
)

/**
 * One UI 9 ZZI4 post-late-load userspace readiness gate.
 *
 * KernelSU control/global readiness proves the root framework is available, but
 * does not prove that Zygisk Next and LSPosed have completed their own service
 * lifecycle before a new Zygote/system_server is created. This command is run
 * only after KernelSuGlobalReadiness succeeds and before the optional automatic
 * Zygote restart.
 *
 * The standalone defex_lsposed_compat LKM remains diagnostic-only. The
 * production path relies on the target-specific ksud/KernelSU pair whose SHA is
 * verified before the exploit and which embeds the permanent narrow DEFEX fix.
 */
internal object Zzi4PostRootRuntime {
    const val PROFILE_ID = "pa3q-S938BXXUCZZI4"
    private const val READY_MARKER = "RMG_ZZI4_POST_ROOT_READY"
    private const val SKIP_MARKER = "RMG_ZZI4_POST_ROOT_SKIP"
    private const val ERROR_MARKER = "RMG_ZZI4_POST_ROOT_ERROR"

    fun prepareCommand(expectedBootId: String): String {
        val boot = shellQuote(expectedBootId)
        return """
            EXPECTED_BOOT=$boot
            ZN='/data/adb/modules/zygisksu'
            ZNS='/data/adb/zygisksu'
            LS='/data/adb/modules/zygisk_lsposed'
            ZLOG='/data/local/tmp/rmg-zzi4-zygisk-start.log'
            LLOG='/data/local/tmp/rmg-zzi4-lsposed-start.log'

            current_boot() { /system/bin/cat /proc/sys/kernel/random/boot_id 2>/dev/null; }
            fail() {
                printf '%s\n' '$ERROR_MARKER:'"${'$'}1"
                exit 70
            }
            zn_ready() {
                /system/bin/ps -A -o NAME,ARGS 2>/dev/null | /system/bin/toybox awk \
                    '${'$'}1 == "zn-daemon" { found=1 } END { exit(found ? 0 : 1) }'
            }
            module_ready() {
                /system/bin/grep -Eq '^zygisk_lsposed[[:space:]]+64([[:space:]]|${'$'})' \
                    "${'$'}ZNS/modules_info" 2>/dev/null
            }
            lspd_ready() {
                /system/bin/ps -A -o NAME,ARGS 2>/dev/null | /system/bin/toybox awk \
                    '${'$'}1 ~ /^lspd($|:)/ || ${'$'}1 == "LSPosed" || index(${'$'}0, "/data/adb/modules/zygisk_lsposed/daemon") { found=1 } END { exit(found ? 0 : 1) }'
            }

            [ "${'$'}(/system/bin/id -u 2>/dev/null)" = '0' ] || fail 'not-root'
            [ "${'$'}(current_boot)" = "${'$'}EXPECTED_BOOT" ] || fail 'boot-changed'

            if [ ! -d "${'$'}LS" ] || [ -e "${'$'}LS/disable" ] || [ -e "${'$'}LS/remove" ]; then
                printf '%s\n' '$SKIP_MARKER:lsposed-not-active'
                exit 0
            fi
            [ -d "${'$'}ZN" ] || fail 'zygisk-next-not-installed'
            [ ! -e "${'$'}ZN/disable" ] && [ ! -e "${'$'}ZN/remove" ] || fail 'zygisk-next-disabled'
            [ -x "${'$'}ZN/bin/zygiskd" ] || fail 'zygiskd-missing'
            [ -x "${'$'}LS/daemon" ] || fail 'lsposed-daemon-missing'

            if [ "${'$'}(/system/bin/cat "${'$'}ZNS/memory_type" 2>/dev/null)" != '0' ]; then
                "${'$'}ZN/bin/zygiskd" memory-type default >>"${'$'}ZLOG" 2>&1 || true
            fi
            [ "${'$'}(/system/bin/cat "${'$'}ZNS/memory_type" 2>/dev/null)" = '0' ] || fail 'memory-type-not-default'

            if [ "${'$'}(/system/bin/cat "${'$'}ZNS/linker" 2>/dev/null)" != '0' ]; then
                "${'$'}ZN/bin/zygiskd" linker system >>"${'$'}ZLOG" 2>&1 || true
            fi
            [ "${'$'}(/system/bin/cat "${'$'}ZNS/linker" 2>/dev/null)" = '0' ] || fail 'linker-not-system'

            # Give KernelSU's normal module lifecycle a short chance to finish so
            # we do not race it into a duplicate zn-daemon.
            if ! zn_ready; then
                /system/bin/sleep 1
            fi
            if ! zn_ready; then
                (cd "${'$'}ZN" && ./bin/zygiskd daemon >>"${'$'}ZLOG" 2>&1) || true
            fi
            i=0
            while [ "${'$'}i" -lt 3 ] && ! zn_ready; do
                i="${'$'}((i + 1))"
                /system/bin/sleep 1
            done
            zn_ready || fail 'zn-daemon-not-ready'

            # service-stage may return 1 when the daemon/stage is already active.
            # Readiness is modules_info, never that isolated exit code.
            if ! module_ready; then
                (cd "${'$'}ZN" && ./bin/zygiskd service-stage >>"${'$'}ZLOG" 2>&1)
                SERVICE_RC="${'$'}?"
                printf 'service-stage-rc=%s\n' "${'$'}SERVICE_RC" >>"${'$'}ZLOG"
            fi
            i=0
            while [ "${'$'}i" -lt 3 ] && ! module_ready; do
                i="${'$'}((i + 1))"
                /system/bin/sleep 1
            done
            module_ready || fail 'zygisk-lsposed-not-registered'

            if ! lspd_ready; then
                /system/bin/toybox setsid "${'$'}LS/daemon" --force \
                    >"${'$'}LLOG" 2>&1 </dev/null &
            fi
            i=0
            while [ "${'$'}i" -lt 3 ] && ! lspd_ready; do
                i="${'$'}((i + 1))"
                /system/bin/sleep 1
            done
            lspd_ready || fail 'lsposed-daemon-not-ready'

            EXT='absent'
            if [ -r /sys/module/defex_lsposed_compat/parameters/bypass ]; then
                EXT="${'$'}(/system/bin/cat /sys/module/defex_lsposed_compat/parameters/bypass 2>/dev/null)"
            fi
            PERM='unobserved'
            if /system/bin/dmesg 2>/dev/null | /system/bin/grep -Fq 'LSPosed app_process64 exception enabled'; then
                PERM='kernel-log-confirmed'
            fi

            printf '%s\n' '$READY_MARKER memory_type=0 linker=0 zn_daemon=1 module=zygisk_lsposed lspd=1 permanent_defex='"${'$'}PERM"' external_helper='"${'$'}EXT"
            exit 0
        """.trimIndent()
    }

    fun parse(exitCode: Int, output: String): Zzi4PostRootRuntimeResult {
        val clean = output.trim()
        val skip = clean.lineSequence().lastOrNull { it.startsWith("$SKIP_MARKER:") }
        if (exitCode == 0 && skip != null) {
            return Zzi4PostRootRuntimeResult(
                applicable = false,
                ready = true,
                detail = skip.removePrefix("$SKIP_MARKER:"),
            )
        }
        val ready = clean.lineSequence().lastOrNull { it.startsWith(READY_MARKER) }
        if (exitCode == 0 && ready != null) {
            return Zzi4PostRootRuntimeResult(
                applicable = true,
                ready = true,
                detail = ready,
            )
        }
        val error = clean.lineSequence().lastOrNull { it.startsWith("$ERROR_MARKER:") }
            ?.removePrefix("$ERROR_MARKER:")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: clean.takeLast(240).ifBlank { "post-root readiness command exited $exitCode without a readiness marker" }
        return Zzi4PostRootRuntimeResult(
            applicable = true,
            ready = false,
            detail = error,
        )
    }

    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"
}
''', encoding='utf-8')


# PostRootAutomation: make ZZI4 module preparation another reason to acquire a
# root bridge, and execute it before any caller is allowed to restart Zygote.
replace_once(
    POST,
    '''internal data class PostRootResult(\n    val softRebootStarted: Boolean = false,\n    val shizukuStarted: Boolean = false,\n    val detail: String = "",\n)''',
    '''internal data class PostRootResult(\n    val softRebootStarted: Boolean = false,\n    val shizukuStarted: Boolean = false,\n    val zzi4RuntimeApplicable: Boolean = false,\n    val zzi4RuntimeReady: Boolean = false,\n    val detail: String = "",\n)''',
    'PostRootResult',
)
replace_once(
    POST,
    '''        startShizuku: Boolean,\n        onLog: (String) -> Unit = {},\n    ): PostRootResult = withContext(Dispatchers.IO) {\n        if (!softReboot && !startShizuku) return@withContext PostRootResult()''',
    '''        startShizuku: Boolean,\n        prepareZzi4Modules: Boolean = false,\n        onLog: (String) -> Unit = {},\n    ): PostRootResult = withContext(Dispatchers.IO) {\n        if (!softReboot && !startShizuku && !prepareZzi4Modules) {\n            return@withContext PostRootResult(zzi4RuntimeReady = true)\n        }''',
    'PostRootAutomation signature',
)

post_text = POST.read_text(encoding='utf-8')
post_text = post_text.replace(
    '''                    softReboot = softReboot,\n                    shizukuStarted = true,\n                    onLog = onLog,''',
    '''                    softReboot = softReboot,\n                    shizukuStarted = true,\n                    prepareZzi4Modules = prepareZzi4Modules,\n                    onLog = onLog,''',
)
post_text = post_text.replace(
    '''                softReboot = softReboot,\n                shizukuStarted = shizukuStarted,\n                onLog = onLog,''',
    '''                softReboot = softReboot,\n                shizukuStarted = shizukuStarted,\n                prepareZzi4Modules = prepareZzi4Modules,\n                onLog = onLog,''',
)
# There are two variable-shizukuStarted call sites (helper and local ADB).
if post_text.count('prepareZzi4Modules = prepareZzi4Modules') != 3:
    raise SystemExit('PostRootAutomation finishPostRoot propagation count changed')
POST.write_text(post_text, encoding='utf-8')

replace_once(
    POST,
    '''        softReboot: Boolean,\n        shizukuStarted: Boolean,\n        onLog: (String) -> Unit,\n    ): PostRootResult {\n        if (!softReboot) {\n            return PostRootResult(\n                shizukuStarted = shizukuStarted,\n                detail = "post-root automation complete",\n            )\n        }\n\n        val bootId = AutoRootSupport.currentBootToken()''',
    '''        softReboot: Boolean,\n        shizukuStarted: Boolean,\n        prepareZzi4Modules: Boolean,\n        onLog: (String) -> Unit,\n    ): PostRootResult {\n        var runtimeApplicable = false\n        var runtimeReady = !prepareZzi4Modules\n        var runtimeDetail = ""\n        if (prepareZzi4Modules) {\n            val runtimeBootId = AutoRootSupport.currentBootToken()\n            if (runtimeBootId.isNullOrBlank()) {\n                return PostRootResult(\n                    shizukuStarted = shizukuStarted,\n                    zzi4RuntimeApplicable = true,\n                    zzi4RuntimeReady = false,\n                    detail = "kernel boot id unavailable before ZZI4 post-root readiness",\n                )\n            }\n            val shellResult = rootShell(Zzi4PostRootRuntime.prepareCommand(runtimeBootId))\n            if (shellResult.output.isNotBlank()) {\n                onLog("[*] ZZI4 post-root readiness: ${shellResult.output.trim().takeLast(640)}")\n            }\n            val runtime = Zzi4PostRootRuntime.parse(shellResult.exitCode, shellResult.output)\n            runtimeApplicable = runtime.applicable\n            runtimeReady = runtime.ready\n            runtimeDetail = runtime.detail\n            if (!runtime.ready) {\n                onLog("[-] ZZI4 post-root runtime is not ready: ${runtime.detail}")\n                return PostRootResult(\n                    shizukuStarted = shizukuStarted,\n                    zzi4RuntimeApplicable = runtime.applicable,\n                    zzi4RuntimeReady = false,\n                    detail = "ZZI4 post-root runtime not ready: ${runtime.detail}",\n                )\n            }\n            if (runtime.applicable) {\n                onLog("[+] ZZI4 Zygisk/LSPosed runtime ready before Zygote restart")\n            } else {\n                onLog("[*] ZZI4 LSPosed runtime gate skipped: ${runtime.detail}")\n            }\n        }\n\n        if (!softReboot) {\n            return PostRootResult(\n                shizukuStarted = shizukuStarted,\n                zzi4RuntimeApplicable = runtimeApplicable,\n                zzi4RuntimeReady = runtimeReady,\n                detail = runtimeDetail.ifBlank { "post-root automation complete" },\n            )\n        }\n\n        val bootId = AutoRootSupport.currentBootToken()''',
    'finishPostRoot readiness insertion',
)
# Preserve runtime fields on soft-reboot result paths that leave finishPostRoot.
post_text = POST.read_text(encoding='utf-8')
post_text = post_text.replace(
    '''            return PostRootResult(\n                shizukuStarted = shizukuStarted,\n                detail = "kernel boot id unavailable before KernelSU soft reboot",\n            )''',
    '''            return PostRootResult(\n                shizukuStarted = shizukuStarted,\n                zzi4RuntimeApplicable = runtimeApplicable,\n                zzi4RuntimeReady = runtimeReady,\n                detail = "kernel boot id unavailable before KernelSU soft reboot",\n            )''',
)
post_text = post_text.replace(
    '''            return PostRootResult(\n                shizukuStarted = shizukuStarted,\n                detail = keeper.detail,\n            )''',
    '''            return PostRootResult(\n                shizukuStarted = shizukuStarted,\n                zzi4RuntimeApplicable = runtimeApplicable,\n                zzi4RuntimeReady = runtimeReady,\n                detail = keeper.detail,\n            )''',
)
post_text = post_text.replace(
    '''        return PostRootResult(\n            softRebootStarted = true,\n            shizukuStarted = shizukuStarted,\n            detail = "KernelSU native soft-reboot handoff accepted",\n        )''',
    '''        return PostRootResult(\n            softRebootStarted = true,\n            shizukuStarted = shizukuStarted,\n            zzi4RuntimeApplicable = runtimeApplicable,\n            zzi4RuntimeReady = runtimeReady,\n            detail = "KernelSU native soft-reboot handoff accepted",\n        )''',
)
POST.write_text(post_text, encoding='utf-8')


# Manual install: call PostRootAutomation whenever ZZI4 needs the runtime gate,
# even if Shizuku auto-start is disabled. Never restart Zygote when the gate fails.
old_manual = '''                    try {\n                        if (startShizuku) {\n                            val postRoot = PostRootAutomation.run(\n                                context = app,\n                                softReboot = false,\n                                startShizuku = true,\n                                onLog = ::appendLog,\n                            )\n                            if (!postRoot.shizukuStarted && postRoot.detail.isNotBlank()) {\n                                appendLog("[!] Post-root Shizuku automation: ${postRoot.detail.take(200)}")\n                            }\n                        }\n\n                        if (restartZygote) {\n                            val restart = RootRecoveryActions.restartZygote(app)'''
new_manual = '''                    try {\n                        val requireZzi4Runtime =\n                            restartZygote && profile.profileId == Zzi4PostRootRuntime.PROFILE_ID\n                        val postRoot = if (startShizuku || requireZzi4Runtime) {\n                            PostRootAutomation.run(\n                                context = app,\n                                softReboot = false,\n                                startShizuku = startShizuku,\n                                prepareZzi4Modules = requireZzi4Runtime,\n                                onLog = ::appendLog,\n                            )\n                        } else {\n                            null\n                        }\n                        if (startShizuku && postRoot != null &&\n                            !postRoot.shizukuStarted && postRoot.detail.isNotBlank()\n                        ) {\n                            appendLog("[!] Post-root Shizuku automation: ${postRoot.detail.take(200)}")\n                        }\n\n                        if (restartZygote && requireZzi4Runtime && postRoot?.zzi4RuntimeReady != true) {\n                            appendLog(\n                                "[!] Zygote restart skipped: " +\n                                    (postRoot?.detail ?: "ZZI4 post-root runtime could not be verified"),\n                            )\n                        } else if (restartZygote) {\n                            val restart = RootRecoveryActions.restartZygote(app)'''
replace_once(INSTALL, old_manual, new_manual, 'Manual post-root gate')


# Auto Root service: same shared gate and same fail-closed rule for the restart.
old_auto_service = '''                if (startShizuku) {\n                    val postRoot = try {\n                        PostRootAutomation.run(\n                            context = this,\n                            softReboot = false,\n                            startShizuku = true,\n                            onLog = { appendHistory(it) },\n                        )\n                    } catch (error: Throwable) {\n                        val detail = error.message ?: error.javaClass.simpleName\n                        appendHistory("[!] Post-root Shizuku automation failed: $detail")\n                        Log.w(TAG, "Post-root Shizuku automation failed after verified root", error)\n                        null\n                    }\n                    if (postRoot != null && !postRoot.shizukuStarted && postRoot.detail.isNotBlank()) {\n                        appendHistory("[!] Post-root Shizuku automation: ${postRoot.detail.take(200)}")\n                    }\n                }\n\n                if (restartZygote) {\n                    val restart = try {'''
new_auto_service = '''                val requireZzi4Runtime =\n                    restartZygote && payloads.profile.profileId == Zzi4PostRootRuntime.PROFILE_ID\n                val postRoot = if (startShizuku || requireZzi4Runtime) {\n                    try {\n                        PostRootAutomation.run(\n                            context = this,\n                            softReboot = false,\n                            startShizuku = startShizuku,\n                            prepareZzi4Modules = requireZzi4Runtime,\n                            onLog = { appendHistory(it) },\n                        )\n                    } catch (error: Throwable) {\n                        val detail = error.message ?: error.javaClass.simpleName\n                        appendHistory("[!] Post-root automation failed: $detail")\n                        Log.w(TAG, "Post-root automation failed after verified root", error)\n                        null\n                    }\n                } else {\n                    null\n                }\n                if (startShizuku && postRoot != null &&\n                    !postRoot.shizukuStarted && postRoot.detail.isNotBlank()\n                ) {\n                    appendHistory("[!] Post-root Shizuku automation: ${postRoot.detail.take(200)}")\n                }\n\n                if (restartZygote && requireZzi4Runtime && postRoot?.zzi4RuntimeReady != true) {\n                    val detail = postRoot?.detail ?: "ZZI4 post-root runtime could not be verified"\n                    val message = "KernelSU root is active; Zygote restart skipped: ${detail.take(180)}"\n                    appendHistory("[!] $message")\n                    finishHistory(InstallRunResult.Succeeded)\n                    Log.w(TAG, message)\n                    finishWithResult(message)\n                    return\n                }\n\n                if (restartZygote) {\n                    val restart = try {'''
replace_once(AUTO_SERVICE, old_auto_service, new_auto_service, 'Auto Root post-root gate')


# AutoRootRunner: for shell-required ZZI4, put the exact verified offline ksud
# at the stable path BEFORE the UMH helper can auto-late-load it.
replace_once(
    AUTO_RUNNER,
    '''        executeExploit(\n            payload = payloads.exploit,\n            bootToken = bootToken,''',
    '''        executeExploit(\n            payloads = payloads,\n            bootToken = bootToken,''',
    'AutoRoot execute call',
)
replace_once(
    AUTO_RUNNER,
    '''    private suspend fun executeExploit(\n        payload: File,\n        bootToken: String,''',
    '''    private suspend fun executeExploit(\n        payloads: VerifiedPayloads,\n        bootToken: String,''',
    'AutoRoot execute signature',
)
replace_once(
    AUTO_RUNNER,
    '''    ) {\n        val useShellTransport = shellTransport != null\n        onLog(''',
    '''    ) {\n        val payload = payloads.exploit\n        val useShellTransport = shellTransport != null\n        onLog(''',
    'AutoRoot execute payload local',
)
replace_once(
    AUTO_RUNNER,
    '''                executeExploitViaLocalAdb(\n                    payload = payload,\n                    localHelper = localHelper,''',
    '''                executeExploitViaLocalAdb(\n                    payloads = payloads,\n                    localHelper = localHelper,''',
    'AutoRoot local ADB call',
)
replace_once(
    AUTO_RUNNER,
    '''                val stagedHelper = shizukuStage(localHelper, SHELL_HELPER_PATH)\n                val stagedPayload = shizukuStage(payload, SHELL_PAYLOAD_PATH)\n                beforeExploit()''',
    '''                val stagedHelper = shizukuStage(localHelper, SHELL_HELPER_PATH)\n                val stagedPayload = shizukuStage(payload, SHELL_PAYLOAD_PATH)\n                if (payloads.profile.profileId == Zzi4PostRootRuntime.PROFILE_ID) {\n                    preStageZzi4KernelSuViaShizuku(payloads)\n                }\n                beforeExploit()''',
    'AutoRoot Shizuku KSU pre-stage',
)
replace_once(
    AUTO_RUNNER,
    '''    private suspend fun executeExploitViaLocalAdb(\n        payload: File,\n        localHelper: File,''',
    '''    private suspend fun executeExploitViaLocalAdb(\n        payloads: VerifiedPayloads,\n        localHelper: File,''',
    'AutoRoot local ADB signature',
)
replace_once(
    AUTO_RUNNER,
    '''    ) {\n        require(AppPreferences.adbPaired(context)) {\n            "Shell-required Auto Root needs either an authorized Shizuku Binder or the paired local ADB key"\n        }''',
    '''    ) {\n        val payload = payloads.exploit\n        require(AppPreferences.adbPaired(context)) {\n            "Shell-required Auto Root needs either an authorized Shizuku Binder or the paired local ADB key"\n        }''',
    'AutoRoot local payload local',
)
replace_once(
    AUTO_RUNNER,
    '''                session.push(localHelper, SHELL_HELPER_PATH, executable = true)\n                session.push(payload, SHELL_PAYLOAD_PATH, executable = true)\n\n                val env = localAdbEnvironment(''',
    '''                session.push(localHelper, SHELL_HELPER_PATH, executable = true)\n                session.push(payload, SHELL_PAYLOAD_PATH, executable = true)\n                if (payloads.profile.profileId == Zzi4PostRootRuntime.PROFILE_ID) {\n                    preStageZzi4KernelSuViaLocalAdb(session, payloads)\n                }\n\n                val env = localAdbEnvironment(''',
    'AutoRoot local KSU pre-stage',
)

# Insert AutoRoot pre-stage helpers immediately before shizukuStage().
replace_once(
    AUTO_RUNNER,
    '''    private fun shizukuStage(source: File, target: String): File {''',
    r'''    private fun zzi4KernelSuVerifyCommand(): String =
        "set -e; " +
            "h=$(/system/bin/toybox sha256sum ${shellQuote(KSUD_PATH)} | " +
            "/system/bin/toybox awk '{print \\$1}'); " +
            "s=$(/system/bin/toybox wc -c < ${shellQuote(KSUD_PATH)}); " +
            "printf '%s %s\\n' \\"\\$h\\" \\"\\$s\\""

    private fun verifyZzi4KernelSuPreStage(
        payloads: VerifiedPayloads,
        result: LocalAdbClient.ShellResult,
    ) {
        val artifact = payloads.profile.kernelSu.artifact
        require(
            result.exitCode == 0 &&
                remoteArtifactMatches(result.output, artifact.sha256, artifact.size),
        ) {
            "ZZI4 Auto Root KernelSU pre-stage verification failed: expected=" +
                "${artifact.sha256}/${artifact.size} remote=${result.output.takeLast(240)}"
        }
        onLog(
            "[+] ZZI4 Auto Root pre-exploit KernelSU bootstrap verified " +
                "sha256=${artifact.sha256} size=${artifact.size}",
        )
    }

    private fun preStageZzi4KernelSuViaShizuku(payloads: VerifiedPayloads) {
        val cleanup = ShizukuController.shell("rm -f ${shellQuote(KSUD_STAGE_PATH)}")
        require(cleanup.exitCode == 0) {
            "Unable to clear stale Auto Root KernelSU stage: ${cleanup.output}"
        }
        ShizukuController.writeFile(KSUD_PATH, "755", payloads.kernelSu.inputStream())
        verifyZzi4KernelSuPreStage(
            payloads,
            ShizukuController.shell(zzi4KernelSuVerifyCommand()),
        )
    }

    private fun preStageZzi4KernelSuViaLocalAdb(
        session: WirelessAdbSession,
        payloads: VerifiedPayloads,
    ) {
        session.remove(KSUD_STAGE_PATH)
        session.push(payloads.kernelSu, KSUD_PATH, executable = true)
        verifyZzi4KernelSuPreStage(payloads, session.shell(zzi4KernelSuVerifyCommand()))
    }

    private fun shizukuStage(source: File, target: String): File {''',
    'AutoRoot pre-stage helpers',
)


TEST.write_text(r'''package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Zzi4PostRootRuntimeTest {
    @Test
    fun runtimeCommandPreservesValidatedOrderAndSignals() {
        val command = Zzi4PostRootRuntime.prepareCommand("boot-test")

        val memory = command.indexOf("memory-type default")
        val linker = command.indexOf("linker system")
        val daemon = command.indexOf("./bin/zygiskd daemon")
        val modules = command.indexOf("modules_info")
        val serviceStage = command.indexOf("./bin/zygiskd service-stage")
        val lsposed = command.indexOf("setsid \"$LS/daemon\" --force")
        val ready = command.indexOf("RMG_ZZI4_POST_ROOT_READY")

        assertTrue(memory >= 0)
        assertTrue(linker > memory)
        assertTrue(daemon > linker)
        assertTrue(modules > linker)
        assertTrue(serviceStage > daemon)
        assertTrue(lsposed > serviceStage)
        assertTrue(ready > lsposed)
        assertTrue(command.contains("zn-daemon"))
        assertTrue(command.contains("zygisk_lsposed"))
        assertFalse(command.contains("pidof zygiskd"))
        assertFalse(command.contains("pidof zygiskd64"))
        assertFalse(command.contains("cat /data/adb/zygisksu/znctx"))
        assertFalse(command.contains("insmod"))
    }

    @Test
    fun parserSeparatesReadySkippedAndFailure() {
        val ready = Zzi4PostRootRuntime.parse(
            0,
            "RMG_ZZI4_POST_ROOT_READY memory_type=0 linker=0 zn_daemon=1 module=zygisk_lsposed lspd=1",
        )
        assertTrue(ready.applicable)
        assertTrue(ready.ready)

        val skipped = Zzi4PostRootRuntime.parse(0, "RMG_ZZI4_POST_ROOT_SKIP:lsposed-not-active")
        assertFalse(skipped.applicable)
        assertTrue(skipped.ready)

        val failed = Zzi4PostRootRuntime.parse(70, "RMG_ZZI4_POST_ROOT_ERROR:zn-daemon-not-ready")
        assertTrue(failed.applicable)
        assertFalse(failed.ready)
        assertTrue(failed.detail.contains("zn-daemon-not-ready"))
    }

    @Test
    fun manualAndAutoRestartAreGatedBySameRuntimePreparation() {
        val manual = File("src/main/java/dev/busung/s25uroot/InstallViewModel.kt").readText()
        val auto = File("src/main/java/dev/busung/s25uroot/AutoRootExecutorService.kt").readText()
        val post = File("src/main/java/dev/busung/s25uroot/PostRootAutomation.kt").readText()

        assertTrue(manual.contains("prepareZzi4Modules = requireZzi4Runtime"))
        assertTrue(manual.contains("postRoot?.zzi4RuntimeReady != true"))
        assertTrue(auto.contains("prepareZzi4Modules = requireZzi4Runtime"))
        assertTrue(auto.contains("postRoot?.zzi4RuntimeReady != true"))
        assertTrue(post.contains("Zzi4PostRootRuntime.prepareCommand"))
        assertTrue(post.contains("Zygisk/LSPosed runtime ready before Zygote restart"))
    }

    @Test
    fun autoRootStagesVerifiedZzi4KernelSuBeforeExploitClaim() {
        val source = File("src/main/java/dev/busung/s25uroot/AutoRootRunner.kt").readText()
        val shizukuStage = source.indexOf("preStageZzi4KernelSuViaShizuku(payloads)")
        val shizukuClaim = source.indexOf("beforeExploit()", shizukuStage)
        val localStage = source.indexOf("preStageZzi4KernelSuViaLocalAdb(session, payloads)")
        val localClaim = source.indexOf("beforeExploit()", localStage)

        assertTrue(shizukuStage >= 0)
        assertTrue(shizukuClaim > shizukuStage)
        assertTrue(localStage >= 0)
        assertTrue(localClaim > localStage)
        assertTrue(source.contains("remoteArtifactMatches"))
        assertTrue(source.contains("session.remove(KSUD_STAGE_PATH)"))
    }
}
''', encoding='utf-8')

print('ZZI4 post-root ordering patch applied')
