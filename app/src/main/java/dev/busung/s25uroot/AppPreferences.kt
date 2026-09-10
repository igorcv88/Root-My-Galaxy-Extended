package dev.busung.s25uroot

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList

enum class AccentColor(val storedValue: String) {
    Dynamic("dynamic"),
    Blue("blue"),
    Violet("violet"),
    Green("green"),
    Orange("orange");

    companion object {
        fun fromStoredValue(value: String?): AccentColor =
            entries.firstOrNull { it.storedValue == value } ?: Dynamic
    }
}

enum class AppThemeMode(val storedValue: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    companion object {
        fun fromStoredValue(value: String?): AppThemeMode =
            entries.firstOrNull { it.storedValue == value } ?: System
    }
}

object AppPreferences {
    private const val PREFERENCES = "appearance"
    private const val ACCENT_COLOR = "accent_color"
    private const val THEME_MODE = "theme_mode"
    private const val ADVANCED_MODE = "advanced_mode"
    private const val SHIZUKU_MODE = "shizuku_mode"
    private const val AUTO_ROOT_ENABLED = "auto_root_enabled"
    // Keep the legacy storage key so existing users who enabled the old automatic
    // KernelSU soft reboot retain the opt-in when its behavior becomes the lighter
    // post-root Zygote restart.
    private const val RESTART_ZYGOTE_AFTER_ROOT = "soft_reboot_after_root"
    private const val AUTO_START_SHIZUKU_AFTER_ROOT = "auto_start_shizuku_after_root"
    private const val START_SHIZUKU_ON_BOOT = "start_shizuku_on_boot"
    private const val SHIZUKU_AUTOMATION_TOKEN = "shizuku_automation_token"
    private const val ADB_PAIRED = "adb_paired"
    private const val CZG3_BOOT_MIN_UPTIME_SEC = "czg3_boot_min_uptime_sec"
    private const val AUTO_ROOT_BOOT_MIN_UPTIME_SEC = "auto_root_boot_min_uptime_sec"
    private const val CONSUMED_INSTALL_REQUEST = "consumed_install_request"

    fun accentColor(context: Context): AccentColor = AccentColor.fromStoredValue(
        prefs(context).getString(ACCENT_COLOR, null),
    )

    fun setAccentColor(context: Context, color: AccentColor) {
        prefs(context).edit().putString(ACCENT_COLOR, color.storedValue).apply()
    }

    fun themeMode(context: Context): AppThemeMode = AppThemeMode.fromStoredValue(
        prefs(context).getString(THEME_MODE, null),
    )

    fun setThemeMode(context: Context, themeMode: AppThemeMode) {
        prefs(context).edit().putString(THEME_MODE, themeMode.storedValue).apply()
    }

    fun advancedMode(context: Context): Boolean =
        prefs(context).getBoolean(ADVANCED_MODE, false)

    fun setAdvancedMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(ADVANCED_MODE, enabled).apply()
    }

    fun shizukuMode(context: Context): Boolean =
        prefs(context).getBoolean(SHIZUKU_MODE, false)

    fun setShizukuMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(SHIZUKU_MODE, enabled).apply()
    }

    fun autoRootEnabled(context: Context): Boolean =
        prefs(context).getBoolean(AUTO_ROOT_ENABLED, false)

    fun setAutoRootEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(AUTO_ROOT_ENABLED, enabled).apply()
    }

    /**
     * Used only before a destructive reboot. `commit()` is deliberate: a reboot
     * must not race the asynchronous SharedPreferences disk write and come back
     * with Auto Root still enabled.
     */
    internal fun setAutoRootEnabledImmediately(context: Context, enabled: Boolean): Boolean =
        prefs(context).edit().putBoolean(AUTO_ROOT_ENABLED, enabled).commit()

    fun restartZygoteAfterRoot(context: Context): Boolean =
        prefs(context).getBoolean(RESTART_ZYGOTE_AFTER_ROOT, false)

    fun setRestartZygoteAfterRoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(RESTART_ZYGOTE_AFTER_ROOT, enabled).apply()
    }

    fun autoStartShizukuAfterRoot(context: Context): Boolean =
        prefs(context).getBoolean(AUTO_START_SHIZUKU_AFTER_ROOT, true)

    fun setAutoStartShizukuAfterRoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(AUTO_START_SHIZUKU_AFTER_ROOT, enabled).apply()
    }

    /**
     * Independent pre-root boot bootstrap. Default off so an existing Shizuku,
     * Tasker, or other boot starter remains the single owner until the user opts
     * in to RMG's redundant coordinator explicitly.
     */
    fun startShizukuOnBoot(context: Context): Boolean =
        prefs(context).getBoolean(START_SHIZUKU_ON_BOOT, false)

    fun setStartShizukuOnBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(START_SHIZUKU_ON_BOOT, enabled).apply()
    }

    /** Authentication token shown by the user's Shizuku automation UI. */
    fun shizukuAutomationToken(context: Context): String =
        prefs(context).getString(SHIZUKU_AUTOMATION_TOKEN, "").orEmpty()

    fun setShizukuAutomationToken(context: Context, token: String) {
        prefs(context).edit().putString(SHIZUKU_AUTOMATION_TOKEN, token.trim()).apply()
    }

    fun adbPaired(context: Context): Boolean =
        prefs(context).getBoolean(ADB_PAIRED, false)

    fun setAdbPaired(context: Context, paired: Boolean) {
        prefs(context).edit().putBoolean(ADB_PAIRED, paired).apply()
    }

    /** Manual Online/Offline launch gate. */
    fun czg3BootMinUptimeSeconds(context: Context): Int = DiagnosticUptime.normalize(
        prefs(context).getInt(CZG3_BOOT_MIN_UPTIME_SEC, DiagnosticUptime.DEFAULT_SECONDS),
    )

    fun setCzg3BootMinUptimeSeconds(context: Context, seconds: Int) {
        prefs(context).edit()
            .putInt(CZG3_BOOT_MIN_UPTIME_SEC, DiagnosticUptime.normalize(seconds))
            .apply()
    }

    /**
     * Auto Root has an independent total-uptime floor. Keeping this separate is
     * intentional: changing the automatic boot policy must not silently alter
     * Manual Standalone/Online/Offline behavior or the Advanced diagnostic knob.
     */
    fun autoRootBootMinUptimeSeconds(context: Context): Int = DiagnosticUptime.normalize(
        prefs(context).getInt(
            AUTO_ROOT_BOOT_MIN_UPTIME_SEC,
            DiagnosticUptime.AUTO_ROOT_DEFAULT_SECONDS,
        ),
    )

    fun setAutoRootBootMinUptimeSeconds(context: Context, seconds: Int) {
        prefs(context).edit()
            .putInt(AUTO_ROOT_BOOT_MIN_UPTIME_SEC, DiagnosticUptime.normalize(seconds))
            .apply()
    }

    @Synchronized
    fun consumeInstallRequest(context: Context, requestId: String?): Boolean {
        if (requestId.isNullOrBlank()) return false
        val preferences = prefs(context)
        if (preferences.getString(CONSUMED_INSTALL_REQUEST, null) == requestId) return false
        return preferences.edit().putString(CONSUMED_INSTALL_REQUEST, requestId).commit()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun languageTag(context: Context): String {
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        return if (locales.isEmpty) "" else locales[0].toLanguageTag()
    }

    fun setLanguage(context: Context, languageTag: String) {
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            LocaleList.forLanguageTags(languageTag)
    }
}
