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


# Manual: restore the validated architecture. The exploit gets bootstrap root
# first; only afterwards does installKernelSu() stage/late-load KernelSU.
replace_once(
    MANUAL,
    '''        if (runTransport() != ManualRunTransport.LocalAdb) {\n            preStageKernelSuForAutoLateLoad(payloads)\n            setPhase(InstallPhase.Exploiting, app.getString(R.string.status_exploit_running))''',
    '''        if (runTransport() != ManualRunTransport.LocalAdb) {\n            setPhase(InstallPhase.Exploiting, app.getString(R.string.status_exploit_running))''',
    'manual non-local prestage call',
)
replace_once(
    MANUAL,
    '''                activeLocalAdbSession = session\n                try {\n                    preStageKernelSuForAutoLateLoad(payloads)\n                    setPhase(InstallPhase.Exploiting, app.getString(R.string.status_exploit_running))''',
    '''                activeLocalAdbSession = session\n                try {\n                    setPhase(InstallPhase.Exploiting, app.getString(R.string.status_exploit_running))''',
    'manual local-adb prestage call',
)
replace_regex_once(
    MANUAL,
    r'\n    private fun preStageKernelSuForAutoLateLoad\(payloads: VerifiedPayloads\) \{.*?\n    \}\n\n(?=    private suspend fun executeExploit)',
    '\n',
    'manual prestage helper removal',
)

# Auto Root: likewise, stage only helper+exploit before the race. An already
# persisted ksud may still auto-late-load opportunistically, but a clean boot
# falls through to the existing post-root stage + --late-load path.
replace_once(
    AUTO,
    '''                val stagedHelper = shizukuStage(localHelper, SHELL_HELPER_PATH)\n                val stagedPayload = shizukuStage(payload, SHELL_PAYLOAD_PATH)\n                if (payloads.profile.profileId == Zzi4PostRootRuntime.PROFILE_ID) {\n                    preStageZzi4KernelSuViaShizuku(payloads)\n                }\n                beforeExploit()''',
    '''                val stagedHelper = shizukuStage(localHelper, SHELL_HELPER_PATH)\n                val stagedPayload = shizukuStage(payload, SHELL_PAYLOAD_PATH)\n                beforeExploit()''',
    'autoroot shizuku prestage call',
)
replace_once(
    AUTO,
    '''                session.push(localHelper, SHELL_HELPER_PATH, executable = true)\n                session.push(payload, SHELL_PAYLOAD_PATH, executable = true)\n                if (payloads.profile.profileId == Zzi4PostRootRuntime.PROFILE_ID) {\n                    preStageZzi4KernelSuViaLocalAdb(session, payloads)\n                }\n\n                val env = localAdbEnvironment(''',
    '''                session.push(localHelper, SHELL_HELPER_PATH, executable = true)\n                session.push(payload, SHELL_PAYLOAD_PATH, executable = true)\n\n                val env = localAdbEnvironment(''',
    'autoroot local-adb prestage call',
)
replace_once(
    AUTO,
    '''                // Claim the once-per-boot attempt only after a real shell transport\n                // exists and both exact offline artifacts have been staged.''',
    '''                // Claim the once-per-boot attempt only after a real shell transport\n                // exists and the exact helper/exploit artifacts have been staged.\n                // KernelSU staging is deliberately post-root on ZZI4 so the\n                // scheduler-sensitive exploit hot path performs no KSUD I/O.''',
    'autoroot local-adb claim comment',
)
replace_regex_once(
    AUTO,
    r'\n    private fun zzi4KernelSuVerifyCommand\(\): String =.*?\n    \}\n\n(?=    private fun shizukuStage)',
    '\n',
    'autoroot prestage helper removal',
)

# Replace tests that blessed pre-stage with a regression contract forbidding
# *any* KSUD pre-stage operation from the exploit hot path while requiring the
# existing post-root stage/late-load fallback to remain present.
t = TEST.read_text(encoding='utf-8')
pattern = r'''\n    @Test\n    fun autoRootStagesVerifiedZzi4KernelSuBeforeExploitClaim\(\) \{.*?\n    \}\n    @Test\n    fun zzi4PreStageReusesVerifiedStableKsudBeforeRefreshingIt\(\) \{.*?\n    \}\n'''
replacement = r'''
    @Test
    fun zzi4ExploitHotPathPerformsNoKernelSuPreStageIo() {
        val manual = File("src/main/java/dev/busung/s25uroot/InstallViewModel.kt").readText()
        val auto = File("src/main/java/dev/busung/s25uroot/AutoRootRunner.kt").readText()

        val manualRunStart = manual.indexOf("private suspend fun runExploitAndKernelSu")
        val manualRunEnd = manual.indexOf("private suspend fun executeExploit", manualRunStart)
        assertTrue(manualRunStart >= 0)
        assertTrue(manualRunEnd > manualRunStart)
        val manualHotPath = manual.substring(manualRunStart, manualRunEnd)
        assertFalse(manualHotPath.contains("preStageKernelSu"))
        assertFalse(manualHotPath.contains("KSUD"))
        assertFalse(manual.contains("private fun preStageKernelSuForAutoLateLoad"))
        assertTrue(manualHotPath.indexOf("executeExploit(") < manualHotPath.indexOf("installKernelSu(payloads)"))

        val autoRunStart = auto.indexOf("suspend fun run(")
        val autoRunEnd = auto.indexOf("private suspend fun verifyKernelSu", autoRunStart)
        assertTrue(autoRunStart >= 0)
        assertTrue(autoRunEnd > autoRunStart)
        val autoRun = auto.substring(autoRunStart, autoRunEnd)
        assertTrue(autoRun.indexOf("executeExploit(") < autoRun.indexOf("withKernelSuClient"))

        val autoExploitStart = auto.indexOf("private suspend fun executeExploit(")
        val autoExploitEnd = auto.indexOf("private suspend fun executeExploitViaLocalAdb", autoExploitStart)
        assertTrue(autoExploitStart >= 0)
        assertTrue(autoExploitEnd > autoExploitStart)
        val autoShizukuHotPath = auto.substring(autoExploitStart, autoExploitEnd)
        assertFalse(autoShizukuHotPath.contains("preStageZzi4KernelSu"))
        assertFalse(autoShizukuHotPath.contains("KSUD_PATH"))

        val autoLocalStart = autoExploitEnd
        val autoLocalEnd = auto.indexOf("private fun shizukuStage", autoLocalStart)
        assertTrue(autoLocalEnd > autoLocalStart)
        val autoLocalHotPath = auto.substring(autoLocalStart, autoLocalEnd)
        assertFalse(autoLocalHotPath.contains("preStageZzi4KernelSu"))
        assertFalse(autoLocalHotPath.contains("KSUD_PATH"))
        assertFalse(auto.contains("private fun preStageZzi4KernelSu"))
        assertFalse(auto.contains("private fun zzi4KernelSuVerifyCommand"))

        // The safe fallback remains post-root in both paths.
        assertTrue(manual.contains("val stage = runHelper(\"-c\", kernelSuStageCommand(payloads))"))
        assertTrue(manual.contains("runHelper(\"--late-load\")"))
        assertTrue(auto.contains("stageKernelSuRequired(payloads, ksuExec)"))
        assertTrue(auto.contains("ksuExec(arrayOf(\"--late-load\"))"))
    }
'''
updated, count = re.subn(pattern, '\n' + replacement, t, count=1, flags=re.S)
if count != 1:
    raise SystemExit(f'test replacement: expected one block, found {count}')
TEST.write_text(updated, encoding='utf-8')

print('ZZI4 pre-exploit KernelSU staging removed from exploit hot path')
