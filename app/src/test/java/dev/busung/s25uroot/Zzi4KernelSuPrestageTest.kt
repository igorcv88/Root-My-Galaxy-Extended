package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Zzi4KernelSuPrestageTest {
    @Test
    fun manualZzi4ExploitRunsBeforeAnyKernelSuStage() {
        val source = File("src/main/java/dev/busung/s25uroot/InstallViewModel.kt").readText()
        val start = source.indexOf("private suspend fun runExploitAndKernelSu")
        val end = source.indexOf("private suspend fun executeExploit", start)
        assertTrue(start >= 0)
        assertTrue(end > start)
        val body = source.substring(start, end)

        assertFalse(body.contains("preStageKernelSu"))
        assertFalse(body.contains("SHIZUKU_KSUD"))
        assertFalse(body.contains("sha256sum"))
        assertFalse(body.contains("wc -c"))
        assertTrue(body.indexOf("executeExploit(") < body.indexOf("installKernelSu(payloads)"))
    }

    @Test
    fun manualKernelSuStageAndLateLoadRemainPostRoot() {
        val source = File("src/main/java/dev/busung/s25uroot/InstallViewModel.kt").readText()
        assertFalse(source.contains("private fun preStageKernelSuForAutoLateLoad"))
        assertFalse(source.contains("remoteArtifactMatches"))
        assertTrue(source.contains("val stage = runHelper(\"-c\", kernelSuStageCommand(payloads))"))
        assertTrue(source.contains("runHelper(\"--late-load\")"))
        assertTrue(source.contains("KernelSuGlobalReadiness.command(bootToken)"))
    }
}
