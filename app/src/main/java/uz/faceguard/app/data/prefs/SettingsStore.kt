package uz.faceguard.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uz.faceguard.app.domain.model.AppSettings
import uz.faceguard.app.domain.model.BlockPolicy
import uz.faceguard.app.domain.model.ScanMode

private val Context.settingsStore by preferencesDataStore(name = "settings")

/** DataStore-backed settings; everything stays on-device. */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store = context.settingsStore

    val settings: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            protectionEnabled = prefs[KEY_PROTECTION_ENABLED] ?: false,
            scanMode = runCatching {
                ScanMode.valueOf(prefs[KEY_SCAN_MODE] ?: ScanMode.BALANCED.name)
            }.getOrDefault(ScanMode.BALANCED),
            recoveryDelayMs = prefs[KEY_RECOVERY_DELAY_MS] ?: AppSettings.DEFAULT_RECOVERY_DELAY_MS,
            unknownUserPolicy = runCatching {
                BlockPolicy.valueOf(prefs[KEY_UNKNOWN_POLICY] ?: BlockPolicy.SOFT_BLOCK.name)
            }.getOrDefault(BlockPolicy.SOFT_BLOCK),
            noFacePolicy = runCatching {
                BlockPolicy.valueOf(prefs[KEY_NO_FACE_POLICY] ?: BlockPolicy.ALLOW.name)
            }.getOrDefault(BlockPolicy.ALLOW),
            lowBatteryBehaviorEnabled = prefs[KEY_LOW_BATTERY_BEHAVIOR] ?: true,
        )
    }

    suspend fun setProtectionEnabled(enabled: Boolean) {
        store.edit { it[KEY_PROTECTION_ENABLED] = enabled }
    }

    suspend fun setScanMode(mode: ScanMode) {
        store.edit { it[KEY_SCAN_MODE] = mode.name }
    }

    suspend fun setRecoveryDelayMs(delayMs: Long) {
        store.edit { it[KEY_RECOVERY_DELAY_MS] = delayMs }
    }

    suspend fun setUnknownUserPolicy(policy: BlockPolicy) {
        store.edit { it[KEY_UNKNOWN_POLICY] = policy.name }
    }

    suspend fun setNoFacePolicy(policy: BlockPolicy) {
        store.edit { it[KEY_NO_FACE_POLICY] = policy.name }
    }

    suspend fun setLowBatteryBehaviorEnabled(enabled: Boolean) {
        store.edit { it[KEY_LOW_BATTERY_BEHAVIOR] = enabled }
    }

    /** Wipes all preferences; used by the full reset tool. */
    suspend fun clearAll() {
        store.edit { it.clear() }
    }

    private companion object {
        val KEY_PROTECTION_ENABLED = booleanPreferencesKey("protection_enabled")
        val KEY_SCAN_MODE = stringPreferencesKey("scan_mode")
        val KEY_RECOVERY_DELAY_MS = longPreferencesKey("recovery_delay_ms")
        val KEY_UNKNOWN_POLICY = stringPreferencesKey("unknown_user_policy")
        val KEY_NO_FACE_POLICY = stringPreferencesKey("no_face_policy")
        val KEY_LOW_BATTERY_BEHAVIOR = booleanPreferencesKey("low_battery_behavior")
    }
}
