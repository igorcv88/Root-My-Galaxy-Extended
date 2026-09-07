package dev.busung.s25uroot

import android.content.Context

/**
 * Verifies the userspace completion boundary of the CZG3 late-load path.
 * KernelSU control becoming visible is not enough: the patched ksud publishes
 * a boot-scoped marker only after its blocking mount stages complete in PID 1's
 * mount namespace. When a metamodule is installed, its backing mount must also
 * be visible in /proc/1/mountinfo.
 */
internal object KernelSuGlobalReadiness {
    fun probe(context: Context, bootToken: String): LocalAdbClient.ShellResult {
        val boot = shellQuote(bootToken)
        val command =
            "boot=$boot; " +
                "marker='$READY_MARKER'; " +
                "init=\$(readlink /proc/1/ns/mnt 2>/dev/null) || exit 70; " +
                "self=\$(readlink /proc/self/ns/mnt 2>/dev/null || true); " +
                "echo \"RMG late-load namespaces: helper=\$self init=\$init\"; " +
                "[ -r \"\$marker\" ] || { echo 'late-load readiness marker missing' >&2; exit 71; }; " +
                "grep -Fqx \"boot_id=\$boot\" \"\$marker\" || { " +
                "echo 'late-load readiness marker belongs to another kernel boot' >&2; exit 72; }; " +
                "grep -Fqx \"mount_ns=\$init\" \"\$marker\" || { " +
                "echo 'late-load marker was not published from PID1 mount namespace' >&2; exit 73; }; " +
                "if [ -f /data/adb/metamodule/metamount.sh ] || [ -f /data/adb/metamodule/module.prop ]; then " +
                "awk '\$5 == \"/data/adb/metamodule/mnt\" { found=1; exit } END { exit(found ? 0 : 1) }' " +
                "/proc/1/mountinfo || { echo 'metamodule mount missing from PID1 mountinfo' >&2; exit 74; }; " +
                "echo 'metamodule mount verified in PID1 mountinfo'; " +
                "fi; " +
                "cat \"\$marker\""
        return RootHelperShell.shell(context, command)
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private const val READY_MARKER = "/data/local/tmp/.rmg-ksu-late-load-ready"
}
