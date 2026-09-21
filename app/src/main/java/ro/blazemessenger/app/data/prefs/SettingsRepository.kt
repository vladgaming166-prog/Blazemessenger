package ro.blazemessenger.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ro.blazemessenger.app.domain.model.AppLanguage
import ro.blazemessenger.app.domain.model.LocalSettings
import ro.blazemessenger.app.domain.model.RingtoneChoice
import ro.blazemessenger.app.domain.model.ThemeMode

private val Context.dataStore by preferencesDataStore(name = "blaze_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val settings: Flow<LocalSettings> = context.dataStore.data.map { prefs ->
        LocalSettings(
            themeMode = prefs[Keys.theme].toTheme(),
            language = prefs[Keys.language].toLanguage(),
            ringtone = prefs[Keys.ringtone].toRingtone(),
            customRingtoneUri = prefs[Keys.customUri].orEmpty(),
            ringtoneVolume = prefs[Keys.volume] ?: 0.8f,
            vibrateCalls = prefs[Keys.vibrateCalls] ?: true,
            messageNotifications = prefs[Keys.messages] ?: true,
            messagePreview = prefs[Keys.preview] ?: true,
            messageVibrate = prefs[Keys.messageVibrate] ?: true,
            onboardingDone = prefs[Keys.onboarding] ?: false,
        )
    }

    suspend fun setTheme(mode: ThemeMode) = put(Keys.theme, mode.name)
    suspend fun setLanguage(language: AppLanguage) = put(Keys.language, language.name)
    suspend fun setRingtone(choice: RingtoneChoice) = put(Keys.ringtone, choice.name)
    suspend fun setCustomRingtone(uri: String) = put(Keys.customUri, uri)
    suspend fun setRingtoneVolume(volume: Float) {
        context.dataStore.edit { it[Keys.volume] = volume.coerceIn(0f, 1f) }
    }
    suspend fun setVibrateCalls(enabled: Boolean) = putBool(Keys.vibrateCalls, enabled)
    suspend fun setMessageNotifications(enabled: Boolean) = putBool(Keys.messages, enabled)
    suspend fun setMessagePreview(enabled: Boolean) = putBool(Keys.preview, enabled)
    suspend fun setMessageVibrate(enabled: Boolean) = putBool(Keys.messageVibrate, enabled)
    suspend fun setOnboardingDone() = putBool(Keys.onboarding, true)

    private suspend fun put(key: androidx.datastore.preferences.core.Preferences.Key<String>, value: String) {
        context.dataStore.edit { it[key] = value }
    }

    private suspend fun putBool(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, value: Boolean) {
        context.dataStore.edit { it[key] = value }
    }

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val language = stringPreferencesKey("language")
        val ringtone = stringPreferencesKey("ringtone")
        val customUri = stringPreferencesKey("custom_ringtone")
        val volume = floatPreferencesKey("ringtone_volume")
        val vibrateCalls = booleanPreferencesKey("vibrate_calls")
        val messages = booleanPreferencesKey("message_notifications")
        val preview = booleanPreferencesKey("message_preview")
        val messageVibrate = booleanPreferencesKey("message_vibrate")
        val onboarding = booleanPreferencesKey("onboarding_done")
    }
}

private fun String?.toTheme(): ThemeMode = runCatching { ThemeMode.valueOf(this ?: "") }.getOrDefault(ThemeMode.SYSTEM)
private fun String?.toLanguage(): AppLanguage = runCatching { AppLanguage.valueOf(this ?: "") }.getOrDefault(AppLanguage.SYSTEM)
private fun String?.toRingtone(): RingtoneChoice = runCatching { RingtoneChoice.valueOf(this ?: "") }.getOrDefault(RingtoneChoice.DEFAULT)
