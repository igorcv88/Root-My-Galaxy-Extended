from pathlib import Path
import re

ROOT = Path('.')
MANUAL = ROOT / 'app/src/main/java/dev/busung/s25uroot/InstallViewModel.kt'
AUTO = ROOT / 'app/src/main/java/dev/busung/s25uroot/AutoRootRunner.kt'
TEST = ROOT / 'app/src/test/java/dev/busung/s25uroot/Zzi4PostRootRuntimeTest.kt'


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected one anchor, found {count}')
    path.write_text(text.replace(old, new, 1), encoding='utf-8')


def replace_regex_once(path: Path, pattern: str, replacement: str, label: str) -> None:
    text = path.read_text(encoding='utf-8')
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f'{label}: expected one regex match, found {count}')
    path.write_text(updated, encoding='utf-8')


# Keep the existing deterministic Local ADB preference, but make a Shizuku
# fallback explicit in logs so a future hardware run tells us why the pin was
# unavailable without changing transport policy.
replace_once(
    MANUAL,
    '''        if (profile.profileId == "pa3q-S938BXXUCZZI4" && shellRequired && localAdbPaired) {\n            appendLog("[*] ZZI4 Manual transport pinned to paired Local ADB shell")\n            return ManualRunTransport.LocalAdb\n        }\n\n        val requestedShizuku = AppPreferences.shizukuMode(app)''',
    '''        if (profile.profileId == "pa3q-S938BXXUCZZI4" && shellRequired && localAdbPaired) {\n            appendLog("[*] ZZI4 Manual transport pinned to paired Local ADB shell")\n            return ManualRunTransport.LocalAdb\n        }\n        if (profile.profileId == "pa3q-S938BXXUCZZI4" && shellRequired && !localAdbPaired) {\n            appendLog("[*] ZZI4 Local ADB pin unavailable: adbPaired=false; evaluating Shizuku shell")\n        }\n\n        val requestedShizuku = AppPreferences.shizukuMode(app)''',
    'manual transport diagnostic',
)

manual_prestage = r'''    private fun preStageKernelSuForAutoLateLoad(payloads: VerifiedPayloads) {
        if (payloads.profile.profileId != "pa3q-S938BXXUCZZI4") return

        val artifact = payloads.profile.kernelSu.artifact
        val expectedSha256 = artifact.sha256
        val expectedSize = artifact.size
        val verifyCommand =
            "set -e; " +
                "/system/bin/toybox sha256sum ${shellQuote(SHIZUKU_KSUD_PATH)} | " +
                "/system/bin/toybox cut -d ' ' -f 1; " +
                "/system/bin/toybox wc -c < ${shellQuote(SHIZUKU_KSUD_PATH)}"
        var reusedExisting = false

        val verification = when (runTransport()) {
            ManualRunTransport.LocalAdb -> {
                val session = requireNotNull(activeLocalAdbSession) {
                    "Manual Local ADB session disappeared before KernelSU pre-stage"
                }
                // The root helper consumes the stable KSUD_PATH. Remove only the
                // transient stage first, then verify the stable artifact before
                // doing any multi-megabyte write immediately ahead of the race.
                session.remove(SHIZUKU_KSUD_STAGE_PATH)
                val current = session.shell(verifyCommand)
                if (
                    current.exitCode == 0 &&
                    remoteArtifactMatches(current.output, expectedSha256, expectedSize)
                ) {
                    val chmod = session.shell(
                        "/system/bin/chmod 755 ${shellQuote(SHIZUKU_KSUD_PATH)}",
                    )
                    require(chmod.exitCode == 0) {
                        "Unable to restore executable mode on verified ZZI4 KernelSU bootstrap: ${chmod.output}"
                    }
                    reusedExisting = true
                    current
                } else {
                    session.push(payloads.kernelSu, SHIZUKU_KSUD_PATH, executable = true)
                    session.shell(verifyCommand)
                }
            }
            ManualRunTransport.Shizuku -> {
                val cleanup = ShizukuController.shell(
                    "rm -f ${shellQuote(SHIZUKU_KSUD_STAGE_PATH)}",
                )
                require(cleanup.exitCode == 0) {
                    "Unable to clear stale KernelSU stage before ZZI4 exploit: ${cleanup.output}"
                }
                val current = ShizukuController.shell(verifyCommand)
                if (
                    current.exitCode == 0 &&
                    remoteArtifactMatches(current.output, expectedSha256, expectedSize)
                ) {
                    val chmod = ShizukuController.shell(
                        "/system/bin/chmod 755 ${shellQuote(SHIZUKU_KSUD_PATH)}",
                    )
                    require(chmod.exitCode == 0) {
                        "Unable to restore executable mode on verified ZZI4 KernelSU bootstrap: ${chmod.output}"
                    }
                    reusedExisting = true
                    current
                } else {
                    ShizukuController.writeFile(
                        SHIZUKU_KSUD_PATH,
                        "755",
                        payloads.kernelSu.inputStream(),
                    )
                    ShizukuController.shell(verifyCommand)
                }
            }
            ManualRunTransport.App -> {
                error("ZZI4 KernelSU pre-stage requires a real shell transport")
            }
        }

        require(
            verification.exitCode == 0 &&
                remoteArtifactMatches(verification.output, expectedSha256, expectedSize),
        ) {
            "ZZI4 KernelSU pre-stage verification failed: expected=" +
                "$expectedSha256/$expectedSize remote=${verification.output.takeLast(240)}"
        }
        appendLog(
            "[+] ZZI4 pre-exploit KernelSU bootstrap verified " +
                "sha256=$expectedSha256 size=$expectedSize " +
                "action=${if (reusedExisting) "reuse" else "refresh"}",
        )
    }

'''
replace_regex_once(
    MANUAL,
    r'    private fun preStageKernelSuForAutoLateLoad\(payloads: VerifiedPayloads\) \{.*?\n    \}\n\n(?=    private suspend fun executeExploit)',
    manual_prestage,
    'manual idempotent prestage',
)

