package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupportManifestRootHelperTest {
    @Test
    fun rootHelperRoundTripsWhenPresent() {
        val helper = RemoteArtifact(
            url = "https://raw.githubusercontent.com/example/repo/main/helper",
            size = 42,
            sha256 = "2".repeat(64),
        )
        val parsed = SupportManifest.parse(
            SupportManifest(3, listOf(profile(rootHelper = helper))).toJsonBytes(),
        )
        assertEquals(helper, parsed.targets.single().rootHelper)
    }

    @Test
    fun oldV3ManifestWithoutRootHelperRemainsAccepted() {
        val parsed = SupportManifest.parse(
            SupportManifest(3, listOf(profile(rootHelper = null))).toJsonBytes(),
        )
        assertNull(parsed.targets.single().rootHelper)
    }

    private fun profile(rootHelper: RemoteArtifact?): TargetProfile = TargetProfile(
        profileId = "test",
        displayName = "test",
        models = setOf("SM-S938B"),
        kernelVersions = setOf("6.6.98"),
        exactMatch = null,
        exploit = RemoteArtifact(
            url = "https://raw.githubusercontent.com/example/repo/main/exploit",
            size = 1,
            sha256 = "0".repeat(64),
        ),
        kernelSu = KernelSuArtifact(
            artifact = RemoteArtifact(
                url = "https://raw.githubusercontent.com/example/repo/main/ksud",
                size = 1,
                sha256 = "1".repeat(64),
            ),
        ),
        rootHelper = rootHelper,
    )
}
