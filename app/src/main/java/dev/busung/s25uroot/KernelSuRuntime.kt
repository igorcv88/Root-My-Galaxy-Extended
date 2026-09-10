package dev.busung.s25uroot

import android.content.Context

/** Runtime checks and the post-KernelSU root bridge.
 *
 * The bootstrap helper socket exists only to cross the pre-KernelSU handoff.
 * Once KernelSU has loaded, Samsung/SELinux may reject new shell/app connects to
 * that socket even though KernelSU itself is healthy. Runtime detection therefore
 * accepts three independent proofs: module visibility, an already-authorized
 * KernelSU `su` through Shizuku, or the helper's direct `--ksu-info` control probe.
 * Privileged post-load commands use KernelSU itself rather than reconnecting to
 * the temporary bootstrap socket.
 */
internal object KernelSuRuntime {
    fun isControlActive(context: Context): Boolean {
        if (NativeProbe.isKernelSuActive()) return true

        // This is the strongest userspace proof in the normal ZZI4 boot path.
        // Shizuku already runs as shell and KernelSU late-load was invoked with
        // --allow-shell, so a successful elevation proves both a live KSU control
        // channel and a usable post-root command bridge. It also avoids relying on
        // /proc/modules visibility from an untrusted app process.
        if (ShizukuController.isRunning() && ShizukuController.isGranted()) {
            val elevated = runCatching {
                ShizukuController.shell("su -c id")
            }.getOrNull()
            if (elevated != null &&
                elevated.exitCode == 0 &&
                elevated.output.contains("uid=0")
            ) {
                return true
            }
        }

        // `--ksu-info` is handled directly by the native helper and never touches
        // temp_su.sock. Keep it as a transport-independent fallback for boots where
        // Shizuku is unavailable but the app process can reach the KSU control ABI.
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
