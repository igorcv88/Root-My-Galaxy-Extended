package dev.busung.s25uroot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Optional Shizuku boot coordinator.
 *
 * A soft/userspace reboot preserves KernelSU because the kernel boot does not
 * change. Therefore, after the initial Binder probe, this service first tries a
 * root-backed Shizuku starter without waiting for Wi-Fi or touching Wireless
 * Debugging. On a normal full boot where ephemeral KernelSU is not active yet,
 * that probe fails closed and the existing event-driven Wi-Fi/ADB path remains
 * unchanged.
 *
 * Auto Root remains independent. If Wi-Fi only appears near the configured
 * exploit gate, RMG yields through a small critical window rather than starting
 * mDNS/ADB beside the exploit.
 */
class ShizukuBootService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bootstrapJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isConfigured(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (bootstrapJob?.isActive == true) return START_NOT_STICKY

        val notification = buildNotification(getString(R.string.shizuku_boot_waiting))
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
                // TemporaryWirelessAdb.use owns cleanup for the ADB branch.
                // Root-only starts never touch adb_wifi_enabled at all.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        bootstrapJob?.cancel()
        // Cancellation inside TemporaryWirelessAdb.use executes its own finally;
        // abrupt process death is covered by TemporaryWirelessAdb's failsafe alarm.
        // Do not disable Wireless ADB here when this service only used root.
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun bootstrap() {
        if (ShizukuController.awaitRunning(BINDER_INITIAL_PROBE_MILLIS)) {
            Log.i(TAG, "Shizuku Binder already available at boot; no starter selected")
            return
        }

        // Soft reboot keeps KernelSU alive. Prefer that already-authorized root
        // immediately and avoid the entire Wireless ADB/mDNS path when possible.
        // A cold boot normally has no active ephemeral KernelSU yet, so this is a
        // quick no-op before the legacy boot bootstrap continues unchanged.
        val rootOutcome = tryExistingKernelSuRootStarterOnce()
        if (rootOutcome?.started == true) return

        // If the fork's own BOOT_COMPLETED receiver is enabled, give its unique
        // WorkManager job a longer head start. With Tasker/other starters we still
        // provide a short generic grace period. This makes dual configuration a
        // redundancy setup rather than an immediate two-starter race.
        val externalBootStarter = ShizukuIntentStarter.isShizukuBootReceiverEnabled(this)
        val coexistenceGrace = if (externalBootStarter) {
            SHIZUKU_OWN_BOOT_GRACE_MILLIS
        } else {
            GENERIC_STARTER_GRACE_MILLIS
        }
        if (ShizukuController.awaitRunning(coexistenceGrace)) {
            Log.i(TAG, "Another boot starter produced the Shizuku Binder; RMG stayed idle")
            return
        }

        while (currentCoroutineContext().isActive && isConfigured(this)) {
            if (ShizukuController.isRunning()) return

            // KernelSU can become available while the service is alive (for
            // example Auto Root finished after this service started). Re-check
            // before sleeping on Wi-Fi so an available root path always wins.
            val laterRootOutcome = tryExistingKernelSuRootStarterOnce()
            if (laterRootOutcome?.started == true) return

            when (awaitWifiOrBinder()) {
                WakeReason.Binder -> return
                WakeReason.Wifi -> Unit
            }

            // A competing starter waiting on the same Wi-Fi gets one final chance
            // before RMG enables Wireless Debugging itself.
            if (ShizukuController.awaitRunning(AFTER_WIFI_COEXISTENCE_GRACE_MILLIS)) {
                Log.i(TAG, "Shizuku Binder appeared after Wi-Fi connection; RMG ADB path skipped")
                return
            }

            val criticalDelay = autoRootCriticalDelayMillis()
            if (criticalDelay > 0) {
                Log.i(TAG, "Wi-Fi became available inside Auto Root critical window; yielding ${criticalDelay}ms")
                if (ShizukuController.awaitRunning(criticalDelay)) return
                continue
            }

            val shellOutcome = tryShellStartersOnce()
            if (shellOutcome?.started == true) return

            // Intent is deliberately last. It delegates to the fork's own launch
            // policy and may choose ADB or root according to the user's Shizuku
            // setting, so RMG uses it only after deterministic native/start.sh
            // attempts have failed or are unavailable.
            val intentOutcome = ShizukuIntentStarter.start(
                context = this,
                binderTimeoutMillis = INTENT_BINDER_TIMEOUT_MILLIS,
                onLog = { message -> Log.i(TAG, message) },
            )
            if (intentOutcome.started) return

            // Low-duty retry while the same Wi-Fi remains connected. No mDNS or
            // socket is kept alive between attempts and Wireless ADB is already off.
            if (ShizukuController.awaitRunning(RETRY_IDLE_MILLIS)) return
        }
    }

    /**
     * Try to start Shizuku from root that already exists for this kernel boot.
     * The helper bridge is preferred. Direct `su` is probed with a short timeout
     * and is used for the starter only after that probe already returns uid=0.
     * If neither bridge is usable, return immediately and leave the existing ADB
     * fallback untouched.
     */
    private suspend fun tryExistingKernelSuRootStarterOnce(): ShizukuStarter.Outcome? {
        if (!NativeProbe.isKernelSuActive()) return null

        val helperProbe = RootHelperShell.shell(this, "id")
        if (helperProbe.exitCode == 0 && helperProbe.output.contains("uid=0")) {
            Log.i(TAG, "KernelSU already active after userspace boot; starting Shizuku through RMG root helper")
            return ShizukuStarter.start(
                context = this,
                shell = { command -> RootHelperShell.shell(this, command) },
                binderTimeoutMillis = BINDER_START_TIMEOUT_MILLIS,
                onLog = { message -> Log.i(TAG, message) },
            )
        }

        val directProbe = directSuShell("id", DIRECT_SU_PROBE_TIMEOUT_MILLIS)
        if (directProbe.exitCode == 0 && directProbe.output.contains("uid=0")) {
            Log.i(TAG, "KernelSU app root already authorized after userspace boot; starting Shizuku directly with su")
            return ShizukuStarter.start(
                context = this,
                shell = { command -> directSuShell(command, DIRECT_SU_COMMAND_TIMEOUT_MILLIS) },
                binderTimeoutMillis = BINDER_START_TIMEOUT_MILLIS,
                onLog = { message -> Log.i(TAG, message) },
            )
        }

        Log.i(TAG, "KernelSU is active but no usable app root bridge is available; keeping existing ADB fallback")
        return null
    }

    /** Execute an already-available KernelSU su path with a caller-selected bound. */
    private fun directSuShell(
        command: String,
        timeoutMillis: Long,
    ): LocalAdbClient.ShellResult {
        val process = runCatching {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
        }.getOrElse { error ->
            return LocalAdbClient.ShellResult(
                LocalAdbClient.UNKNOWN_SHELL_EXIT_CODE,
                error.message ?: error.javaClass.simpleName,
            )
        }

        val output = StringBuilder()
        val reader = Thread({
            runCatching {
                process.inputStream.bufferedReader().use { stream ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count <= 0) break
                        output.append(buffer, 0, count)
                    }
                }
            }
        }, "rmg-shizuku-root-reader").apply {
            isDaemon = true
            start()
        }

        val finished = runCatching {
            process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!finished) {
            process.destroy()
            runCatching { process.waitFor(250, TimeUnit.MILLISECONDS) }
            if (process.isAlive) process.destroyForcibly()
        }
        runCatching { reader.join(500) }

        val code = if (finished) {
            runCatching { process.exitValue() }
                .getOrDefault(LocalAdbClient.UNKNOWN_SHELL_EXIT_CODE)
        } else {
            LocalAdbClient.UNKNOWN_SHELL_EXIT_CODE
        }
        val body = output.toString().trim()
        return LocalAdbClient.ShellResult(
            code,
            if (!finished && body.isBlank()) "KernelSU su command timed out" else body,
        )
    }

    private suspend fun tryShellStartersOnce(): ShizukuStarter.Outcome? {
        if (!AppPreferences.adbPaired(this)) {
            Log.i(TAG, "RMG local ADB key is not paired; shell starters skipped")
            return null
        }

        return try {
            TemporaryWirelessAdb.use(
                context = this,
                settleMillis = WIRELESS_ADB_ENABLE_SETTLE_MILLIS,
                onLog = { message -> Log.i(TAG, message) },
            ) {
                WirelessAdbSession.open(this, PORT_DISCOVERY_ATTEMPT_MILLIS).use { session ->
                    ShizukuStarter.start(
                        context = this,
                        shell = { command -> session.shell(command) },
                        binderTimeoutMillis = BINDER_START_TIMEOUT_MILLIS,
                        onLog = { message -> Log.i(TAG, message) },
                    )
                }
            }
        } catch (error: Throwable) {
            val detail = error.message ?: error.javaClass.simpleName
            if (looksLikePairingLoss(detail)) {
                AppPreferences.setAdbPaired(this, false)
                Log.w(TAG, "Local ADB pairing appears invalid; cached pairing flag cleared")
            }
            Log.w(TAG, "Network-triggered Shizuku ADB attempt failed: $detail")
            null
        }
    }

    /** Passive wait: Android wakes us only for a matching Wi-Fi network or Binder. */
    private suspend fun awaitWifiOrBinder(): WakeReason = coroutineScope {
        if (ShizukuController.isRunning()) return@coroutineScope WakeReason.Binder

        val wifi = async { awaitWifi(); WakeReason.Wifi }
        val binder = async { awaitBinder(); WakeReason.Binder }
        try {
            select {
                wifi.onAwait { it }
                binder.onAwait { it }
            }
        } finally {
            wifi.cancel()
            binder.cancel()
        }
    }

    private suspend fun awaitWifi() {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        suspendCancellableCoroutine<Unit> { continuation ->
            val completed = AtomicBoolean(false)
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            lateinit var callback: ConnectivityManager.NetworkCallback

            fun finish() {
                if (!completed.compareAndSet(false, true)) return
                runCatching { connectivity.unregisterNetworkCallback(callback) }
                if (continuation.isActive) continuation.resume(Unit)
            }

            callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = finish()
            }

            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) {
                    runCatching { connectivity.unregisterNetworkCallback(callback) }
                }
            }

            try {
                connectivity.registerNetworkCallback(request, callback)
            } catch (error: Throwable) {
                if (completed.compareAndSet(false, true) && continuation.isActive) {
                    continuation.cancel(error)
                }
            }
        }
    }

    private suspend fun awaitBinder() {
        while (currentCoroutineContext().isActive) {
            if (ShizukuController.awaitRunning(BINDER_EVENT_WAIT_CHUNK_MILLIS)) return
        }
    }

    private fun autoRootCriticalDelayMillis(): Long {
        if (!AppPreferences.autoRootEnabled(this)) return 0L
        val gate = AppPreferences.autoRootBootMinUptimeSeconds(this) * 1_000L
        val start = (gate - AUTO_ROOT_GUARD_BEFORE_MILLIS).coerceAtLeast(0L)
        val end = gate + AUTO_ROOT_GUARD_AFTER_MILLIS
        val now = SystemClock.elapsedRealtime()
        return if (now in start until end) end - now else 0L
    }

    private fun looksLikePairingLoss(message: String): Boolean {
        val lower = message.lowercase()
        return "certificate_unknown" in lower ||
            "certificate unknown" in lower ||
            ("pairing" in lower && "revoked" in lower) ||
            LocalAdbClient.PAIRING_LOST_MARKER.lowercase() in lower
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                SHIZUKU_BOOT_CHANNEL_ID,
                getString(R.string.shizuku_mode),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.shizuku_boot_description)
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(message: String) =
        NotificationCompat.Builder(this, SHIZUKU_BOOT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle(getString(R.string.shizuku_boot_title))
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

    private enum class WakeReason { Wifi, Binder }

    companion object {
        private const val TAG = "RootMyGalaxyShizukuBoot"
        private const val SHIZUKU_BOOT_CHANNEL_ID = "shizuku_bootstrap"
        private const val SHIZUKU_BOOT_NOTIFICATION_ID = 43501

        private const val BINDER_INITIAL_PROBE_MILLIS = 1_500L
        private const val GENERIC_STARTER_GRACE_MILLIS = 4_000L
        private const val SHIZUKU_OWN_BOOT_GRACE_MILLIS = 20_000L
        private const val AFTER_WIFI_COEXISTENCE_GRACE_MILLIS = 5_000L
        private const val WIRELESS_ADB_ENABLE_SETTLE_MILLIS = 800L
        private const val PORT_DISCOVERY_ATTEMPT_MILLIS = 15_000L
        private const val BINDER_START_TIMEOUT_MILLIS = 12_000L
        private const val INTENT_BINDER_TIMEOUT_MILLIS = 20_000L
        private const val RETRY_IDLE_MILLIS = 15_000L
        private const val BINDER_EVENT_WAIT_CHUNK_MILLIS = 60_000L
        private const val AUTO_ROOT_GUARD_BEFORE_MILLIS = 10_000L
        private const val AUTO_ROOT_GUARD_AFTER_MILLIS = 45_000L
        private const val DIRECT_SU_PROBE_TIMEOUT_MILLIS = 2_000L
        private const val DIRECT_SU_COMMAND_TIMEOUT_MILLIS = 15_000L

        fun startIfConfigured(context: Context) {
            if (!isConfigured(context)) return
            context.startForegroundService(Intent(context, ShizukuBootService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ShizukuBootService::class.java))
        }

        private fun isConfigured(context: Context): Boolean {
            if (!AppPreferences.startShizukuOnBoot(context)) return false
            return NativeProbe.isKernelSuActive() ||
                AppPreferences.adbPaired(context) ||
                AppPreferences.shizukuAutomationToken(context).isNotBlank()
        }
    }
}
