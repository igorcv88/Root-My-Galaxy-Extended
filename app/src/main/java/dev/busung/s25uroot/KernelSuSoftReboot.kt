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
 * The old implementation pre-armed a bootstrap-daemon shell and treated a
 * successful stdin write as proof that `ksud soft-reboot` started. That could
 * return a false positive after KernelSU changed the live security state.
 *
 * Soft reboot is now a post-root operation implemented by PostRootAutomation:
 * local Wireless ADB + KernelSU `su`, ksud lifecycle stages, then zygote restart.
 * This mirrors the device-tested HyperRamzey route and produces observable
 * command results instead of relying on a second bootstrap-daemon connection.
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
