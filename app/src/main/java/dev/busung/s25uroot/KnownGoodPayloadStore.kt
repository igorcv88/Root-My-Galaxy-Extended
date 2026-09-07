package dev.busung.s25uroot

import android.content.Context
import android.system.Os
import java.io.File
import java.io.FileOutputStream

/**
 * Minimal last-known-good offline payload store.
 *
 * Nothing is written here before or during the exploit. Manual Online publishes
 * a payload only after the exploit has succeeded and KernelSU has been verified.
 * Manual Offline and Auto Root only read this private cache and revalidate exact
 * target identity plus artifact size/SHA-256.
 */
internal object KnownGoodPayloadStore {
    private const val PREFS = "known_good_payload"
    private const val ACTIVE = "active"
    private const val ROOT = "payloads/known-good"
    private const val MANIFEST = "target-v3.json"
    private const val EXPLOIT = "cve-2026-43499-app.so"
    private const val KSUD = "ksud-s25u-kdp"
    private const val ROOT_HELPER_LIBRARY = "libcve43499root.so"
    private val CACHE_ID = Regex("v3-[0-9a-f]{16}-[0-9a-f]{16}(?:-[0-9a-f]{16})?")

    fun hasValid(context: Context): Boolean = runCatching {
        load(context)
        true
    }.getOrDefault(false)

    fun load(context: Context, profileId: String? = null): VerifiedPayloads {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(ACTIVE, null)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: error("No last-known-good payload is available. Run Manual Online successfully first.")
        require(CACHE_ID.matches(id)) { "Invalid last-known-good payload reference" }

        val payloads = loadDirectory(context, File(context.filesDir, "$ROOT/$id"))
        if (profileId != null) {
            require(payloads.profile.profileId == profileId) {
                "The cached payload does not match the selected profile"
            }
        }
        return payloads
    }

    /** Publish only after the normal install path has verified KernelSU. */
    @Synchronized
    fun publish(context: Context, payloads: VerifiedPayloads): VerifiedPayloads {
        require(payloads.source == PayloadSource.Online) {
            "Only a verified Manual Online payload can replace the offline cache"
        }
        val profile = payloads.profile
        require(profile.exactMatch != null && profile.matches(DeviceSnapshot.current())) {
            "Only the exact verified target can become the offline payload"
        }
        require(fileMatchesArtifact(payloads.exploit, profile.exploit)) {
            "Exploit failed final cache verification"
        }
        require(fileMatchesArtifact(payloads.kernelSu, profile.kernelSu.artifact)) {
            "KernelSU failed final cache verification"
        }
        verifyBundledRootHelper(context, profile)

        val id = cacheId(profile)
        val root = File(context.filesDir, ROOT).apply {
            require(mkdirs() || isDirectory) { "Unable to create offline payload cache" }
        }
        val destination = File(root, id)

        val reusable = runCatching {
            val existing = loadDirectory(context, destination)
            existing.profile.profileId == profile.profileId &&
                existing.profile.exploit.sha256 == profile.exploit.sha256 &&
                existing.profile.kernelSu.artifact.sha256 == profile.kernelSu.artifact.sha256 &&
                existing.profile.rootHelper?.sha256 == profile.rootHelper?.sha256
        }.getOrDefault(false)

        if (!reusable) {
            val temporary = File(root, ".$id-${System.nanoTime()}.tmp")
            temporary.deleteRecursively()
            require(temporary.mkdirs()) { "Unable to create offline payload candidate" }
            try {
                copyVerified(payloads.exploit, File(temporary, EXPLOIT), profile.exploit)
                copyVerified(payloads.kernelSu, File(temporary, KSUD), profile.kernelSu.artifact)
                writeSynced(
                    File(temporary, MANIFEST),
                    SupportManifest(3, listOf(profile)).toJsonBytes(),
                )
                loadDirectory(context, temporary)

                if (destination.exists()) destination.deleteRecursively()
                require(temporary.renameTo(destination)) { "Unable to publish offline payload cache" }
            } finally {
                temporary.deleteRecursively()
            }
        }

        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(ACTIVE, id)
            .commit()
        require(stored) { "Unable to activate offline payload cache" }

        root.listFiles()
            ?.filter { it.name != id }
            ?.forEach(File::deleteRecursively)

        return loadDirectory(context, destination)
    }

    private fun loadDirectory(context: Context, directory: File): VerifiedPayloads {
        require(directory.isDirectory) { "Offline payload directory is missing" }
        val manifestFile = File(directory, MANIFEST)
        require(manifestFile.isFile) { "Offline payload manifest is missing" }
        val manifest = SupportManifest.parse(manifestFile.readBytes())
        require(manifest.targets.size == 1) { "Offline payload manifest is invalid" }
        val profile = manifest.targets.single()
        require(profile.exactMatch != null && profile.matches(DeviceSnapshot.current())) {
            context.getString(R.string.autoroot_unsupported_firmware)
        }

        // v0265-era caches predate the root-helper binding. Once the APK ships a
        // v0266 helper, accepting one of those old two-hash cache IDs would pair
        // an old exploit with a different helper. Parser compatibility is kept,
        // but executable offline state is deliberately invalidated and must be
        // refreshed by one successful Manual Online run.
        require(profile.rootHelper != null) {
            "Offline payload cache predates root-helper verification; run Manual Online successfully to refresh it"
        }
        verifyBundledRootHelper(context, profile)

        val exploit = File(directory, EXPLOIT)
        val kernelSu = File(directory, KSUD)
        require(fileMatchesArtifact(exploit, profile.exploit)) {
            context.getString(R.string.autoroot_cached_payload_invalid, exploit.name)
        }
        require(fileMatchesArtifact(kernelSu, profile.kernelSu.artifact)) {
            context.getString(R.string.autoroot_cached_payload_invalid, kernelSu.name)
        }
        Os.chmod(exploit.absolutePath, 0b100100100)
        Os.chmod(kernelSu.absolutePath, 0b100100100)
        return VerifiedPayloads(profile, exploit, kernelSu, PayloadSource.Offline)
    }

    private fun verifyBundledRootHelper(context: Context, profile: TargetProfile) {
        val expected = requireNotNull(profile.rootHelper) {
            "Support profile has no root-helper metadata; refresh the v3 feed before caching it offline"
        }
        val helper = File(context.applicationInfo.nativeLibraryDir, ROOT_HELPER_LIBRARY)
        require(fileMatchesArtifact(helper, expected)) {
            "The cached payload requires a different root helper; update Root My Galaxy and refresh Manual Online"
        }
    }

    private fun cacheId(profile: TargetProfile): String {
        val expected = requireNotNull(profile.rootHelper) {
            "Cannot create an offline cache without root-helper metadata"
        }
        val base = "v3-${profile.exploit.sha256.take(16)}-${profile.kernelSu.artifact.sha256.take(16)}"
        return "$base-${expected.sha256.take(16)}"
    }

    private fun copyVerified(source: File, destination: File, artifact: RemoteArtifact) {
        require(fileMatchesArtifact(source, artifact))
        source.inputStream().use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        Os.chmod(destination.absolutePath, 0b100100100)
        require(fileMatchesArtifact(destination, artifact))
    }

    private fun writeSynced(destination: File, bytes: ByteArray) {
        FileOutputStream(destination).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }
}
