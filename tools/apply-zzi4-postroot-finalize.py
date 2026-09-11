from pathlib import Path

ROOT = Path('.')
RUNTIME = ROOT / 'app/src/main/java/dev/busung/s25uroot/Zzi4PostRootRuntime.kt'
POST = ROOT / 'app/src/main/java/dev/busung/s25uroot/PostRootAutomation.kt'
INSTALL = ROOT / 'app/src/main/java/dev/busung/s25uroot/InstallViewModel.kt'
AUTO = ROOT / 'app/src/main/java/dev/busung/s25uroot/AutoRootExecutorService.kt'
RECOVERY = ROOT / 'app/src/main/java/dev/busung/s25uroot/RootRecoveryActions.kt'
TEST = ROOT / 'app/src/test/java/dev/busung/s25uroot/Zzi4PostRootRuntimeTest.kt'


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected one anchor, found {count}')
    path.write_text(text.replace(old, new, 1), encoding='utf-8')


replace_once(
    RUNTIME,
    '''internal data class Zzi4PostRootRuntimeResult(\n    val applicable: Boolean,\n    val ready: Boolean,\n    val detail: String,\n)''',
    '''internal data class Zzi4PostRootRuntimeResult(\n    val applicable: Boolean,\n    val ready: Boolean,\n    val restartNeeded: Boolean,\n    val detail: String,\n)''',
    'runtime result fields',
)

replace_once(
    RUNTIME,
    '''            EXT='absent'\n            if [ -r /sys/module/defex_lsposed_compat/parameters/bypass ]; then''',
    '''            RESTART_NEEDED=1\n            SS="${'$'}(/system/bin/pidof system_server 2>/dev/null)"\n            if [ -n "${'$'}SS" ] && /system/bin/grep -Fq \\\n                '/data/adb/modules/zygisk_lsposed/zygisk/arm64-v8a.so' \\\n                "/proc/${'$'}SS/maps" 2>/dev/null; then\n                RESTART_NEEDED=0\n            fi\n\n            EXT='absent'\n            if [ -r /sys/module/defex_lsposed_compat/parameters/bypass ]; then''',
    'runtime system_server injection check',
)

replace_once(
    RUNTIME,
    '''            printf '%s\\n' '$READY_MARKER memory_type=0 linker=0 zn_daemon=1 module=zygisk_lsposed lspd=1 permanent_defex='"${'$'}PERM"' external_helper='"${'$'}EXT"''',
    '''            printf '%s\\n' '$READY_MARKER memory_type=0 linker=0 zn_daemon=1 module=zygisk_lsposed lspd=1 restart_needed='"${'$'}RESTART_NEEDED"' permanent_defex='"${'$'}PERM"' external_helper='"${'$'}EXT"''',
    'runtime ready marker',
)

replace_once(
    RUNTIME,
    '''            return Zzi4PostRootRuntimeResult(\n                applicable = false,\n                ready = true,\n                detail = skip.removePrefix("$SKIP_MARKER:"),\n            )''',
    '''            return Zzi4PostRootRuntimeResult(\n                applicable = false,\n                ready = true,\n                restartNeeded = true,\n                detail = skip.removePrefix("$SKIP_MARKER:"),\n            )''',
    'runtime skip parse',
)

replace_once(
    RUNTIME,
    '''            return Zzi4PostRootRuntimeResult(\n                applicable = true,\n                ready = true,\n                detail = ready,\n            )''',
    '''            return Zzi4PostRootRuntimeResult(\n                applicable = true,\n                ready = true,\n                restartNeeded = !ready.contains("restart_needed=0"),\n                detail = ready,\n            )''',
    'runtime ready parse',
)

replace_once(
    RUNTIME,
    '''        return Zzi4PostRootRuntimeResult(\n            applicable = true,\n            ready = false,\n            detail = error,\n        )''',
    '''        return Zzi4PostRootRuntimeResult(\n            applicable = true,\n            ready = false,\n            restartNeeded = true,\n            detail = error,\n        )''',
    'runtime error parse',
)

