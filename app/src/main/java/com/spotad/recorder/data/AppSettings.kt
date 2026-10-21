package com.spotad.recorder.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Everything the user can configure. Kept as a single immutable value object so the UI and the
 * background service can never disagree about the current configuration.
 */
data class AppSettings(
    val monitoringEnabled: Boolean = false,
    val onboardingComplete: Boolean = false,
    val captureConsentGiven: Boolean = false,
    /** Safety valve: a recording can never run longer than this, even if the ad signal is lost. */
    val maxAdSeconds: Int = 120,
    /** 0 means "keep recordings until the user deletes them". */
    val retentionDays: Int = 30,
    val themeMode: ThemeMode = ThemeMode.SYSTEM
)

class SettingsStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun snapshot(): AppSettings = _settings.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        if (updated == _settings.value) return
        save(updated)
        _settings.value = updated
    }

    private fun load(): AppSettings = AppSettings(
        monitoringEnabled = prefs.getBoolean(KEY_MONITORING, false),
        onboardingComplete = prefs.getBoolean(KEY_ONBOARDING, false),
        captureConsentGiven = prefs.getBoolean(KEY_CAPTURE_CONSENT, false),
        maxAdSeconds = prefs.getInt(KEY_MAX_SECONDS, 120).coerceIn(15, 600),
        retentionDays = prefs.getInt(KEY_RETENTION, 30).coerceIn(0, 3650),
        themeMode = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name)!!)
        }.getOrDefault(ThemeMode.SYSTEM)
    )

    private fun save(settings: AppSettings) {
        prefs.edit()
            .putBoolean(KEY_MONITORING, settings.monitoringEnabled)
            .putBoolean(KEY_ONBOARDING, settings.onboardingComplete)
            .putBoolean(KEY_CAPTURE_CONSENT, settings.captureConsentGiven)
            .putInt(KEY_MAX_SECONDS, settings.maxAdSeconds)
            .putInt(KEY_RETENTION, settings.retentionDays)
            .putString(KEY_THEME, settings.themeMode.name)
            .apply()
    }

    companion object {
        const val FILE_NAME = "spotad_settings"
        private const val KEY_MONITORING = "monitoring_enabled"
        private const val KEY_ONBOARDING = "onboarding_complete"
        private const val KEY_CAPTURE_CONSENT = "capture_consent_given"
        private const val KEY_MAX_SECONDS = "max_ad_seconds"
        private const val KEY_RETENTION = "retention_days"
        private const val KEY_THEME = "theme_mode"

        @Volatile
        private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context).also { instance = it }
            }
    }
}
