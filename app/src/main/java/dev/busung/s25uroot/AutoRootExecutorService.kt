package dev.busung.s25uroot

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Auto Root execution service.
 *
 * The normal component is hosted in the fresh :autoroot_exec process for
 * standalone targets. Shell-required targets bind AutoRootShellExecutorService,
 * which inherits this implementation but stays in the default/provider process.
 * A live authorized Shizuku Binder is preferred there; if early-boot Binder
 * delivery fails, the same executor falls back to RMG's paired local ADB client,
 * which still launches the exploit from u:r:shell:s0.
 *
 * onBind() has no execution side effect: the gate must first connect and then
 * send an explicit start command over Messenger.
 */
open class AutoRootExecutorService : Service() {
    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "RootMyGalaxy-AutoRootExec")
        }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var runJob: Job? = null
    private val splitExecution: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        AutoRootExecutionService.shouldUseSplitExecution(DeviceSnapshot.current())
    }

    private val commandHandler = Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            MSG_START_AUTO_ROOT -> {
                handleStartCommand(message)
                true
            }
            else -> false
        }
    }
    private val commandMessenger = Messenger(commandHandler)

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action != ACTION_RUN_AUTO_ROOT) return null
        Log.i(
            TAG,
            "Auto Root executor bound in ${Application.getProcessName()}; awaiting explicit start command",
        )
        return commandMessenger.binder
    }

    override fun onDestroy() {
        scope.cancel()
        dispatcher.close()
        super.onDestroy()
    }

    private fun handleStartCommand(message: Message) {
        if (runJob != null) {
            Log.w(TAG, "Ignoring duplicate Auto Root executor start command")
            return
        }

        val bootToken = message.data.getString(EXTRA_BOOT_TOKEN)
        if (bootToken.isNullOrBlank()) {
            finishWithResult(getString(R.string.autoroot_failed, "executor received no boot token"))
            return
        }

        updateNotification(getString(R.string.autoroot_preparing_exploit))
        Log.i(TAG, "Auto Root executor start command accepted")
        runJob = scope.launch { runAutoRoot(bootToken) }
    }

    private suspend fun runAutoRoot(expectedBootToken: String) {
        val wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:AutoRootExecutor",
        )
        wakeLock.acquire(MAX_EXECUTOR_WAKELOCK_MILLIS)

        var historyEntry: InstallHistoryEntry? = null

        fun appendHistory(line: String?) {
            val current = historyEntry ?: return
            val clean = line?.trim().orEmpty()
            if (clean.isBlank()) return
            historyEntry = current.copy(log = (current.log + "\n" + clean).trim())
        }

        fun finishHistory(result: InstallRunResult) {
            val current = historyEntry ?: return
            val updated = current.copy(
                completedAtMillis = current.completedAtMillis ?: System.currentTimeMillis(),
                result = result,
            )
            historyEntry = updated
            runCatching { InstallHistoryStore(this).save(updated) }
        }

        try {
            require(AppPreferences.autoRootEnabled(this)) { "Auto Root was disabled before execution" }

            val bootToken = AutoRootSupport.currentBootToken()
                ?: error(getString(R.string.error_boot_id))
            require(bootToken == expectedBootToken) {
                getString(R.string.autoroot_boot_changed)
            }

            if (KernelSuRuntime.isControlActive(this)) {
                AutoRootSupport.markVerifiedForBoot(this, bootToken)
                finishWithResult(getString(R.string.autoroot_root_restored))
                return
            }

            val processName = Application.getProcessName()
            historyEntry = InstallHistoryEntry(
                id = UUID.randomUUID().toString(),
                startedAtMillis = System.currentTimeMillis(),
                completedAtMillis = null,
                result = InstallRunResult.Running,
                profileId = null,
                usedShizuku = false,
                log = "[*] Auto Root started: executor=$processName, offline cache",
            )

            val payloads = AutoRootSupport.loadVerifiedLocalPayloads(this)
            require(payloads.source == PayloadSource.Offline) {
                "Auto Root requires the last-known-good offline payload"
            }
            val shellTransportRequired = payloads.profile.routePolicy.prefersShellTransport
            val currentHistory = historyEntry ?: error("Auto Root history state missing")
            historyEntry = currentHistory.copy(
                profileId = payloads.profile.profileId,
                log = currentHistory.log +
                    "\n[*] profile=${payloads.profile.profileId} transport=" +
                    if (shellTransportRequired) "shell-required" else "standalone",
            )

            var selectedShellTransport: AutoRootShellTransport? = null
            if (shellTransportRequired) {
                require(processName == packageName) {
                    "Shell-required Auto Root was dispatched outside the provider process ($processName)"
                }

                updateNotification(getString(R.string.autoroot_preparing_exploit))
                appendHistory("[*] Checking preferred Shizuku shell transport in provider process")
                ShizukuBootService.startForAutoRoot(this)

                val shizukuRunning =
                    ShizukuController.awaitRunning(AUTO_ROOT_SHIZUKU_PREFERENCE_GRACE_MILLIS)
                selectedShellTransport = if (shizukuRunning && ShizukuController.isGranted()) {
                    appendHistory("[+] Shizuku shell transport is ready in provider process")
                    AutoRootShellTransport.Shizuku
                } else {
                    require(AppPreferences.adbPaired(this)) {
                        if (shizukuRunning) {
                            "Shizuku is running but Root My Galaxy is not authorized, and no paired local ADB fallback is available"
                        } else {
                            "Shizuku Binder is unavailable and no paired local ADB fallback is available"
                        }
                    }
                    appendHistory(
                        if (shizukuRunning) {
                            "[!] Shizuku Binder is present but unauthorized; selecting paired local ADB shell fallback"
                        } else {
                            "[!] Shizuku Binder was not delivered; selecting paired local ADB shell fallback"
                        },
                    )
                    // The boot coordinator may itself be retrying local ADB solely
                    // to obtain a Binder that this app is not receiving. Stop that
                    // redundant owner before Auto Root opens its own serialized ADB
                    // session. This does not stop the already-running Shizuku server.
                    ShizukuBootService.stop(this)
                    AutoRootShellTransport.LocalAdb
                }

                val selectedHistory = historyEntry ?: error("Auto Root history state missing")
                historyEntry = selectedHistory.copy(
                    usedShizuku = selectedShellTransport == AutoRootShellTransport.Shizuku,
                )
            }

            var attemptClaimed = false
            var lastRunnerSnapshot = ""
            val runner = AutoRootRunner(
                context = this,
                onStage = { stage ->
                    val messageRes = when (stage) {
                        AutoRootStage.PreparingExploit -> R.string.autoroot_preparing_exploit
                        AutoRootStage.RunningExploit -> R.string.autoroot_running_exploit
                        AutoRootStage.LoadingKernelSu -> R.string.autoroot_loading_ksu
                        AutoRootStage.VerifyingRoot -> R.string.autoroot_verifying_root
                    }
                    val stageMessage = getString(messageRes)
                    updateNotification(stageMessage)
                    appendHistory("[*] $stageMessage")
                },
                onLog = { snapshot ->
                    val delta = if (
                        lastRunnerSnapshot.isNotEmpty() &&
                        snapshot.startsWith(lastRunnerSnapshot)
                    ) {
                        snapshot.substring(lastRunnerSnapshot.length).trimStart('\n', '\r')
                    } else {
                        snapshot
                    }
                    lastRunnerSnapshot = snapshot
                    appendHistory(delta)
                },
            )
            runner.run(
                payloads = payloads,
                bootToken = bootToken,
                shellTransport = selectedShellTransport,
                beforeExploit = {
                    require(!attemptClaimed) {
                        "Auto Root exploit claim callback was invoked twice"
                    }
                    require(AutoRootSupport.claimAttempt(this, bootToken)) {
                        getString(R.string.autoroot_already_attempted)
                    }
                    attemptClaimed = true
                    appendHistory("[*] Once-per-boot exploit attempt claimed after transport readiness")
                },
            )

            AutoRootSupport.markVerifiedForBoot(this, bootToken)
            appendHistory("[+] Auto Root completed")

            // Root restoration is the terminal automatic action. A userspace
            // restart is disruptive and must remain explicitly user initiated.
            finishHistory(InstallRunResult.Succeeded)

            val startShizuku = AppPreferences.autoStartShizukuAfterRoot(this)
            if (startShizuku) {
                try {
                    val postRoot = PostRootAutomation.run(
                        context = this,
                        softReboot = false,
                        startShizuku = true,
                        prepareZzi4Modules = false,
                        onLog = { appendHistory(it) },
                    )
                    if (!postRoot.shizukuStarted && postRoot.detail.isNotBlank()) {
                        appendHistory("[!] Post-root Shizuku automation: ${postRoot.detail.take(200)}")
                    }
                } catch (error: Throwable) {
                    val detail = error.message ?: error.javaClass.simpleName
                    appendHistory("[!] Post-root Shizuku automation failed: $detail")
                    Log.w(TAG, "Post-root Shizuku automation failed after verified root", error)
                }
            }

            val offerSoftReboot =
                payloads.profile.profileId == Zzi4PostRootRuntime.PROFILE_ID
            if (offerSoftReboot) {
                appendHistory(
                    "[*] ZZI4 root restored; KernelSU soft reboot left to the user notification action",
                )
            }
            finishWithResult(
                message = if (offerSoftReboot) {
                    getString(R.string.autoroot_root_restored_modules_pending)
                } else {
                    getString(R.string.autoroot_root_restored)
                },
                offerSoftReboot = offerSoftReboot,
            )
        } catch (error: Throwable) {
            if (!scope.isActive) return
            val detail = error.message ?: error.javaClass.simpleName

            // Root acquisition and full userspace/module readiness are separate
            // boundaries. If kernelsu.ko is already controllable, persist the
            // current boot as rooted even when a later readiness/automation step
            // failed. This prevents the UI and the next BOOT_COMPLETED from
            // treating a live KernelSU instance as unrooted and replaying exploit.
            val controlActive = runCatching { KernelSuRuntime.isControlActive(this) }
                .getOrDefault(false)
            if (controlActive) {
                AutoRootSupport.currentBootToken()?.let { bootToken ->
                    runCatching { AutoRootSupport.markVerifiedForBoot(this, bootToken) }
                }
                if (historyEntry != null) {
                    appendHistory("[!] KernelSU control is active; post-root verification incomplete: $detail")
                    finishHistory(InstallRunResult.Succeeded)
                }
                Log.w(TAG, "KernelSU control active after Auto Root post-root failure: $detail", error)
                finishWithResult("KernelSU root is active; post-root verification incomplete: ${detail.takeLast(180)}")
                return
            }

            Log.e(TAG, "Auto Root executor failed", error)
            if (historyEntry != null) {
                appendHistory("[-] $detail")
                finishHistory(InstallRunResult.Failed)
            }
            finishWithResult(getString(R.string.autoroot_failed, detail))
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun isSplitExecution(): Boolean = splitExecution

    private fun updateNotification(message: String) {
        if (isSplitExecution()) {
            // The split foreground host owns one progress notification (43500).
            // Reuse that same ID/channel for every stage instead of creating a
            // second legacy 43499 notification beside it.
            AutoRootExecutionService.postProgressNotification(this, message)
        } else {
            getSystemService(NotificationManager::class.java).notify(
                AUTO_ROOT_NOTIFICATION_ID,
                buildNotification(message, ongoing = true),
            )
        }
    }

    /** Let the already-running foreground gate own the terminal notification. */
    private fun resultHostClass(): Class<out Service> =
        if (isSplitExecution()) {
            AutoRootExecutionService::class.java
        } else {
            AutoRootService::class.java
        }

    private fun finishWithResult(message: String, offerSoftReboot: Boolean = false) {
        val delivered = deliverGateResult(
            message = message,
            removeNotification = false,
            offerSoftReboot = offerSoftReboot,
        )
        if (!delivered) {
            if (isSplitExecution()) {
                AutoRootExecutionService.postResultNotification(
                    this,
                    message,
                    offerSoftReboot,
                )
            } else {
                getSystemService(NotificationManager::class.java).notify(
                    AUTO_ROOT_NOTIFICATION_ID,
                    buildNotification(
                        message = message,
                        ongoing = false,
                        offerSoftReboot = offerSoftReboot,
                    ),
                )
            }
            stopService(Intent(this, resultHostClass()))
        }
        stopSelf()
    }

    private fun stopGateAndSelf(removeNotification: Boolean) {
        val delivered = deliverGateResult(message = null, removeNotification = removeNotification)
        if (!delivered) {
            if (removeNotification) {
                getSystemService(NotificationManager::class.java).apply {
                    cancel(AUTO_ROOT_NOTIFICATION_ID)
                    if (isSplitExecution()) {
                        cancel(AutoRootExecutionService.EXECUTION_NOTIFICATION_ID)
                    }
                }
            }
            stopService(Intent(this, resultHostClass()))
        }
        stopSelf()
    }

    private fun deliverGateResult(
        message: String?,
        removeNotification: Boolean,
        offerSoftReboot: Boolean = false,
    ): Boolean = runCatching {
        startService(
            Intent(this, resultHostClass())
                .setAction(AutoRootService.ACTION_EXECUTOR_RESULT)
                .putExtra(AutoRootService.EXTRA_RESULT_MESSAGE, message)
                .putExtra(AutoRootService.EXTRA_REMOVE_NOTIFICATION, removeNotification)
                .putExtra(AutoRootService.EXTRA_OFFER_SOFT_REBOOT, offerSoftReboot),
        ) != null
    }.onFailure { error ->
        Log.e(TAG, "Unable to deliver executor result to foreground host", error)
    }.getOrDefault(false)

    private fun buildNotification(
        message: String,
        ongoing: Boolean,
        offerSoftReboot: Boolean = false,
    ): android.app.Notification {
        val builder = NotificationCompat.Builder(this, AUTO_ROOT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle(getString(R.string.autoroot_notification_title))
            .setContentText(message)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0,
                getString(R.string.autoroot_disable),
                PendingIntent.getBroadcast(
                    this,
                    1,
                    Intent(this, AutoRootActionReceiver::class.java)
                        .setAction(AutoRootActionReceiver.ACTION_DISABLE_AUTO_ROOT),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        if (offerSoftReboot) {
            builder.addAction(
                0,
                getString(R.string.autoroot_apply_modules),
                PendingIntent.getBroadcast(
                    this,
                    2,
                    Intent(this, AutoRootActionReceiver::class.java)
                        .setAction(AutoRootActionReceiver.ACTION_APPLY_MODULES_SOFT_REBOOT),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                AUTO_ROOT_CHANNEL_ID,
                getString(R.string.autoroot_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.autoroot_channel_description)
            },
        )
    }

    companion object {
        const val ACTION_RUN_AUTO_ROOT = "dev.busung.s25uroot.action.RUN_FRESH_AUTO_ROOT"
        const val EXTRA_BOOT_TOKEN = "boot_token"
        const val MSG_START_AUTO_ROOT = 1

        private const val TAG = "RootMyGalaxyAutoRootExec"
        private const val AUTO_ROOT_SHIZUKU_PREFERENCE_GRACE_MILLIS = 5_000L
        private const val MAX_EXECUTOR_WAKELOCK_MILLIS = 20 * 60 * 1_000L
    }
}