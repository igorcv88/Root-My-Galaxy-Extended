package dev.busung.s25uroot

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class RootRecoveryResult(
    val accepted: Boolean,
    val detail: String,
)

/**
 * Explicit post-root repair actions for the Advanced settings page.
 *
 * These actions never acquire bootstrap root and never replay the exploit. The
 * module reload uses only the already-installed /data/adb/ksud; because the RMG
 * ksud intentionally blocks duplicate late-load calls per boot, it stages an
 * identical verified copy into RMG's existing .ksud-stage handoff and clears the
 * boot-scoped readiness marker immediately before the explicit replay.
 */
internal object RootRecoveryActions {
    const val HOLD_TO_CONFIRM_MILLIS = 1_400L

    suspend fun restartZygote(context: Context): RootRecoveryResult = withContext(Dispatchers.IO) {
        val bootId = verifiedRootBoot(context) ?: return@withContext RootRecoveryResult(
            accepted = false,
            detail = "KernelSU root is not available for this boot",
        )
        val token = actionToken()
        val scriptPath = "/data/local/tmp/rmg-restart-zygote-$token.sh"
        val logPath = "/data/local/tmp/rmg-restart-zygote.log"
        val acceptedPath = "/data/local/tmp/.rmg-restart-zygote-accepted-$token"
        val acceptedMarker = "RMG_ZYGOTE_RESTART_ACCEPTED"
        val script = """
            #!/system/bin/sh
            EXPECTED_BOOT=${shellQuote(bootId)}
            ACCEPTED=${shellQuote(acceptedPath)}
            ACCEPTED_VALUE=${shellQuote(acceptedMarker)}
            current_boot() { cat /proc/sys/kernel/random/boot_id 2>/dev/null; }
            publish_handoff() {
                printf '%s\n' "${'$'}1" > "${'$'}ACCEPTED" || exit 79
                chmod 0666 "${'$'}ACCEPTED" 2>/dev/null || true
            }
            reject_handoff() {
                publish_handoff "error:${'$'}1"
                rm -f -- "${'$'}0"
                exit 0
            }

            [ "${'$'}(id -u 2>/dev/null)" = "0" ] || reject_handoff 'not-root'
            [ "${'$'}(current_boot)" = "${'$'}EXPECTED_BOOT" ] || reject_handoff 'boot-changed'
            [ "${'$'}(getprop init.svc.zygote 2>/dev/null)" = "running" ] || reject_handoff 'zygote-not-running'

            if [ "${'$'}(getprop init.svc.zygote_secondary 2>/dev/null)" = "running" ]; then
                setprop ctl.restart zygote_secondary || reject_handoff 'zygote-secondary-restart-failed'
            fi

            # Do not report success merely because this detached shell forked.
            # The parent waits for this marker, which is published only after the
            # child has root, the same boot, and a live Zygote to hand off to.
            publish_handoff "${'$'}ACCEPTED_VALUE"

            # Give the caller enough time to persist UI/history state after it
            # observes the acknowledgement and before system_server is recreated.
            sleep 0.75
            rm -f -- "${'$'}0"
            setprop ctl.restart zygote
        """.trimIndent() + "\n"

        val launch = launchDetachedScript(
            context = context,
            scriptPath = scriptPath,
            logPath = logPath,
            script = script,
            acceptedPath = acceptedPath,
            acceptedMarker = acceptedMarker,
        )
        if (!launch.accepted) launch else RootRecoveryResult(
            accepted = true,
            detail = "Zygote restart scheduled; Android runtime and system_server will be recreated",
        )
    }

