package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Zzi4KernelSuPrestageTest {
    @Test
    fun remoteArtifactVerificationRequiresExactShaAndSize() {
        val sha = "d76b44c984b8d6f7121f712d8aa4e7cf0178ff36a8cd531bddaca9dd2fd361e2"
        assertTrue(remoteArtifactMatches("$sha 6664656", sha, 6664656L))
        assertFalse(remoteArtifactMatches("$sha 1", sha, 6664656L))
        assertFalse(remoteArtifactMatches("deadbeef 6664656", sha, 6664656L))
    }

    @Test
    fun zzi4PrestageRunsBeforeExploitAndClearsOldStage() {
        val source = File("src/main/java/dev/busung/s25uroot/InstallViewModel.kt").readText()
        val start = source.indexOf("private suspend fun runExploitAndKernelSu")
        val end = source.indexOf("private suspend fun executeExploit", start)
        val body = source.substring(start, end)

        assertTrue(body.contains("preStageKernelSuForAutoLateLoad(payloads)"))
        assertTrue(body.indexOf("preStageKernelSuForAutoLateLoad(payloads)") < body.indexOf("executeExploit(payloads.exploit"))
        assertTrue(body.contains("session.remove(SHIZUKU_KSUD_STAGE_PATH)"))
        assertTrue(body.contains("ShizukuController.writeFile"))
        assertTrue(body.contains("remoteArtifactMatches"))
        assertTrue(body.contains("pa3q-S938BXXUCZZI4"))
    }
}