replace_once(
    POST,
    '''    val zzi4RuntimeApplicable: Boolean = false,\n    val zzi4RuntimeReady: Boolean = false,\n    val detail: String = "",''',
    '''    val zzi4RuntimeApplicable: Boolean = false,\n    val zzi4RuntimeReady: Boolean = false,\n    val zzi4RestartNeeded: Boolean = true,\n    val detail: String = "",''',
    'post-root result restart field',
)

replace_once(
    POST,
    '''        var runtimeApplicable = false\n        var runtimeReady = !prepareZzi4Modules\n        var runtimeDetail = ""''',
    '''        var runtimeApplicable = false\n        var runtimeReady = !prepareZzi4Modules\n        var runtimeRestartNeeded = true\n        var runtimeDetail = ""''',
    'post-root runtime state vars',
)

replace_once(
    POST,
    '''            runtimeApplicable = runtime.applicable\n            runtimeReady = runtime.ready\n            runtimeDetail = runtime.detail''',
    '''            runtimeApplicable = runtime.applicable\n            runtimeReady = runtime.ready\n            runtimeRestartNeeded = runtime.restartNeeded\n            runtimeDetail = runtime.detail''',
    'post-root runtime assignment',
)

# Every result emitted after runtime evaluation carries restartNeeded. The early
# failure results before parsing can keep the default true.
post = POST.read_text(encoding='utf-8')
post = post.replace(
    '''                zzi4RuntimeReady = runtimeReady,\n                detail = runtimeDetail.ifBlank { "post-root automation complete" },''',
    '''                zzi4RuntimeReady = runtimeReady,\n                zzi4RestartNeeded = runtimeRestartNeeded,\n                detail = runtimeDetail.ifBlank { "post-root automation complete" },''',
)
post = post.replace(
    '''                zzi4RuntimeReady = runtimeReady,\n                detail = "kernel boot id unavailable before KernelSU soft reboot",''',
    '''                zzi4RuntimeReady = runtimeReady,\n                zzi4RestartNeeded = runtimeRestartNeeded,\n                detail = "kernel boot id unavailable before KernelSU soft reboot",''',
)
post = post.replace(
    '''                zzi4RuntimeReady = runtimeReady,\n                detail = keeper.detail,''',
    '''                zzi4RuntimeReady = runtimeReady,\n                zzi4RestartNeeded = runtimeRestartNeeded,\n                detail = keeper.detail,''',
)
post = post.replace(
    '''            zzi4RuntimeReady = runtimeReady,\n            detail = "KernelSU native soft-reboot handoff accepted",''',
    '''            zzi4RuntimeReady = runtimeReady,\n            zzi4RestartNeeded = runtimeRestartNeeded,\n            detail = "KernelSU native soft-reboot handoff accepted",''',
)
POST.write_text(post, encoding='utf-8')

replace_once(
    INSTALL,
    '''                        if (restartZygote && requireZzi4Runtime && postRoot?.zzi4RuntimeReady != true) {\n                            appendLog(\n                                "[!] Zygote restart skipped: " +\n                                    (postRoot?.detail ?: "ZZI4 post-root runtime could not be verified"),\n                            )\n                        } else if (restartZygote) {\n                            val restart = RootRecoveryActions.restartZygote(app)''',
    '''                        if (restartZygote && requireZzi4Runtime && postRoot?.zzi4RuntimeReady != true) {\n                            appendLog(\n                                "[!] Zygote restart skipped: " +\n                                    (postRoot?.detail ?: "ZZI4 post-root runtime could not be verified"),\n                            )\n                        } else if (\n                            restartZygote && requireZzi4Runtime &&\n                            postRoot?.zzi4RuntimeApplicable == true &&\n                            !postRoot.zzi4RestartNeeded\n                        ) {\n                            appendLog(\n                                "[+] Zygote restart not needed: LSPosed is already mapped in system_server",\n                            )\n                        } else if (restartZygote) {\n                            val restart = RootRecoveryActions.restartZygote(\n                                app,\n                                oncePerBoot = requireZzi4Runtime,\n                            )''',
    'manual restart decision',
)

