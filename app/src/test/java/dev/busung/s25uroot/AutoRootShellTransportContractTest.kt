package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRootShellTransportContractTest {
    private fun source(name: String): String {
        val relative = "src/main/java/dev/busung/s25uroot/$name"
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Unable to locate $relative from ${File(".").absolutePath}")
    }

    private fun manifest(): String {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Unable to locate AndroidManifest.xml")
    }

    @Test
    fun shellRequiredAutoRootSelectsRealShellBeforeClaimingAttempt() {
        val executor = source("AutoRootExecutorService.kt")
        assertTrue(executor.contains("AutoRootShellTransport.Shizuku"))
        assertTrue(executor.contains("AutoRootShellTransport.LocalAdb"))
        assertTrue(executor.contains("AppPreferences.adbPaired(this)"))
        assertTrue(executor.contains("AUTO_ROOT_SHIZUKU_PREFERENCE_GRACE_MILLIS"))

        val runnerCall = executor.indexOf("runner.run(")
        val callback = executor.indexOf("beforeExploit = {", runnerCall)
        val claim = executor.indexOf("AutoRootSupport.claimAttempt(this, bootToken)", callback)
        assertTrue(runnerCall >= 0)
        assertTrue(callback > runnerCall)
        assertTrue(claim > callback)
        assertFalse(executor.substring(0, runnerCall).contains("AutoRootSupport.claimAttempt(this, bootToken)"))

        val gate = source("AutoRootService.kt")
        assertFalse(gate.contains("AutoRootSupport.claimAttempt(this, bootToken)"))
    }

    @Test
    fun localAdbFallbackRemainsRealShellAndNeverAppFallback() {
        val runner = source("AutoRootRunner.kt")
        assertTrue(runner.contains("executeExploitViaLocalAdb("))
        assertTrue(runner.contains("AutoRootShellTransport.LocalAdb"))
        assertTrue(runner.contains("WirelessAdbSession.open("))
        assertTrue(runner.contains("identity.output.contains(\"uid=2000\")"))
        assertTrue(runner.contains("identity.output.contains(\"u:r:shell:s0\")"))
        assertTrue(runner.contains("session.push(localHelper, SHELL_HELPER_PATH"))
        assertTrue(runner.contains("session.push(payload, SHELL_PAYLOAD_PATH"))
        assertTrue(runner.contains("session.runStreaming("))
        assertTrue(runner.contains("ExploitRoutePolicy.SHELL_TRANSPORT"))
        assertTrue(runner.contains("Shell-required Auto Root has no usable shell transport"))
    }

    @Test
    fun localAdbStagesExactArtifactsBeforeClaimCallback() {
        val runner = source("AutoRootRunner.kt")
        val fallback = runner.indexOf("private suspend fun executeExploitViaLocalAdb(")
        val helperPush = runner.indexOf("session.push(localHelper, SHELL_HELPER_PATH", fallback)
        val payloadPush = runner.indexOf("session.push(payload, SHELL_PAYLOAD_PATH", helperPush)
        val claimCallback = runner.indexOf("beforeExploit()", payloadPush)
        val launch = runner.indexOf("session.runStreaming(", claimCallback)
        assertTrue(fallback >= 0)
        assertTrue(helperPush > fallback)
        assertTrue(payloadPush > helperPush)
        assertTrue(claimCallback > payloadPush)
        assertTrue(launch > claimCallback)
    }

    @Test
    fun temporaryAdbUsersAreSerialized() {
        val temporaryAdb = source("TemporaryWirelessAdb.kt")
        assertTrue(temporaryAdb.contains("private val sessionMutex = Mutex()"))
        assertTrue(temporaryAdb.contains("sessionMutex.withLock"))
    }

    @Test
    fun shellRequiredAutoRootUsesProviderProcessExecutor() {
        val gate = source("AutoRootService.kt")
        assertTrue(gate.contains("AutoRootShellExecutorService::class.java"))
        assertTrue(gate.contains("if (shellTransportRequired)"))

        val shellExecutor = source("AutoRootShellExecutorService.kt")
        assertTrue(shellExecutor.contains(": AutoRootExecutorService()"))

        val manifest = manifest()
        val shellServiceStart = manifest.indexOf("android:name=\".AutoRootShellExecutorService\"")
        assertTrue(shellServiceStart >= 0)
        val shellServiceEnd = manifest.indexOf("</service>", shellServiceStart)
            .takeIf { it >= 0 }
            ?: manifest.indexOf("/>", shellServiceStart)
        val shellServiceDeclaration = manifest.substring(shellServiceStart, shellServiceEnd + 2)
        assertFalse(shellServiceDeclaration.contains("android:process="))

        val standaloneServiceStart = manifest.indexOf("android:name=\".AutoRootExecutorService\"")
        assertTrue(standaloneServiceStart >= 0)
        val standaloneServiceEnd = manifest.indexOf("/>", standaloneServiceStart)
        val standaloneDeclaration = manifest.substring(standaloneServiceStart, standaloneServiceEnd + 2)
        assertTrue(standaloneDeclaration.contains("android:process=\":autoroot_exec\""))
    }

    @Test
    fun kernelSuHandoffKeepsRootDaemonClientIdentity() {
        val runner = source("AutoRootRunner.kt")
        assertTrue(runner.contains("withKernelSuClient(shellTransport)"))
        assertTrue(runner.contains("KernelSU handoff client=shizuku-shell uid=2000"))
        assertTrue(runner.contains("runShizukuHelper(*arguments)"))
        assertTrue(runner.contains("KernelSU handoff client=local-adb-shell uid=2000"))
        assertTrue(runner.contains("runLocalAdbHelper(session, *arguments)"))
        assertTrue(runner.contains("KernelSU handoff client=standalone-app"))
        assertTrue(runner.contains("""ksuExec(arrayOf("--late-load"))"""))
        assertTrue(runner.contains("postRootExec(KernelSuGlobalReadiness.command(bootToken))"))
        assertTrue(runner.contains("runShizukuKernelSuRoot(command)"))
        assertTrue(runner.contains("runLocalAdbKernelSuRoot(session, command)"))
        assertFalse(runner.contains("""ksuExec(arrayOf("-c", KernelSuGlobalReadiness.command(bootToken)))"""))
        assertFalse(runner.contains("KernelSuGlobalReadiness.probe(context, bootToken)"))
    }

    @Test
    fun kernelSuRuntimeDetectionDoesNotDependOnProcModulesOrReceipt() {
        val runtime = source("KernelSuRuntime.kt")
        assertTrue(runtime.contains("""RootHelperShell.execute(context, "--ksu-info")"""))

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

    @Test
    fun shizukuBootDoesNotYieldToItsAutoRootConsumer() {
        val service = source("ShizukuBootService.kt")
        assertTrue(service.contains("autoRootPriority || autoRootRequiresShellTransport()"))
        assertTrue(service.contains("fun startForAutoRoot(context: Context)"))
        assertTrue(service.contains("AUTO_ROOT_STARTER_GRACE_MILLIS = 750L"))
    }

    @Test
    fun shizukuBinderSharingIsEnabledAndRequestedInSecondaryProcesses() {
        val app = source("RootMyGalaxyApplication.kt")
        assertTrue(app.contains("ShizukuProvider.enableMultiProcessSupport"))
        assertTrue(app.contains("Application.getProcessName() == base.packageName"))
        assertTrue(app.contains("ShizukuProvider.requestBinderForNonProviderProcess(this)"))
        assertTrue(manifest().contains("android:name=\".RootMyGalaxyApplication\""))
    }
}
