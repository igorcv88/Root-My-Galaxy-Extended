#!/usr/bin/env python3
from pathlib import Path

root = Path('.')
base = root / 'app/src/main/java/dev/busung/s25uroot'

runtime = base / 'KernelSuRuntime.kt'
if runtime.exists():
    raise SystemExit('KernelSuRuntime.kt already exists')
runtime.write_text('''package dev.busung.s25uroot

import android.content.Context

/** Runtime checks and the post-KernelSU root bridge.
 *
 * The bootstrap helper socket exists only to cross the pre-KernelSU handoff.
 * Once KernelSU has loaded, Samsung/SELinux may reject new shell/app connects to
 * that socket even though KernelSU itself is healthy. Control detection therefore
 * uses the helper's direct --ksu-info path, and privileged post-load commands use
 * KernelSU su through an already-authorized Shizuku shell when available.
 */
internal object KernelSuRuntime {
    fun isControlActive(context: Context): Boolean {
        if (NativeProbe.isKernelSuActive()) return true
        val direct = runCatching {
            RootHelperShell.execute(context, "--ksu-info")
        }.getOrNull()
        return direct?.exitCode == 0
    }

    fun shizukuRootShell(command: String): LocalAdbClient.ShellResult? {
        if (!ShizukuController.isRunning() || !ShizukuController.isGranted()) return null

        val direct = runCatching { ShizukuController.shell("id") }.getOrNull()
        if (direct != null && direct.exitCode == 0 && direct.output.contains("uid=0")) {
            return runCatching { ShizukuController.shell(command) }.getOrNull()
        }

        val elevated = runCatching { ShizukuController.shell("su -c id") }.getOrNull()
        if (elevated == null || elevated.exitCode != 0 || !elevated.output.contains("uid=0")) {
            return null
        }
        return runCatching {
            ShizukuController.shell("su -c ${shellQuote(command)}")
        }.getOrNull()
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\\\''")}'"
}
''', encoding='utf-8')

p = base / 'RootHelperShell.kt'
text = p.read_text(encoding='utf-8')
old = '''internal object RootHelperShell {
    fun shell(context: Context, command: String): LocalAdbClient.ShellResult =
        execute(context, "-c", command)

    fun execute(context: Context, vararg arguments: String): LocalAdbClient.ShellResult {
'''
new = '''internal object RootHelperShell {
    fun shell(context: Context, command: String): LocalAdbClient.ShellResult {
        val bootstrap = execute(context, "-c", command)
        if (!bootstrapTransportUnavailable(bootstrap)) return bootstrap

        // After kernelsu.ko loads, Samsung/SELinux can deny reconnecting to the
        // temporary bootstrap socket. Never replay a command merely because it
        // returned non-zero: switch transports only for an unmistakable helper
        // transport/authentication failure.
        return KernelSuRuntime.shizukuRootShell(command) ?: bootstrap
    }

    fun execute(context: Context, vararg arguments: String): LocalAdbClient.ShellResult {
'''
if text.count(old) != 1:
    raise SystemExit('RootHelperShell entry anchor mismatch')
text = text.replace(old, new, 1)
anchor = '''    private fun helperFile(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libcve43499root.so")

    private const val COMMAND_TIMEOUT_MILLIS = 15_000L
'''
replacement = '''    private fun bootstrapTransportUnavailable(result: LocalAdbClient.ShellResult): Boolean {
        val output = result.output.lowercase()
        return "su: connect daemon:" in output ||
            "su: permission denied" in output
    }

    private fun helperFile(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libcve43499root.so")

    private const val COMMAND_TIMEOUT_MILLIS = 15_000L
'''
if text.count(anchor) != 1:
    raise SystemExit('RootHelperShell tail anchor mismatch')
p.write_text(text.replace(anchor, replacement, 1), encoding='utf-8')

