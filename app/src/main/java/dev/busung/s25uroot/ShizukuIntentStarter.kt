package dev.busung.s25uroot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Optional compatibility fallback for the automation interface exposed by the
 * user's thedjchi/Shizuku fork. The token is never logged and the broadcast is
 * package-scoped so it cannot leak to unrelated receivers.
 */
internal object ShizukuIntentStarter {
    internal data class Outcome(
        val started: Boolean,
        val attempted: Boolean,
        val detail: String = "",
    )

    suspend fun start(
        context: Context,
        binderTimeoutMillis: Long,
        onLog: (String) -> Unit = {},
    ): Outcome = ShizukuStartCoordinator.withStartLock(context) {
        if (ShizukuController.pingUntilRunning(BINDER_RACE_PROBE_MILLIS)) {
            onLog("[+] Shizuku Binder appeared before Intent fallback; skipped broadcast")
            return@withStartLock Outcome(started = true, attempted = false)
        }

        val token = AppPreferences.shizukuAutomationToken(context).trim()
        if (token.isBlank()) {
            onLog("[*] Shizuku authenticated Intent fallback is not configured; skipped")
            return@withStartLock Outcome(
                started = false,
                attempted = false,
                detail = "Shizuku automation auth token is not configured",
            )
        }

        val intent = Intent(START_ACTION)
            .setPackage(SHIZUKU_PACKAGE)
            .putExtra(AUTH_EXTRA, token)

        val receiverAvailable = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.queryBroadcastReceivers(intent, 0).isNotEmpty()
        }.getOrDefault(false)
        if (!receiverAvailable) {
            val detail = "Shizuku automation START receiver is unavailable"
            onLog("[!] $detail")
            return@withStartLock Outcome(started = false, attempted = false, detail = detail)
        }

        return@withStartLock try {
            context.sendBroadcast(intent)
            onLog("[*] Shizuku startup fallback selected: authenticated-intent")
            val running = ShizukuController.pingUntilRunning(binderTimeoutMillis)
            if (running) {
                onLog("[+] Shizuku authenticated Intent fallback produced a Binder")
                Outcome(started = true, attempted = true)
            } else {
                val detail = "authenticated Shizuku START broadcast sent but Binder did not appear"
                onLog("[!] $detail")
                Outcome(started = false, attempted = true, detail = detail)
            }
        } catch (error: Throwable) {
            val detail = error.message ?: error.javaClass.simpleName
            onLog("[!] Shizuku authenticated Intent fallback failed: $detail")
            Outcome(started = false, attempted = true, detail = detail)
        }
    }

    /**
     * Best-effort coexistence hint. The fork keeps its BOOT_COMPLETED receiver
     * disabled in the manifest and enables it when the user turns on its own
     * "Start on boot" option.
     */
    fun isShizukuBootReceiverEnabled(context: Context): Boolean {
        val component = ComponentName(SHIZUKU_PACKAGE, SHIZUKU_BOOT_RECEIVER_CLASS)
        val pm = context.packageManager
        return runCatching {
            when (pm.getComponentEnabledSetting(component)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
                else -> {
                    @Suppress("DEPRECATION")
                    pm.getReceiverInfo(component, 0).enabled
                }
            }
        }.getOrDefault(false)
    }

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val START_ACTION = "$SHIZUKU_PACKAGE.START"
    private const val AUTH_EXTRA = "auth"
    private const val SHIZUKU_BOOT_RECEIVER_CLASS =
        "moe.shizuku.manager.receiver.BootCompleteReceiver"
    private const val BINDER_RACE_PROBE_MILLIS = 500L
}
