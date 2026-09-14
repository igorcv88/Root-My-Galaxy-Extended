package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRootBootBarrierContractTest {
    private fun source(name: String): String {
        val relative = "src/main/java/dev/busung/s25uroot/$name"
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Unable to locate $relative")
    }

    @Test
    fun priorityShizukuOwnershipIsChosenBeforeAutoRootForegroundGate() {
        val receiver = source("AutoRootBootReceiver.kt")
        val ownershipDecision = receiver.indexOf(
            "launchBootShizuku(context, autoRootPriority = autoRootOwnsShizuku)",
        )
        val gateStart = receiver.indexOf("context.startForegroundService(")

        assertTrue(receiver.contains("AppPreferences.shizukuBootRequiredByAutoRoot(context)"))
        assertTrue(ownershipDecision >= 0)
        assertTrue(gateStart > ownershipDecision)
    }

    @Test
    fun zzi4BarrierIsAnchoredToBootCompletedThenQuietsShizuku() {
        val gate = source("AutoRootService.kt")

        assertTrue(gate.contains("bootCompletedAt + ZZI4_POST_BOOT_STABILIZATION_MILLIS"))
        assertTrue(gate.contains("ZZI4_POST_BOOT_STABILIZATION_MILLIS = 180_000L"))
        assertTrue(gate.contains("ShizukuBootService.stop(this)"))
        assertTrue(gate.contains("ZZI4_SHIZUKU_SETTLE_QUIET_MILLIS = 10_000L"))
    }

    @Test
    fun zzi4ProviderExecutorIsPreboundButNotStartedUntilAfterQuietBarrier() {
        val gate = source("AutoRootService.kt")
        val prebind = gate.indexOf("bindExecutor(shellTransportRequired = true, prewarm = true)")
        val wait = gate.indexOf("waitUntilElapsedRealtime(target)", prebind)
        val quiet = gate.indexOf("delay(ZZI4_SHIZUKU_SETTLE_QUIET_MILLIS)", wait)
        val arm = gate.indexOf("executorStartReady = true", quiet)
        val deliver = gate.indexOf("deliverExecutorStartIfReady()", arm)

        assertTrue(prebind >= 0)
        assertTrue(wait > prebind)
        assertTrue(quiet > wait)
        assertTrue(arm > quiet)
        assertTrue(deliver > arm)
        assertTrue(gate.contains("holding provider process through stabilization"))
    }

    @Test
    fun stabilizationGateDoesNotHoldPartialWakeLockButExecutorStillDoes() {
        val gate = source("AutoRootService.kt")
        val executor = source("AutoRootExecutorService.kt")

        assertFalse(gate.contains("PowerManager.PARTIAL_WAKE_LOCK"))
        assertFalse(gate.contains("MAX_GATE_WAKELOCK_MILLIS"))
        assertTrue(executor.contains("PowerManager.PARTIAL_WAKE_LOCK"))
        assertTrue(executor.contains("MAX_EXECUTOR_WAKELOCK_MILLIS"))
    }
}