p = base / 'AutoRootRunner.kt'
text = p.read_text(encoding='utf-8')
text = text.replace(
    'withKernelSuClient(shellTransport) { ksuExec ->\n            val autoLoaded = waitForAutoLateLoad(bootToken, ksuExec)',
    'withKernelSuClient(shellTransport) { ksuExec, postRootExec ->\n            val autoLoaded = waitForAutoLateLoad(bootToken, ksuExec, postRootExec)',
    1,
)
text = text.replace('verifyKernelSu(bootToken, ksuExec)', 'verifyKernelSu(bootToken, ksuExec, postRootExec)', 2)
old_sig = '''    private suspend fun verifyKernelSu(
        bootToken: String,
        ksuExec: suspend (Array<out String>) -> AutoRootCommandResult,
    ) {
'''
new_sig = '''    private suspend fun verifyKernelSu(
        bootToken: String,
        ksuExec: suspend (Array<out String>) -> AutoRootCommandResult,
        postRootExec: suspend (String) -> AutoRootCommandResult,
    ) {
'''
if text.count(old_sig) != 1:
    raise SystemExit('verifyKernelSu signature anchor mismatch')
text = text.replace(old_sig, new_sig, 1)
old_global = '''        val global = ksuExec(arrayOf("-c", KernelSuGlobalReadiness.command(bootToken)))
'''
new_global = '''        // Do not reconnect to the bootstrap temp_su.sock after KernelSU is
        // active. On ZZI4 SELinux can reject that socket while KernelSU itself
        // is already healthy. Verify global userspace readiness through the
        // post-load KernelSU root bridge instead.
        val global = postRootExec(KernelSuGlobalReadiness.command(bootToken))
'''
if text.count(old_global) != 1:
    raise SystemExit(f'expected one verify global call, got {text.count(old_global)}')
text = text.replace(old_global, new_global, 1)
old_wait_sig = '''    private suspend fun waitForAutoLateLoad(
        bootToken: String,
        ksuExec: suspend (Array<out String>) -> AutoRootCommandResult,
    ): Boolean {
'''
new_wait_sig = '''    private suspend fun waitForAutoLateLoad(
        bootToken: String,
        ksuExec: suspend (Array<out String>) -> AutoRootCommandResult,
        postRootExec: suspend (String) -> AutoRootCommandResult,
    ): Boolean {
'''
if text.count(old_wait_sig) != 1:
    raise SystemExit('waitForAutoLateLoad signature anchor mismatch')
text = text.replace(old_wait_sig, new_wait_sig, 1)
old_wait_global = '''                val global = runCatching {
                    ksuExec(arrayOf("-c", KernelSuGlobalReadiness.command(bootToken)))
                }.getOrNull()
'''
new_wait_global = '''                val global = runCatching {
                    postRootExec(KernelSuGlobalReadiness.command(bootToken))
                }.getOrNull()
'''
if text.count(old_wait_global) != 1:
    raise SystemExit('wait global loop anchor mismatch')
text = text.replace(old_wait_global, new_wait_global, 1)
old_final_global = '''        val finalGlobal = runCatching {
            ksuExec(arrayOf("-c", KernelSuGlobalReadiness.command(bootToken)))
        }.getOrNull()
'''
new_final_global = '''        val finalGlobal = runCatching {
            postRootExec(KernelSuGlobalReadiness.command(bootToken))
        }.getOrNull()
'''
if text.count(old_final_global) != 1:
    raise SystemExit('wait final global anchor mismatch')
text = text.replace(old_final_global, new_final_global, 1)

old_client = '''    private suspend fun <T> withKernelSuClient(
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
'''
new_client = '''    private suspend fun <T> withKernelSuClient(
        shellTransport: AutoRootShellTransport?,
        block: suspend (
            bootstrapExec: suspend (Array<out String>) -> AutoRootCommandResult,
            postRootExec: suspend (String) -> AutoRootCommandResult,
        ) -> T,
    ): T = when (shellTransport) {
        AutoRootShellTransport.Shizuku -> {
            require(ShizukuController.isGranted()) {
                "Shizuku shell transport disappeared before KernelSU handoff"
            }
            onLog("[*] KernelSU handoff client=shizuku-shell uid=2000")
            block(
                { arguments -> runShizukuHelper(*arguments) },
                { command -> runShizukuKernelSuRoot(command) },
            )
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
                    block(
                        { arguments -> runLocalAdbHelper(session, *arguments) },
                        { command -> runLocalAdbKernelSuRoot(session, command) },
                    )
                }
            }
        }
        null -> {
            onLog("[*] KernelSU handoff client=standalone-app")
            block(
                { arguments -> runHelper(*arguments) },
                { command ->
                    val result = RootHelperShell.shell(context, command)
                    AutoRootCommandResult(result.exitCode, stripAnsi(result.output.trim()))
                },
            )
        }
    }
'''
if text.count(old_client) != 1:
    raise SystemExit('withKernelSuClient block anchor mismatch')
