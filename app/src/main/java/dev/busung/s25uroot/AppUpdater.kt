package dev.busung.s25uroot

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

const val ROOT_MY_GALAXY_URL = "https://github.com/igorcv88/Root-My-Galaxy-Extended"

object AppUpdater {
    private const val RELEASES_PAGE = "$ROOT_MY_GALAXY_URL/releases"

    /**
     * Keep the production runtime quiescent around Manual/Auto Root exactly like
     * the validated Labs baseline. Release discovery/download stays out of the
     * app process; users can open the public releases page explicitly instead.
     */
    suspend fun fetchLatestRelease(): UpdateInfo = withContext(Dispatchers.Default) {
        UpdateInfo(
            versionName = BuildConfig.VERSION_NAME,
            apkUrl = null,
            releaseUrl = RELEASES_PAGE,
        )
    }

    fun isUpdateAvailable(latestVersion: String, currentVersion: String): Boolean = false

    suspend fun downloadApk(
        context: Context,
        url: String,
        onProgress: (Float) -> Unit = {},
    ): File? = null

    fun installApk(context: Context, apk: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun openReleasesPage(context: Context) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE)))
    }
}

data class UpdateInfo(
    val versionName: String,
    val apkUrl: String?,
    val releaseUrl: String,
)
