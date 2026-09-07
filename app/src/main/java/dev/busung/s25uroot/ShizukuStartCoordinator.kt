package dev.busung.s25uroot

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serializes Shizuku startup attempts across Root My Galaxy processes.
 *
 * Auto Root runs in :autoroot_exec while the pre-root boot coordinator runs in
 * the main app process. A process-local Mutex is therefore insufficient. The
 * advisory FileLock closes the narrow race where both paths observed no Binder
 * and were about to launch a second Shizuku server at the same time.
 */
internal object ShizukuStartCoordinator {
    private val localMutex = Mutex()

    suspend fun <T> withStartLock(
        context: Context,
        block: suspend () -> T,
    ): T = localMutex.withLock {
        withContext(Dispatchers.IO) {
            val lockFile = File(context.noBackupFilesDir, LOCK_FILE_NAME)
            RandomAccessFile(lockFile, "rw").use { raf ->
                raf.channel.use { channel ->
                    val lock = channel.lock()
                    try {
                        block()
                    } finally {
                        runCatching { lock.release() }
                    }
                }
            }
        }
    }

    private const val LOCK_FILE_NAME = "shizuku-start.lock"
}
