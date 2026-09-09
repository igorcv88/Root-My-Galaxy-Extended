package dev.busung.s25uroot

internal data class ModuleKeeperLaunchResult(
    val accepted: Boolean,
    val alreadyDone: Boolean = false,
    val detail: String = "",
)

/**
 * Single post-root soft-reboot owner.
 *
 * KernelSU late-load remains the only owner of module loading/staging. This
 * keeper never replays late-load, never replaces /data/adb/ksud, and never
 * restarts zygote directly. Once KernelSU is verified, it only performs a
 * boot-scoped/idempotent handoff to KernelSU's own `ksud soft-reboot` command.
 * That native path owns stop/start plus the normal KernelSU userspace lifecycle.
 */
internal object PostRootModuleKeeper {
    const val DONE_MARKER = "/data/local/tmp/.rmg-soft-reboot-accepted"
    const val START_MARKER = "/data/local/tmp/.rmg-soft-reboot-requesting"
    const val OWNER_LOG = "/data/local/tmp/rmg-postroot-keeper.log"
    private const val KEEPER_PATH = "/data/local/tmp/rmg-postroot-keeper.sh"

    fun launch(
        adb: WirelessAdbSession,
        expectedBootId: String,
        onLog: (String) -> Unit = {},
    ): ModuleKeeperLaunchResult = launch(
        rootShell = { command ->
            adb.shell("su -c ${shellQuote(command)} 2>&1")
        },
        expectedBootId = expectedBootId,
        onLog = onLog,
    )

    /**
     * Launch through any verified root transport. An existing Shizuku Binder is
     * preferred by PostRootAutomation; the authenticated app root helper and
     * local ADB remain fallbacks. The transport only launches this detached
     * keeper and does not participate in KernelSU late-load/staging.
     */
    fun launch(
        rootShell: (String) -> LocalAdbClient.ShellResult,
        expectedBootId: String,
        onLog: (String) -> Unit = {},
    ): ModuleKeeperLaunchResult {
        if (expectedBootId.isBlank()) {
            return ModuleKeeperLaunchResult(false, detail = "kernel boot id unavailable")
        }

        val existing = rootShell("cat '$DONE_MARKER' 2>/dev/null").output.trim()
        if (markerMatchesBoot(existing, expectedBootId)) {
            onLog("[+] KernelSU soft reboot already accepted for this kernel boot")
            return ModuleKeeperLaunchResult(accepted = true, alreadyDone = true)
        }

        val script = buildKeeperScript(expectedBootId)
        val expectedQuoted = shellQuote(expectedBootId)
        val installAndLaunch = buildString {
            append("set -e\n")
            append("tmp='$KEEPER_PATH.tmp.$$'\n")
            append("cat > \"\$tmp\" <<'RMG_KEEPER_EOF'\n")
            append(script)
            if (!script.endsWith('\n')) append('\n')
            append("RMG_KEEPER_EOF\n")
            append("chmod 700 \"\$tmp\"\n")
            append("mv -f \"\$tmp\" '$KEEPER_PATH'\n")
            append("rm -f '$START_MARKER'\n")
            append(": > '$OWNER_LOG'\n")
            append("chmod 0666 '$OWNER_LOG'\n")
            append("setsid sh '$KEEPER_PATH' >>'$OWNER_LOG' 2>&1 < /dev/null &\n")
            // Do not call the handoff successful merely because the keeper
            // forked. KernelSU's soft-reboot CLI daemonizes; this marker appears
            // only after that CLI returned success, proving the native worker was
            // accepted. A concurrent same-boot caller observes the same marker.
            append("i=0; while [ \"\$i\" -lt 50 ]; do ")
            append("[ \"\$(cat '$START_MARKER' 2>/dev/null)\" = $expectedQuoted ] && { echo rmg-soft-reboot-accepted; exit 0; }; ")
            append("i=\$((i+1)); sleep 0.1; done\n")
            append("echo 'soft-reboot keeper did not publish accepted request marker' >&2\n")
            append("tail -n 8 '$OWNER_LOG' >&2 2>/dev/null || true\n")
            append("exit 78\n")
        }

        val result = rootShell(installAndLaunch)
        if (result.exitCode != 0 || !result.output.contains("rmg-soft-reboot-accepted")) {
            val detail = result.output.trim().ifBlank { "keeper launcher exit ${result.exitCode}" }
            return ModuleKeeperLaunchResult(false, detail = detail.takeLast(320))
        }

        onLog("[+] KernelSU native soft-reboot request accepted")
        onLog("[*] Keeper log: $OWNER_LOG")
        return ModuleKeeperLaunchResult(accepted = true)
    }

    internal fun markerMatchesBoot(marker: String, expectedBootId: String): Boolean =
        marker.lineSequence()
            .firstOrNull()
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.firstOrNull() == expectedBootId

