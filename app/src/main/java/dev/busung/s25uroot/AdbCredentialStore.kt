package dev.busung.s25uroot

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Passive inspection and reset helpers for Root My Galaxy's own ADB identity.
 *
 * The persisted `adb_paired` preference is only a record that a pairing
 * transaction succeeded at some point. It is not proof that adbd still accepts
 * this key. Runtime validity is established by [WirelessAdbDiagnostics].
 */
internal object AdbCredentialStore {
    private const val KEY_DIR = "adb_keys"
    private const val PRIVATE_KEY = "adb_private.der"
    private const val PUBLIC_KEY = "adb_public.der"

    fun hasStoredKey(context: Context): Boolean {
        val dir = keyDir(context)
        return File(dir, PRIVATE_KEY).isFile && File(dir, PUBLIC_KEY).isFile
    }

    fun fingerprint(context: Context): String? {
        val publicFile = File(keyDir(context), PUBLIC_KEY)
        if (!publicFile.isFile) return null
        return runCatching {
            MessageDigest.getInstance("SHA-256")
                .digest(publicFile.readBytes())
                .take(FINGERPRINT_BYTES)
                .joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
        }.getOrNull()
    }

    /**
     * Removes only RMG's local ADB identity. Android's device-side paired-device
     * entry is intentionally not mutated here; the user can remove that entry in
     * Wireless Debugging settings when a stale authorization remains visible.
     */
    fun forgetLocalCredential(context: Context): Boolean {
        AppPreferences.setAdbPaired(context, false)
        val dir = keyDir(context)
        var ok = true
        listOf(PRIVATE_KEY, PUBLIC_KEY).forEach { name ->
            val file = File(dir, name)
            if (file.exists() && !file.delete()) ok = false
        }
        if (dir.exists() && dir.listFiles().isNullOrEmpty()) {
            dir.delete()
        }
        return ok && !hasStoredKey(context)
    }

    private fun keyDir(context: Context): File = File(context.filesDir, KEY_DIR)

    private const val FINGERPRINT_BYTES = 8
}
