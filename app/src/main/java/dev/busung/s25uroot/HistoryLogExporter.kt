package dev.busung.s25uroot

import android.content.Context
import android.net.Uri
import android.widget.Toast
import java.io.BufferedOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object HistoryLogExporter {
    fun archiveFileName(entries: Collection<InstallHistoryEntry>): String =
        "RootMyGalaxy-logs-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) +
            "-${entries.size}.zip"

    fun save(context: Context, uri: Uri, entries: Collection<InstallHistoryEntry>) {
        val snapshot = entries.toList()
        val saved = runCatching {
            require(snapshot.isNotEmpty()) { "No logs selected" }
            context.contentResolver.openOutputStream(uri)?.use { raw ->
                ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                    snapshot.forEach { entry ->
                        zip.putNextEntry(ZipEntry(entryFileName(entry)))
                        val content = entry.log.ifBlank { context.getString(R.string.history_log_empty) }
                        zip.write(content.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                }
            } ?: error("Unable to open destination")
            true
        }.getOrDefault(false)

        Toast.makeText(
            context,
            if (saved) {
                context.getString(R.string.export_logs_saved, snapshot.size)
            } else {
                context.getString(R.string.export_logs_failed)
            },
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun entryFileName(entry: InstallHistoryEntry): String =
        "RootMyGalaxy-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(entry.startedAtMillis)) +
            "-${entry.result.name.lowercase(Locale.US)}-${entry.id.take(8)}.log"
}
