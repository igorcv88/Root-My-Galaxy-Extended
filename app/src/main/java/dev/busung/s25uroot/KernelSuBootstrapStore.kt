package dev.busung.s25uroot

import android.content.Context
import android.system.Os
import java.io.File
import java.io.FileOutputStream

/**
 * App-private, deterministic KernelSU source for the post-exploit UID-0 helper.
 *
 * Standalone Auto Root cannot write /data/local/tmp before root exists. The
 * v0266 UMH helper, however, needs the verified ksud before its auto-late-load
 * starts. Keep one verified copy in app-private storage before the exploit; the
 * root helper promotes it into /data/local/tmp only after bootstrap root lands.
 *
 * This preparation is outside the exploit process/race and is skipped when the
 * existing copy already matches the feed-declared size and SHA-256.
 */
internal object KernelSuBootstrapStore {
    private const val DIRECTORY = "ksu-bootstrap"
    const val FILE_NAME = "ksud-s25u-kdp"

    @Synchronized
    fun prepare(context: Context, payloads: VerifiedPayloads): File {
        val artifact = payloads.profile.kernelSu.artifact
        val source = payloads.kernelSu
        require(fileMatchesArtifact(source, artifact)) {
            "KernelSU bootstrap source failed verification"
        }

        val directory = File(context.filesDir, DIRECTORY).apply {
            require(mkdirs() || isDirectory) {
                "Unable to create KernelSU bootstrap directory"
            }
        }
        val destination = File(directory, FILE_NAME)
        if (fileMatchesArtifact(destination, artifact)) {
            Os.chmod(destination.absolutePath, 0b111101101)
            return destination
        }

        val temporary = File(directory, ".$FILE_NAME-${System.nanoTime()}.tmp")
        temporary.delete()
        try {
            source.inputStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            Os.chmod(temporary.absolutePath, 0b111101101)
            require(fileMatchesArtifact(temporary, artifact)) {
                "KernelSU bootstrap copy failed final verification"
            }
            if (destination.exists()) require(destination.delete()) {
                "Unable to replace stale KernelSU bootstrap source"
            }
            require(temporary.renameTo(destination)) {
                "Unable to publish KernelSU bootstrap source"
            }
            Os.chmod(destination.absolutePath, 0b111101101)
            require(fileMatchesArtifact(destination, artifact)) {
                "Published KernelSU bootstrap source failed verification"
            }
            return destination
        } finally {
            temporary.delete()
        }
    }
}
