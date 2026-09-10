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
    fun shellRequiredAutoRootWaitsBeforeClaimingAttempt() {
        val executor = source("AutoRootExecutorService.kt")
        val wait = executor.indexOf(
            "ShizukuController.awaitRunning(AUTO_ROOT_SHIZUKU_WAIT_MILLIS)",
        )
        val grant = executor.indexOf("ShizukuController.isGranted()", wait)
        val claim = executor.indexOf("AutoRootSupport.claimAttempt(this, bootToken)")
        assertTrue(wait >= 0)
        assertTrue(grant > wait)
        assertTrue(claim > grant)

        val gate = source("AutoRootService.kt")
        assertFalse(gate.contains("AutoRootSupport.claimAttempt(this, bootToken)"))
    }

    @Test
    fun autoRootRunnerHonorsShellRouteInsteadOfAppFallback() {
        val runner = source("AutoRootRunner.kt")
        assertTrue(runner.contains("payloads.profile.routePolicy.prefersShellTransport"))
        assertTrue(runner.contains("ExploitRoutePolicy.SHELL_TRANSPORT"))
        assertTrue(runner.contains("ShizukuController.exec("))
        assertTrue(runner.contains("shizukuStage(localHelper, SHIZUKU_HELPER_PATH)"))
        assertFalse(runner.contains("never claims the shell transport"))
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
