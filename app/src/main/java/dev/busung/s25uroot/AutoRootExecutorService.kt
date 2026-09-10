package dev.busung.s25uroot

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
 * Fresh process used only for the critical Auto Root execution window.
 *
 * The foreground gate binds this service at the configured launch uptime with
 * BIND_IMPORTANT | BIND_ABOVE_CLIENT. Auto Root is always offline. Legacy
 * targets remain standalone; targets whose route policy requires shell wait for
 * the boot Shizuku transport before consuming their once-per-boot exploit attempt.
 * onBind() has no execution side effect: the gate must first connect and then send
 * an explicit start command over Messenger.
 */
class AutoRootExecutorService : Service() {
    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "RootMyGalaxy-AutoRootExec")
        }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var runJob: Job? = null

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
        Log.i(TAG, "Fresh Auto Root executor bound; awaiting explicit start command")
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

            if (NativeProbe.isKernelSuActive()) {
                AutoRootSupport.markVerifiedForBoot(this, bootToken)
                finishWithResult(getString(R.string.autoroot_root_restored))
                return
            }

            historyEntry = InstallHistoryEntry(
                id = UUID.randomUUID().toString(),
                startedAtMillis = System.currentTimeMillis(),
                completedAtMillis = null,
                result = InstallRunResult.Running,
                profileId = null,
                usedShizuku = false,
                log = "[*] Auto Root started: fresh executor, offline cache",
            )

            val payloads = AutoRootSupport.loadVerifiedLocalPayloads(this)
            require(payloads.source == PayloadSource.Offline) {
                "Auto Root requires the last-known-good offline payload"
            }
            val shellTransportRequired = payloads.profile.routePolicy.prefersShellTransport
            val currentHistory = historyEntry ?: error("Auto Root history state missing")
            historyEntry = currentHistory.copy(
                profileId = payloads.profile.profileId,
                usedShizuku = shellTransportRequired,
                log = currentHistory.log +
                    "\n[*] profile=${payloads.profile.profileId} transport=" +
                    if (shellTransportRequired) "shell-required" else "standalone",
            )

            if (shellTransportRequired) {
                updateNotification(getString(R.string.autoroot_preparing_exploit))
                appendHistory("[*] Waiting for Shizuku shell transport required by target policy")
                ShizukuBootService.startForAutoRoot(this)
                require(ShizukuController.awaitRunning(AUTO_ROOT_SHIZUKU_WAIT_MILLIS)) {
                    "Shizuku did not become available for the shell-required Auto Root route"
                }
                require(ShizukuController.isGranted()) {
                    "Root My Galaxy is not authorized to use Shizuku for the shell-required Auto Root route"
                }
                appendHistory("[+] Shizuku shell transport is ready")
            }

            require(AutoRootSupport.claimAttempt(this, bootToken)) {
                getString(R.string.autoroot_already_attempted)
            }
            appendHistory("[*] Once-per-boot exploit attempt claimed after transport readiness")

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
            runner.run(payloads, bootToken)

            AutoRootSupport.markVerifiedForBoot(this, bootToken)
            appendHistory("[+] Auto Root completed")

            // Persist verified root before any userspace restart. The automatic
            // recovery action is deliberately only a Zygote/system_server restart;
            // KernelSU's full userspace soft reboot remains an explicit Advanced tool.
            finishHistory(InstallRunResult.Succeeded)

            val restartZygote = AppPreferences.restartZygoteAfterRoot(this)
            val startShizuku = AppPreferences.autoStartShizukuAfterRoot(this)
            if (restartZygote || startShizuku) {
                if (restartZygote) updateNotification(getString(R.string.zygote_restart_starting))

                if (startShizuku) {
                    val postRoot = try {
                        PostRootAutomation.run(
                            context = this,
                            softReboot = false,
                            startShizuku = true,
                            onLog = { appendHistory(it) },
                        )
                    } catch (error: Throwable) {
                        val detail = error.message ?: error.javaClass.simpleName
                        appendHistory("[!] Post-root Shizuku automation failed: $detail")
                        Log.w(TAG, "Post-root Shizuku automation failed after verified root", error)
                        null
                    }
                    if (postRoot != null && !postRoot.shizukuStarted && postRoot.detail.isNotBlank()) {
                        appendHistory("[!] Post-root Shizuku automation: ${postRoot.detail.take(200)}")
                    }
                }

                if (restartZygote) {
                    val restart = try {
                        RootRecoveryActions.restartZygote(this)
                    } catch (error: Throwable) {
                        RootRecoveryResult(
                            accepted = false,
                            detail = error.message ?: error.javaClass.simpleName,
                        )
                    }
                    if (restart.accepted) {
                        appendHistory("[+] ${restart.detail}")
                        finishHistory(InstallRunResult.Succeeded)
                        Log.i(TAG, "Post-root Zygote restart scheduled")
                        // The detached recovery script waits briefly before ctl.restart,
                        // giving this executor time to close the foreground gate cleanly.
                        stopGateAndSelf(removeNotification = true)
                        return
                    }

                    val failureMessage = getString(
                        R.string.zygote_restart_failed,
                        restart.detail.take(160),
                    )
                    appendHistory("[!] $failureMessage")
                    finishHistory(InstallRunResult.Succeeded)
                    Log.w(TAG, failureMessage)
                    finishWithResult(failureMessage)
                    return
                }

                finishHistory(InstallRunResult.Succeeded)
            }

            finishWithResult(getString(R.string.autoroot_root_restored))
        } catch (error: Throwable) {
            if (!scope.isActive) return
            val detail = error.message ?: error.javaClass.simpleName
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

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(
            AUTO_ROOT_NOTIFICATION_ID,
            buildNotification(message, ongoing = true),
        )
    }

    /** Let the already-running foreground gate own the terminal notification. */
    private fun finishWithResult(message: String) {
        val delivered = deliverGateResult(message, removeNotification = false)
        if (!delivered) {
            getSystemService(NotificationManager::class.java).notify(
                AUTO_ROOT_NOTIFICATION_ID,
                buildNotification(message, ongoing = false),
            )
            stopService(Intent(this, AutoRootService::class.java))
        }
        stopSelf()
    }

    private fun stopGateAndSelf(removeNotification: Boolean) {
        val delivered = deliverGateResult(message = null, removeNotification = removeNotification)
        if (!delivered) {
            if (removeNotification) {
                getSystemService(NotificationManager::class.java).cancel(AUTO_ROOT_NOTIFICATION_ID)
            }
            stopService(Intent(this, AutoRootService::class.java))
        }
        stopSelf()
    }

    private fun deliverGateResult(message: String?, removeNotification: Boolean): Boolean =
        runCatching {
            startService(
                Intent(this, AutoRootService::class.java)
                    .setAction(AutoRootService.ACTION_EXECUTOR_RESULT)
                    .putExtra(AutoRootService.EXTRA_RESULT_MESSAGE, message)
                    .putExtra(AutoRootService.EXTRA_REMOVE_NOTIFICATION, removeNotification),
            ) != null
        }.onFailure { error ->
            Log.e(TAG, "Unable to deliver executor result to foreground gate", error)
        }.getOrDefault(false)

    private fun buildNotification(message: String, ongoing: Boolean) =
        NotificationCompat.Builder(this, AUTO_ROOT_CHANNEL_ID)
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
            .build()

    companion object {
        const val ACTION_RUN_AUTO_ROOT = "dev.busung.s25uroot.action.RUN_FRESH_AUTO_ROOT"
        const val EXTRA_BOOT_TOKEN = "boot_token"
        const val MSG_START_AUTO_ROOT = 1

        private const val TAG = "RootMyGalaxyAutoRootExec"
        private const val AUTO_ROOT_SHIZUKU_WAIT_MILLIS = 90_000L
        private const val MAX_EXECUTOR_WAKELOCK_MILLIS = 20 * 60 * 1_000L
    }
}