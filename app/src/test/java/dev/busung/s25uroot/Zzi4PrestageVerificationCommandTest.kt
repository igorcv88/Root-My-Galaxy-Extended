package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Zzi4PrestageVerificationCommandTest {
    @Test
    fun autoRootZzi4HotPathHasNoKsudVerificationOrWrite() {
        val source = File("src/main/java/dev/busung/s25uroot/AutoRootRunner.kt").readText()

        val shizukuStart = source.indexOf("private suspend fun executeExploit(")
        val localStart = source.indexOf("private suspend fun executeExploitViaLocalAdb", shizukuStart)
        val localEnd = source.indexOf("private fun shizukuStage", localStart)
        assertTrue(shizukuStart >= 0)
        assertTrue(localStart > shizukuStart)
        assertTrue(localEnd > localStart)

        val shizukuHotPath = source.substring(shizukuStart, localStart)
        val localHotPath = source.substring(localStart, localEnd)
        for (hotPath in listOf(shizukuHotPath, localHotPath)) {
            assertFalse(hotPath.contains("preStageZzi4KernelSu"))
            assertFalse(hotPath.contains("KSUD_PATH"))
            assertFalse(hotPath.contains("sha256sum"))
            assertFalse(hotPath.contains("wc -c"))
        }
        assertFalse(source.contains("zzi4KernelSuVerifyCommand"))
        assertFalse(source.contains("private fun preStageZzi4KernelSu"))
    }

    @Test
    fun autoRootKeepsPostRootStageAndExplicitLateLoadFallback() {
        val source = File("src/main/java/dev/busung/s25uroot/AutoRootRunner.kt").readText()
        val runStart = source.indexOf("suspend fun run(")
        val runEnd = source.indexOf("private suspend fun verifyKernelSu", runStart)
        val runBody = source.substring(runStart, runEnd)

        assertTrue(runBody.indexOf("executeExploit(") < runBody.indexOf("withKernelSuClient"))
        assertTrue(source.contains("stageKernelSuRequired(payloads, ksuExec)"))
        assertTrue(source.contains("ksuExec(arrayOf(\"--late-load\"))"))
        assertTrue(source.contains("verifyKernelSu(bootToken, ksuExec, postRootExec)"))
    }
}
