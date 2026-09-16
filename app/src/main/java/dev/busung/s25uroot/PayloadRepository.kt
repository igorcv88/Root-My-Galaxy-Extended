package dev.busung.s25uroot

import android.content.Context
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class VerifiedPayloads(
    val profile: TargetProfile,
    val exploit: File,
    val kernelSu: File,
    val source: PayloadSource = PayloadSource.Online,
)

class PayloadRepository(private val context: Context) {
    fun loadTargets(): List<TargetProfile> {
        val manifestBytes = context.assets.open(PRODUCTION_MANIFEST_ASSET).use { it.readBytes() }
        return SupportManifest.parse(manifestBytes).targets.map { profile ->
            profile.copy(
                exploit = profile.exploit.copy(url = validateProductionUrl(profile.exploit.url)),
                kernelSu = profile.kernelSu.copy(
                    artifact = profile.kernelSu.artifact.copy(
                        url = validateProductionUrl(profile.kernelSu.artifact.url),
                    ),
                ),
                rootHelper = profile.rootHelper?.copy(
                    url = validateProductionUrl(profile.rootHelper.url),
                ),
            )
        }
    }

    fun resolveTarget(snapshot: DeviceSnapshot): TargetProfile = loadTargets()
        .firstOrNull { it.matches(snapshot) }
        ?: error(context.getString(R.string.repo_no_profile))

    fun resolveTarget(profileId: String): TargetProfile {
        if (profileId.startsWith(OFFLINE_REQUEST_PREFIX)) {
            val requestedProfileId = profileId
                .removePrefix(OFFLINE_REQUEST_PREFIX)
                .takeIf(String::isNotBlank)
            return KnownGoodPayloadStore.load(context, requestedProfileId)
                .profile
                .copy(source = PayloadSource.Offline)
        }
        return loadTargets()
            .firstOrNull { it.profileId == profileId }
            ?: error(context.getString(R.string.repo_profile_missing, profileId))
    }

    fun download(profile: TargetProfile, onProgress: (String) -> Unit): VerifiedPayloads {
        verifyBundledRootHelper(profile)

        if (profile.source == PayloadSource.Offline) {
            onProgress("Payload source: last-known-good offline cache")
            val payloads = KnownGoodPayloadStore.load(context, profile.profileId)
            KernelSuBootstrapStore.prepare(context, payloads)
            onProgress("KernelSU bootstrap source prepared for root-side auto-late-load")
            return payloads
        }

        onProgress("Payload source: bundled production payload snapshot")
        val directory = File(context.filesDir, "payloads/production-bundled/${profile.profileId}")
        directory.deleteRecursively()
        require(directory.mkdirs() || directory.isDirectory) {
            context.getString(R.string.repo_finalize_failed, directory.name)
        }
        val exploit = materializeArtifact(
            profile.exploit,
            File(directory, "cve-2026-43499-app.so"),
            context.getString(R.string.artifact_exploit),
            onProgress,
        )
        val kernelSu = materializeArtifact(
            profile.kernelSu.artifact,
            File(directory, "ksud-s25u-kdp"),
            context.getString(R.string.artifact_kernelsu),
            onProgress,
        )
        Os.chmod(exploit.absolutePath, 0b100100100)
        Os.chmod(kernelSu.absolutePath, 0b100100100)
        val payloads = VerifiedPayloads(profile, exploit, kernelSu, PayloadSource.Online)
        KernelSuBootstrapStore.prepare(context, payloads)
        onProgress("KernelSU bootstrap source prepared for root-side auto-late-load")
        return payloads
    }

    private fun verifyBundledRootHelper(profile: TargetProfile) {
        val expected = profile.rootHelper ?: return
        validateProductionUrl(expected.url)
        val helper = File(context.applicationInfo.nativeLibraryDir, ROOT_HELPER_LIBRARY)
        require(helper.isFile) {
            "The required root helper is not bundled in Root My Galaxy ${BuildConfig.VERSION_NAME}"
        }

        val actualSize = helper.length()
        require(actualSize == expected.size) {
            "Bundled root helper size mismatch in Root My Galaxy ${BuildConfig.VERSION_NAME}: " +
                "expected=${expected.size} actual=$actualSize. Update/rebuild the app before running this payload."
        }

        val actualSha256 = sha256(helper)
        require(actualSha256 == expected.sha256) {
            "Bundled root helper SHA-256 mismatch in Root My Galaxy ${BuildConfig.VERSION_NAME}: " +
                "expected=${expected.sha256} actual=$actualSha256. Update/rebuild the app before running this payload."
        }
    }

    private fun materializeArtifact(
        artifact: RemoteArtifact,
        destination: File,
        label: String,
        onProgress: (String) -> Unit,
    ): File {
        val assetPath = productionAssetPath(artifact.url)
        onProgress("Loading bundled production $label")
        val temporary = File(destination.parentFile, "${destination.name}.part")
        if (temporary.exists()) temporary.delete()

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            context.assets.open(assetPath).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= artifact.size) {
                            context.getString(R.string.repo_size_exceeded, label)
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            require(total == artifact.size) { context.getString(R.string.repo_incomplete, label) }
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualSha256 == artifact.sha256) {
                "$label SHA-256 does not match the bundled production manifest"
            }
            if (destination.exists()) destination.delete()
            require(temporary.renameTo(destination)) {
                context.getString(R.string.repo_finalize_failed, label)
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
        onProgress("Bundled production $label verified")
        return destination
    }

    private fun validateProductionUrl(url: String): String {
        require(url.startsWith(PRODUCTION_RAW_PREFIX)) {
            "Root My Galaxy refuses non-production payload repository URL: $url"
        }
        productionAssetPath(url)
        return url
    }

    private fun productionAssetPath(url: String): String {
        require(url.startsWith(PRODUCTION_RAW_PREFIX)) {
            "Root My Galaxy refuses non-production payload repository URL: $url"
        }
        val relative = url.removePrefix(PRODUCTION_RAW_PREFIX)
        require(
            relative.isNotBlank() &&
                !relative.startsWith('/') &&
                relative.split('/').none { it == ".." },
        ) { "Unsafe production payload path: $relative" }
        return "$PRODUCTION_ASSET_ROOT/$relative"
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val OFFLINE_REQUEST_PREFIX = "offline-cache:"
        private const val PRODUCTION_PAYLOAD_REPOSITORY =
            "igorcv88/Root-My-Galaxy-Payloads-Extended"
        private const val PRODUCTION_RAW_PREFIX =
            "https://raw.githubusercontent.com/$PRODUCTION_PAYLOAD_REPOSITORY/main/"
        private const val PRODUCTION_ASSET_ROOT = "production-payloads"
        private const val PRODUCTION_MANIFEST_ASSET = "$PRODUCTION_ASSET_ROOT/targets-v3.json"
        private const val ROOT_HELPER_LIBRARY = "libcve43499root.so"

        fun offlineRequest(profileId: String?): String =
            OFFLINE_REQUEST_PREFIX + profileId.orEmpty()
    }
}
