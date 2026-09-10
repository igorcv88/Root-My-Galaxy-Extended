package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualShellTransportPolicyTest {
    @Test
    fun shellRequiredStandaloneUsesPairedLocalAdb() {
        assertEquals(
            ManualRunTransport.LocalAdb,
            chooseManualRunTransport(
                shellRequired = true,
                shizukuRequested = false,
                shizukuUsable = false,
                localAdbPaired = true,
            ),
        )
    }

    @Test
    fun shellRequiredNeverFallsBackToAppDomain() {
        assertNull(
            chooseManualRunTransport(
                shellRequired = true,
                shizukuRequested = false,
                shizukuUsable = false,
                localAdbPaired = false,
            ),
        )
    }

    @Test
    fun usableShizukuStillWinsWhenRequested() {
        assertEquals(
            ManualRunTransport.Shizuku,
            chooseManualRunTransport(
                shellRequired = true,
                shizukuRequested = true,
                shizukuUsable = true,
                localAdbPaired = true,
            ),
        )
    }

    @Test
    fun legacyStandaloneKeepsAppTransport() {
        assertEquals(
            ManualRunTransport.App,
            chooseManualRunTransport(
                shellRequired = false,
                shizukuRequested = false,
                shizukuUsable = false,
                localAdbPaired = true,
            ),
        )
    }
}
