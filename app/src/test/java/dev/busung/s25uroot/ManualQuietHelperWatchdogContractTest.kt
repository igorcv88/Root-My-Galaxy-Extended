package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualQuietHelperWatchdogContractTest {
    private fun source(name: String): String {
        val relative = "src/main/java/dev/busung/s25uroot/$name"
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Unable to locate $relative from ${File(".").absolutePath}")
    }

    @Test
    fun manualExploitDoesNotTreatQuietHelperSilenceAsFailure() {
        val viewModel = source("InstallViewModel.kt")

        assertFalse(viewModel.contains("EXPLOIT_STALL_MILLIS"))
        assertTrue(
            viewModel.contains(
                "SystemClock.elapsedRealtime() - startedAt < EXPLOIT_TOTAL_MILLIS",
            ),
        )
        assertTrue(viewModel.contains("stallTimeoutMs = EXPLOIT_TOTAL_MILLIS"))
    }
}