    suspend fun reloadKernelSuModules(context: Context): RootRecoveryResult = withContext(Dispatchers.IO) {
        val bootId = verifiedRootBoot(context) ?: return@withContext RootRecoveryResult(
            accepted = false,
            detail = "KernelSU root is not available for this boot",
        )
        val token = actionToken()
        val resultPath = "/data/local/tmp/.rmg-module-reload-result-$token"
        val markerPath = "/data/local/tmp/.rmg-module-reload-complete-$token"
        val acceptedPath = "/data/local/tmp/.rmg-module-reload-accepted-$token"
        val acceptedMarker = "RMG_MODULE_RELOAD_ACCEPTED"
        val hookPath = "/data/adb/boot-completed.d/99-rmg-module-reload-$token.sh"
        val scriptPath = "/data/local/tmp/rmg-module-reload-$token.sh"
        val logPath = "/data/local/tmp/rmg-module-reload.log"
        val lockPath = "/data/local/tmp/.rmg-module-reload-owner"

        val script = """
            #!/system/bin/sh
            EXPECTED_BOOT=${shellQuote(bootId)}
            TOKEN=${shellQuote(token)}
            RESULT=${shellQuote(resultPath)}
            MARKER=${shellQuote(markerPath)}
            ACCEPTED=${shellQuote(acceptedPath)}
            ACCEPTED_VALUE=${shellQuote(acceptedMarker)}
            HOOK=${shellQuote(hookPath)}
            LOCK=${shellQuote(lockPath)}
            KSUD='/data/adb/ksud'
            STAGE='/data/local/tmp/.ksud-stage'
            READY='/data/local/tmp/.rmg-ksu-late-load-ready'

            current_boot() { cat /proc/sys/kernel/random/boot_id 2>/dev/null; }
            publish() {
                printf '%s\n' "${'$'}1" > "${'$'}RESULT" 2>/dev/null || true
                chmod 0666 "${'$'}RESULT" 2>/dev/null || true
            }
            publish_handoff() {
                printf '%s\n' "${'$'}1" > "${'$'}ACCEPTED" || exit 79
                chmod 0666 "${'$'}ACCEPTED" 2>/dev/null || true
            }
            reject_handoff() {
                publish_handoff "error:${'$'}1"
                publish "error:${'$'}1"
                exit 0
            }
            cleanup() {
                rm -f -- "${'$'}HOOK" "${'$'}MARKER" "${'$'}STAGE" "${'$'}0" 2>/dev/null
                if [ "${'$'}(cat "${'$'}LOCK/token" 2>/dev/null)" = "${'$'}TOKEN" ]; then
                    rm -rf -- "${'$'}LOCK" 2>/dev/null
                fi
            }
            trap cleanup EXIT INT TERM HUP

            [ "${'$'}(id -u 2>/dev/null)" = "0" ] || reject_handoff 'not-root'
            [ "${'$'}(current_boot)" = "${'$'}EXPECTED_BOOT" ] || reject_handoff 'boot-changed'
            [ -x "${'$'}KSUD" ] || reject_handoff 'installed-ksud-missing'
            grep -Fqx "boot_id=${'$'}EXPECTED_BOOT" "${'$'}READY" 2>/dev/null || reject_handoff 'global-readiness-missing'

            if ! mkdir "${'$'}LOCK" 2>/dev/null; then
                LOCK_BOOT="${'$'}(cat "${'$'}LOCK/boot_id" 2>/dev/null)"
                if [ "${'$'}LOCK_BOOT" = "${'$'}EXPECTED_BOOT" ]; then
                    reject_handoff 'reload-already-running'
                fi
                rm -rf -- "${'$'}LOCK" 2>/dev/null
                mkdir "${'$'}LOCK" 2>/dev/null || reject_handoff 'reload-lock-failed'
            fi
            printf '%s\n' "${'$'}EXPECTED_BOOT" > "${'$'}LOCK/boot_id"
            printf '%s\n' "${'$'}TOKEN" > "${'$'}LOCK/token"

            # RMG's patched ksud consumes .ksud-stage before the post-load
            # namespace switch. Use a byte-identical copy of the installed daemon
            # so this explicit recovery operation never introduces another build.
            rm -f -- "${'$'}STAGE"
            /system/bin/cp "${'$'}KSUD" "${'$'}STAGE" || reject_handoff 'ksud-stage-copy-failed'
            chmod 0755 "${'$'}STAGE" || reject_handoff 'ksud-stage-chmod-failed'
            INSTALLED_HASH="${'$'}(sha256sum "${'$'}KSUD" 2>/dev/null)"
            INSTALLED_HASH="${'$'}{INSTALLED_HASH%% *}"
            STAGE_HASH="${'$'}(sha256sum "${'$'}STAGE" 2>/dev/null)"
            STAGE_HASH="${'$'}{STAGE_HASH%% *}"
            [ -n "${'$'}INSTALLED_HASH" ] && [ "${'$'}INSTALLED_HASH" = "${'$'}STAGE_HASH" ] || reject_handoff 'ksud-stage-hash-mismatch'

            mkdir -p /data/adb/boot-completed.d || reject_handoff 'hook-dir-create-failed'
            cat > "${'$'}HOOK" <<RMG_RELOAD_HOOK
            #!/system/bin/sh
            printf '%s\n' '${token}' > '${markerPath}'
            chmod 0666 '${markerPath}' 2>/dev/null || true
            rm -f -- '${hookPath}'
            RMG_RELOAD_HOOK
            [ -s "${'$'}HOOK" ] || reject_handoff 'hook-write-failed'
            chmod 0755 "${'$'}HOOK" || reject_handoff 'hook-chmod-failed'

            # At this point the child owns a fully validated/staged reload. The
            # caller may treat the handoff as accepted, but final success still
            # requires the result marker plus restored PID1/global readiness.
            publish_handoff "${'$'}ACCEPTED_VALUE"

            # The normal RMG path treats READY as a same-boot replay guard. The
            # user explicitly requested a reload, so clear it only after all
            # checks, staging and the completion hook are ready. The patched ksud
            # republishes READY only after its blocking mount stages finish in
            # PID1's namespace.
            rm -f -- "${'$'}READY" || { publish 'error:readiness-clear-failed'; exit 0; }
            "${'$'}KSUD" late-load --allow-shell --package-name me.weishu.kernelsu
            RC="${'$'}?"
            if [ "${'$'}RC" != "0" ]; then
                publish "error:ksud-late-load-rc-${'$'}RC"
                exit 0
            fi

            i=0
            while [ "${'$'}i" -lt 120 ]; do
                if [ "${'$'}(cat "${'$'}MARKER" 2>/dev/null)" = "${'$'}TOKEN" ] && \
                   grep -Fqx "boot_id=${'$'}EXPECTED_BOOT" "${'$'}READY" 2>/dev/null; then
                    publish 'ok'
                    exit 0
                fi
                [ "${'$'}(current_boot)" = "${'$'}EXPECTED_BOOT" ] || { publish 'error:boot-changed'; exit 0; }
                i="${'$'}((i + 1))"
                sleep 1
            done
            publish 'error:boot-completed-or-readiness-timeout'
            exit 0
        """.trimIndent() + "\n"

        val launch = launchDetachedScript(
            context = context,
            scriptPath = scriptPath,
            logPath = logPath,
            script = script,
            acceptedPath = acceptedPath,
            acceptedMarker = acceptedMarker,
        )
        if (!launch.accepted) return@withContext launch

        val deadline = SystemClock.elapsedRealtime() + MODULE_RELOAD_WAIT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            val status = RootHelperShell.shell(
                context,
                "cat ${shellQuote(resultPath)} 2>/dev/null || true",
            ).output.lineSequence().lastOrNull()?.trim().orEmpty()
            when {
                status == "ok" -> {
                    RootHelperShell.shell(context, "rm -f -- ${shellQuote(resultPath)}")
                    val global = KernelSuGlobalReadiness.probe(context, bootId)
                    if (global.exitCode != 0) {
                        return@withContext RootRecoveryResult(
                            accepted = false,
                            detail = global.output.ifBlank { "KernelSU global readiness verification failed" }.takeLast(240),
                        )
                    }
                    return@withContext RootRecoveryResult(
                        accepted = true,
                        detail = "KernelSU module lifecycle reapplied and PID1 mount readiness verified",
                    )
                }
                status.startsWith("error:") -> {
                    RootHelperShell.shell(context, "rm -f -- ${shellQuote(resultPath)}")
                    return@withContext RootRecoveryResult(
                        accepted = false,
                        detail = status.removePrefix("error:").replace('-', ' '),
                    )
                }
            }
            delay(MODULE_RELOAD_POLL_MILLIS)
        }

