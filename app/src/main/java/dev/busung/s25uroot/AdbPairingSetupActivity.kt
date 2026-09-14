package dev.busung.s25uroot

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Visible bridge for local Wireless ADB pairing.
 *
 * Normal callers keep the one-shot behavior, while diagnostics can explicitly
 * force re-pairing even when a historical pairing flag is still present. This
 * is required when adbd has discarded the device-side authorization but RMG's
 * local credential remains on disk.
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
        val forceRepair = intent.getBooleanExtra(EXTRA_FORCE_REPAIR, false)
        if (forceRepair) {
            // A successful pairing service transaction sets this back to true.
            // Until then the saved boolean must not be treated as proof that
            // adbd still accepts the local TLS identity.
            AppPreferences.setAdbPaired(this, false)
        } else if (AppPreferences.adbPaired(this)) {
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

    companion object {
        private const val EXTRA_FORCE_REPAIR = "force_repair"

        fun pairingIntent(context: Context, forceRepair: Boolean = false): Intent =
            Intent(context, AdbPairingSetupActivity::class.java)
                .putExtra(EXTRA_FORCE_REPAIR, forceRepair)
    }
}
