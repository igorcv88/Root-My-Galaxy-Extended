package dev.busung.s25uroot

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AutoRootBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Shizuku is a boot utility, not part of root acquisition. Start its
        // coordinator immediately on every framework BOOT_COMPLETED. After a
        // KernelSU soft/userspace reboot the same kernel root is still active,
        // so the service first tries an already-authorized root starter and can
        // skip Wireless ADB entirely. On a cold boot without root it falls back
        // to the existing Binder/Wi-Fi/ADB flow.
        runCatching { ShizukuBootService.startIfConfigured(context) }
            .onFailure { error ->
                Log.w(
                    TAG,
                    "Unable to launch early Shizuku bootstrap: ${error.message ?: error.javaClass.simpleName}",
                )
            }

        // Kernel boot_id changes only on a real kernel reboot. A userspace/zygote
        // soft reboot may re-emit BOOT_COMPLETED while keeping this token intact.
        // Consume each kernel boot event once and hard-stop stale Auto Root runtime
        // on duplicates instead of starting another foreground gate. This gate is
        // deliberately independent from the Shizuku bootstrap above.
        val bootToken = AutoRootSupport.currentBootToken() ?: return
        if (!AutoRootSupport.claimBootCompletedForKernel(context, bootToken)) {
            stopAutoRootRuntime(context)
            return
        }

        if (!AppPreferences.autoRootEnabled(context)) return

        // A previous Auto Root attempt in this same kernel boot also makes any
        // later framework BOOT_COMPLETED ineligible, including after an app update
        // where the new boot-event marker did not exist at the first boot event.
        if (AutoRootSupport.hasAttemptedBoot(context, bootToken)) {
            stopAutoRootRuntime(context)
            return
        }

        // Keep BOOT_COMPLETED deliberately tiny: no payload hashing, network or
        // file walking here. The foreground gate performs full cache validation.
        if (!AutoRootSupport.shouldRunForBoot(context, bootToken)) {
            stopAutoRootRuntime(context)
            return
        }

        context.startForegroundService(Intent(context, AutoRootService::class.java))
    }

    private fun stopAutoRootRuntime(context: Context) {
        context.stopService(Intent(context, AutoRootExecutorService::class.java))
        context.stopService(Intent(context, AutoRootService::class.java))
        context.getSystemService(NotificationManager::class.java)
            .cancel(AUTO_ROOT_NOTIFICATION_ID)
    }

    companion object {
        private const val TAG = "RootMyGalaxyBoot"
    }
}

class AutoRootActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DISABLE_AUTO_ROOT -> {
                AppPreferences.setAutoRootEnabled(context, false)
                context.stopService(Intent(context, AutoRootExecutorService::class.java))
                context.stopService(Intent(context, AutoRootService::class.java))
                context.getSystemService(NotificationManager::class.java)
                    .cancel(AUTO_ROOT_NOTIFICATION_ID)
            }

            ACTION_APPLY_MODULES_SOFT_REBOOT -> {
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        val result = RootRecoveryActions.kernelSuSoftReboot(
                            context.applicationContext,
                        )
                        if (result.accepted) {
                            context.getSystemService(NotificationManager::class.java)
                                .cancel(AUTO_ROOT_NOTIFICATION_ID)
                            Log.i(TAG, "User accepted KernelSU soft reboot from Auto Root notification")
                        } else {
                            Log.w(TAG, "Soft reboot notification action failed: ${result.detail}")
                        }
                    } catch (error: Throwable) {
                        Log.e(TAG, "Soft reboot notification action failed", error)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_DISABLE_AUTO_ROOT =
            "dev.busung.s25uroot.action.DISABLE_AUTO_ROOT"
        const val ACTION_APPLY_MODULES_SOFT_REBOOT =
            "dev.busung.s25uroot.action.APPLY_MODULES_SOFT_REBOOT"
        private const val TAG = "RootMyGalaxyAutoRootAction"
    }
}
