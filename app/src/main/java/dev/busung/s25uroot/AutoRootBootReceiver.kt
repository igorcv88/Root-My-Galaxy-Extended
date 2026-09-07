package dev.busung.s25uroot

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AutoRootBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Shizuku is a boot prerequisite/utility, not a post-root side effect.
        // Start its paired local-ADB bootstrap immediately on every framework
        // BOOT_COMPLETED, before any 60/120s Auto Root uptime gate. The service
        // itself is Binder-first and becomes a no-op when Shizuku is already up.
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
        if (intent.action != ACTION_DISABLE_AUTO_ROOT) return
        AppPreferences.setAutoRootEnabled(context, false)
        context.stopService(Intent(context, AutoRootExecutorService::class.java))
        context.stopService(Intent(context, AutoRootService::class.java))
        context.getSystemService(NotificationManager::class.java)
            .cancel(AUTO_ROOT_NOTIFICATION_ID)
    }

    companion object {
        const val ACTION_DISABLE_AUTO_ROOT =
            "dev.busung.s25uroot.action.DISABLE_AUTO_ROOT"
    }
}
