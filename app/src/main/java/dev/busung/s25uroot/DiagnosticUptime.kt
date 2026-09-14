package dev.busung.s25uroot

import android.os.SystemClock
import kotlinx.coroutines.delay

/**
 * Plain Android-side minimum boot-uptime gates for CZG3.
 *
 * The manual setting keeps the historical "Diagnostic Launch Time" behavior.
 * Auto Root deliberately uses its own shorter conservative floor so changing
 * boot automation latency cannot silently change Manual Standalone behavior.
 * Neither gate performs diagnostics, observation, logging or native
 * instrumentation, and neither touches the exploit hot path.
 */
internal object DiagnosticUptime {
    /** Manual Online/Offline default; preserves the established manual behavior. */
    const val DEFAULT_SECONDS = 120

    /**
     * Auto Root default total kernel uptime.
     *
     * 60 s keeps a modest post-boot stabilization margin for the still-racy FOPS
     * stage while removing the former mandatory 120 s Auto Root wait. Because
     * BOOT_COMPLETED itself normally arrives after part of this interval has
     * elapsed, the actual foreground wait is only the remaining time to 60 s.
     */
    const val AUTO_ROOT_DEFAULT_SECONDS = 60

    val allowedSeconds = listOf(0, 30, 60, 90, 120, 180, 300, 600)

    fun normalize(seconds: Int): Int =
        allowedSeconds.minByOrNull { kotlin.math.abs(it - seconds) } ?: DEFAULT_SECONDS

    suspend fun waitUntil(seconds: Int) {
        val targetMillis = normalize(seconds) * 1_000L
        while (true) {
            val remaining = targetMillis - SystemClock.elapsedRealtime()
            if (remaining <= 0L) return
            delay(minOf(remaining, 1_000L))
        }
    }
}

internal fun isExactCzg3(profile: TargetProfile): Boolean =
    profile.profileId == "pa3q-S938BXXSBCZG3"

internal fun isExactCzg3(device: DeviceSnapshot): Boolean =
    device.manufacturer.equals("samsung", ignoreCase = true) &&
        device.model == "SM-S938B" &&
        device.device == "pa3q" &&
        device.buildId == "BP4A.251205.006.S938BXXSBCZG3" &&
        device.kernelRelease == "6.6.98-android15-8-pd6ff1cd-abogkiS938BXXSBCZG3-4k"
