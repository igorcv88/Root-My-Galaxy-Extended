from pathlib import Path
import re


def read(path):
    return Path(path).read_text(encoding="utf-8")


def write(path, text):
    Path(path).write_text(text, encoding="utf-8")


def replace_once(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exact block once, found {count}")
    write(path, text.replace(old, new, 1))


def regex_once(path, pattern, replacement):
    text = read(path)
    new_text, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{path}: regex expected once, found {count}: {pattern[:80]}")
    write(path, new_text)


# 1) ZZI4 readiness: Android 17 ZZI4 toybox has no awk applet. Process-name
# checks need only ps+grep and must not depend on an optional toybox command.
p = "app/src/main/java/dev/busung/s25uroot/Zzi4PostRootRuntime.kt"
text = read(p)
text, n1 = re.subn(
    r'''            zn_ready\(\) \{\n.*?            \}\n            module_ready\(\) \{''',
    '''            zn_ready() {\n                /system/bin/ps -A -o NAME 2>/dev/null | /system/bin/grep -Eq \\\n                    '^[[:space:]]*zn-daemon[[:space:]]*$'\n            }\n            module_ready() {''',
    text,
    count=1,
    flags=re.S,
)
text, n2 = re.subn(
    r'''            lspd_ready\(\) \{\n.*?            \}\n\n            \[ "\$\{''',
    '''            lspd_ready() {\n                /system/bin/ps -A -o NAME 2>/dev/null | /system/bin/grep -Eq \\\n                    '^[[:space:]]*(lspd(:.*)?|LSPosed)[[:space:]]*$'\n            }\n\n            [ "${''',
    text,
    count=1,
    flags=re.S,
)
if n1 != 1 or n2 != 1:
    raise SystemExit(f"{p}: readiness function replacement failed zn={n1} lspd={n2}")
write(p, text)

# 2) Soft-reboot keeper: remove the remaining awk dependency from the boot marker.
p = "app/src/main/java/dev/busung/s25uroot/PostRootModuleKeeper.kt"
replace_once(
    p,
    '''        marker_boot() {\n            [ -f "${'$'}DONE" ] || return 1\n            awk 'NR == 1 { print ${'$'}1; exit }' "${'$'}DONE" 2>/dev/null\n        }\n''',
    '''        marker_boot() {\n            [ -f "${'$'}DONE" ] || return 1\n            IFS=' ' read -r MARKER_BOOT _ < "${'$'}DONE" 2>/dev/null || return 1\n            printf '%s\\n' "${'$'}MARKER_BOOT"\n        }\n''',
)

# 3) Manual install: restore the original semantics of the persisted option.
# A user-initiated manual root may automatically hand off to KernelSU soft-reboot;
# it no longer tries to make a Zygote-only restart stand in for the full module lifecycle.
p = "app/src/main/java/dev/busung/s25uroot/InstallViewModel.kt"
regex_once(
    p,
    r'''                val restartZygote = AppPreferences\.restartZygoteAfterRoot\(app\).*?\n\n                finishHistory\(InstallRunResult\.Succeeded\)''',
    '''                val softRebootAfterRoot = AppPreferences.restartZygoteAfterRoot(app)\n                val startShizuku = AppPreferences.autoStartShizukuAfterRoot(app)\n                if (softRebootAfterRoot || startShizuku) {\n                    if (softRebootAfterRoot) {\n                        mutableState.value = mutableState.value.copy(\n                            message = app.getString(R.string.zygote_restart_starting),\n                        )\n                    }\n                    try {\n                        val postRoot = PostRootAutomation.run(\n                            context = app,\n                            softReboot = softRebootAfterRoot,\n                            startShizuku = startShizuku,\n                            prepareZzi4Modules = false,\n                            onLog = ::appendLog,\n                        )\n                        if (startShizuku && !postRoot.shizukuStarted && postRoot.detail.isNotBlank()) {\n                            appendLog("[!] Post-root Shizuku automation: ${postRoot.detail.take(200)}")\n                        }\n                        if (softRebootAfterRoot) {\n                            if (postRoot.softRebootStarted) {\n                                appendLog("[+] KernelSU native soft reboot accepted; module lifecycle will restart")\n                                finishHistory(InstallRunResult.Succeeded)\n                                return@launch\n                            }\n                            appendLog(\n                                "[!] Root succeeded, but KernelSU soft reboot was not accepted: " +\n                                    postRoot.detail.take(200),\n                            )\n                        }\n                    } catch (error: Throwable) {\n                        appendLog(\n                            "[!] Post-root automation failed: " +\n                                (error.message ?: error.javaClass.simpleName),\n                        )\n                    }\n                }\n\n                finishHistory(InstallRunResult.Succeeded)''',
)

# 4) Auto Root: root restoration is terminal. Never restart Zygote or soft-reboot
# automatically. On ZZI4, offer the user a notification action instead.
p = "app/src/main/java/dev/busung/s25uroot/AutoRootExecutorService.kt"
regex_once(
    p,
    r'''            // Persist verified root before any userspace restart\..*?            finishWithResult\(getString\(R\.string\.autoroot_root_restored\)\)''',
    '''            // Root restoration is the terminal automatic action. A userspace\n            // restart is disruptive and must remain explicitly user initiated.\n            finishHistory(InstallRunResult.Succeeded)\n\n            val startShizuku = AppPreferences.autoStartShizukuAfterRoot(this)\n            if (startShizuku) {\n                try {\n                    val postRoot = PostRootAutomation.run(\n                        context = this,\n                        softReboot = false,\n                        startShizuku = true,\n                        prepareZzi4Modules = false,\n                        onLog = { appendHistory(it) },\n                    )\n                    if (!postRoot.shizukuStarted && postRoot.detail.isNotBlank()) {\n                        appendHistory("[!] Post-root Shizuku automation: ${postRoot.detail.take(200)}")\n                    }\n                } catch (error: Throwable) {\n                    val detail = error.message ?: error.javaClass.simpleName\n                    appendHistory("[!] Post-root Shizuku automation failed: $detail")\n                    Log.w(TAG, "Post-root Shizuku automation failed after verified root", error)\n                }\n            }\n\n            val offerSoftReboot =\n                payloads.profile.profileId == Zzi4PostRootRuntime.PROFILE_ID\n            if (offerSoftReboot) {\n                appendHistory(\n                    "[*] ZZI4 root restored; KernelSU soft reboot left to the user notification action",\n                )\n            }\n            finishWithResult(\n                message = if (offerSoftReboot) {\n                    getString(R.string.autoroot_root_restored_modules_pending)\n                } else {\n                    getString(R.string.autoroot_root_restored)\n                },\n                offerSoftReboot = offerSoftReboot,\n            )''',
)
replace_once(
    p,
    '''    private fun finishWithResult(message: String) {\n        val delivered = deliverGateResult(message, removeNotification = false)\n        if (!delivered) {\n            getSystemService(NotificationManager::class.java).notify(\n                AUTO_ROOT_NOTIFICATION_ID,\n                buildNotification(message, ongoing = false),\n            )\n            stopService(Intent(this, AutoRootService::class.java))\n        }\n        stopSelf()\n    }\n''',
    '''    private fun finishWithResult(message: String, offerSoftReboot: Boolean = false) {\n        val delivered = deliverGateResult(\n            message = message,\n            removeNotification = false,\n            offerSoftReboot = offerSoftReboot,\n        )\n        if (!delivered) {\n            getSystemService(NotificationManager::class.java).notify(\n                AUTO_ROOT_NOTIFICATION_ID,\n                buildNotification(\n                    message = message,\n                    ongoing = false,\n                    offerSoftReboot = offerSoftReboot,\n                ),\n            )\n            stopService(Intent(this, AutoRootService::class.java))\n        }\n        stopSelf()\n    }\n''',
)
replace_once(
    p,
    '''    private fun deliverGateResult(message: String?, removeNotification: Boolean): Boolean =\n        runCatching {\n            startService(\n                Intent(this, AutoRootService::class.java)\n                    .setAction(AutoRootService.ACTION_EXECUTOR_RESULT)\n                    .putExtra(AutoRootService.EXTRA_RESULT_MESSAGE, message)\n                    .putExtra(AutoRootService.EXTRA_REMOVE_NOTIFICATION, removeNotification),\n            ) != null\n        }.onFailure { error ->\n            Log.e(TAG, "Unable to deliver executor result to foreground gate", error)\n        }.getOrDefault(false)\n''',
    '''    private fun deliverGateResult(\n        message: String?,\n        removeNotification: Boolean,\n        offerSoftReboot: Boolean = false,\n    ): Boolean = runCatching {\n        startService(\n            Intent(this, AutoRootService::class.java)\n                .setAction(AutoRootService.ACTION_EXECUTOR_RESULT)\n                .putExtra(AutoRootService.EXTRA_RESULT_MESSAGE, message)\n                .putExtra(AutoRootService.EXTRA_REMOVE_NOTIFICATION, removeNotification)\n                .putExtra(AutoRootService.EXTRA_OFFER_SOFT_REBOOT, offerSoftReboot),\n        ) != null\n    }.onFailure { error ->\n        Log.e(TAG, "Unable to deliver executor result to foreground gate", error)\n    }.getOrDefault(false)\n''',
)
# Refactor executor fallback notification builder to optionally expose the same action.
regex_once(
    p,
    r'''    private fun buildNotification\(message: String, ongoing: Boolean\) =\n        NotificationCompat\.Builder\(this, AUTO_ROOT_CHANNEL_ID\).*?            \.build\(\)\n''',
    '''    private fun buildNotification(\n        message: String,\n        ongoing: Boolean,\n        offerSoftReboot: Boolean = false,\n    ): android.app.Notification {\n        val builder = NotificationCompat.Builder(this, AUTO_ROOT_CHANNEL_ID)\n            .setSmallIcon(R.drawable.ic_app_logo)\n            .setContentTitle(getString(R.string.autoroot_notification_title))\n            .setContentText(message)\n            .setContentIntent(\n                PendingIntent.getActivity(\n                    this,\n                    0,\n                    Intent(this, MainActivity::class.java),\n                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,\n                ),\n            )\n            .setOnlyAlertOnce(true)\n            .setOngoing(ongoing)\n            .setAutoCancel(!ongoing)\n            .setPriority(NotificationCompat.PRIORITY_LOW)\n            .addAction(\n                0,\n                getString(R.string.autoroot_disable),\n                PendingIntent.getBroadcast(\n                    this,\n                    1,\n                    Intent(this, AutoRootActionReceiver::class.java)\n                        .setAction(AutoRootActionReceiver.ACTION_DISABLE_AUTO_ROOT),\n                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,\n                ),\n            )\n        if (offerSoftReboot) {\n            builder.addAction(\n                0,\n                getString(R.string.autoroot_apply_modules),\n                PendingIntent.getBroadcast(\n                    this,\n                    2,\n                    Intent(this, AutoRootActionReceiver::class.java)\n                        .setAction(AutoRootActionReceiver.ACTION_APPLY_MODULES_SOFT_REBOOT),\n                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,\n                ),\n            )\n        }\n        return builder.build()\n    }\n''',
)

# 5) Gate service preserves the user-action flag in the terminal notification.
p = "app/src/main/java/dev/busung/s25uroot/AutoRootService.kt"
replace_once(
    p,
    '''            val removeNotification = intent.getBooleanExtra(EXTRA_REMOVE_NOTIFICATION, false)\n            val message = intent.getStringExtra(EXTRA_RESULT_MESSAGE)\n            if (removeNotification) {\n                stopWithoutResult()\n            } else {\n                finishWithResult(\n                    message ?: getString(R.string.autoroot_failed, "executor returned no result"),\n                )\n            }\n''',
    '''            val removeNotification = intent.getBooleanExtra(EXTRA_REMOVE_NOTIFICATION, false)\n            val offerSoftReboot = intent.getBooleanExtra(EXTRA_OFFER_SOFT_REBOOT, false)\n            val message = intent.getStringExtra(EXTRA_RESULT_MESSAGE)\n            if (removeNotification) {\n                stopWithoutResult()\n            } else {\n                finishWithResult(\n                    message = message ?: getString(\n                        R.string.autoroot_failed,\n                        "executor returned no result",\n                    ),\n                    offerSoftReboot = offerSoftReboot,\n                )\n            }\n''',
)
replace_once(
    p,
    '''    private fun finishWithResult(message: String) {\n        if (shuttingDown) return\n        shuttingDown = true\n        getSystemService(NotificationManager::class.java).notify(\n            AUTO_ROOT_NOTIFICATION_ID,\n            buildNotification(message, ongoing = false),\n        )\n        stopForeground(STOP_FOREGROUND_DETACH)\n        stopSelf()\n    }\n''',
    '''    private fun finishWithResult(message: String, offerSoftReboot: Boolean = false) {\n        if (shuttingDown) return\n        shuttingDown = true\n        getSystemService(NotificationManager::class.java).notify(\n            AUTO_ROOT_NOTIFICATION_ID,\n            buildNotification(\n                message = message,\n                ongoing = false,\n                offerSoftReboot = offerSoftReboot,\n            ),\n        )\n        stopForeground(STOP_FOREGROUND_DETACH)\n        stopSelf()\n    }\n''',
)
regex_once(
    p,
    r'''    private fun buildNotification\(message: String, ongoing: Boolean\) =\n        NotificationCompat\.Builder\(this, AUTO_ROOT_CHANNEL_ID\).*?            \.build\(\)\n''',
    '''    private fun buildNotification(\n        message: String,\n        ongoing: Boolean,\n        offerSoftReboot: Boolean = false,\n    ): android.app.Notification {\n        val builder = NotificationCompat.Builder(this, AUTO_ROOT_CHANNEL_ID)\n            .setSmallIcon(R.drawable.ic_app_logo)\n            .setContentTitle(getString(R.string.autoroot_notification_title))\n            .setContentText(message)\n            .setContentIntent(\n                PendingIntent.getActivity(\n                    this,\n                    0,\n                    Intent(this, MainActivity::class.java),\n                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,\n                ),\n            )\n            .setOnlyAlertOnce(true)\n            .setOngoing(ongoing)\n            .setAutoCancel(!ongoing)\n            .setPriority(NotificationCompat.PRIORITY_LOW)\n            .addAction(\n                0,\n                getString(R.string.autoroot_disable),\n                PendingIntent.getBroadcast(\n                    this,\n                    1,\n                    Intent(this, AutoRootActionReceiver::class.java)\n                        .setAction(AutoRootActionReceiver.ACTION_DISABLE_AUTO_ROOT),\n                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,\n                ),\n            )\n        if (offerSoftReboot) {\n            builder.addAction(\n                0,\n                getString(R.string.autoroot_apply_modules),\n                PendingIntent.getBroadcast(\n                    this,\n                    2,\n                    Intent(this, AutoRootActionReceiver::class.java)\n                        .setAction(AutoRootActionReceiver.ACTION_APPLY_MODULES_SOFT_REBOOT),\n                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,\n                ),\n            )\n        }\n        return builder.build()\n    }\n''',
)
replace_once(
    p,
    '''        const val EXTRA_RESULT_MESSAGE = "executor_result_message"\n        const val EXTRA_REMOVE_NOTIFICATION = "executor_remove_notification"\n''',
    '''        const val EXTRA_RESULT_MESSAGE = "executor_result_message"\n        const val EXTRA_REMOVE_NOTIFICATION = "executor_remove_notification"\n        const val EXTRA_OFFER_SOFT_REBOOT = "executor_offer_soft_reboot"\n''',
)

# 6) Notification action: user explicitly requests the disruptive userspace restart.
p = "app/src/main/java/dev/busung/s25uroot/AutoRootBootReceiver.kt"
text = read(p)
text = text.replace(
    'import android.util.Log\n',
    'import android.util.Log\nimport kotlinx.coroutines.CoroutineScope\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.SupervisorJob\nimport kotlinx.coroutines.launch\n',
    1,
)
regex_once(
    p,
    r'''class AutoRootActionReceiver : BroadcastReceiver\(\) \{.*?\n\}\n\Z''',
    '''class AutoRootActionReceiver : BroadcastReceiver() {\n    override fun onReceive(context: Context, intent: Intent) {\n        when (intent.action) {\n            ACTION_DISABLE_AUTO_ROOT -> {\n                AppPreferences.setAutoRootEnabled(context, false)\n                context.stopService(Intent(context, AutoRootExecutorService::class.java))\n                context.stopService(Intent(context, AutoRootService::class.java))\n                context.getSystemService(NotificationManager::class.java)\n                    .cancel(AUTO_ROOT_NOTIFICATION_ID)\n            }\n\n            ACTION_APPLY_MODULES_SOFT_REBOOT -> {\n                val pending = goAsync()\n                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {\n                    try {\n                        val result = RootRecoveryActions.kernelSuSoftReboot(\n                            context.applicationContext,\n                        )\n                        if (result.accepted) {\n                            context.getSystemService(NotificationManager::class.java)\n                                .cancel(AUTO_ROOT_NOTIFICATION_ID)\n                            Log.i(TAG, "User accepted KernelSU soft reboot from Auto Root notification")\n                        } else {\n                            Log.w(TAG, "Soft reboot notification action failed: ${result.detail}")\n                        }\n                    } catch (error: Throwable) {\n                        Log.e(TAG, "Soft reboot notification action failed", error)\n                    } finally {\n                        pending.finish()\n                    }\n                }\n            }\n        }\n    }\n\n    companion object {\n        const val ACTION_DISABLE_AUTO_ROOT =\n            "dev.busung.s25uroot.action.DISABLE_AUTO_ROOT"\n        const val ACTION_APPLY_MODULES_SOFT_REBOOT =\n            "dev.busung.s25uroot.action.APPLY_MODULES_SOFT_REBOOT"\n        private const val TAG = "RootMyGalaxyAutoRootAction"\n    }\n}\n''',
)

# 7) Settings: Advanced Mode controls only manual payload selection. Recovery tools
# are a sibling inside the Advanced section and are always rendered independently.
p = "app/src/main/java/dev/busung/s25uroot/MainActivity.kt"
replace_once(
    p,
    '''        if (advancedMode) {\n            item {\n                AdvancedRecoverySettings(\n                    rootActive = installState.phase == InstallPhase.Installed,\n                    autoRootEnabled = autoRootEnabled,\n                    onAutoRootEnabledChanged = onAutoRootEnabledChanged,\n                )\n            }\n        }\n''',
    '''        item {\n            AdvancedRecoverySettings(\n                rootActive = installState.phase == InstallPhase.Installed,\n                autoRootEnabled = autoRootEnabled,\n                onAutoRootEnabledChanged = onAutoRootEnabledChanged,\n            )\n        }\n''',
)

# 8) Preference comment: same persisted key, restored to its original soft-reboot semantics.
p = "app/src/main/java/dev/busung/s25uroot/AppPreferences.kt"
replace_once(
    p,
    '''    // Keep the legacy storage key so existing users who enabled the old automatic\n    // KernelSU soft reboot retain the opt-in when its behavior becomes the lighter\n    // post-root Zygote restart.\n''',
    '''    // Keep the original storage key. It again means the user opted into a\n    // post-root KernelSU soft reboot for manual installs. Auto Root never consumes\n    // this preference automatically; it offers soft reboot as a notification action.\n''',
)

# 9) Strings: reflect real behavior and expose the explicit Auto Root action.
for path, lang in [
    ("app/src/main/res/values/strings_recovery.xml", "en"),
    ("app/src/main/res/values-pt-rBR/strings_recovery.xml", "pt"),
]:
    text = read(path)
    if lang == "en":
        repl = {
            'Restart Zygote after root': 'KernelSU soft reboot after root',
            'After KernelSU is verified, recreate Zygote and system_server so Zygisk and LSPosed can attach without a full KernelSU userspace reboot.': 'After KernelSU is verified, run its native userspace soft reboot so modules, Zygisk, and LSPosed start in the normal lifecycle order.',
            'Restarting Zygote after root': 'Starting KernelSU soft reboot after root',
            'Root succeeded, but Zygote restart did not start: %1$s': 'Root succeeded, but KernelSU soft reboot did not start: %1$s',
            'Recreates Zygote and system_server without restarting the kernel. Use when Zygisk, LSPosed, or app hooks did not attach after root.': 'Recreates only Zygote and system_server. Useful as a lightweight diagnostic; on ZZI4 prefer KernelSU soft reboot when Zygisk or LSPosed did not attach.',
            "Runs KernelSU\\'s native userspace restart and module lifecycle. Use when several modules or mounts need a clean userspace reload.": "Runs KernelSU\\'s native userspace restart and full module lifecycle. Preferred on ZZI4 when Zygisk or LSPosed needs a clean DEFEX-compatible startup.",
        }
    else:
        repl = {
            'Reiniciar Zygote após root': 'Soft reboot do KernelSU após root',
            'Depois que o KernelSU for verificado, recria o Zygote e o system_server para que Zygisk e LSPosed possam se conectar sem um reboot completo do userspace do KernelSU.': 'Depois que o KernelSU for verificado, executa o soft reboot nativo do userspace para iniciar módulos, Zygisk e LSPosed na ordem normal do ciclo de vida.',
            'Reiniciando Zygote após root': 'Iniciando soft reboot do KernelSU após root',
            'O root funcionou, mas o reinício do Zygote não iniciou: %1$s': 'O root funcionou, mas o soft reboot do KernelSU não iniciou: %1$s',
            'Recria o Zygote e o system_server sem reiniciar o kernel. Use quando Zygisk, LSPosed ou hooks de apps não se conectarem após o root.': 'Recria apenas o Zygote e o system_server. Serve como diagnóstico leve; no ZZI4, prefira o soft reboot do KernelSU quando Zygisk ou LSPosed não iniciar.',
            'Executa o reinício nativo do userspace e o ciclo de vida dos módulos do KernelSU. Use quando vários módulos ou mounts precisarem de uma recarga limpa do userspace.': 'Executa o reinício nativo do userspace e o ciclo de vida completo dos módulos do KernelSU. É a opção preferida no ZZI4 quando Zygisk ou LSPosed precisa iniciar de forma limpa com o DEFEX corrigido.',
        }
    for old, new in repl.items():
        if old not in text:
            raise SystemExit(f"{path}: missing string text: {old}")
        text = text.replace(old, new, 1)
    write(path, text)

for path, lang in [
    ("app/src/main/res/values/strings_autoroot.xml", "en"),
    ("app/src/main/res/values-pt-rBR/strings_autoroot.xml", "pt"),
]:
    text = read(path)
    anchor = '<string name="autoroot_root_restored">Root restored</string>' if lang == "en" else '<string name="autoroot_root_restored">Root restaurado</string>'
    if anchor not in text:
        raise SystemExit(f"{path}: root-restored anchor missing")
    if lang == "en":
        insert = anchor + '\n    <string name="autoroot_root_restored_modules_pending">Root restored. Soft reboot is available when you want to apply Zygisk/LSPosed modules.</string>\n    <string name="autoroot_apply_modules">Apply modules</string>'
    else:
        insert = anchor + '\n    <string name="autoroot_root_restored_modules_pending">Root restaurado. Faça o soft reboot quando quiser para aplicar os módulos Zygisk/LSPosed.</string>\n    <string name="autoroot_apply_modules">Aplicar módulos</string>'
    text = text.replace(anchor, insert, 1)
    write(path, text)

# 10) Update source-contract tests for the new architecture.
p = "app/src/test/java/dev/busung/s25uroot/Zzi4PostRootRuntimeTest.kt"
text = read(p)
text = text.replace(
    '        assertFalse(command.contains("insmod"))\n',
    '        assertFalse(command.contains("insmod"))\n        assertFalse(command.contains("toybox awk"))\n',
    1,
)
text, n = re.subn(
    r'''    @Test\n    fun manualAndAutoRestartAreGatedBySameRuntimePreparation\(\) \{.*?\n    \}\n\n\n    @Test\n    fun zzi4ExploitHotPath''',
    '''    @Test\n    fun manualSoftRebootAndAutoRootNotificationAreSeparated() {\n        val manual = File("src/main/java/dev/busung/s25uroot/InstallViewModel.kt").readText()\n        val auto = File("src/main/java/dev/busung/s25uroot/AutoRootExecutorService.kt").readText()\n        val gate = File("src/main/java/dev/busung/s25uroot/AutoRootService.kt").readText()\n        val receiver = File("src/main/java/dev/busung/s25uroot/AutoRootBootReceiver.kt").readText()\n        val settings = File("src/main/java/dev/busung/s25uroot/MainActivity.kt").readText()\n        val keeper = File("src/main/java/dev/busung/s25uroot/PostRootModuleKeeper.kt").readText()\n\n        assertTrue(manual.contains("softReboot = softRebootAfterRoot"))\n        assertFalse(manual.contains("RootRecoveryActions.restartZygote(\\n                                app"))\n        assertTrue(auto.contains("offerSoftReboot ="))\n        assertTrue(auto.contains("Zzi4PostRootRuntime.PROFILE_ID"))\n        assertFalse(auto.contains("RootRecoveryActions.restartZygote("))\n        assertFalse(auto.contains("prepareZzi4Modules = requireZzi4Runtime"))\n        assertTrue(auto.contains("EXTRA_OFFER_SOFT_REBOOT"))\n        assertTrue(gate.contains("ACTION_APPLY_MODULES_SOFT_REBOOT"))\n        assertTrue(receiver.contains("RootRecoveryActions.kernelSuSoftReboot"))\n        assertTrue(receiver.contains("ACTION_APPLY_MODULES_SOFT_REBOOT"))\n        assertFalse(keeper.contains("awk 'NR == 1"))\n        assertTrue(settings.contains("item {\\n            AdvancedRecoverySettings("))\n        assertFalse(settings.contains("if (advancedMode) {\\n            item {\\n                AdvancedRecoverySettings("))\n    }\n\n\n    @Test\n    fun zzi4ExploitHotPath''',
    text,
    count=1,
    flags=re.S,
)
if n != 1:
    raise SystemExit(f"{p}: obsolete architecture test replacement failed count={n}")
write(p, text)

print("Applied ZZI4 post-root soft-reboot notification and settings separation patch")
