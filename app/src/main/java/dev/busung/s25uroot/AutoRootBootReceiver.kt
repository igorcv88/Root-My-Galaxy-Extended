package dev.busung.s25uroot

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AutoRootBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Kernel boot_id changes only on a real kernel reboot. A userspace/zygote
        // soft reboot may re-emit BOOT_COMPLETED while keeping this token intact.
        // Consume each kernel boot event once and hard-stop stale Auto Root runtime
        // on duplicates instead of starting another foreground gate.
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
