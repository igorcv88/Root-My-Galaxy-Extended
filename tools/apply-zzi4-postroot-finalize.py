from pathlib import Path

RECOVERY = Path('app/src/main/java/dev/busung/s25uroot/RootRecoveryActions.kt')
TEST = Path('app/src/test/java/dev/busung/s25uroot/Zzi4PostRootRuntimeTest.kt')


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected one anchor, found {count}')
    path.write_text(text.replace(old, new, 1), encoding='utf-8')


replace_once(
    RECOVERY,
    '''            BOOT_MARKER='/data/local/tmp/.rmg-auto-zygote-restart-boot'\n            current_boot() { cat /proc/sys/kernel/random/boot_id 2>/dev/null; }\n            publish_handoff() {''',
    '''            BOOT_MARKER='/data/local/tmp/.rmg-auto-zygote-restart-boot'\n            POST_STATUS='/data/local/tmp/.rmg-zzi4-postrestart-status'\n            OLD_SS="${'$'}(pidof system_server 2>/dev/null)"\n            current_boot() { cat /proc/sys/kernel/random/boot_id 2>/dev/null; }\n            publish_post_status() {\n                printf '%s\\n' "${'$'}1" > "${'$'}POST_STATUS" 2>/dev/null || true\n                chmod 0666 "${'$'}POST_STATUS" 2>/dev/null || true\n            }\n            publish_handoff() {''',
    'restart header',
)

replace_once(
    RECOVERY,
    '''            if [ "${'$'}ONCE_PER_BOOT" = "1" ]; then\n                [ "${'$'}(cat "${'$'}BOOT_MARKER" 2>/dev/null)" != "${'$'}EXPECTED_BOOT" ] || \\\n                    reject_handoff 'restart-already-performed-this-boot'\n                printf '%s\\n' "${'$'}EXPECTED_BOOT" > "${'$'}BOOT_MARKER" || \\\n                    reject_handoff 'restart-boot-marker-write-failed'\n                chmod 0666 "${'$'}BOOT_MARKER" 2>/dev/null || true\n            fi\n\n            if [ "${'$'}(getprop init.svc.zygote_secondary 2>/dev/null)" = "running" ]; then\n                setprop ctl.restart zygote_secondary || reject_handoff 'zygote-secondary-restart-failed'\n            fi\n\n            # Do not report success merely because this detached shell forked.''',
    '''            if [ "${'$'}ONCE_PER_BOOT" = "1" ]; then\n                [ "${'$'}(cat "${'$'}BOOT_MARKER" 2>/dev/null)" != "${'$'}EXPECTED_BOOT" ] || \\\n                    reject_handoff 'restart-already-performed-this-boot'\n                rm -f -- "${'$'}POST_STATUS" 2>/dev/null || true\n            fi\n\n            if [ "${'$'}(getprop init.svc.zygote_secondary 2>/dev/null)" = "running" ]; then\n                setprop ctl.restart zygote_secondary || reject_handoff 'zygote-secondary-restart-failed'\n            fi\n\n            # Commit the once-per-boot marker only after the child-side restart\n            # prerequisites above succeed, so a failed secondary restart does not\n            # poison the boot and suppress a later automatic recovery attempt.\n            if [ "${'$'}ONCE_PER_BOOT" = "1" ]; then\n                printf '%s\\n' "${'$'}EXPECTED_BOOT" > "${'$'}BOOT_MARKER" || \\\n                    reject_handoff 'restart-boot-marker-write-failed'\n                chmod 0666 "${'$'}BOOT_MARKER" 2>/dev/null || true\n            fi\n\n            # Do not report success merely because this detached shell forked.''',
    'restart marker ordering',
)

