from pathlib import Path
import re

ROOT = Path('.')
MANUAL = ROOT / 'app/src/main/java/dev/busung/s25uroot/InstallViewModel.kt'
AUTO = ROOT / 'app/src/main/java/dev/busung/s25uroot/AutoRootRunner.kt'
POST_TEST = ROOT / 'app/src/test/java/dev/busung/s25uroot/Zzi4PostRootRuntimeTest.kt'
MANUAL_TEST = ROOT / 'app/src/test/java/dev/busung/s25uroot/Zzi4KernelSuPrestageTest.kt'
AUTO_TEST = ROOT / 'app/src/test/java/dev/busung/s25uroot/Zzi4PrestageVerificationCommandTest.kt'


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
replace_regex_once(
    MANUAL,
    r'\ninternal fun remoteArtifactMatches\(.*?\n\}\n',
    '\n',
    'obsolete remote artifact parser removal',
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

# Replace the 0.3.83 structural contract that blessed pre-stage with a contract
# that explicitly forbids KSUD I/O from the exploit hot path.
t = POST_TEST.read_text(encoding='utf-8')
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
    raise SystemExit(f'post-root structural test replacement: expected one block, found {count}')
POST_TEST.write_text(updated, encoding='utf-8')

MANUAL_TEST.write_text('''package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Zzi4KernelSuPrestageTest {
    @Test
    fun manualZzi4ExploitRunsBeforeAnyKernelSuStage() {
        val source = File("src/main/java/dev/busung/s25uroot/InstallViewModel.kt").readText()
        val start = source.indexOf("private suspend fun runExploitAndKernelSu")
        val end = source.indexOf("private suspend fun executeExploit", start)
        assertTrue(start >= 0)
        assertTrue(end > start)
        val body = source.substring(start, end)

        assertFalse(body.contains("preStageKernelSu"))
        assertFalse(body.contains("SHIZUKU_KSUD"))
        assertFalse(body.contains("sha256sum"))
        assertFalse(body.contains("wc -c"))
        assertTrue(body.indexOf("executeExploit(") < body.indexOf("installKernelSu(payloads)"))
    }

    @Test
    fun manualKernelSuStageAndLateLoadRemainPostRoot() {
        val source = File("src/main/java/dev/busung/s25uroot/InstallViewModel.kt").readText()
        assertFalse(source.contains("private fun preStageKernelSuForAutoLateLoad"))
        assertFalse(source.contains("remoteArtifactMatches"))
        assertTrue(source.contains("val stage = runHelper(\\\"-c\\\", kernelSuStageCommand(payloads))"))
        assertTrue(source.contains("runHelper(\\\"--late-load\\\")"))
        assertTrue(source.contains("KernelSuGlobalReadiness.command(bootToken)"))
    }
}
''', encoding='utf-8')

AUTO_TEST.write_text('''package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Zzi4PrestageVerificationCommandTest {
    @Test
    fun autoRootZzi4HotPathHasNoKsudVerificationOrWrite() {
        val source = File("src/main/java/dev/busung/s25uroot/AutoRootRunner.kt").readText()

        val shizukuStart = source.indexOf("private suspend fun executeExploit(")
        val localStart = source.indexOf("private suspend fun executeExploitViaLocalAdb", shizukuStart)
        val localEnd = source.indexOf("private fun shizukuStage", localStart)
        assertTrue(shizukuStart >= 0)
        assertTrue(localStart > shizukuStart)
        assertTrue(localEnd > localStart)

        val shizukuHotPath = source.substring(shizukuStart, localStart)
        val localHotPath = source.substring(localStart, localEnd)
        for (hotPath in listOf(shizukuHotPath, localHotPath)) {
            assertFalse(hotPath.contains("preStageZzi4KernelSu"))
            assertFalse(hotPath.contains("KSUD_PATH"))
            assertFalse(hotPath.contains("sha256sum"))
            assertFalse(hotPath.contains("wc -c"))
        }
        assertFalse(source.contains("zzi4KernelSuVerifyCommand"))
        assertFalse(source.contains("private fun preStageZzi4KernelSu"))
    }

    @Test
    fun autoRootKeepsPostRootStageAndExplicitLateLoadFallback() {
        val source = File("src/main/java/dev/busung/s25uroot/AutoRootRunner.kt").readText()
        val runStart = source.indexOf("suspend fun run(")
        val runEnd = source.indexOf("private suspend fun verifyKernelSu", runStart)
        val runBody = source.substring(runStart, runEnd)

        assertTrue(runBody.indexOf("executeExploit(") < runBody.indexOf("withKernelSuClient"))
        assertTrue(source.contains("stageKernelSuRequired(payloads, ksuExec)"))
        assertTrue(source.contains("ksuExec(arrayOf(\\\"--late-load\\\"))"))
        assertTrue(source.contains("verifyKernelSu(bootToken, ksuExec, postRootExec)"))
    }
}
''', encoding='utf-8')

print('ZZI4 pre-exploit KernelSU staging removed; obsolete pre-stage tests replaced')
