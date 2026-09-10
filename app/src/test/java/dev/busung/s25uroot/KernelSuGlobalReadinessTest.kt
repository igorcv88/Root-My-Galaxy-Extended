package dev.busung.s25uroot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelSuGlobalReadinessTest {
    @Test
    fun readinessIsBootAndPid1ScopedWithoutMetamoduleMountGate() {
        val command = KernelSuGlobalReadiness.command("test-boot-id")

        assertTrue(command.contains("/proc/1/ns/mnt"))
        assertTrue(command.contains("boot_id="))
        assertTrue(command.contains("mount_ns="))
        assertTrue(command.contains("late-load readiness marker"))
        assertFalse(command.contains("/data/adb/metamodule"))
        assertFalse(command.contains("metamodule mount missing"))
        assertFalse(command.contains("exit 74"))
    }
}
