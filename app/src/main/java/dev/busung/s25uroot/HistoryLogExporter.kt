package dev.busung.s25uroot

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.BufferedOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object HistoryLogExporter {
    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "history-log-export").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    fun archiveFileName(entries: Collection<InstallHistoryEntry>): String {
        val completedCount = entries.count { it.result != InstallRunResult.Running }
        return "RootMyGalaxy-logs-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) +
            "-$completedCount.zip"
    }

    /**
     * Exports a stable snapshot outside the UI thread. Running entries are
     * rejected at the exporter boundary even if a caller accidentally includes
     * one while selection mode is active.
     */
    fun save(context: Context, uri: Uri, entries: Collection<InstallHistoryEntry>) {
        val appContext = context.applicationContext
        val snapshot = entries
            .asSequence()
            .filter { it.result != InstallRunResult.Running }
            .toList()

        ioExecutor.execute {
            val saved = runCatching {
                require(snapshot.isNotEmpty()) { "No completed logs selected" }
                appContext.contentResolver.openOutputStream(uri)?.use { raw ->
                    ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                        snapshot.forEach { entry ->
                            zip.putNextEntry(ZipEntry(entryFileName(entry)))
                            val content = entry.log.ifBlank {
                                appContext.getString(R.string.history_log_empty)
                            }
                            zip.write(content.toByteArray(Charsets.UTF_8))
                            zip.closeEntry()
                        }
                    }
                } ?: error("Unable to open destination")
                true
            }.getOrDefault(false)

            mainHandler.post {
                Toast.makeText(
                    appContext,
                    if (saved) {
                        appContext.getString(R.string.export_logs_saved, snapshot.size)
                    } else {
                        appContext.getString(R.string.export_logs_failed)
                    },
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun entryFileName(entry: InstallHistoryEntry): String =
        "RootMyGalaxy-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(entry.startedAtMillis)) +
            "-${entry.result.name.lowercase(Locale.US)}-${entry.id.take(8)}.log"
}
