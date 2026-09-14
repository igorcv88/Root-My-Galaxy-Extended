package dev.busung.s25uroot

import java.io.File
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
    fun zzi4PriorityShizukuBootstrapStartsBeforeAutoRootForegroundGate() {
        val receiver = source("AutoRootBootReceiver.kt")
        val priorityBootstrap = receiver.indexOf("ShizukuBootService.startForAutoRoot(context)")
        val gateStart = receiver.indexOf("context.startForegroundService(")

        assertTrue(priorityBootstrap >= 0)
        assertTrue(gateStart > priorityBootstrap)
    }

    @Test
    fun zzi4BarrierIsAnchoredToBootCompletedThenQuietsShizuku() {
        val gate = source("AutoRootService.kt")

        assertTrue(gate.contains("bootCompletedAt + ZZI4_POST_BOOT_STABILIZATION_MILLIS"))
        assertTrue(gate.contains("ZZI4_POST_BOOT_STABILIZATION_MILLIS = 180_000L"))
        assertTrue(gate.contains("ShizukuBootService.stop(this)"))
        assertTrue(gate.contains("ZZI4_SHIZUKU_SETTLE_QUIET_MILLIS = 10_000L"))
    }
}
