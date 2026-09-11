package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Zzi4PostRootRuntimeTest {
    @Test
    fun runtimeCommandPreservesValidatedOrderAndSignals() {
        val command = Zzi4PostRootRuntime.prepareCommand("boot-test")

        val memory = command.indexOf("memory-type default")
        val linker = command.indexOf("linker system")
        val daemon = command.indexOf("./bin/zygiskd daemon")
        val modules = command.indexOf("if ! module_ready; then")
        val serviceStage = command.indexOf("./bin/zygiskd service-stage")
        val lsposed = command.indexOf("setsid \"\$LS/daemon\" --force")
        val ready = command.indexOf("RMG_ZZI4_POST_ROOT_READY")

        assertTrue(memory >= 0)
        assertTrue(linker > memory)
        assertTrue(daemon > linker)
        assertTrue(modules > daemon)
        assertTrue(serviceStage > daemon)
        assertTrue(lsposed > serviceStage)
        assertTrue(ready > lsposed)
        assertTrue(command.contains("zn-daemon"))
        assertTrue(command.contains("zygisk_lsposed"))
        assertFalse(command.contains("pidof zygiskd"))
        assertFalse(command.contains("pidof zygiskd64"))
        assertFalse(command.contains("cat /data/adb/zygisksu/znctx"))
        assertFalse(command.contains("insmod"))
    }

    @Test
    fun parserSeparatesReadySkippedAndFailure() {
        val ready = Zzi4PostRootRuntime.parse(
            0,
            "RMG_ZZI4_POST_ROOT_READY memory_type=0 linker=0 zn_daemon=1 module=zygisk_lsposed lspd=1",
        )
        assertTrue(ready.applicable)
        assertTrue(ready.ready)

        val skipped = Zzi4PostRootRuntime.parse(0, "RMG_ZZI4_POST_ROOT_SKIP:lsposed-not-active")
        assertFalse(skipped.applicable)
        assertTrue(skipped.ready)

        val failed = Zzi4PostRootRuntime.parse(70, "RMG_ZZI4_POST_ROOT_ERROR:zn-daemon-not-ready")
        assertTrue(failed.applicable)
        assertFalse(failed.ready)
        assertTrue(failed.detail.contains("zn-daemon-not-ready"))
    }

    @Test
    fun manualAndAutoRestartAreGatedBySameRuntimePreparation() {
        val manual = File("src/main/java/dev/busung/s25uroot/InstallViewModel.kt").readText()
        val auto = File("src/main/java/dev/busung/s25uroot/AutoRootExecutorService.kt").readText()
        val post = File("src/main/java/dev/busung/s25uroot/PostRootAutomation.kt").readText()

        assertTrue(manual.contains("prepareZzi4Modules = requireZzi4Runtime"))
        assertTrue(manual.contains("postRoot?.zzi4RuntimeReady != true"))
        assertTrue(auto.contains("prepareZzi4Modules = requireZzi4Runtime"))
        assertTrue(auto.contains("postRoot?.zzi4RuntimeReady != true"))
        assertTrue(post.contains("Zzi4PostRootRuntime.prepareCommand"))
        assertTrue(post.contains("Zygisk/LSPosed runtime ready before Zygote restart"))
    }

    @Test
    fun autoRootStagesVerifiedZzi4KernelSuBeforeExploitClaim() {
        val source = File("src/main/java/dev/busung/s25uroot/AutoRootRunner.kt").readText()
        val shizukuStage = source.indexOf("preStageZzi4KernelSuViaShizuku(payloads)")
        val shizukuClaim = source.indexOf("beforeExploit()", shizukuStage)
        val localStage = source.indexOf("preStageZzi4KernelSuViaLocalAdb(session, payloads)")
        val localClaim = source.indexOf("beforeExploit()", localStage)

        assertTrue(shizukuStage >= 0)
        assertTrue(shizukuClaim > shizukuStage)
        assertTrue(localStage >= 0)
        assertTrue(localClaim > localStage)
        assertTrue(source.contains("remoteArtifactMatches"))
        assertTrue(source.contains("session.remove(KSUD_STAGE_PATH)"))
    }
}
