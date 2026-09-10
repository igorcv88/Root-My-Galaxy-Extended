from pathlib import Path

SRC = Path("app/src/main/java/dev/busung/s25uroot/InstallViewModel.kt")
TEST = Path("app/src/test/java/dev/busung/s25uroot/ManualShellTransportPolicyTest.kt")

text = SRC.read_text(encoding="utf-8")

old_choose = '''internal fun chooseManualRunTransport(
    shellRequired: Boolean,
    shizukuRequested: Boolean,
    shizukuUsable: Boolean,
    localAdbPaired: Boolean,
): ManualRunTransport? {
    if (shizukuRequested && shizukuUsable) return ManualRunTransport.Shizuku
    if (shellRequired) {
        return if (localAdbPaired) ManualRunTransport.LocalAdb else null
    }
    return if (shizukuRequested) null else ManualRunTransport.App
}
'''
new_choose = '''internal fun chooseManualRunTransport(
    shellRequired: Boolean,
    shizukuRequested: Boolean,
    shizukuUsable: Boolean,
    localAdbPaired: Boolean,
): ManualRunTransport? {
    if (shellRequired) {
        if (localAdbPaired) return ManualRunTransport.LocalAdb
        return if (shizukuRequested && shizukuUsable) ManualRunTransport.Shizuku else null
    }
    if (shizukuRequested && shizukuUsable) return ManualRunTransport.Shizuku
    return if (shizukuRequested) null else ManualRunTransport.App
}
'''
if text.count(old_choose) != 1:
    raise SystemExit("chooseManualRunTransport block changed")
text = text.replace(old_choose, new_choose, 1)

old_select = '''    private suspend fun selectRunTransport(profile: TargetProfile): ManualRunTransport {
        val requestedShizuku = AppPreferences.shizukuMode(app)
        var shizukuUsable = false
        if (requestedShizuku) {
            appendLog(app.getString(R.string.log_shizuku_prepare))
            val running = ShizukuController.isRunning() || ShizukuController.pingUntilRunning()
            if (running) {
                shizukuUsable = ShizukuController.isGranted() || ShizukuController.requestPermission()
            }
            if (shizukuUsable) {
                appendLog(app.getString(R.string.log_shizuku_permission))
            } else if (profile.routePolicy.prefersShellTransport) {
                appendLog("[!] Shizuku is unavailable; using paired local ADB for this shell-required target")
            }
        }

        return chooseManualRunTransport(
            shellRequired = profile.routePolicy.prefersShellTransport,
            shizukuRequested = requestedShizuku,
            shizukuUsable = shizukuUsable,
            localAdbPaired = AppPreferences.adbPaired(app),
        ) ?: if (profile.routePolicy.prefersShellTransport) {
            error("This target requires shell transport, but neither Shizuku nor the paired local ADB key is usable")
        } else {
            error(app.getString(R.string.error_shizuku_unavailable))
        }
    }
'''
new_select = '''    private suspend fun selectRunTransport(profile: TargetProfile): ManualRunTransport {
        val shellRequired = profile.routePolicy.prefersShellTransport
        val localAdbPaired = AppPreferences.adbPaired(app)

        // ZZI4 was hardware-validated through the paired Local ADB shell. Keep
        // that launch path deterministic even when a Shizuku binder happens to
        // be alive after boot; the exploit is scheduler-sensitive despite both
        // transports reporting uid=2000 / u:r:shell:s0.
        if (profile.profileId == "pa3q-S938BXXUCZZI4" && shellRequired && localAdbPaired) {
            appendLog("[*] ZZI4 Manual transport pinned to paired Local ADB shell")
            return ManualRunTransport.LocalAdb
        }

        val requestedShizuku = AppPreferences.shizukuMode(app)
        var shizukuUsable = false
        if (requestedShizuku) {
            appendLog(app.getString(R.string.log_shizuku_prepare))
            val running = ShizukuController.isRunning() || ShizukuController.pingUntilRunning()
            if (running) {
                shizukuUsable = ShizukuController.isGranted() || ShizukuController.requestPermission()
            }
            if (shizukuUsable) {
                appendLog(app.getString(R.string.log_shizuku_permission))
            } else if (shellRequired) {
                appendLog("[!] Shizuku is unavailable; using paired local ADB for this shell-required target")
            }
        }

        return chooseManualRunTransport(
            shellRequired = shellRequired,
            shizukuRequested = requestedShizuku,
            shizukuUsable = shizukuUsable,
            localAdbPaired = localAdbPaired,
        ) ?: if (shellRequired) {
            error("This target requires shell transport, but neither Shizuku nor the paired local ADB key is usable")
        } else {
            error(app.getString(R.string.error_shizuku_unavailable))
        }
    }
'''
if text.count(old_select) != 1:
    raise SystemExit("selectRunTransport block changed")
text = text.replace(old_select, new_select, 1)
SRC.write_text(text, encoding="utf-8")

TEST.write_text('''package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualShellTransportPolicyTest {
    @Test
    fun shellRequiredStandaloneUsesPairedLocalAdb() {
        assertEquals(
            ManualRunTransport.LocalAdb,
            chooseManualRunTransport(
                shellRequired = true,
                shizukuRequested = false,
                shizukuUsable = false,
                localAdbPaired = true,
            ),
        )
    }

    @Test
    fun shellRequiredPairedLocalAdbWinsOverUsableShizuku() {
        assertEquals(
            ManualRunTransport.LocalAdb,
            chooseManualRunTransport(
                shellRequired = true,
                shizukuRequested = true,
                shizukuUsable = true,
                localAdbPaired = true,
            ),
        )
    }

    @Test
    fun shellRequiredUsesShizukuOnlyWhenLocalAdbUnavailable() {
        assertEquals(
            ManualRunTransport.Shizuku,
            chooseManualRunTransport(
                shellRequired = true,
                shizukuRequested = true,
                shizukuUsable = true,
                localAdbPaired = false,
            ),
        )
    }

    @Test
    fun shellRequiredNeverFallsBackToAppDomain() {
        assertNull(
            chooseManualRunTransport(
                shellRequired = true,
                shizukuRequested = false,
                shizukuUsable = false,
                localAdbPaired = false,
            ),
        )
    }

    @Test
    fun legacyRequestedShizukuKeepsShizukuTransport() {
        assertEquals(
            ManualRunTransport.Shizuku,
            chooseManualRunTransport(
                shellRequired = false,
                shizukuRequested = true,
                shizukuUsable = true,
                localAdbPaired = true,
            ),
        )
    }

    @Test
    fun legacyStandaloneKeepsAppTransport() {
        assertEquals(
            ManualRunTransport.App,
            chooseManualRunTransport(
                shellRequired = false,
                shizukuRequested = false,
                shizukuUsable = false,
                localAdbPaired = true,
            ),
        )
    }
}
''', encoding="utf-8")

print("ZZI4 Manual Local ADB priority patch applied")
