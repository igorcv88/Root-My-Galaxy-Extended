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
        assertTrue(command.contains("/proc/\$SS/maps"))
        assertTrue(command.contains("restart_needed="))
        assertFalse(command.contains("pidof zygiskd"))
        assertFalse(command.contains("pidof zygiskd64"))
        assertFalse(command.contains("cat /data/adb/zygisksu/znctx"))
        assertFalse(command.contains("insmod"))
        assertFalse(command.contains("toybox awk"))
    }

    @Test
    fun parserSeparatesReadySkippedAndFailure() {
        val ready = Zzi4PostRootRuntime.parse(
            0,
            "RMG_ZZI4_POST_ROOT_READY memory_type=0 linker=0 zn_daemon=1 module=zygisk_lsposed lspd=1 restart_needed=1",
        )
        assertTrue(ready.applicable)
        assertTrue(ready.ready)
        assertTrue(ready.restartNeeded)

        val alreadyInjected = Zzi4PostRootRuntime.parse(
            0,
            "RMG_ZZI4_POST_ROOT_READY memory_type=0 linker=0 zn_daemon=1 module=zygisk_lsposed lspd=1 restart_needed=0",
        )
        assertTrue(alreadyInjected.applicable)
        assertTrue(alreadyInjected.ready)
        assertFalse(alreadyInjected.restartNeeded)

        val skipped = Zzi4PostRootRuntime.parse(0, "RMG_ZZI4_POST_ROOT_SKIP:lsposed-not-active")
        assertFalse(skipped.applicable)
        assertTrue(skipped.ready)
        assertTrue(skipped.restartNeeded)

        val failed = Zzi4PostRootRuntime.parse(70, "RMG_ZZI4_POST_ROOT_ERROR:zn-daemon-not-ready")
        assertTrue(failed.applicable)
        assertFalse(failed.ready)
        assertTrue(failed.restartNeeded)
        assertTrue(failed.detail.contains("zn-daemon-not-ready"))
    }

    @Test
    fun manualSoftRebootAndAutoRootNotificationAreSeparated() {
        val manual = File("src/main/java/dev/busung/s25uroot/InstallViewModel.kt").readText()
        val auto = File("src/main/java/dev/busung/s25uroot/AutoRootExecutorService.kt").readText()
        val gate = File("src/main/java/dev/busung/s25uroot/AutoRootService.kt").readText()
        val receiver = File("src/main/java/dev/busung/s25uroot/AutoRootBootReceiver.kt").readText()
        val settings = File("src/main/java/dev/busung/s25uroot/MainActivity.kt").readText()
        val keeper = File("src/main/java/dev/busung/s25uroot/PostRootModuleKeeper.kt").readText()

        assertTrue(manual.contains("softReboot = softRebootAfterRoot"))
        assertFalse(manual.contains("RootRecoveryActions.restartZygote("))
        assertTrue(auto.contains("offerSoftReboot ="))
        assertTrue(auto.contains("Zzi4PostRootRuntime.PROFILE_ID"))
        assertFalse(auto.contains("RootRecoveryActions.restartZygote("))
        assertFalse(auto.contains("prepareZzi4Modules = requireZzi4Runtime"))
        assertTrue(auto.contains("EXTRA_OFFER_SOFT_REBOOT"))
        assertTrue(gate.contains("ACTION_APPLY_MODULES_SOFT_REBOOT"))
        assertTrue(receiver.contains("RootRecoveryActions.kernelSuSoftReboot"))
        assertTrue(receiver.contains("ACTION_APPLY_MODULES_SOFT_REBOOT"))
        assertFalse(keeper.contains("awk 'NR == 1"))
        val advancedStart = settings.indexOf("item { SectionLabel(stringResource(R.string.advanced)) }")
        val aboutStart = settings.indexOf("item { SectionLabel(stringResource(R.string.about)) }", advancedStart)
        assertTrue(advancedStart >= 0)
        assertTrue(aboutStart > advancedStart)
        val advancedSection = settings.substring(advancedStart, aboutStart)
        assertTrue(advancedSection.contains("AdvancedRecoverySettings("))
        assertFalse(advancedSection.contains("if (advancedMode)"))
    }


    @Test
    fun zzi4ExploitHotPathPerformsNoKernelSuPreStageIo() {
        val manual = File("src/main/java/dev/busung/s25uroot/InstallViewModel.kt").readText()
        val auto = File("src/main/java/dev/busung/s25uroot/AutoRootRunner.kt").readText()

        val manualRunStart = manual.indexOf("private suspend fun runExploitAndKernelSu")
        val manualRunEnd = manual.indexOf("private suspend fun executeExploit", manualRunStart)
        assertTrue(manualRunStart >= 0)
        assertTrue(manualRunEnd > manualRunStart)
        val manualHotPath = manual.substring(manualRunStart, manualRunEnd)
        assertFalse(manualHotPath.contains("preStageKernelSu"))
        assertFalse(manualHotPath.contains("KSUD"))
        assertFalse(manual.contains("private fun preStageKernelSuForAutoLateLoad"))
        assertTrue(manualHotPath.indexOf("executeExploit(") < manualHotPath.indexOf("installKernelSu(payloads)"))

        val autoRunStart = auto.indexOf("suspend fun run(")
        val autoRunEnd = auto.indexOf("private suspend fun verifyKernelSu", autoRunStart)
        assertTrue(autoRunStart >= 0)
        assertTrue(autoRunEnd > autoRunStart)
        val autoRun = auto.substring(autoRunStart, autoRunEnd)
        assertTrue(autoRun.indexOf("executeExploit(") < autoRun.indexOf("withKernelSuClient"))

        val autoExploitStart = auto.indexOf("private suspend fun executeExploit(")
        val autoExploitEnd = auto.indexOf("private suspend fun executeExploitViaLocalAdb", autoExploitStart)
        assertTrue(autoExploitStart >= 0)
        assertTrue(autoExploitEnd > autoExploitStart)
        val autoShizukuHotPath = auto.substring(autoExploitStart, autoExploitEnd)
        assertFalse(autoShizukuHotPath.contains("preStageZzi4KernelSu"))
        assertFalse(autoShizukuHotPath.contains("KSUD_PATH"))

        val autoLocalStart = autoExploitEnd
        val autoLocalEnd = auto.indexOf("private fun shizukuStage", autoLocalStart)
        assertTrue(autoLocalEnd > autoLocalStart)
        val autoLocalHotPath = auto.substring(autoLocalStart, autoLocalEnd)
        assertFalse(autoLocalHotPath.contains("preStageZzi4KernelSu"))
        assertFalse(autoLocalHotPath.contains("KSUD_PATH"))
        assertFalse(auto.contains("private fun preStageZzi4KernelSu"))
        assertFalse(auto.contains("private fun zzi4KernelSuVerifyCommand"))

        // The safe fallback remains post-root in both paths.
        assertTrue(manual.contains("val stage = runHelper(\"-c\", kernelSuStageCommand(payloads))"))
        assertTrue(manual.contains("runHelper(\"--late-load\")"))
        assertTrue(auto.contains("stageKernelSuRequired(payloads, ksuExec)"))
        assertTrue(auto.contains("ksuExec(arrayOf(\"--late-load\"))"))
    }

}
