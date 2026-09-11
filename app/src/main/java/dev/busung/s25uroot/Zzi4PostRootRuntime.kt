package dev.busung.s25uroot

internal data class Zzi4PostRootRuntimeResult(
    val applicable: Boolean,
    val ready: Boolean,
    val detail: String,
)

/**
 * One UI 9 ZZI4 post-late-load userspace readiness gate.
 *
 * KernelSU control/global readiness proves the root framework is available, but
 * does not prove that Zygisk Next and LSPosed have completed their own service
 * lifecycle before a new Zygote/system_server is created. This command is run
 * only after KernelSuGlobalReadiness succeeds and before the optional automatic
 * Zygote restart.
 *
 * The standalone defex_lsposed_compat LKM remains diagnostic-only. The
 * production path relies on the target-specific ksud/KernelSU pair whose SHA is
 * verified before the exploit and which embeds the permanent narrow DEFEX fix.
 */
internal object Zzi4PostRootRuntime {
    const val PROFILE_ID = "pa3q-S938BXXUCZZI4"
    private const val READY_MARKER = "RMG_ZZI4_POST_ROOT_READY"
    private const val SKIP_MARKER = "RMG_ZZI4_POST_ROOT_SKIP"
    private const val ERROR_MARKER = "RMG_ZZI4_POST_ROOT_ERROR"

