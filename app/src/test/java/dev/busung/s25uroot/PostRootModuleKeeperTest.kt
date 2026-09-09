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
    fun keeperPublishesStartHandshakeBeforeReadinessWaits() {
        val script = PostRootModuleKeeper.buildKeeperScript(
            "11111111-2222-3333-4444-555555555555",
        )

        val startMarker = script.indexOf(PostRootModuleKeeper.START_MARKER)
        val bootCompletedWait = script.indexOf("sys.boot_completed")
        assertTrue(startMarker >= 0)
        assertTrue(bootCompletedWait > startMarker)
        assertTrue(script.contains("keeper process started"))
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
