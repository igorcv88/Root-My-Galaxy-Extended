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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
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
 * Wireless ADB pairing/key, starts the user's thedjchi/Shizuku fork through the
 * fork's real ADB starter command (`libshizuku.so --apk=<sourceDir>`), waits for
 * the Binder, then closes local ADB. Auto Root may still use post-root startup as
 * a recovery fallback, but normal boot must not wait 60/120 seconds for root.
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

        val notification = buildNotification(getString(R.string.shizuku_boot_starting))
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
            Log.i(TAG, "Shizuku Binder already available at boot")
            return
        }

        val appInfo = runCatching {
            packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0)
        }.getOrElse { error ->
            Log.w(TAG, "Shizuku package unavailable", error)
            return
        }
        if (appInfo.nativeLibraryDir.isNullOrBlank() || appInfo.sourceDir.isNullOrBlank()) {
            Log.w(TAG, "Shizuku starter paths unavailable")
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
            val starter = File(appInfo.nativeLibraryDir, "libshizuku.so").absolutePath
            val command =
                "${shellQuote(starter)} --apk=${shellQuote(appInfo.sourceDir)}"
            val result = session.shell("$command 2>&1")
            if (result.exitCode != 0) {
                Log.w(
                    TAG,
                    "Shizuku ADB starter failed rc=${result.exitCode}: ${result.output.takeLast(240)}",
                )
                return
            }

            if (ShizukuController.pingUntilRunning(BINDER_START_TIMEOUT_MILLIS)) {
                Log.i(TAG, "Shizuku Binder restored during early boot bootstrap")
            } else {
                Log.w(TAG, "Shizuku ADB starter exited successfully but Binder did not appear")
            }
        } finally {
            runCatching { session?.close() }
            // Match the fork's own AdbStarter behavior without overriding a user
            // choice: only turn Wireless Debugging back off if RMG turned it on.
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
                getString(R.string.shizuku_boot_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.shizuku_boot_channel_description)
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

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    companion object {
        private const val TAG = "RootMyGalaxyShizukuBoot"
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
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