        RootRecoveryResult(
            accepted = false,
            detail = "Timed out waiting for the KernelSU module reload result",
        )
    }

    suspend fun kernelSuSoftReboot(context: Context): RootRecoveryResult {
        val bootId = withContext(Dispatchers.IO) { verifiedRootBoot(context) }
            ?: return RootRecoveryResult(false, "KernelSU root is not available for this boot")
        if (AutoRootSupport.currentBootToken() != bootId) {
            return RootRecoveryResult(false, "Kernel boot changed before soft reboot")
        }
        val result = PostRootAutomation.run(
            context = context,
            softReboot = true,
            startShizuku = false,
        )
        return RootRecoveryResult(
            accepted = result.softRebootStarted,
            detail = if (result.softRebootStarted) {
                "KernelSU native soft reboot accepted"
            } else {
                result.detail.ifBlank { "KernelSU did not accept the soft reboot" }
            },
        )
    }

    suspend fun rebootAndUnroot(context: Context): RootRecoveryResult = withContext(Dispatchers.IO) {
        val bootId = verifiedRootBoot(context) ?: return@withContext RootRecoveryResult(
            accepted = false,
            detail = "KernelSU root is not available for this boot",
        )
        val autoRootWasEnabled = AppPreferences.autoRootEnabled(context)
        if (!AppPreferences.setAutoRootEnabledImmediately(context, false)) {
            return@withContext RootRecoveryResult(
                accepted = false,
                detail = "Unable to persist Auto Root disabled before reboot",
            )
        }

        val token = actionToken()
        val scriptPath = "/data/local/tmp/rmg-reboot-unroot-$token.sh"
        val logPath = "/data/local/tmp/rmg-reboot-unroot.log"
        val acceptedPath = "/data/local/tmp/.rmg-reboot-unroot-accepted-$token"
        val acceptedMarker = "RMG_REBOOT_UNROOT_ACCEPTED"
        val script = """
            #!/system/bin/sh
            EXPECTED_BOOT=${shellQuote(bootId)}
            ACCEPTED=${shellQuote(acceptedPath)}
            ACCEPTED_VALUE=${shellQuote(acceptedMarker)}
            current_boot() { cat /proc/sys/kernel/random/boot_id 2>/dev/null; }
            publish_handoff() {
                printf '%s\n' "${'$'}1" > "${'$'}ACCEPTED" || exit 79
                chmod 0666 "${'$'}ACCEPTED" 2>/dev/null || true
            }
            reject_handoff() {
                publish_handoff "error:${'$'}1"
                rm -f -- "${'$'}0"
                exit 0
            }

            [ "${'$'}(id -u 2>/dev/null)" = "0" ] || reject_handoff 'not-root'
            [ "${'$'}(current_boot)" = "${'$'}EXPECTED_BOOT" ] || reject_handoff 'boot-changed'
            [ -x /system/bin/reboot ] || reject_handoff 'reboot-command-missing'

            # Flush persistent state before acknowledging the destructive handoff.
            # Auto Root was synchronously disabled by the parent before this child
            # was launched, so a successful acknowledgement is safe to commit to.
            sync
            publish_handoff "${'$'}ACCEPTED_VALUE"

            # Let the parent observe the child acknowledgement and update UI state
            # before Android tears down the process tree.
            sleep 0.75
            rm -f -- "${'$'}0"
            /system/bin/reboot
        """.trimIndent() + "\n"

        val launch = launchDetachedScript(
            context = context,
            scriptPath = scriptPath,
            logPath = logPath,
            script = script,
            acceptedPath = acceptedPath,
            acceptedMarker = acceptedMarker,
        )
        if (!launch.accepted) {
            if (autoRootWasEnabled) AppPreferences.setAutoRootEnabledImmediately(context, true)
            launch
        } else {
            RootRecoveryResult(
                accepted = true,
                detail = "Full reboot scheduled; Auto Root disabled and ephemeral KernelSU will be cleared",
            )
        }
    }

    private fun verifiedRootBoot(context: Context): String? {
        val bootId = AutoRootSupport.currentBootToken()?.takeIf { it.isNotBlank() } ?: return null
        val root = RootHelperShell.shell(context, "id")
        if (root.exitCode != 0 || !root.output.contains("uid=0")) return null
        return bootId
    }

    /**
     * Installs a detached recovery script, then waits for an acknowledgement that
     * is written by that child itself after its own validation. A successful fork
     * is deliberately not sufficient: this prevents false success when the boot,
     * root session, Zygote state, or another child-side prerequisite changed in
     * the handoff window.
     */
    private fun launchDetachedScript(
        context: Context,
        scriptPath: String,
        logPath: String,
        script: String,
        acceptedPath: String,
        acceptedMarker: String,
    ): RootRecoveryResult {
        val command = buildString {
            append("set -eu\n")
            append("script=${shellQuote(scriptPath)}\n")
            append("accepted=${shellQuote(acceptedPath)}\n")
            append("tmp=\"${'$'}script.tmp.${'$'}${'$'}\"\n")
            append("cat > \"${'$'}tmp\" <<'RMG_RECOVERY_EOF'\n")
            append(script)
            if (!script.endsWith('\n')) append('\n')
            append("RMG_RECOVERY_EOF\n")
            append("chmod 0700 \"${'$'}tmp\"\n")
            append("mv -f \"${'$'}tmp\" \"${'$'}script\"\n")
            append("rm -f -- \"${'$'}accepted\"\n")
            append(": > ${shellQuote(logPath)}\n")
            append("chmod 0666 ${shellQuote(logPath)} 2>/dev/null || true\n")
            append("setsid sh \"${'$'}script\" >>${shellQuote(logPath)} 2>&1 < /dev/null &\n")
        }
        val launch = RootHelperShell.shell(context, command)
        if (launch.exitCode != 0) {
            return RootRecoveryResult(
                accepted = false,
                detail = launch.output.trim().ifBlank { "Unable to launch detached recovery action" }.takeLast(320),
            )
        }

        val acknowledgement = RootHelperShell.shell(
            context,
            buildString {
                append("accepted=${shellQuote(acceptedPath)}\n")
                append("i=0\n")
                append("while [ \"${'$'}i\" -lt 100 ]; do\n")
                append("  if [ -s \"${'$'}accepted\" ]; then\n")
                append("    ack=\"${'$'}(cat \"${'$'}accepted\" 2>/dev/null || true)\"\n")
                append("    rm -f -- \"${'$'}accepted\"\n")
                append("    printf '%s\\n' \"${'$'}ack\"\n")
                append("    [ \"${'$'}ack\" = ${shellQuote(acceptedMarker)} ] && exit 0\n")
                append("    exit 78\n")
                append("  fi\n")
                append("  i=\"${'$'}((${ '$' }i + 1))\"\n")
                append("  sleep 0.1\n")
                append("done\n")
                append("rm -f -- \"${'$'}accepted\"\n")
                append("echo 'recovery child did not publish accepted handoff marker' >&2\n")
                append("tail -n 8 ${shellQuote(logPath)} >&2 2>/dev/null || true\n")
                append("exit 78\n")
            },
        )
        val accepted = acknowledgement.exitCode == 0 &&
            acknowledgement.output.lineSequence().any { it.trim() == acceptedMarker }
        val childError = acknowledgement.output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("error:") }
            ?.removePrefix("error:")
            ?.replace('-', ' ')

        return RootRecoveryResult(
            accepted = accepted,
            detail = if (accepted) {
                acceptedMarker
            } else {
                childError ?: acknowledgement.output.trim()
                    .ifBlank { "Recovery child did not acknowledge the action" }
                    .takeLast(320)
            },
        )
    }

    private fun actionToken(): String = java.lang.Long.toHexString(SystemClock.elapsedRealtimeNanos())

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private const val MODULE_RELOAD_WAIT_MILLIS = 125_000L
    private const val MODULE_RELOAD_POLL_MILLIS = 500L
}
