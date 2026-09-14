package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Zzi4PrestageVerificationCommandTest {
    @Test
    fun manualAndAutoRootUseQuoteSafeRemoteVerification() {
        val manual = File("src/main/java/dev/busung/s25uroot/InstallViewModel.kt").readText()
        val autoRoot = File("src/main/java/dev/busung/s25uroot/AutoRootRunner.kt").readText()

        for (source in listOf(manual, autoRoot)) {
  assertTrue(source.contains("/system/bin/toybox cut -d ' ' -f 1"))
  assertFalse(source.contains("/system/bin/toybox awk"))
        }
    }

    @Test
    fun remoteArtifactParserAcceptsHashAndSizeOnSeparateLines() {
        val sha = "d76b44c984b8d6f7121f712d8aa4e7cf0178ff36a8cd531bddaca9dd2fd361e2"
        assertTrue(remoteArtifactMatches("$sha\n6664656", sha, 6664656L))
    }
}