auto_prestage = r'''    private fun zzi4KernelSuVerifyCommand(): String =
        "set -e; " +
            "/system/bin/toybox sha256sum ${shellQuote(KSUD_PATH)} | " +
            "/system/bin/toybox cut -d ' ' -f 1; " +
            "/system/bin/toybox wc -c < ${shellQuote(KSUD_PATH)}"

    private fun zzi4KernelSuPreStageMatches(
        payloads: VerifiedPayloads,
        result: LocalAdbClient.ShellResult,
    ): Boolean {
        val artifact = payloads.profile.kernelSu.artifact
        return result.exitCode == 0 &&
            remoteArtifactMatches(result.output, artifact.sha256, artifact.size)
    }

    private fun verifyZzi4KernelSuPreStage(
        payloads: VerifiedPayloads,
        result: LocalAdbClient.ShellResult,
        action: String,
    ) {
        val artifact = payloads.profile.kernelSu.artifact
        require(zzi4KernelSuPreStageMatches(payloads, result)) {
            "ZZI4 Auto Root KernelSU pre-stage verification failed: expected=" +
                "${artifact.sha256}/${artifact.size} remote=${result.output.takeLast(240)}"
        }
        onLog(
            "[+] ZZI4 Auto Root pre-exploit KernelSU bootstrap verified " +
                "sha256=${artifact.sha256} size=${artifact.size} action=$action",
        )
    }

    private fun preStageZzi4KernelSuViaShizuku(payloads: VerifiedPayloads) {
        val cleanup = ShizukuController.shell("rm -f ${shellQuote(KSUD_STAGE_PATH)}")
        require(cleanup.exitCode == 0) {
            "Unable to clear stale Auto Root KernelSU stage: ${cleanup.output}"
        }
        val current = ShizukuController.shell(zzi4KernelSuVerifyCommand())
        if (zzi4KernelSuPreStageMatches(payloads, current)) {
            val chmod = ShizukuController.shell(
                "/system/bin/chmod 755 ${shellQuote(KSUD_PATH)}",
            )
            require(chmod.exitCode == 0) {
                "Unable to restore executable mode on verified Auto Root KernelSU bootstrap: ${chmod.output}"
            }
            verifyZzi4KernelSuPreStage(payloads, current, action = "reuse")
            return
        }
        ShizukuController.writeFile(KSUD_PATH, "755", payloads.kernelSu.inputStream())
        verifyZzi4KernelSuPreStage(
            payloads,
            ShizukuController.shell(zzi4KernelSuVerifyCommand()),
            action = "refresh",
        )
    }

    private fun preStageZzi4KernelSuViaLocalAdb(
        session: WirelessAdbSession,
        payloads: VerifiedPayloads,
    ) {
        session.remove(KSUD_STAGE_PATH)
        val current = session.shell(zzi4KernelSuVerifyCommand())
        if (zzi4KernelSuPreStageMatches(payloads, current)) {
            val chmod = session.shell("/system/bin/chmod 755 ${shellQuote(KSUD_PATH)}")
            require(chmod.exitCode == 0) {
                "Unable to restore executable mode on verified Auto Root KernelSU bootstrap: ${chmod.output}"
            }
            verifyZzi4KernelSuPreStage(payloads, current, action = "reuse")
            return
        }
        session.push(payloads.kernelSu, KSUD_PATH, executable = true)
        verifyZzi4KernelSuPreStage(
            payloads,
            session.shell(zzi4KernelSuVerifyCommand()),
            action = "refresh",
        )
    }

'''
replace_regex_once(
    AUTO,
    r'    private fun zzi4KernelSuVerifyCommand\(\): String =.*?\n    \}\n\n(?=    private fun shizukuStage)',
    auto_prestage,
    'auto idempotent prestage',
)