replace_once(
    AUTO,
    '''                if (restartZygote && requireZzi4Runtime && postRoot?.zzi4RuntimeReady != true) {\n                    val detail = postRoot?.detail ?: "ZZI4 post-root runtime could not be verified"\n                    val message = "KernelSU root is active; Zygote restart skipped: ${detail.take(180)}"\n                    appendHistory("[!] $message")\n                    finishHistory(InstallRunResult.Succeeded)\n                    Log.w(TAG, message)\n                    finishWithResult(message)\n                    return\n                }\n\n                if (restartZygote) {\n                    val restart = try {\n                        RootRecoveryActions.restartZygote(this)''',
    '''                if (restartZygote && requireZzi4Runtime && postRoot?.zzi4RuntimeReady != true) {\n                    val detail = postRoot?.detail ?: "ZZI4 post-root runtime could not be verified"\n                    val message = "KernelSU root is active; Zygote restart skipped: ${detail.take(180)}"\n                    appendHistory("[!] $message")\n                    finishHistory(InstallRunResult.Succeeded)\n                    Log.w(TAG, message)\n                    finishWithResult(message)\n                    return\n                }\n\n                if (\n                    restartZygote && requireZzi4Runtime &&\n                    postRoot?.zzi4RuntimeApplicable == true &&\n                    !postRoot.zzi4RestartNeeded\n                ) {\n                    val message = "KernelSU root is active; Zygote restart not needed: LSPosed is already mapped in system_server"\n                    appendHistory("[+] $message")\n                    finishHistory(InstallRunResult.Succeeded)\n                    Log.i(TAG, message)\n                    finishWithResult(message)\n                    return\n                }\n\n                if (restartZygote) {\n                    val restart = try {\n                        RootRecoveryActions.restartZygote(\n                            this,\n                            oncePerBoot = requireZzi4Runtime,\n                        )''',
    'auto restart decision',
)

replace_once(
    RECOVERY,
    '''    suspend fun restartZygote(context: Context): RootRecoveryResult = withContext(Dispatchers.IO) {''',
    '''    suspend fun restartZygote(\n        context: Context,\n        oncePerBoot: Boolean = false,\n    ): RootRecoveryResult = withContext(Dispatchers.IO) {''',
    'restart signature',
)

replace_once(
    RECOVERY,
    '''        val acceptedPath = "/data/local/tmp/.rmg-restart-zygote-accepted-$token"\n        val acceptedMarker = "RMG_ZYGOTE_RESTART_ACCEPTED"\n        val script = """''',
    '''        val acceptedPath = "/data/local/tmp/.rmg-restart-zygote-accepted-$token"\n        val acceptedMarker = "RMG_ZYGOTE_RESTART_ACCEPTED"\n        val oncePerBootFlag = if (oncePerBoot) "1" else "0"\n        val script = """''',
    'restart flag variable',
)

replace_once(
    RECOVERY,
    '''            ACCEPTED_VALUE=${shellQuote(acceptedMarker)}\n            current_boot() { cat /proc/sys/kernel/random/boot_id 2>/dev/null; }''',
    '''            ACCEPTED_VALUE=${shellQuote(acceptedMarker)}\n            ONCE_PER_BOOT=${shellQuote('${oncePerBootFlag}')}\n            BOOT_MARKER='/data/local/tmp/.rmg-auto-zygote-restart-boot'\n            current_boot() { cat /proc/sys/kernel/random/boot_id 2>/dev/null; }''',
    'restart script vars',
)
# The literal above intentionally contains ${oncePerBootFlag} for Kotlin interpolation.

replace_once(
    RECOVERY,
    '''            [ "${'$'}(id -u 2>/dev/null)" = "0" ] || reject_handoff 'not-root'\n            [ "${'$'}(current_boot)" = "${'$'}EXPECTED_BOOT" ] || reject_handoff 'boot-changed'\n            [ "${'$'}(getprop init.svc.zygote 2>/dev/null)" = "running" ] || reject_handoff 'zygote-not-running'\n\n            if [ "${'$'}(getprop init.svc.zygote_secondary 2>/dev/null)" = "running" ]; then''',
    '''            [ "${'$'}(id -u 2>/dev/null)" = "0" ] || reject_handoff 'not-root'\n            [ "${'$'}(current_boot)" = "${'$'}EXPECTED_BOOT" ] || reject_handoff 'boot-changed'\n            [ "${'$'}(getprop init.svc.zygote 2>/dev/null)" = "running" ] || reject_handoff 'zygote-not-running'\n\n            if [ "${'$'}ONCE_PER_BOOT" = "1" ]; then\n                [ "${'$'}(cat "${'$'}BOOT_MARKER" 2>/dev/null)" != "${'$'}EXPECTED_BOOT" ] || \\\n                    reject_handoff 'restart-already-performed-this-boot'\n                printf '%s\\n' "${'$'}EXPECTED_BOOT" > "${'$'}BOOT_MARKER" || \\\n                    reject_handoff 'restart-boot-marker-write-failed'\n                chmod 0666 "${'$'}BOOT_MARKER" 2>/dev/null || true\n            fi\n\n            if [ "${'$'}(getprop init.svc.zygote_secondary 2>/dev/null)" = "running" ]; then''',
    'restart once-per-boot guard',
)

