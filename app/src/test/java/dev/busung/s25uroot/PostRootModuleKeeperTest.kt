package dev.busung.s25uroot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostRootModuleKeeperTest {
    @Test
    fun markerMustMatchExactKernelBoot() {
        val boot = "11111111-2222-3333-4444-555555555555"
        assertTrue(PostRootModuleKeeper.markerMatchesBoot("$boot 245\n", boot))
        assertFalse(
            PostRootModuleKeeper.markerMatchesBoot(
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee 245\n",
                boot,
            ),
        )
        assertFalse(PostRootModuleKeeper.markerMatchesBoot("", boot))
    }

    @Test
    fun keeperDelegatesUserspaceRestartToKernelSu() {
        val script = PostRootModuleKeeper.buildKeeperScript(
            "11111111-2222-3333-4444-555555555555",
        )

        val executable = script.lineSequence()
            .map(String::trim)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .joinToString("\n")

        assertTrue(executable.contains("\"\$KSUD\" soft-reboot"))
        assertFalse(executable.contains("ctl.restart zygote"))
        assertFalse(executable.contains("kill -9"))
        assertFalse(executable.contains("pidof zygote"))
    }

    @Test
    fun keeperNeverReplaysOrRestagesKernelSuLateLoad() {
        val script = PostRootModuleKeeper.buildKeeperScript(
            "11111111-2222-3333-4444-555555555555",
        )

        val executable = script.lineSequence()
            .map(String::trim)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .joinToString("\n")

        val replay = Regex("""(^|[;&|]\s*)[^\n]*ksud\s+(late-load|post-fs-data|services|boot-completed)(\s|$)""")
        assertFalse(replay.containsMatchIn(executable))
        assertFalse(executable.contains(".ksud-stage"))
        assertFalse(executable.contains("mv -f /data/adb/ksud"))
        assertFalse(executable.contains("stage_daemon"))
    }

    @Test
    fun keeperUsesOnlyInstalledKernelSuDaemon() {
        val script = PostRootModuleKeeper.buildKeeperScript(
            "11111111-2222-3333-4444-555555555555",
        )

        assertTrue(script.contains("KSUD='/data/adb/ksud'"))
        assertFalse(script.contains("/data/local/tmp/ksud-s25u-kdp"))
        assertFalse(script.contains("/data/adb/ksu/bin/ksud"))
        assertTrue(script.contains("installed KernelSU daemon missing or not executable"))
    }

    @Test
    fun acceptanceHandshakeIsPublishedOnlyAfterKsudReturnsSuccess() {
        val script = PostRootModuleKeeper.buildKeeperScript(
            "11111111-2222-3333-4444-555555555555",
        )

        val bootCompletedWait = script.indexOf("sys.boot_completed never became ready")
        val ksudSelection = script.indexOf("KSUD='/data/adb/ksud'")
        val softRebootCommand = script.indexOf("\"\$KSUD\" soft-reboot")
        val rcGuard = script.indexOf("KernelSU native soft reboot request failed")
        val acceptedMarkerWrite = script.indexOf("publish_request_accepted || exit 79")

        assertTrue(bootCompletedWait >= 0)
        assertTrue(ksudSelection >= 0)
        assertTrue(softRebootCommand > ksudSelection)
        assertTrue(rcGuard > softRebootCommand)
        assertTrue(acceptedMarkerWrite > rcGuard)
    }

    @Test
    fun sameBootOwnerDoesNotPublishSuccessOnItsBehalf() {
        val script = PostRootModuleKeeper.buildKeeperScript(
            "11111111-2222-3333-4444-555555555555",
        )

        val ownerBranch = script.indexOf(
            "another soft-reboot keeper already owns this kernel boot; waiting for its acceptance marker",
        )
        assertTrue(ownerBranch >= 0)
        val ownerExit = script.indexOf("exit 0", ownerBranch)
        assertTrue(ownerExit > ownerBranch)
        val ownerBlock = script.substring(ownerBranch, ownerExit)
        assertFalse(ownerBlock.contains("publish_request_accepted"))
    }

    @Test
    fun moduleMountReadinessDoesNotBlockNativeSoftReboot() {
        val script = PostRootModuleKeeper.buildKeeperScript(
            "11111111-2222-3333-4444-555555555555",
        )

        assertFalse(script.contains("OVERLAY_META="))
        assertFalse(script.contains("Meta-Overlayfsx ext4 image never became mounted"))
        assertFalse(script.contains("Granular ViPER mounting completed without partition-root overlays"))
        assertTrue(script.contains("Do not gate this on mounts"))
    }
}