    internal fun buildKeeperScript(expectedBootId: String): String = """
        #!/system/bin/sh
        # Root My Galaxy post-root soft-reboot keeper.
        # Single owner: never replay KernelSU late-load or replace its daemon.

        EXPECTED_BOOT=${shellQuote(expectedBootId)}
        DONE='$DONE_MARKER'
        ACCEPTED='$START_MARKER'
        LOCK='/data/local/tmp/.rmg-soft-reboot-owner'
        KSUD='/data/adb/ksud'

        log() {
            echo "[keeper] ${'$'}(date +%s 2>/dev/null) ${'$'}*"
        }

        current_boot() {
            cat /proc/sys/kernel/random/boot_id 2>/dev/null
        }

        marker_boot() {
            [ -f "${'$'}DONE" ] || return 1
            awk 'NR == 1 { print ${'$'}1; exit }' "${'$'}DONE" 2>/dev/null
        }

        publish_request_accepted() {
            printf '%s\n' "${'$'}EXPECTED_BOOT" > "${'$'}ACCEPTED" || return 1
            chown 2000:2000 "${'$'}ACCEPTED" 2>/dev/null
            chmod 0664 "${'$'}ACCEPTED" 2>/dev/null
        }

        publish_done() {
            UP="${'$'}(cut -d. -f1 /proc/uptime 2>/dev/null)"
            TMP="${'$'}DONE.tmp.${'$'}${'$'}"
            printf '%s %s\n' "${'$'}EXPECTED_BOOT" "${'$'}UP" > "${'$'}TMP" || return 1
            chown 2000:2000 "${'$'}TMP" 2>/dev/null
            chmod 0664 "${'$'}TMP" 2>/dev/null
            restorecon "${'$'}TMP" >/dev/null 2>&1 || true
            mv -f "${'$'}TMP" "${'$'}DONE" || return 1
            chown 2000:2000 "${'$'}DONE" 2>/dev/null
            chmod 0664 "${'$'}DONE" 2>/dev/null
        }

        log "keeper process started boot_id=${'$'}EXPECTED_BOOT pid=${'$'}${'$'}"

        BOOT="${'$'}(current_boot)"
        if [ -z "${'$'}BOOT" ] || [ "${'$'}BOOT" != "${'$'}EXPECTED_BOOT" ]; then
            log "boot id changed before soft-reboot handoff; refusing restart"
            exit 60
        fi

        if [ "${'$'}(marker_boot 2>/dev/null)" = "${'$'}EXPECTED_BOOT" ]; then
            log "soft-reboot accepted marker already belongs to this boot; nothing to do"
            publish_request_accepted 2>/dev/null || true
            exit 0
        fi

        if ! mkdir "${'$'}LOCK" 2>/dev/null; then
            LOCK_BOOT="${'$'}(cat "${'$'}LOCK/boot_id" 2>/dev/null)"
            if [ "${'$'}LOCK_BOOT" = "${'$'}EXPECTED_BOOT" ]; then
                log "another soft-reboot keeper already owns this kernel boot; waiting for its acceptance marker"
                # Do not publish success on behalf of an owner that may still
                # fail. The launcher polls the shared ACCEPTED marker and will
                # succeed only if the real owner gets ksud's successful return.
                exit 0
            fi
            rm -rf "${'$'}LOCK" 2>/dev/null
            mkdir "${'$'}LOCK" 2>/dev/null || exit 61
        fi
        echo "${'$'}EXPECTED_BOOT" > "${'$'}LOCK/boot_id" 2>/dev/null
        cleanup() { rm -rf "${'$'}LOCK" 2>/dev/null; }
        trap cleanup EXIT INT TERM

        if [ "${'$'}(id -u 2>/dev/null)" != "0" ]; then
            log "keeper is not root"
            exit 62
        fi

        i=0
        while [ "${'$'}i" -lt 90 ]; do
            [ "${'$'}(getprop sys.boot_completed 2>/dev/null)" = "1" ] && break
            i=${'$'}((i + 1))
            sleep 1
        done
        if [ "${'$'}(getprop sys.boot_completed 2>/dev/null)" != "1" ]; then
            log "sys.boot_completed never became ready"
            exit 63
        fi

        # Consume only the daemon installed by the already-verified late-load.
        # Do not fall back to a target-named /data/local/tmp ksud: on a multi-
        # firmware app that could select a stale binary embedding the wrong LKM.
        # Missing /data/adb/ksud is therefore a post-root failure, not permission
        # to stage, replace, or re-run late-load from here.
        if [ ! -x "${'$'}KSUD" ]; then
            log "installed KernelSU daemon missing or not executable at ${'$'}KSUD"
            exit 64
        fi
        log "KernelSU soft-reboot binary accepted via ${'$'}KSUD"

        if [ "${'$'}(current_boot)" != "${'$'}EXPECTED_BOOT" ]; then
            log "kernel reboot detected before KernelSU soft reboot; aborting"
            exit 71
        fi
        if [ "${'$'}(marker_boot 2>/dev/null)" = "${'$'}EXPECTED_BOOT" ]; then
            log "another owner accepted soft reboot while waiting; no second request"
            publish_request_accepted 2>/dev/null || true
            exit 0
        fi

        # KernelSU owns the userspace transition. Its native soft-reboot path
        # daemonizes in init's process group, switches to PID1's mount namespace,
        # performs stop/post-fs-data/start/services/boot-completed, and therefore
        # gives metamodules and ordinary modules their normal lifecycle ordering.
        # Do not gate this on mounts that the soft reboot itself is responsible
        # for creating, and do not restart zygote directly.
        log "requesting KernelSU native soft reboot"
        "${'$'}KSUD" soft-reboot
        RC=${'$'}?
        if [ "${'$'}RC" != "0" ]; then
            log "KernelSU native soft reboot request failed rc=${'$'}RC"
            exit 72
        fi

        # `ksud soft-reboot` daemonizes internally. A zero return here means its
        # detached native worker was created successfully. Publish the launcher
        # handshake only now, eliminating the old keeper-start false positive.
        publish_request_accepted || exit 79
        if publish_done; then
            log "KernelSU native soft reboot accepted marker=${'$'}DONE"
        else
            log "KernelSU native soft reboot accepted; done-marker write raced userspace stop"
        fi
        exit 0
    """.trimIndent() + "\n"

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