# Update and extend the existing tests.
test = TEST.read_text(encoding='utf-8')
test = test.replace(
    '''        assertTrue(command.contains("zygisk_lsposed"))\n        assertFalse(command.contains("pidof zygiskd"))''',
    '''        assertTrue(command.contains("zygisk_lsposed"))\n        assertTrue(command.contains("/proc/$SS/maps"))\n        assertTrue(command.contains("restart_needed="))\n        assertFalse(command.contains("pidof zygiskd"))''',
)
test = test.replace(
    '''        val ready = Zzi4PostRootRuntime.parse(\n            0,\n            "RMG_ZZI4_POST_ROOT_READY memory_type=0 linker=0 zn_daemon=1 module=zygisk_lsposed lspd=1",\n        )\n        assertTrue(ready.applicable)\n        assertTrue(ready.ready)''',
    '''        val ready = Zzi4PostRootRuntime.parse(\n            0,\n            "RMG_ZZI4_POST_ROOT_READY memory_type=0 linker=0 zn_daemon=1 module=zygisk_lsposed lspd=1 restart_needed=1",\n        )\n        assertTrue(ready.applicable)\n        assertTrue(ready.ready)\n        assertTrue(ready.restartNeeded)\n\n        val alreadyInjected = Zzi4PostRootRuntime.parse(\n            0,\n            "RMG_ZZI4_POST_ROOT_READY memory_type=0 linker=0 zn_daemon=1 module=zygisk_lsposed lspd=1 restart_needed=0",\n        )\n        assertTrue(alreadyInjected.applicable)\n        assertTrue(alreadyInjected.ready)\n        assertFalse(alreadyInjected.restartNeeded)''',
)
test = test.replace(
    '''        assertFalse(skipped.applicable)\n        assertTrue(skipped.ready)''',
    '''        assertFalse(skipped.applicable)\n        assertTrue(skipped.ready)\n        assertTrue(skipped.restartNeeded)''',
)
test = test.replace(
    '''        assertTrue(failed.applicable)\n        assertFalse(failed.ready)\n        assertTrue(failed.detail.contains("zn-daemon-not-ready"))''',
    '''        assertTrue(failed.applicable)\n        assertFalse(failed.ready)\n        assertTrue(failed.restartNeeded)\n        assertTrue(failed.detail.contains("zn-daemon-not-ready"))''',
)
test = test.replace(
    '''        assertTrue(auto.contains("postRoot?.zzi4RuntimeReady != true"))\n        assertTrue(post.contains("Zzi4PostRootRuntime.prepareCommand"))''',
    '''        assertTrue(auto.contains("postRoot?.zzi4RuntimeReady != true"))\n        assertTrue(manual.contains("!postRoot.zzi4RestartNeeded"))\n        assertTrue(auto.contains("!postRoot.zzi4RestartNeeded"))\n        assertTrue(manual.contains("oncePerBoot = requireZzi4Runtime"))\n        assertTrue(auto.contains("oncePerBoot = requireZzi4Runtime"))\n        val recovery = File("src/main/java/dev/busung/s25uroot/RootRecoveryActions.kt").readText()\n        assertTrue(recovery.contains("oncePerBoot: Boolean = false"))\n        assertTrue(recovery.contains(".rmg-auto-zygote-restart-boot"))\n        assertTrue(recovery.contains("restart-already-performed-this-boot"))\n        assertTrue(post.contains("Zzi4PostRootRuntime.prepareCommand"))''',
)
TEST.write_text(test, encoding='utf-8')

print('ZZI4 post-root final idempotency patch applied')
