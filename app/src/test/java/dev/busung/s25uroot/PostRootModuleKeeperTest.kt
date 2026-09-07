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
    fun keeperNeverReplaysKernelSuLifecycle() {
        val script = PostRootModuleKeeper.buildKeeperScript(
            "11111111-2222-3333-4444-555555555555",
        )

        // Comments intentionally document why the lifecycle is not replayed, so
        // test executable shell lines rather than rejecting those words anywhere
        // in the generated script text.
        val executable = script.lineSequence()
            .map(String::trim)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .joinToString("\n")

        val replay = Regex("""(^|[;&|]\s*)ksud\s+(post-fs-data|services|boot-completed)(\s|$)""")
        assertFalse(replay.containsMatchIn(executable))
        assertFalse(executable.contains("kill -9"))
        assertTrue(executable.contains("/system/bin/setprop ctl.restart zygote"))
        assertTrue(script.contains(PostRootModuleKeeper.DONE_MARKER))
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
    fun keeperGuardsMetaOverlayfsxViperSafeBeforeZygote() {
        val script = PostRootModuleKeeper.buildKeeperScript(
            "11111111-2222-3333-4444-555555555555",
        )

        // The generated shell factors /data/adb/metamodule into OVERLAY_HOME;
        // verify the actual variable-based mount probe instead of requiring an
        // expanded literal path that never appears in the source string.
        assertTrue(script.contains("OVERLAY_META='/data/adb/modules/meta-overlayfsx'"))
        assertTrue(script.contains("OVERLAY_HOME='/data/adb/metamodule'"))
        assertTrue(script.contains("${'$'}OVERLAY_HOME/mnt"))
        assertTrue(script.contains("OverlayFSx kernel inspector did not report success"))
        assertTrue(script.contains("Granular ViPER mounting completed without partition-root overlays"))
        assertTrue(script.contains("unsafe broad ViPER root overlay detected"))
    }
}
