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

        assertFalse(script.contains("ksud post-fs-data"))
        assertFalse(script.contains("ksud services"))
        assertFalse(script.contains("ksud boot-completed"))
        assertTrue(script.contains("kill -9"))
        assertTrue(script.contains(PostRootModuleKeeper.DONE_MARKER))
    }

    @Test
    fun keeperGuardsMetaOverlayfsxViperSafeBeforeZygote() {
        val script = PostRootModuleKeeper.buildKeeperScript(
            "11111111-2222-3333-4444-555555555555",
        )

        assertTrue(script.contains("/data/adb/modules/meta-overlayfsx"))
        assertTrue(script.contains("/data/adb/metamodule/mnt"))
        assertTrue(script.contains("OverlayFSx kernel inspector did not report success"))
        assertTrue(script.contains("Granular ViPER mounting completed without partition-root overlays"))
        assertTrue(script.contains("unsafe broad ViPER root overlay detected"))
    }
}
