package dev.busung.s25uroot

internal data class ModuleKeeperLaunchResult(
    val accepted: Boolean,
    val alreadyDone: Boolean = false,
    val detail: String = "",
)

/**
 * Single post-root module-refresh owner.
 *
 * The app never replays KernelSU post-fs-data/services/boot-completed. v0266
 * late-load already owns module activation; this keeper only waits for that
 * state to become usable, performs one guarded zygote respawn, and publishes a
 * boot-id keyed completion marker.
 */
internal object PostRootModuleKeeper {
    const val DONE_MARKER = "/data/local/tmp/.cve43499-modules-done"
    const val START_MARKER = "/data/local/tmp/.cve43499-modules-keeper-started"
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
     * Launch through any already-available root command transport. This lets an
     * already-running Shizuku Binder be the preferred post-root bridge while
     * retaining local ADB as a compatibility fallback.
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
            onLog("[+] Module/zygote refresh already completed for this kernel boot")
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
            // A successful fork is not enough: require the detached keeper
            // itself to prove that it reached its first executable action.
            append("i=0; while [ \"\$i\" -lt 30 ]; do ")
            append("[ \"\$(cat '$START_MARKER' 2>/dev/null)\" = $expectedQuoted ] && { echo rmg-keeper-started; exit 0; }; ")
            append("i=\$((i+1)); sleep 0.1; done\n")
            append("echo 'keeper did not publish start marker' >&2; exit 78\n")
        }

        val result = rootShell(installAndLaunch)
        if (result.exitCode != 0 || !result.output.contains("rmg-keeper-started")) {
            val detail = result.output.trim().ifBlank { "keeper launcher exit ${result.exitCode}" }
            return ModuleKeeperLaunchResult(false, detail = detail.takeLast(240))
        }

        onLog("[+] Single-owner module keeper confirmed running; app will not replay KernelSU lifecycle")
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
        # Root My Galaxy post-root keeper.
        # Single owner: no KernelSU lifecycle replay here.

        EXPECTED_BOOT=${shellQuote(expectedBootId)}
        DONE='$DONE_MARKER'
        STARTED='$START_MARKER'
        LOCK='/data/local/tmp/.cve43499-modules-owner'
        OVERLAY_META='/data/adb/modules/meta-overlayfsx'
        OVERLAY_HOME='/data/adb/metamodule'
        OVERLAY_DATA='/data/adb/overlayfsx-data'
        VIPER_META='/data/adb/modules/ViPER4Android-RE-AIDL'

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

        zygote_pids() {
            echo "${'$'}(pidof zygote64 2>/dev/null) ${'$'}(pidof zygote 2>/dev/null)" |
                sed 's/^ *//; s/  */ /g; s/ *${'$'}//'
        }

        # First executable action visible to the launcher. If this marker never
        # appears, the app must not claim that a detached keeper is running.
        printf '%s\n' "${'$'}EXPECTED_BOOT" > "${'$'}STARTED" || exit 59
        chown 2000:2000 "${'$'}STARTED" 2>/dev/null
        chmod 0664 "${'$'}STARTED" 2>/dev/null
        log "keeper process started boot_id=${'$'}EXPECTED_BOOT pid=${'$'}${'$'}"

        BOOT="${'$'}(current_boot)"
        if [ -z "${'$'}BOOT" ] || [ "${'$'}BOOT" != "${'$'}EXPECTED_BOOT" ]; then
            log "boot id changed before keeper start; refusing userspace restart"
            exit 60
        fi

        if [ "${'$'}(marker_boot 2>/dev/null)" = "${'$'}EXPECTED_BOOT" ]; then
            log "done marker already belongs to this boot; nothing to do"
            exit 0
        fi

        if ! mkdir "${'$'}LOCK" 2>/dev/null; then
            LOCK_BOOT="${'$'}(cat "${'$'}LOCK/boot_id" 2>/dev/null)"
            if [ "${'$'}LOCK_BOOT" = "${'$'}EXPECTED_BOOT" ]; then
                log "another keeper already owns this kernel boot"
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

        # KernelSU late-load already owns module activation. Merely require its
        # executable state to exist; never invoke lifecycle stages here.
        KSUD=''
        for p in /data/adb/ksud /data/adb/ksu/bin/ksud /data/local/tmp/ksud-s25u-kdp; do
            if [ -x "${'$'}p" ]; then KSUD="${'$'}p"; break; fi
        done
        if [ -z "${'$'}KSUD" ]; then
            log "KernelSU loader not found after verified late-load"
            exit 64
        fi
        log "KernelSU late-load state accepted via ${'$'}KSUD"

        # Meta-Overlayfsx-ViPER-safe readiness. viper-safe.4 mounts its ext4
        # image outside the metamodule directory so KernelSU can replace the
        # module on updates without EBUSY. Accept the legacy path only for older
        # installed releases while the migration is being rolled out.
        if [ -d "${'$'}OVERLAY_META" ] && [ ! -f "${'$'}OVERLAY_META/disable" ]; then
            mounted=0
            overlay_mnt=''
            i=0
            while [ "${'$'}i" -lt 45 ]; do
                if grep -F " ${'$'}OVERLAY_DATA/mnt " /proc/mounts >/dev/null 2>&1; then
                    mounted=1
                    overlay_mnt="${'$'}OVERLAY_DATA/mnt"
                    break
                fi
                if grep -F " ${'$'}OVERLAY_HOME/mnt " /proc/mounts >/dev/null 2>&1; then
                    mounted=1
                    overlay_mnt="${'$'}OVERLAY_HOME/mnt"
                    break
                fi
                i=${'$'}((i + 1))
                sleep 1
            done
            if [ "${'$'}mounted" != "1" ]; then
                log "Meta-Overlayfsx ext4 image never became mounted"
                exit 65
            fi
            log "Meta-Overlayfsx ext4 image ready at ${'$'}overlay_mnt"

            if [ -x "${'$'}OVERLAY_HOME/overlayfsx" ]; then
                inspect_ok=0
                i=0
                while [ "${'$'}i" -lt 10 ]; do
                    if "${'$'}OVERLAY_HOME/overlayfsx" inspect -r 2>/dev/null |
                        grep -F '\"status\": \"success\"' >/dev/null 2>&1; then
                        inspect_ok=1
                        break
                    fi
                    i=${'$'}((i + 1))
                    sleep 2
                done
                if [ "${'$'}inspect_ok" != "1" ]; then
                    log "OverlayFSx kernel inspector did not report success"
                    exit 66
                fi
            fi

            if [ -d "${'$'}VIPER_META" ] && [ ! -f "${'$'}VIPER_META/disable" ] &&
                [ ! -f "${'$'}VIPER_META/skip_mount" ]; then
                if grep -E '^KSU /(vendor|system) overlay .*ViPER4Android-RE-AIDL' \
                    /proc/mounts >/dev/null 2>&1; then
                    log "unsafe broad ViPER root overlay detected; refusing zygote restart"
                    exit 67
                fi

                viper_ready=0
                i=0
                while [ "${'$'}i" -lt 30 ]; do
                    if grep -F 'Granular ViPER mounting completed without partition-root overlays' \
                        "${'$'}OVERLAY_HOME/overlayfsx.log" >/dev/null 2>&1; then
                        viper_ready=1
                        break
                    fi
                    i=${'$'}((i + 1))
                    sleep 1
                done
                if [ "${'$'}viper_ready" != "1" ]; then
                    log "ViPER-safe granular mount completion was not observed"
                    exit 68
                fi
                log "Meta-Overlayfsx-ViPER-safe granular mounts verified"
            fi
        fi

        OLD="${'$'}(zygote_pids)"
        if [ -z "${'$'}OLD" ]; then
            log "no zygote process found"
            exit 69
        fi
        last="${'$'}OLD"
        stable=0
        i=0
        while [ "${'$'}i" -lt 20 ]; do
            sleep 1
            now="${'$'}(zygote_pids)"
            if [ -n "${'$'}now" ] && [ "${'$'}now" = "${'$'}last" ]; then
                stable=${'$'}((stable + 1))
            else
                stable=0
                last="${'$'}now"
            fi
            [ "${'$'}stable" -ge 3 ] && break
            i=${'$'}((i + 1))
        done
        if [ "${'$'}stable" -lt 3 ]; then
            log "zygote set did not stabilize; refusing restart"
            exit 70
        fi
        OLD="${'$'}last"

        if [ "${'$'}(current_boot)" != "${'$'}EXPECTED_BOOT" ]; then
            log "kernel reboot detected before zygote restart; aborting"
            exit 71
        fi
        if [ "${'$'}(marker_boot 2>/dev/null)" = "${'$'}EXPECTED_BOOT" ]; then
            log "another owner completed while waiting; no restart needed"
            exit 0
        fi

        # Ask Android init to own the zygote transition. Sending SIGKILL to
        # zygote directly is indistinguishable from a crash to Zygisk Next's
        # crash guard and can leave the replacement zygote uninjected.
        RESTART_OUT="${'$'}(/system/bin/setprop ctl.restart zygote 2>&1)"
        RESTART_RC=${'$'}?
        if [ "${'$'}RESTART_RC" != "0" ]; then
            log "init-managed zygote restart request failed rc=${'$'}RESTART_RC out=${'$'}RESTART_OUT"
            exit 72
        fi
        log "init-managed zygote restart requested; old pids=${'$'}OLD"

        NEW=''
        i=0
        while [ "${'$'}i" -lt 30 ]; do
            sleep 1
            now="${'$'}(zygote_pids)"
            if [ -n "${'$'}now" ] && [ "${'$'}now" != "${'$'}OLD" ]; then
                NEW="${'$'}now"
                break
            fi
            i=${'$'}((i + 1))
        done
        if [ -z "${'$'}NEW" ]; then
            log "new zygote did not appear within 30s after init-managed restart"
            exit 73
        fi

        if [ "${'$'}(current_boot)" != "${'$'}EXPECTED_BOOT" ]; then
            log "kernel boot id changed during zygote restart; no done marker"
            exit 74
        fi

        i=0
        while [ "${'$'}i" -lt 30 ]; do
            if [ -n "${'$'}(pidof system_server 2>/dev/null)" ] &&
                [ "${'$'}(getprop sys.boot_completed 2>/dev/null)" = "1" ]; then
                break
            fi
            i=${'$'}((i + 1))
            sleep 1
        done
        if [ -z "${'$'}(pidof system_server 2>/dev/null)" ]; then
            log "system_server not healthy after zygote restart"
            exit 75
        fi

        UP="${'$'}(cut -d. -f1 /proc/uptime 2>/dev/null)"
        TMP="${'$'}DONE.tmp.${'$'}${'$'}"
        printf '%s %s\n' "${'$'}EXPECTED_BOOT" "${'$'}UP" > "${'$'}TMP" || exit 76
        chown 2000:2000 "${'$'}TMP" 2>/dev/null
        chmod 0664 "${'$'}TMP" 2>/dev/null
        restorecon "${'$'}TMP" >/dev/null 2>&1 || true
        mv -f "${'$'}TMP" "${'$'}DONE" || exit 77
        chown 2000:2000 "${'$'}DONE" 2>/dev/null
        chmod 0664 "${'$'}DONE" 2>/dev/null
        log "zygote refresh complete; new pids=${'$'}NEW marker=${'$'}DONE"
        exit 0
    """.trimIndent() + "\n"

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
