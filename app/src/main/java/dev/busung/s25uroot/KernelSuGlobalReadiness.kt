package dev.busung.s25uroot

import android.content.Context

/**
 * Verifies the boot-scoped userspace completion boundary of the RMG KernelSU
 * late-load path. KernelSU control becoming visible is not enough: the patched
 * ksud publishes a marker only after its blocking late-load stages complete in
 * PID 1's mount namespace.
 *
 * Individual metamodule mounts are deliberately not part of this root/readiness
 * contract. They are module-lifecycle outcomes and may be absent or pending even
 * though KernelSU itself is fully loaded and ready. Actions that specifically
 * repair/reload modules can validate their own mount result separately.
 */
internal object KernelSuGlobalReadiness {
    fun probe(context: Context, bootToken: String): LocalAdbClient.ShellResult =
        RootHelperShell.shell(context, command(bootToken))

    internal fun command(bootToken: String): String {
        val boot = shellQuote(bootToken)
        return "boot=$boot; " +
                "marker='$READY_MARKER'; " +
                "init=\$(readlink /proc/1/ns/mnt 2>/dev/null) || exit 70; " +
                "self=\$(readlink /proc/self/ns/mnt 2>/dev/null || true); " +
                "echo \"RMG late-load namespaces: helper=\$self init=\$init\"; " +
                "[ -r \"\$marker\" ] || { echo 'late-load readiness marker missing' >&2; exit 71; }; " +
                "grep -Fqx \"boot_id=\$boot\" \"\$marker\" || { " +
                "echo 'late-load readiness marker belongs to another kernel boot' >&2; exit 72; }; " +
                "grep -Fqx \"mount_ns=\$init\" \"\$marker\" || { " +
                "echo 'late-load marker was not published from PID1 mount namespace' >&2; exit 73; }; " +
                "echo 'KernelSU late-load marker verified in PID1 mount namespace'; " +
                "cat \"\$marker\""
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private const val READY_MARKER = "/data/local/tmp/.rmg-ksu-late-load-ready"
}
