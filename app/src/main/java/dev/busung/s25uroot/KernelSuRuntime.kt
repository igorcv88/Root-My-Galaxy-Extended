package dev.busung.s25uroot

import android.content.Context

/** Runtime checks and the post-KernelSU root bridge.
 *
 * The bootstrap helper socket exists only to cross the pre-KernelSU handoff.
 * Once KernelSU has loaded, Samsung/SELinux may reject new shell/app connects to
 * that socket even though KernelSU itself is healthy. Control detection therefore
 * uses the helper's direct --ksu-info path, and privileged post-load commands use
 * KernelSU su through an already-authorized Shizuku shell when available.
 */
internal object KernelSuRuntime {
    fun isControlActive(context: Context): Boolean {
        if (NativeProbe.isKernelSuActive()) return true
        val direct = runCatching {
            RootHelperShell.execute(context, "--ksu-info")
        }.getOrNull()
        return direct?.exitCode == 0
    }

    fun shizukuRootShell(command: String): LocalAdbClient.ShellResult? {
        if (!ShizukuController.isRunning() || !ShizukuController.isGranted()) return null

        val direct = runCatching { ShizukuController.shell("id") }.getOrNull()
        if (direct != null && direct.exitCode == 0 && direct.output.contains("uid=0")) {
            return runCatching { ShizukuController.shell(command) }.getOrNull()
        }

        val elevated = runCatching { ShizukuController.shell("su -c id") }.getOrNull()
        if (elevated == null || elevated.exitCode != 0 || !elevated.output.contains("uid=0")) {
            return null
        }
        return runCatching {
            ShizukuController.shell("su -c ${shellQuote(command)}")
        }.getOrNull()
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
