package dev.busung.s25uroot

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AutoRootBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val bootCompletedElapsedRealtime = SystemClock.elapsedRealtime()

        // Kernel boot_id changes only on a real kernel reboot. A userspace/zygote
        // soft reboot may re-emit BOOT_COMPLETED while keeping this token intact.
        // On duplicate framework boot events Auto Root must stay stopped, while the
        // user's independent Shizuku-on-boot preference remains free to run.
        val bootToken = AutoRootSupport.currentBootToken()
        if (bootToken == null) {
            launchBootShizuku(context, autoRootPriority = false)
            return
        }
        if (!AutoRootSupport.claimBootCompletedForKernel(context, bootToken)) {
            launchBootShizuku(context, autoRootPriority = false)
            stopAutoRootRuntime(context)
            return
        }

        val autoRootEnabled = AppPreferences.autoRootEnabled(context)
        val alreadyAttempted = AutoRootSupport.hasAttemptedBoot(context, bootToken)
        val autoRootEligible = autoRootEnabled &&
            !alreadyAttempted &&
            AutoRootSupport.shouldRunForBoot(context, bootToken)
        val autoRootOwnsShizuku = autoRootEligible &&
            AppPreferences.shizukuBootRequiredByAutoRoot(context)

        // Exactly one boot coordinator owns the startup request. If this Auto Root
        // run needs a shell transport, its priority bootstrap supersedes the visible
        // Start Shizuku on boot preference for this boot only. Otherwise the user's
        // independent preference is honored. The stored preference is never changed.
        launchBootShizuku(context, autoRootPriority = autoRootOwnsShizuku)

        if (!autoRootEnabled) return

        // A previous Auto Root attempt in this same kernel boot also makes any
        // later framework BOOT_COMPLETED ineligible, including after an app update
        // where the new boot-event marker did not exist at the first boot event.
        if (alreadyAttempted) {
            stopAutoRootRuntime(context)
            return
        }

        // Keep BOOT_COMPLETED deliberately tiny: no network or file walking here.
        // The foreground gate performs full payload/cache validation. The lightweight
        // route-policy read above only decides which Shizuku coordinator owns boot.
        if (!autoRootEligible) {
            stopAutoRootRuntime(context)
            return
        }

        context.startForegroundService(
            Intent(context, AutoRootService::class.java)
                .putExtra(
                    AutoRootService.EXTRA_BOOT_COMPLETED_ELAPSED_REALTIME,
                    bootCompletedElapsedRealtime,
                ),
        )
    }

    private fun launchBootShizuku(context: Context, autoRootPriority: Boolean) {
        runCatching {
            if (autoRootPriority) {
                ShizukuBootService.startForAutoRoot(context)
            } else {
                ShizukuBootService.startIfConfigured(context)
            }
        }.onFailure { error ->
            Log.w(
                TAG,
                if (autoRootPriority) {
                    "Unable to launch Auto Root priority Shizuku bootstrap: ${error.message ?: error.javaClass.simpleName}"
                } else {
                    "Unable to launch configured Shizuku bootstrap: ${error.message ?: error.javaClass.simpleName}"
                },
            )
        }
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
