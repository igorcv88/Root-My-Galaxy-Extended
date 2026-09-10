package dev.busung.s25uroot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * RMG treats Wireless Debugging as a short-lived transport, never as persistent
 * device state. Every owned session ends by forcing adb_wifi_enabled=0 even if
 * it was already enabled when RMG entered the path.
 *
 * A best-effort alarm is also armed before enabling it. If the app process dies
 * during the narrow ADB window, the receiver still gets a chance to force the
 * setting off later instead of leaving Wireless Debugging enabled indefinitely.
 * The alarm covers the longest supported Auto Root stream (15 minutes).
 *
 * All in-process users are serialized. Shizuku boot bootstrap and shell-required
 * Auto Root both live in the provider process; without this lock one caller could
 * disable Wireless Debugging while the other still owns an authenticated session.
 */
internal object TemporaryWirelessAdb {
    private val sessionMutex = Mutex()

    fun begin(
        context: Context,
        onLog: (String) -> Unit = {},
    ): Boolean {
        armCleanup(context)
        val enabled = AdbPairing.enableWirelessAdb(context)
        if (enabled) {
            onLog("[*] Wireless Debugging enabled temporarily by RMG")
        } else {
            cancelCleanup(context)
            onLog("[!] Unable to enable Wireless Debugging; WRITE_SECURE_SETTINGS is required")
        }
        return enabled
    }

    suspend fun <T> use(
        context: Context,
        settleMillis: Long = 1_000L,
        onLog: (String) -> Unit = {},
        block: suspend () -> T,
    ): T = sessionMutex.withLock {
        check(begin(context, onLog)) {
            "Unable to enable Wireless Debugging; WRITE_SECURE_SETTINGS is required"
        }
        try {
            if (settleMillis > 0) delay(settleMillis)
            block()
        } finally {
            forceDisable(context, onLog)
        }
    }

    fun forceDisable(
        context: Context,
        onLog: (String) -> Unit = {},
    ) {
        val disabled = runCatching { AdbPairing.disableWirelessAdb(context) }.getOrDefault(false)
        cancelCleanup(context)
        if (disabled) {
            onLog("[+] Wireless Debugging disabled")
        } else {
            Log.e(TAG, "Failed to force Wireless Debugging off")
            onLog("[!] Failed to force Wireless Debugging off")
        }
    }

    private fun armCleanup(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + FAILSAFE_DISABLE_DELAY_MILLIS,
            cleanupPendingIntent(context),
        )
    }

    private fun cancelCleanup(context: Context) {
        context.getSystemService(AlarmManager::class.java)
            .cancel(cleanupPendingIntent(context))
    }

    private fun cleanupPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            CLEANUP_REQUEST_CODE,
            Intent(context, WirelessAdbCleanupReceiver::class.java)
                .setAction(ACTION_FORCE_DISABLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    const val ACTION_FORCE_DISABLE = "dev.busung.s25uroot.action.FORCE_DISABLE_WIRELESS_ADB"
    private const val CLEANUP_REQUEST_CODE = 0x57414442
    private const val FAILSAFE_DISABLE_DELAY_MILLIS = 20 * 60 * 1_000L
    private const val TAG = "RmgTemporaryWirelessAdb"
}

class WirelessAdbCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TemporaryWirelessAdb.ACTION_FORCE_DISABLE) return
        TemporaryWirelessAdb.forceDisable(context)
    }
}