replace_once(
    RECOVERY,
    '''            sleep 0.75\n            rm -f -- "${'$'}0"\n            setprop ctl.restart zygote\n        """.trimIndent() + "\\n"''',
    '''            sleep 0.75\n            rm -f -- "${'$'}0"\n            if ! setprop ctl.restart zygote; then\n                if [ "${'$'}ONCE_PER_BOOT" = "1" ]; then\n                    rm -f -- "${'$'}BOOT_MARKER" 2>/dev/null || true\n                    publish_post_status 'RMG_ZZI4_POST_RESTART_ERROR reason=zygote-restart-command-failed'\n                fi\n                exit 0\n            fi\n\n            # Automatic ZZI4 recovery survives the framework restart in this\n            # detached root shell and verifies the newly-created system_server.\n            # Mapping the LSPosed Zygisk library is the hard success criterion;\n            # LSPosedBridge in logcat is recorded as an additional diagnostic.\n            if [ "${'$'}ONCE_PER_BOOT" = "1" ]; then\n                i=0\n                while [ "${'$'}i" -lt 40 ]; do\n                    if [ "${'$'}(current_boot)" != "${'$'}EXPECTED_BOOT" ]; then\n                        publish_post_status 'RMG_ZZI4_POST_RESTART_ERROR reason=boot-changed'\n                        exit 0\n                    fi\n                    NEW_SS="${'$'}(pidof system_server 2>/dev/null)"\n                    if [ -n "${'$'}NEW_SS" ] && [ "${'$'}NEW_SS" != "${'$'}OLD_SS" ]; then\n                        if grep -Fq \\\n                            '/data/adb/modules/zygisk_lsposed/zygisk/arm64-v8a.so' \\\n                            "/proc/${'$'}NEW_SS/maps" 2>/dev/null; then\n                            BRIDGE=0\n                            if logcat -d -b all -v threadtime 2>/dev/null | grep -Fq 'LSPosedBridge'; then\n                                BRIDGE=1\n                            fi\n                            publish_post_status \\\n                                "RMG_ZZI4_POST_RESTART_OK system_server=${'$'}NEW_SS lsposed_map=1 bridge=${'$'}BRIDGE"\n                            echo "post-restart: LSPosed mapped in new system_server=${'$'}NEW_SS bridge=${'$'}BRIDGE"\n                            exit 0\n                        fi\n                    fi\n                    i="${'$'}((i + 1))"\n                    sleep 0.5\n                done\n                NEW_SS="${'$'}(pidof system_server 2>/dev/null)"\n                publish_post_status \\\n                    "RMG_ZZI4_POST_RESTART_ERROR reason=lsposed-map-timeout old_ss=${'$'}OLD_SS new_ss=${'$'}NEW_SS"\n                echo "post-restart: LSPosed map verification timed out old_ss=${'$'}OLD_SS new_ss=${'$'}NEW_SS"\n                dmesg 2>/dev/null | grep -E 'defex_lsposed_compat|DEFEX.*zygisk_lsposed' | tail -n 30 || true\n                logcat -d -b all -v threadtime 2>/dev/null | \\\n                    grep -E 'LSPosedBridge|LSPosedService|zygisk_lsposed|zn-daemon|zn-zygisk|dlopen' | tail -n 80 || true\n            fi\n        """.trimIndent() + "\\n"''',
    'restart post verification',
)

t = TEST.read_text(encoding='utf-8')
anchor = '''        assertTrue(recovery.contains("restart-already-performed-this-boot"))\n        assertTrue(post.contains("Zzi4PostRootRuntime.prepareCommand"))'''
replacement = '''        assertTrue(recovery.contains("restart-already-performed-this-boot"))\n        assertTrue(recovery.contains(".rmg-zzi4-postrestart-status"))\n        assertTrue(recovery.contains("RMG_ZZI4_POST_RESTART_OK"))\n        assertTrue(recovery.contains("lsposed-map-timeout"))\n        val secondary = recovery.indexOf("setprop ctl.restart zygote_secondary")\n        val markerWrite = recovery.indexOf("restart-boot-marker-write-failed")\n        assertTrue(secondary >= 0)\n        assertTrue(markerWrite > secondary)\n        assertTrue(post.contains("Zzi4PostRootRuntime.prepareCommand"))'''
if t.count(anchor) != 1:
    raise SystemExit('runtime test anchor changed')
TEST.write_text(t.replace(anchor, replacement, 1), encoding='utf-8')

print('Detached ZZI4 post-restart verification patch applied')
