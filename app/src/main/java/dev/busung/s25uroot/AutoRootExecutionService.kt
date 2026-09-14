package dev.busung.s25uroot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ZZI4-only fresh foreground execution phase.
 *
 * The lightweight boot gate stays alive through the stabilization period. This
 * service is promoted exactly ARM_LEAD_MILLIS before the absolute monotonic
 * launch target computed once by BOOT_COMPLETED, then waits for that target
 * before binding the existing executor. No exploit log, history entry, payload
 * timing, or FOPS timing is added here.
 */
class AutoRootExecutionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runJob: Job? = null
    private var handoffTimeoutJob: Job? = null
    private var bindingLossJob: Job? = null
    private var executorBound = false
    private var executorConnected = false
    private var shuttingDown = false
    private var pendingBootToken: String? = null

    private val executorConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (shuttingDown) return
            if (service == null) {
                failWithoutExecutorResult("executor connected without a binder")
                return
            }
            val bootToken = pendingBootToken
            if (bootToken.isNullOrBlank()) {
                failWithoutExecutorResult("executor handoff lost the boot token")
                return
            }
            try {
                val command = Message.obtain(null, AutoRootExecutorService.MSG_START_AUTO_ROOT).apply {
                    data = Bundle().apply {
                        putString(AutoRootExecutorService.EXTRA_BOOT_TOKEN, bootToken)
                    }
                }
                Messenger(service).send(command)
                executorConnected = true
                handoffTimeoutJob?.cancel()
                handoffTimeoutJob = null
                updateExecutionNotification(getString(R.string.autoroot_preparing_exploit))
            } catch (error: Throwable) {
                failWithoutExecutorResult(
                    "unable to start connected executor: " +
                        (error.message ?: error.javaClass.simpleName),
                )
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            scheduleBindingLossFailure("executor disconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            scheduleBindingLossFailure("executor binding died")
        }

        override fun onNullBinding(name: ComponentName?) {
            if (!shuttingDown) failWithoutExecutorResult("executor returned a null binding")
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == AutoRootService.ACTION_EXECUTOR_RESULT) {
            bindingLossJob?.cancel()
            bindingLossJob = null
            val removeNotification =
                intent.getBooleanExtra(AutoRootService.EXTRA_REMOVE_NOTIFICATION, false)
            val offerSoftReboot =
                intent.getBooleanExtra(AutoRootService.EXTRA_OFFER_SOFT_REBOOT, false)
            val message = intent.getStringExtra(AutoRootService.EXTRA_RESULT_MESSAGE)
            if (removeNotification) {
                stopWithoutResult()
            } else {
                finishWithResult(
                    message ?: getString(
                        R.string.autoroot_failed,
                        "executor returned no result",
                    ),
                    offerSoftReboot,
                )
            }
            return START_NOT_STICKY
        }

        if (runJob?.isActive == true || executorBound) return START_NOT_STICKY

        val bootToken = intent?.getStringExtra(EXTRA_BOOT_TOKEN)
        if (bootToken.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        pendingBootToken = bootToken

        val launchTargetElapsedRealtime = intent?.getLongExtra(
            EXTRA_LAUNCH_TARGET_ELAPSED_REALTIME,
            MIN_LAUNCH_UPTIME_MILLIS,
        ) ?: MIN_LAUNCH_UPTIME_MILLIS
        val effectiveLaunchTarget =
            if (launchTargetElapsedRealtime > 0L) {
                launchTargetElapsedRealtime
            } else {
                MIN_LAUNCH_UPTIME_MILLIS
            }

        val initial = buildProgressNotification(
            this,
            getString(R.string.autoroot_checking_firmware),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                EXECUTION_NOTIFICATION_ID,
                initial,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(EXECUTION_NOTIFICATION_ID, initial)
        }

        stopService(Intent(this, AutoRootService::class.java))
        getSystemService(NotificationManager::class.java).cancel(AUTO_ROOT_NOTIFICATION_ID)

        runJob = scope.launch { runExecution(bootToken, effectiveLaunchTarget) }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shuttingDown = true
        handoffTimeoutJob?.cancel()
        bindingLossJob?.cancel()
        if (executorBound) {
            runCatching { unbindService(executorConnection) }
            executorBound = false
        }
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private suspend fun runExecution(
        initialBootToken: String,
        launchTargetElapsedRealtime: Long,
    ) {
        val wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:AutoRootExecution",
        )
        wakeLock.acquire(MAX_EXECUTION_WAKELOCK_MILLIS)
        try {
            waitUntilElapsedRealtime(launchTargetElapsedRealtime)

            if (!AppPreferences.autoRootEnabled(this)) {
                stopWithoutResult()
                return
            }

            val bootToken = AutoRootSupport.currentBootToken()
                ?: error(getString(R.string.error_boot_id))
            require(bootToken == initialBootToken) { getString(R.string.autoroot_boot_changed) }

            if (KernelSuRuntime.isControlActive(this)) {
                AutoRootSupport.markVerifiedForBoot(this, bootToken)
                stopWithoutResult()
                return
            }

            require(AutoRootSupport.hasVerifiedInstall(this)) {
                getString(R.string.autoroot_prior_install_required)
            }

            val shellTransportRequired = AutoRootSupport.requiresShellTransport(this)
            val executorClass = if (shellTransportRequired) {
                AutoRootShellExecutorService::class.java
            } else {
                AutoRootExecutorService::class.java
            }
            val executorIntent = Intent(this, executorClass)
                .setAction(AutoRootExecutorService.ACTION_RUN_AUTO_ROOT)
            val bindFlags = Context.BIND_AUTO_CREATE or
                Context.BIND_IMPORTANT or
                Context.BIND_ABOVE_CLIENT
            require(bindService(executorIntent, executorConnection, bindFlags)) {
                "Unable to bind Auto Root executor ${executorClass.simpleName}"
            }
            executorBound = true

            handoffTimeoutJob = scope.launch {
                delay(EXECUTOR_CONNECT_TIMEOUT_MILLIS)
                if (!executorConnected && !shuttingDown) {
                    failWithoutExecutorResult(
                        "Auto Root executor ${executorClass.simpleName} did not connect within " +
                            "${EXECUTOR_CONNECT_TIMEOUT_MILLIS / 1_000}s",
                    )
                }
            }
        } catch (error: Throwable) {
            if (!scope.isActive) return
            val detail = error.message ?: error.javaClass.simpleName
            failWithoutExecutorResult(detail)
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private suspend fun waitUntilElapsedRealtime(targetMillis: Long) {
        while (true) {
            val remaining = targetMillis - SystemClock.elapsedRealtime()
            if (remaining <= 0L) return
            delay(minOf(remaining, 250L))
        }
    }

    private fun scheduleBindingLossFailure(detail: String) {
        if (shuttingDown) return
        bindingLossJob?.cancel()
        bindingLossJob = scope.launch {
            delay(EXECUTOR_RESULT_GRACE_MILLIS)
            if (!shuttingDown) failWithoutExecutorResult(detail)
        }
    }

    private fun failWithoutExecutorResult(detail: String) {
        if (shuttingDown) return
        finishWithResult(getString(R.string.autoroot_failed, detail))
    }

    private fun updateExecutionNotification(message: String) {
        postProgressNotification(this, message)
    }

    private fun finishWithResult(message: String, offerSoftReboot: Boolean = false) {
        if (shuttingDown) return
        shuttingDown = true
        postResultNotification(this, message, offerSoftReboot)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopWithoutResult() {
        if (shuttingDown) return
        shuttingDown = true
        getSystemService(NotificationManager::class.java).apply {
            cancel(AUTO_ROOT_NOTIFICATION_ID)
            cancel(EXECUTION_NOTIFICATION_ID)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val EXTRA_BOOT_TOKEN = "dev.busung.s25uroot.extra.AUTO_ROOT_EXECUTION_BOOT_TOKEN"
        const val EXTRA_LAUNCH_TARGET_ELAPSED_REALTIME =
            "dev.busung.s25uroot.extra.AUTO_ROOT_LAUNCH_TARGET_ELAPSED_REALTIME"
        const val EXECUTION_NOTIFICATION_ID = 43500
        const val RESULT_NOTIFICATION_ID = 43502

        const val MIN_LAUNCH_UPTIME_MILLIS = 120_000L
        const val POST_BOOT_GUARD_MILLIS = 75_000L
        const val ARM_LEAD_MILLIS = 2_000L

        private const val EXECUTION_CHANNEL_ID = "auto_root_execution"
        private const val RESULT_CHANNEL_ID = "auto_root_result"
        private const val EXECUTOR_CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val EXECUTOR_RESULT_GRACE_MILLIS = 1_500L
        private const val MAX_EXECUTION_WAKELOCK_MILLIS = 5 * 60 * 1_000L

        fun shouldUseSplitExecution(snapshot: DeviceSnapshot): Boolean =
            snapshot.device == "pa3q" &&
                snapshot.model.equals("SM-S938B", ignoreCase = true) &&
                snapshot.buildId.contains("S938BXXUCZZI4", ignoreCase = true)

        fun computeLaunchTargetElapsedRealtime(bootCompletedElapsedRealtime: Long): Long =
            maxOf(
                MIN_LAUNCH_UPTIME_MILLIS,
                bootCompletedElapsedRealtime + POST_BOOT_GUARD_MILLIS,
            )

        internal fun postProgressNotification(context: Context, message: String) {
            // The split host creates this channel in onCreate(). Avoid recreating
            // channels on every stage transition immediately around the race.
            context.getSystemService(NotificationManager::class.java).notify(
                EXECUTION_NOTIFICATION_ID,
                buildProgressNotification(context, message),
            )
        }

        internal fun postResultNotification(
            context: Context,
            message: String,
            offerSoftReboot: Boolean,
        ) {
            ensureNotificationChannels(context)
            context.getSystemService(NotificationManager::class.java).apply {
                cancel(AUTO_ROOT_NOTIFICATION_ID)
                cancel(EXECUTION_NOTIFICATION_ID)
                notify(
                    RESULT_NOTIFICATION_ID,
                    buildResultNotification(context, message, offerSoftReboot),
                )
            }
        }

        private fun buildProgressNotification(
            context: Context,
            message: String,
        ): android.app.Notification =
            NotificationCompat.Builder(context, EXECUTION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_app_logo)
                .setContentTitle(context.getString(R.string.autoroot_notification_title))
                .setContentText(message)
                .setContentIntent(mainPendingIntent(context))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(
                    0,
                    context.getString(R.string.autoroot_disable),
                    disablePendingIntent(context),
                )
                .build()

        private fun buildResultNotification(
            context: Context,
            message: String,
            offerSoftReboot: Boolean,
        ): android.app.Notification {
            val builder = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_app_logo)
                .setContentTitle(context.getString(R.string.autoroot_notification_title))
                .setContentText(message)
                .setContentIntent(mainPendingIntent(context))
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)

            if (offerSoftReboot) {
                builder.addAction(
                    0,
                    context.getString(R.string.autoroot_apply_modules),
                    PendingIntent.getBroadcast(
                        context,
                        2,
                        Intent(context, AutoRootActionReceiver::class.java)
                            .setAction(AutoRootActionReceiver.ACTION_APPLY_MODULES_SOFT_REBOOT),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
            return builder.build()
        }

        private fun mainPendingIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun disablePendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                1,
                Intent(context, AutoRootActionReceiver::class.java)
                    .setAction(AutoRootActionReceiver.ACTION_DISABLE_AUTO_ROOT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun ensureNotificationChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    EXECUTION_CHANNEL_ID,
                    context.getString(R.string.autoroot_execution_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.autoroot_execution_channel_description)
                    setShowBadge(false)
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    RESULT_CHANNEL_ID,
                    context.getString(R.string.autoroot_result_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.autoroot_result_channel_description)
                },
            )
        }
    }
}