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
    fun shellRequiredAuthorizedShizukuWinsOverPairedLocalAdb() {
        assertEquals(
            ManualRunTransport.Shizuku,
            chooseManualRunTransport(
                shellRequired = true,
                shizukuRequested = false,
                shizukuUsable = true,
                localAdbPaired = true,
            ),
        )
    }

    @Test
    fun shellRequiredUsesShizukuWhenLocalAdbUnavailable() {
        assertEquals(
            ManualRunTransport.Shizuku,
            chooseManualRunTransport(
                shellRequired = true,
                shizukuRequested = true,
                shizukuUsable = true,
                localAdbPaired = false,
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
    fun legacyRequestedShizukuKeepsShizukuTransport() {
        assertEquals(
            ManualRunTransport.Shizuku,
            chooseManualRunTransport(
                shellRequired = false,
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
