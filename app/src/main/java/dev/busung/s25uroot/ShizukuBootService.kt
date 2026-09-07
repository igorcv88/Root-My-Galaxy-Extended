package dev.busung.s25uroot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Restores Shizuku immediately after Android BOOT_COMPLETED, independently of
 * the later Auto Root exploit gate.
 *
 * This is intentionally a pre-root path. Root My Galaxy reuses its one-time
 * Wireless ADB pairing/key (the same boot transport pattern used by the
 * HyperRamzey implementation), prefers the current Shizuku native starter, and
 * keeps the documented legacy start.sh as a compatibility fallback. Auto Root
 * may still use post-root startup as recovery, but normal boot never waits for
 * the 60/120-second root gate.
 */
class ShizukuBootService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bootstrapJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AppPreferences.autoStartShizukuAfterRoot(this) || !AppPreferences.adbPaired(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (bootstrapJob?.isActive == true) return START_NOT_STICKY

        val notification = buildNotification(getString(R.string.log_shizuku_prepare))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                SHIZUKU_BOOT_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(SHIZUKU_BOOT_NOTIFICATION_ID, notification)
        }

        bootstrapJob = scope.launch {
            try {
                bootstrap()
            } catch (error: Throwable) {
                Log.w(TAG, "Early Shizuku bootstrap failed", error)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun bootstrap() {
        // Binder-first: BOOT_COMPLETED may be re-emitted after a userspace/zygote
        // restart. Never restart a Shizuku server that is already alive.
        if (ShizukuController.pingUntilRunning(BINDER_INITIAL_PROBE_MILLIS)) {
            Log.i(TAG, "Shizuku Binder already available at boot; no starter selected")
            return
        }

        val wirelessWasEnabled = AdbPairing.isWirelessAdbEnabled(this)
        if (!wirelessWasEnabled) {
            if (!AdbPairing.enableWirelessAdb(this)) {
                Log.w(TAG, "Cannot enable Wireless ADB for early Shizuku bootstrap")
                return
            }
            delay(WIRELESS_ADB_ENABLE_SETTLE_MILLIS)
        }

        var session: WirelessAdbSession? = null
        try {
            session = WirelessAdbSession.open(this, PORT_DISCOVERY_TIMEOUT_MILLIS)
            val outcome = ShizukuStarter.start(
                context = this,
                shell = { command -> session.shell(command) },
                binderTimeoutMillis = BINDER_START_TIMEOUT_MILLIS,
                onLog = { message -> Log.i(TAG, message) },
            )
            if (!outcome.started) {
                Log.w(TAG, "Early Shizuku bootstrap exhausted compatible starters: ${outcome.detail}")
            }
        } finally {
            runCatching { session?.close() }
            // Do not override the user's Wireless Debugging choice. If RMG
            // enabled it solely for this boot bootstrap, restore the prior state.
            if (!wirelessWasEnabled) {
                runCatching { AdbPairing.disableWirelessAdb(this) }
            }
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                SHIZUKU_BOOT_CHANNEL_ID,
                getString(R.string.shizuku_mode),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.shizuku_mode_description)
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(message: String) =
        NotificationCompat.Builder(this, SHIZUKU_BOOT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    companion object {
        private const val TAG = "RootMyGalaxyShizukuBoot"
        private const val SHIZUKU_BOOT_CHANNEL_ID = "shizuku_bootstrap"
        private const val SHIZUKU_BOOT_NOTIFICATION_ID = 43501
        private const val BINDER_INITIAL_PROBE_MILLIS = 1_500L
        private const val WIRELESS_ADB_ENABLE_SETTLE_MILLIS = 1_000L
        private const val PORT_DISCOVERY_TIMEOUT_MILLIS = 45_000L
        private const val BINDER_START_TIMEOUT_MILLIS = 20_000L

        fun startIfConfigured(context: Context) {
            if (!AppPreferences.autoStartShizukuAfterRoot(context)) return
            if (!AppPreferences.adbPaired(context)) return
            context.startForegroundService(Intent(context, ShizukuBootService::class.java))
        }
    }
}
