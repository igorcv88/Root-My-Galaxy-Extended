package dev.busung.s25uroot

import android.content.Context
import java.io.File


data class SoftRebootResult(
    val started: Boolean,
    val detail: String,
)

/**
 * Compatibility facade for the existing install/Auto Root call sites.
 *
 * The historical implementation pre-armed a bootstrap-daemon shell before
 * KernelSU late-load. That is no longer necessary: after KernelSU is verified,
 * PostRootAutomation obtains an already-working root transport and launches a
 * detached single-owner keeper. The keeper consumes the installed /data/adb/ksud
 * and delegates the userspace transition to KernelSU's native `soft-reboot`.
 *
 * Important invariant: this facade never stages/replaces ksud, never calls
 * late-load, and never restarts zygote directly.
 */
object KernelSuSoftReboot {
    suspend fun arm(
        context: Context,
        helper: File,
        useShizuku: Boolean,
    ) {
        // Intentionally no-op. Kept temporarily so existing install code can be
        // upgraded without placing any new work in the pre-late-load window.
    }

    suspend fun request(context: Context): SoftRebootResult {
        val result = PostRootAutomation.run(
            context = context,
            softReboot = true,
            startShizuku = AppPreferences.autoStartShizukuAfterRoot(context),
        )
        return SoftRebootResult(
            started = result.softRebootStarted,
            detail = result.detail,
        )
    }

    fun cancel() = Unit
}