text = text.replace(old_client, new_client, 1)

anchor = '''    private suspend fun runShizukuHelper(vararg arguments: String): AutoRootCommandResult {
        val process = ShizukuController.exec(
            arrayOf(SHELL_HELPER_PATH, *arguments),
            dir = "/data/local/tmp",
        )
        return awaitHelperProcess(process)
    }

    private fun runLocalAdbHelper(
'''
replacement = '''    private suspend fun runShizukuHelper(vararg arguments: String): AutoRootCommandResult {
        val process = ShizukuController.exec(
            arrayOf(SHELL_HELPER_PATH, *arguments),
            dir = "/data/local/tmp",
        )
        return awaitHelperProcess(process)
    }

    private fun runShizukuKernelSuRoot(command: String): AutoRootCommandResult {
        val result = ShizukuController.shell("su -c ${shellQuote(command)}")
        return AutoRootCommandResult(result.exitCode, stripAnsi(result.output.trim()))
    }

    private fun runLocalAdbKernelSuRoot(
        session: WirelessAdbSession,
        command: String,
    ): AutoRootCommandResult {
        val result = session.shell("su -c ${shellQuote(command)} 2>&1")
        return AutoRootCommandResult(result.exitCode, stripAnsi(result.output.trim()))
    }

    private fun runLocalAdbHelper(
'''
if text.count(anchor) != 1:
    raise SystemExit('helper methods anchor mismatch')
text = text.replace(anchor, replacement, 1)
p.write_text(text, encoding='utf-8')

p = base / 'InstallViewModel.kt'
text = p.read_text(encoding='utf-8')
old = '''    private fun detectInstalled(): Boolean {
        if (NativeProbe.isKernelSuActive()) return true
        val bootToken = currentBootToken() ?: return false
        val receipt = app.getSharedPreferences(INSTALL_RECEIPT, Application.MODE_PRIVATE)
        return receipt.getString(RECEIPT_BOOT_TOKEN, null) == bootToken &&
            receipt.getBoolean(RECEIPT_VERIFIED, false)
    }
'''
new = '''    private fun detectInstalled(): Boolean {
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
'''
if text.count(old) != 1:
    raise SystemExit('detectInstalled anchor mismatch')
p.write_text(text.replace(old, new, 1), encoding='utf-8')

p = base / 'AutoRootService.kt'
text = p.read_text(encoding='utf-8')
count = text.count('if (NativeProbe.isKernelSuActive()) {')
if count != 2:
    raise SystemExit(f'AutoRootService NativeProbe count={count}')
text = text.replace('if (NativeProbe.isKernelSuActive()) {', 'if (KernelSuRuntime.isControlActive(this)) {')
p.write_text(text, encoding='utf-8')

p = base / 'AutoRootExecutorService.kt'
text = p.read_text(encoding='utf-8')
if text.count('if (NativeProbe.isKernelSuActive()) {') != 1:
    raise SystemExit('AutoRootExecutorService early probe anchor mismatch')
text = text.replace('if (NativeProbe.isKernelSuActive()) {', 'if (KernelSuRuntime.isControlActive(this)) {', 1)
old_catch = '''        } catch (error: Throwable) {
            if (!scope.isActive) return
            val detail = error.message ?: error.javaClass.simpleName
            Log.e(TAG, "Auto Root executor failed", error)
            if (historyEntry != null) {
                appendHistory("[-] $detail")
                finishHistory(InstallRunResult.Failed)
            }
            finishWithResult(getString(R.string.autoroot_failed, detail))
        } finally {
'''
new_catch = '''        } catch (error: Throwable) {
            if (!scope.isActive) return
            val detail = error.message ?: error.javaClass.simpleName

            // Root acquisition and full userspace/module readiness are separate
            // boundaries. If kernelsu.ko is already controllable, persist the
            // current boot as rooted even when a later readiness/automation step
            // failed. This prevents the UI and the next BOOT_COMPLETED from
            // treating a live KernelSU instance as unrooted and replaying exploit.
            val controlActive = runCatching { KernelSuRuntime.isControlActive(this) }
                .getOrDefault(false)
            if (controlActive) {
                AutoRootSupport.currentBootToken()?.let { bootToken ->
                    runCatching { AutoRootSupport.markVerifiedForBoot(this, bootToken) }
                }
                if (historyEntry != null) {
                    appendHistory("[!] KernelSU control is active; post-root verification incomplete: $detail")
                    finishHistory(InstallRunResult.Succeeded)
                }
                Log.w(TAG, "KernelSU control active after Auto Root post-root failure: $detail", error)
                finishWithResult("KernelSU root is active; post-root verification incomplete: ${detail.takeLast(180)}")
                return
            }

            Log.e(TAG, "Auto Root executor failed", error)
            if (historyEntry != null) {
                appendHistory("[-] $detail")
                finishHistory(InstallRunResult.Failed)
            }
            finishWithResult(getString(R.string.autoroot_failed, detail))
        } finally {
'''
if text.count(old_catch) != 1:
    raise SystemExit('AutoRootExecutorService catch anchor mismatch')
