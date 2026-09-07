package dev.busung.s25uroot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * One-shot visible bridge used only for the first local Wireless ADB pairing.
 * Android 13+ hides notification content until POST_NOTIFICATIONS is granted,
 * while AdbPairingService intentionally uses a notification RemoteInput for the
 * six-digit pairing code. Requesting permission here guarantees that the user
 * can actually see and interact with that service notification.
 */
class AdbPairingSetupActivity : ComponentActivity() {
    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startPairingService()
        } else {
            Toast.makeText(
                this,
                getString(R.string.adb_pair_notification_permission_required),
                Toast.LENGTH_LONG,
            ).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AppPreferences.adbPaired(this)) {
            finish()
            return
        }

        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startPairingService()
            finish()
        } else {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startPairingService() {
        ContextCompat.startForegroundService(this, AdbPairingService.startIntent(this))
    }
}
