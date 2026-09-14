package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuBootLifecycleContractTest {
    @Test
    fun autoRootPriorityBootstrapRemainsAvailable() {
        val service = File("src/main/java/dev/busung/s25uroot/ShizukuBootService.kt").readText()

        assertTrue(service.contains("fun startForAutoRoot(context: Context)"))
        assertTrue(service.contains("EXTRA_AUTO_ROOT_PRIORITY"))
        assertTrue(service.contains("autoRootPriority || AppPreferences.startShizukuOnBoot(this)"))
    }

    @Test
    fun stopAndDestroyRemoveBootstrapNotification() {
        val service = File("src/main/java/dev/busung/s25uroot/ShizukuBootService.kt").readText()

        assertTrue(service.contains("private fun removeForegroundNotification()"))
        assertTrue(service.contains("stopForeground(STOP_FOREGROUND_REMOVE)"))
        assertTrue(service.contains("cancel(SHIZUKU_BOOT_NOTIFICATION_ID)"))

        val destroy = service.substringAfter("override fun onDestroy()")
            .substringBefore("private suspend fun bootstrap()")
        assertTrue(destroy.contains("removeForegroundNotification()"))

        val stop = service.substringAfter("fun stop(context: Context)")
            .substringBefore("private const val EXTRA_AUTO_ROOT_PRIORITY")
        assertTrue(stop.contains("stopService"))
        assertTrue(stop.contains("cancel(SHIZUKU_BOOT_NOTIFICATION_ID)"))
    }

    @Test
    fun hiddenPostRootShizukuAutostartIsDisabled() {
        val preferences = File("src/main/java/dev/busung/s25uroot/AppPreferences.kt").readText()

        assertTrue(preferences.contains("fun autoStartShizukuAfterRoot(context: Context): Boolean = false"))
        assertFalse(preferences.contains("AUTO_START_SHIZUKU_AFTER_ROOT"))
    }
}