    fun prepareCommand(expectedBootId: String): String {
        val boot = shellQuote(expectedBootId)
        return """
            EXPECTED_BOOT=$boot
            ZN='/data/adb/modules/zygisksu'
            ZNS='/data/adb/zygisksu'
            LS='/data/adb/modules/zygisk_lsposed'
            ZLOG='/data/local/tmp/rmg-zzi4-zygisk-start.log'
            LLOG='/data/local/tmp/rmg-zzi4-lsposed-start.log'

            current_boot() { /system/bin/cat /proc/sys/kernel/random/boot_id 2>/dev/null; }
            fail() {
                printf '%s\n' '$ERROR_MARKER:'"${'$'}1"
                exit 70
            }
            zn_ready() {
                /system/bin/ps -A -o NAME,ARGS 2>/dev/null | /system/bin/toybox awk \
                    '${'$'}1 == "zn-daemon" { found=1 } END { exit(found ? 0 : 1) }'
            }
            module_ready() {
                /system/bin/grep -Eq '^zygisk_lsposed[[:space:]]+64([[:space:]]|${'$'})' \
                    "${'$'}ZNS/modules_info" 2>/dev/null
            }
            lspd_ready() {
                /system/bin/ps -A -o NAME,ARGS 2>/dev/null | /system/bin/toybox awk \
                    '${'$'}1 ~ /^lspd($|:)/ || ${'$'}1 == "LSPosed" || index(${'$'}0, "/data/adb/modules/zygisk_lsposed/daemon") { found=1 } END { exit(found ? 0 : 1) }'
            }

            [ "${'$'}(/system/bin/id -u 2>/dev/null)" = '0' ] || fail 'not-root'
            [ "${'$'}(current_boot)" = "${'$'}EXPECTED_BOOT" ] || fail 'boot-changed'

            if [ ! -d "${'$'}LS" ] || [ -e "${'$'}LS/disable" ] || [ -e "${'$'}LS/remove" ]; then
                printf '%s\n' '$SKIP_MARKER:lsposed-not-active'
                exit 0
            fi
            [ -d "${'$'}ZN" ] || fail 'zygisk-next-not-installed'
            [ ! -e "${'$'}ZN/disable" ] && [ ! -e "${'$'}ZN/remove" ] || fail 'zygisk-next-disabled'
            [ -x "${'$'}ZN/bin/zygiskd" ] || fail 'zygiskd-missing'
            [ -x "${'$'}LS/daemon" ] || fail 'lsposed-daemon-missing'

            if [ "${'$'}(/system/bin/cat "${'$'}ZNS/memory_type" 2>/dev/null)" != '0' ]; then
                "${'$'}ZN/bin/zygiskd" memory-type default >>"${'$'}ZLOG" 2>&1 || true
            fi
            [ "${'$'}(/system/bin/cat "${'$'}ZNS/memory_type" 2>/dev/null)" = '0' ] || fail 'memory-type-not-default'

            if [ "${'$'}(/system/bin/cat "${'$'}ZNS/linker" 2>/dev/null)" != '0' ]; then
                "${'$'}ZN/bin/zygiskd" linker system >>"${'$'}ZLOG" 2>&1 || true
            fi
            [ "${'$'}(/system/bin/cat "${'$'}ZNS/linker" 2>/dev/null)" = '0' ] || fail 'linker-not-system'

            # Give KernelSU's normal module lifecycle a short chance to finish so
            # we do not race it into a duplicate zn-daemon.
            if ! zn_ready; then
                /system/bin/sleep 1
            fi
            if ! zn_ready; then
                (cd "${'$'}ZN" && ./bin/zygiskd daemon >>"${'$'}ZLOG" 2>&1) || true
            fi
            i=0
            while [ "${'$'}i" -lt 3 ] && ! zn_ready; do
                i="${'$'}((i + 1))"
                /system/bin/sleep 1
            done
            zn_ready || fail 'zn-daemon-not-ready'

            # service-stage may return 1 when the daemon/stage is already active.
            # Readiness is modules_info, never that isolated exit code.
            if ! module_ready; then
                (cd "${'$'}ZN" && ./bin/zygiskd service-stage >>"${'$'}ZLOG" 2>&1)
                SERVICE_RC="${'$'}?"
                printf 'service-stage-rc=%s\n' "${'$'}SERVICE_RC" >>"${'$'}ZLOG"
            fi
            i=0
            while [ "${'$'}i" -lt 3 ] && ! module_ready; do
                i="${'$'}((i + 1))"
                /system/bin/sleep 1
            done
            module_ready || fail 'zygisk-lsposed-not-registered'

            if ! lspd_ready; then
                /system/bin/toybox setsid "${'$'}LS/daemon" --force \
                    >"${'$'}LLOG" 2>&1 </dev/null &
            fi
            i=0
            while [ "${'$'}i" -lt 3 ] && ! lspd_ready; do
                i="${'$'}((i + 1))"
                /system/bin/sleep 1
            done
            lspd_ready || fail 'lsposed-daemon-not-ready'

            EXT='absent'
            if [ -r /sys/module/defex_lsposed_compat/parameters/bypass ]; then
                EXT="${'$'}(/system/bin/cat /sys/module/defex_lsposed_compat/parameters/bypass 2>/dev/null)"
            fi
            PERM='unobserved'
            if /system/bin/dmesg 2>/dev/null | /system/bin/grep -Fq 'LSPosed app_process64 exception enabled'; then
                PERM='kernel-log-confirmed'
            fi

            printf '%s\n' '$READY_MARKER memory_type=0 linker=0 zn_daemon=1 module=zygisk_lsposed lspd=1 permanent_defex='"${'$'}PERM"' external_helper='"${'$'}EXT"
            exit 0
        """.trimIndent()
    }

    fun parse(exitCode: Int, output: String): Zzi4PostRootRuntimeResult {
        val clean = output.trim()
        val skip = clean.lineSequence().lastOrNull { it.startsWith("$SKIP_MARKER:") }
        if (exitCode == 0 && skip != null) {
            return Zzi4PostRootRuntimeResult(
                applicable = false,
                ready = true,
                detail = skip.removePrefix("$SKIP_MARKER:"),
            )
        }
        val ready = clean.lineSequence().lastOrNull { it.startsWith(READY_MARKER) }
        if (exitCode == 0 && ready != null) {
            return Zzi4PostRootRuntimeResult(
                applicable = true,
                ready = true,
                detail = ready,
            )
        }
        val error = clean.lineSequence().lastOrNull { it.startsWith("$ERROR_MARKER:") }
            ?.removePrefix("$ERROR_MARKER:")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: clean.takeLast(240).ifBlank { "post-root readiness command exited $exitCode without a readiness marker" }
        return Zzi4PostRootRuntimeResult(
            applicable = true,
            ready = false,
            detail = error,
        )
    }

    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"
}
