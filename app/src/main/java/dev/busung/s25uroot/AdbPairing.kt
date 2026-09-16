package dev.busung.s25uroot

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "AdbPairing"

internal fun parseAdbTlsPort(raw: String): Int? = raw
    .lineSequence()
    .map { it.trim() }
    .firstOrNull { it.isNotEmpty() }
    ?.toIntOrNull()
    ?.takeIf { it in 1..65535 }

/**
 * Helpers for wireless-debugging state and local ADB connectivity.
 * Pairing itself is handled by [AdbPairingService] (notification UX).
 */
object AdbPairing {
    private const val ADB_WIFI_ENABLED_SETTING = "adb_wifi_enabled"
    private const val ADB_TLS_PORT_PROPERTY = "service.adb.tls.port"
    private const val PROPERTY_READ_TIMEOUT_MS = 750L
    private const val PROPERTY_POLL_INTERVAL_MS = 1_000L

    /**
     * Enables wireless debugging programmatically.
     * Requires WRITE_SECURE_SETTINGS.
     */
    fun enableWirelessAdb(context: Context): Boolean = try {
        Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED_SETTING, 1)
    } catch (e: SecurityException) {
        Log.w(TAG, "WRITE_SECURE_SETTINGS not granted", e)
        false
    }

    /**
     * Disables wireless debugging programmatically.
     * Used only to restore the previous state after a temporary local-ADB boot
     * bootstrap; callers decide whether RMG was the component that enabled it.
     */
    fun disableWirelessAdb(context: Context): Boolean = try {
        Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED_SETTING, 0)
    } catch (e: SecurityException) {
        Log.w(TAG, "WRITE_SECURE_SETTINGS not granted", e)
        false
    }

    fun isWirelessAdbEnabled(context: Context): Boolean = try {
        Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED_SETTING, 0) == 1
    } catch (e: Exception) {
        false
    }

    fun hasWriteSecureSettings(context: Context): Boolean =
        context.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Resolve the local TLS connect port without making Wi-Fi/mDNS a hard
     * prerequisite. Android's adbd publishes its active TLS port through
     * service.adb.tls.port; mDNS remains a compatibility fallback when a network
     * is available. The returned socket is still used only on 127.0.0.1.
     */
    fun discoverConnectPort(context: Context, timeoutMs: Long = 15_000): Int {
        readTlsPortProperty()?.let { port ->
            Log.i(TAG, "Local ADB connect port resolved from $ADB_TLS_PORT_PROPERTY: $port")
            return port
        }

        val latch = CountDownLatch(1)
        val discoveredPort = AtomicInteger(-1)
        val mdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { discovered ->
            if (discovered > 0 && discoveredPort.compareAndSet(-1, discovered)) {
                latch.countDown()
            }
        }
        mdns.start()
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
            while (discoveredPort.get() <= 0) {
                readTlsPortProperty()?.let { propertyPort ->
                    discoveredPort.compareAndSet(-1, propertyPort)
                    Log.i(TAG, "Local ADB connect port resolved without mDNS: $propertyPort")
                    break
                }

                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0L) break
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L)
                val waitMs = minOf(PROPERTY_POLL_INTERVAL_MS, remainingMs)
                if (latch.await(waitMs, TimeUnit.MILLISECONDS)) break
            }
        } finally {
            mdns.stop()
        }

        val port = discoveredPort.get().takeIf { it > 0 } ?: readTlsPortProperty() ?: -1
        if (port <= 0) {
            Log.w(TAG, "No local ADB TLS connect port found via property or mDNS")
        }
        return port
    }

    /**
     * Tests connectivity to the loopback ADB daemon. Network connectivity is not
     * required when service.adb.tls.port is available.
     */
    fun testConnection(context: Context): Boolean {
        val port = discoverConnectPort(context)
        if (port <= 0) {
            Log.w(TAG, "No local ADB TLS connect port found")
            return false
        }
        return try {
            val keyManager = AdbKeyManager(context)
            val result = LocalAdbClient.shellOnce("127.0.0.1", port, keyManager, "id")
            result.output.contains("uid=")
        } catch (e: Exception) {
            Log.w(TAG, "Connection test failed", e)
            false
        }
    }

    private fun readTlsPortProperty(): Int? = runCatching {
        val process = ProcessBuilder("/system/bin/getprop", ADB_TLS_PORT_PROPERTY)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(PROPERTY_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            runCatching { process.waitFor(100, TimeUnit.MILLISECONDS) }
            if (process.isAlive) process.destroyForcibly()
            return@runCatching null
        }
        if (process.exitValue() != 0) return@runCatching null
        parseAdbTlsPort(process.inputStream.bufferedReader().use { it.readText() })
    }.getOrNull()
}