# Structural regression test: both launch paths must inspect the stable ksud
# before the expensive refresh and keep the existing verification contract.
t = TEST.read_text(encoding='utf-8')
insert = r'''
    @Test
    fun zzi4PreStageReusesVerifiedStableKsudBeforeRefreshingIt() {
        val manual = File("src/main/java/dev/busung/s25uroot/InstallViewModel.kt").readText()
        val auto = File("src/main/java/dev/busung/s25uroot/AutoRootRunner.kt").readText()

        val manualLocalCheck = manual.indexOf("val current = session.shell(verifyCommand)")
        val manualLocalWrite = manual.indexOf(
            "session.push(payloads.kernelSu, SHIZUKU_KSUD_PATH, executable = true)",
            manualLocalCheck,
        )
        val manualShizukuCheck = manual.indexOf("val current = ShizukuController.shell(verifyCommand)")
        val manualShizukuWrite = manual.indexOf(
            "ShizukuController.writeFile(",
            manualShizukuCheck,
        )
        assertTrue(manualLocalCheck >= 0)
        assertTrue(manualLocalWrite > manualLocalCheck)
        assertTrue(manualShizukuCheck >= 0)
        assertTrue(manualShizukuWrite > manualShizukuCheck)
        assertTrue(manual.contains("action=${if (reusedExisting) \"reuse\" else \"refresh\"}"))
        assertTrue(manual.contains("ZZI4 Local ADB pin unavailable: adbPaired=false"))

        val autoShizukuCheck = auto.indexOf(
            "val current = ShizukuController.shell(zzi4KernelSuVerifyCommand())",
        )
        val autoShizukuWrite = auto.indexOf(
            "ShizukuController.writeFile(KSUD_PATH, \"755\", payloads.kernelSu.inputStream())",
            autoShizukuCheck,
        )
        val autoLocalCheck = auto.indexOf("val current = session.shell(zzi4KernelSuVerifyCommand())")
        val autoLocalWrite = auto.indexOf(
            "session.push(payloads.kernelSu, KSUD_PATH, executable = true)",
            autoLocalCheck,
        )
        assertTrue(autoShizukuCheck >= 0)
        assertTrue(autoShizukuWrite > autoShizukuCheck)
        assertTrue(autoLocalCheck >= 0)
        assertTrue(autoLocalWrite > autoLocalCheck)
        assertTrue(auto.contains("zzi4KernelSuPreStageMatches"))
        assertTrue(auto.contains("action = \"reuse\""))
        assertTrue(auto.contains("action = \"refresh\""))
    }
'''
idx = t.rfind('\n}')
if idx < 0:
    raise SystemExit('test class closing brace not found')
TEST.write_text(t[:idx] + insert + t[idx:], encoding='utf-8')

print('ZZI4 idempotent KernelSU pre-stage patch applied')