p.write_text(text.replace(old_catch, new_catch, 1), encoding='utf-8')

# Extend source-contract tests around the exact regression.
p = root / 'app/src/test/java/dev/busung/s25uroot/AutoRootShellTransportContractTest.kt'
text = p.read_text(encoding='utf-8')
old = '''        assertTrue(runner.contains("KernelSU handoff client=local-adb-shell uid=2000"))
        assertTrue(runner.contains("runLocalAdbHelper(session, *arguments)"))
        assertTrue(runner.contains("KernelSU handoff client=standalone-app"))
        assertTrue(runner.contains("""ksuExec(arrayOf("--late-load"))"""))
        assertTrue(runner.contains("KernelSuGlobalReadiness.command(bootToken)"))
        assertFalse(runner.contains("KernelSuGlobalReadiness.probe(context, bootToken)"))
'''
new = '''        assertTrue(runner.contains("KernelSU handoff client=local-adb-shell uid=2000"))
        assertTrue(runner.contains("runLocalAdbHelper(session, *arguments)"))
        assertTrue(runner.contains("KernelSU handoff client=standalone-app"))
        assertTrue(runner.contains("""ksuExec(arrayOf("--late-load"))"""))
        assertTrue(runner.contains("postRootExec(KernelSuGlobalReadiness.command(bootToken))"))
        assertTrue(runner.contains("runShizukuKernelSuRoot(command)"))
        assertTrue(runner.contains("runLocalAdbKernelSuRoot(session, command)"))
        assertFalse(runner.contains("ksuExec(arrayOf(\"-c\", KernelSuGlobalReadiness.command(bootToken)))"))
        assertFalse(runner.contains("KernelSuGlobalReadiness.probe(context, bootToken)"))
'''
if text.count(old) != 1:
    raise SystemExit('AutoRootShellTransportContractTest anchor mismatch')
text = text.replace(old, new, 1)
insert = '''
    @Test
    fun kernelSuRuntimeDetectionDoesNotDependOnProcModulesOrReceipt() {
        val runtime = source("KernelSuRuntime.kt")
        assertTrue(runtime.contains("RootHelperShell.execute(context, \"--ksu-info\")"))

        val viewModel = source("InstallViewModel.kt")
        assertTrue(viewModel.contains("KernelSuRuntime.isControlActive(app)"))
        assertTrue(viewModel.contains("AutoRootSupport.markVerifiedForBoot(app, bootToken)"))

        val gate = source("AutoRootService.kt")
        assertTrue(gate.contains("KernelSuRuntime.isControlActive(this)"))

        val executor = source("AutoRootExecutorService.kt")
        assertTrue(executor.contains("KernelSuRuntime.isControlActive(this)"))
        assertTrue(executor.contains("post-root verification incomplete"))
    }

    @Test
    fun bootstrapHelperFallsBackOnlyForTransportFailure() {
        val helper = source("RootHelperShell.kt")
        assertTrue(helper.contains("bootstrapTransportUnavailable(bootstrap)"))
        assertTrue(helper.contains("KernelSuRuntime.shizukuRootShell(command) ?: bootstrap"))
        assertTrue(helper.contains("su: connect daemon:"))
        assertTrue(helper.contains("su: permission denied"))
    }
'''
needle = '\n    @Test\n    fun shizukuBootDoesNotYieldToItsAutoRootConsumer() {'
if text.count(needle) != 1:
    raise SystemExit('test insertion anchor mismatch')
text = text.replace(needle, insert + needle, 1)
p.write_text(text, encoding='utf-8')

print('Applied KernelSU runtime/post-load bridge fix')
