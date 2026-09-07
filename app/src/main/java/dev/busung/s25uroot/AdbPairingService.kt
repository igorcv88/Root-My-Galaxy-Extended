package dev.busung.s25uroot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import androidx.core.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.ConnectException

/**
 * Foreground service that handles ADB wireless-debugging pairing via
 * notification with RemoteInput.
 *
 * Flow:
 * 1. Post-root automation starts the service when no saved local ADB key exists.
 * 2. The user enables Wireless Debugging and opens "Pair device with pairing code".
 * 3. The notification accepts the six-digit pairing code.
 * 4. The service performs TLS + SPAKE2 pairing with adbd.
 * 5. If KernelSU is already active, it immediately finishes the one-time setup:
 *    local ADB reconnect, WRITE_SECURE_SETTINGS self-grant and Shizuku startup.
 */
class AdbPairingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var adbMdns: AdbMdns? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundCompat(searchingNotification())
                startSearch()
            }
            ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(REMOTE_INPUT_KEY)?.toString() ?: ""
                val port = intent.getIntExtra(EXTRA_PORT, -1)
                if (port != -1 && code.isNotBlank()) {
                    startForegroundCompat(workingNotification())
                    onInput(code, port)
                } else {
                    startSearch()
                }
            }
            ACTION_STOP -> {
                stopSearch()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun startSearch() {
        if (started) return
        started = true
        adbMdns = AdbMdns(this, AdbMdns.TLS_PAIRING) { port ->
            Log.i(TAG, "Pairing service port: $port")
            if (port <= 0) return@AdbMdns
            val notification = inputNotification(port)
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
        }.apply { start() }
    }

    private fun stopSearch() {
        if (!started) return
        started = false
        adbMdns?.stop()
    }

    private fun onInput(code: String, port: Int) {
        Log.i(TAG, "onInput: code=${code.length} chars, port=$port")
        scope.launch {
            val keyManager = try {
                Log.d(TAG, "Creating AdbKeyManager")
                AdbKeyManager(this@AdbPairingService)
            } catch (e: Throwable) {
                Log.e(TAG, "AdbKeyManager creation failed", e)
                handleResult(false, e)
                return@launch
            }
            try {
                Log.i(TAG, "Starting AdbPairingClient to 127.0.0.1:$port")
                val success = AdbPairingClient("127.0.0.1", port, code, keyManager).use {
                    it.start()
                }
                Log.i(TAG, "Pairing result: $success")
                handleResult(success, null)
            } catch (e: Throwable) {
                Log.e(TAG, "Pairing exception", e)
                handleResult(false, e)
            }
        }
    }

    private suspend fun handleResult(success: Boolean, exception: Throwable?) {
        stopSearch()
        val title: String
        var text: String
        if (success) {
            Log.i(TAG, "Pairing succeeded")
            AppPreferences.setAdbPaired(this, true)
            title = getString(R.string.adb_pair_success_title)
            text = getString(R.string.adb_pair_success_text)

            // The normal first pairing is requested by PostRootAutomation after
            // root is already verified. Complete the bootstrap immediately so
            // the user does not need another reboot just to obtain the persisted
            // WRITE_SECURE_SETTINGS grant and start Shizuku for the first time.
            if (NativeProbe.isKernelSuActive() && AppPreferences.autoStartShizukuAfterRoot(this)) {
                val postRoot = runCatching {
                    PostRootAutomation.run(
                        context = this@AdbPairingService,
                        softReboot = false,
                        startShizuku = true,
                    ) { line -> Log.i(TAG, line) }
                }.onFailure { error ->
                    Log.w(TAG, "Immediate post-pair automation failed", error)
                }.getOrNull()
                if (postRoot?.shizukuStarted == true) {
                    text = getString(R.string.adb_pair_success_shizuku)
                }
            }
        } else {
            title = getString(R.string.adb_pair_failed_title)
            text = when (exception) {
                is ConnectException -> getString(R.string.adb_pair_cannot_connect)
                is AdbInvalidPairingCodeException -> getString(R.string.adb_pair_wrong_code)
                else -> exception?.message ?: getString(R.string.adb_pair_unknown_error)
            }
            Log.w(TAG, "Pairing failed", exception)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(false)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun searchingNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.adb_pair_searching))
            .setOngoing(true)
            .addAction(
                0,
                getString(R.string.action_cancel),
                PendingIntent.getForegroundService(
                    this, 2, stopIntent(this),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun inputNotification(port: Int): Notification {
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY)
            .setLabel(getString(R.string.adb_pair_code_hint))
            .build()
        val replyPendingIntent = PendingIntent.getForegroundService(
            this, 1, replyIntent(this, port),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.adb_pair_service_found))
            .setContentText(getString(R.string.adb_pair_enter_code_notification))
            .setOngoing(true)
            .addAction(
                androidx.core.app.NotificationCompat.Action.Builder(
                    0,
                    getString(R.string.adb_pair_button),
                    replyPendingIntent,
                ).addRemoteInput(remoteInput).build(),
            )
            .build()
    }

    private fun workingNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.adb_pair_working))
            .setOngoing(true)
            .build()

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.boot_notification_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setSound(null, null)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "startForeground failed", e)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        stopSearch()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AdbPairingService"
        private const val CHANNEL_ID = "adb_pairing"
        private const val NOTIFICATION_ID = 0x504149
        private const val REMOTE_INPUT_KEY = "pairing_code"
        private const val EXTRA_PORT = "pairing_port"
        private const val ACTION_START = "start"
        private const val ACTION_REPLY = "reply"
        private const val ACTION_STOP = "stop"

        fun startIntent(context: Context): Intent =
            Intent(context, AdbPairingService::class.java).setAction(ACTION_START)

        private fun stopIntent(context: Context): Intent =
            Intent(context, AdbPairingService::class.java).setAction(ACTION_STOP)

        private fun replyIntent(context: Context, port: Int): Intent =
            Intent(context, AdbPairingService::class.java)
                .setAction(ACTION_REPLY)
                .putExtra(EXTRA_PORT, port)
    }
}
