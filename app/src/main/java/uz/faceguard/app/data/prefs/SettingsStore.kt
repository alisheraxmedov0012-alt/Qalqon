package uz.faceguard.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import uz.faceguard.app.domain.model.AppSettings
import uz.faceguard.app.domain.model.BlockPolicy
import uz.faceguard.app.domain.model.ScanMode

/** Single app-wide preferences DataStore (file name unchanged). */
val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * DataStore-backed settings, scoped to the signed-in account.
 *
 * Why scoped: the legacy layout kept one global copy of every setting, so a
 * second account on the same device inherited the first account's policy.
 * Values now live under `acc_<accountId>_<name>`; the old un-prefixed keys are
 * still read as a fallback and are claimed once by the first account that opens
 * the app after the upgrade (see [claimLegacyKeys]), so nothing is lost and the
 * legacy value cannot leak to another account later.
 *
 * Signed out -> defaults; `protectionEnabled` defaults to false, so a
 * signed-out device is never left protected by accident.
 */
@Singleton
class SettingsStore @Inject constructor(
    private val store: DataStore<Preferences>,
    private val sessionManager: SessionManager,
) {

    val settings: Flow<AppSettings> = sessionManager.currentAccountId.flatMapLatest { accountId ->
        if (accountId == null) {
            flowOf(AppSettings())
        } else {
            store.data
                .map { prefs -> prefs.toAppSettings(accountId) }
                .onStart { claimLegacyKeys(accountId) }
        }
    }

    suspend fun setProtectionEnabled(enabled: Boolean) =
        writeBoolean(KEY_PROTECTION_ENABLED, enabled)

    suspend fun setLowBatteryBehaviorEnabled(enabled: Boolean) =
        writeBoolean(KEY_LOW_BATTERY_BEHAVIOR, enabled)

    suspend fun setScanMode(mode: ScanMode) = writeString(KEY_SCAN_MODE, mode.name)

    suspend fun setUnknownUserPolicy(policy: BlockPolicy) =
        writeString(KEY_UNKNOWN_POLICY, policy.name)

    suspend fun setNoFacePolicy(policy: BlockPolicy) = writeString(KEY_NO_FACE_POLICY, policy.name)

    /** Stored in milliseconds; out-of-range values are rejected before persisting. */
    suspend fun setRecoveryDelayMs(delayMs: Long) =
        writeLong(KEY_RECOVERY_DELAY_MS, validRecoveryDelayMs(delayMs))

    // ------------------------------------------------- Phase 4 screen time

    /**
     * The child this device's screen time is accounted against, or `null` when none is
     * selected.
     *
     * Account-scoped like every other setting, but read/written by an **explicit**
     * account id rather than the signed-in session: the background collector resolves
     * the account itself, and must never fall back to another account's selection.
     * There is deliberately no default and no fallback to "the first child" — an
     * absent value means "no target", not "pick one".
     */
    suspend fun activeChildId(accountId: Long): Long? =
        store.data.first()[scopedLongKey(accountId, KEY_ACTIVE_CHILD_ID.name)]

    /**
     * [activeChildId] as an observable stream, so a screen reflects the selection without
     * polling or re-reading on every recomposition. Emits `null` while nothing is selected
     * — including right after an account switch, so a previous account's choice is never
     * shown as if it were the new one's.
     */
    fun observeActiveChildId(accountId: Long): Flow<Long?> =
        store.data.map { it[scopedLongKey(accountId, KEY_ACTIVE_CHILD_ID.name)] }

    /** Sets the screen-time target for [accountId]. */
    suspend fun setActiveChildId(accountId: Long, childId: Long) {
        store.edit { it[scopedLongKey(accountId, KEY_ACTIVE_CHILD_ID.name)] = childId }
    }

    /** Forgets the screen-time target for [accountId]; other accounts are untouched. */
    suspend fun clearActiveChildId(accountId: Long) {
        store.edit { it.remove(scopedLongKey(accountId, KEY_ACTIVE_CHILD_ID.name)) }
    }

    /** Wipes every preference (all accounts); used by the full reset tool. */
    suspend fun clearAll() {
        store.edit { it.clear() }
    }

    // ------------------------------------------------------------------ read

    private fun Preferences.toAppSettings(accountId: Long) = AppSettings(
        protectionEnabled = booleanValue(accountId, KEY_PROTECTION_ENABLED, false),
        scanMode = parseEnumValue(stringValue(accountId, KEY_SCAN_MODE), ScanMode.BALANCED),
        recoveryDelayMs = validRecoveryDelayMs(longValue(accountId, KEY_RECOVERY_DELAY_MS)),
        unknownUserPolicy = parseEnumValue(
            stringValue(accountId, KEY_UNKNOWN_POLICY),
            BlockPolicy.SOFT_BLOCK,
        ),
        noFacePolicy = parseEnumValue(
            stringValue(accountId, KEY_NO_FACE_POLICY),
            BlockPolicy.ALLOW,
        ),
        lowBatteryBehaviorEnabled = booleanValue(accountId, KEY_LOW_BATTERY_BEHAVIOR, true),
    )

    private fun Preferences.booleanValue(
        accountId: Long,
        legacy: Preferences.Key<Boolean>,
        fallback: Boolean,
    ): Boolean = this[scopedBooleanKey(accountId, legacy.name)] ?: this[legacy] ?: fallback

    private fun Preferences.stringValue(
        accountId: Long,
        legacy: Preferences.Key<String>,
    ): String? = this[scopedStringKey(accountId, legacy.name)] ?: this[legacy]

    private fun Preferences.longValue(
        accountId: Long,
        legacy: Preferences.Key<Long>,
    ): Long? = this[scopedLongKey(accountId, legacy.name)] ?: this[legacy]

    // ----------------------------------------------------------------- write

    private suspend fun writeBoolean(key: Preferences.Key<Boolean>, value: Boolean) =
        withAccount { id ->
            store.edit {
                it[scopedBooleanKey(id, key.name)] = value
                it.remove(key)
            }
        }

    private suspend fun writeString(key: Preferences.Key<String>, value: String) =
        withAccount { id ->
            store.edit {
                it[scopedStringKey(id, key.name)] = value
                it.remove(key)
            }
        }

    private suspend fun writeLong(key: Preferences.Key<Long>, value: Long) =
        withAccount { id ->
            store.edit {
                it[scopedLongKey(id, key.name)] = value
                it.remove(key)
            }
        }

    /** Settings only exist for a signed-in account; writes while signed out are ignored. */
    private suspend fun withAccount(block: suspend (Long) -> Unit) {
        val accountId = sessionManager.currentAccountId.first() ?: return
        block(accountId)
    }

    /**
     * One-time move of un-prefixed (pre-multi-account) values into the given
     * account's namespace. Atomic, and a no-op once the legacy keys are gone.
     */
    private suspend fun claimLegacyKeys(accountId: Long) {
        store.edit { prefs ->
            if (LEGACY_KEYS.none { prefs.contains(it) }) return@edit

            prefs[KEY_PROTECTION_ENABLED]?.let {
                prefs[scopedBooleanKey(accountId, KEY_PROTECTION_ENABLED.name)] = it
            }
            prefs[KEY_LOW_BATTERY_BEHAVIOR]?.let {
                prefs[scopedBooleanKey(accountId, KEY_LOW_BATTERY_BEHAVIOR.name)] = it
            }
            prefs[KEY_SCAN_MODE]?.let { prefs[scopedStringKey(accountId, KEY_SCAN_MODE.name)] = it }
            prefs[KEY_UNKNOWN_POLICY]?.let {
                prefs[scopedStringKey(accountId, KEY_UNKNOWN_POLICY.name)] = it
            }
            prefs[KEY_NO_FACE_POLICY]?.let {
                prefs[scopedStringKey(accountId, KEY_NO_FACE_POLICY.name)] = it
            }
            prefs[KEY_RECOVERY_DELAY_MS]?.let {
                prefs[scopedLongKey(accountId, KEY_RECOVERY_DELAY_MS.name)] = it
            }

            LEGACY_KEYS.forEach { prefs.remove(it) }
        }
    }

    private fun scopedBooleanKey(accountId: Long, name: String) =
        booleanPreferencesKey(scopedSettingsKeyName(accountId, name))

    private fun scopedStringKey(accountId: Long, name: String) =
        stringPreferencesKey(scopedSettingsKeyName(accountId, name))

    private fun scopedLongKey(accountId: Long, name: String) =
        longPreferencesKey(scopedSettingsKeyName(accountId, name))

    private companion object {
        // Legacy (global) keys — kept only so pre-existing installs migrate cleanly.
        val KEY_PROTECTION_ENABLED = booleanPreferencesKey("protection_enabled")
        val KEY_SCAN_MODE = stringPreferencesKey("scan_mode")
        val KEY_RECOVERY_DELAY_MS = longPreferencesKey("recovery_delay_ms")
        val KEY_UNKNOWN_POLICY = stringPreferencesKey("unknown_user_policy")
        val KEY_NO_FACE_POLICY = stringPreferencesKey("no_face_policy")
        val KEY_LOW_BATTERY_BEHAVIOR = booleanPreferencesKey("low_battery_behavior")

        /**
         * Phase 4 Step 1B-7: account-scoped screen-time target. Deliberately absent from
         * [LEGACY_KEYS] — it has no pre-multi-account counterpart, so there is nothing to
         * claim and nothing that could leak from a legacy value.
         */
        val KEY_ACTIVE_CHILD_ID = longPreferencesKey("active_child_id")

        val LEGACY_KEYS: List<Preferences.Key<*>> = listOf(
            KEY_PROTECTION_ENABLED,
            KEY_SCAN_MODE,
            KEY_RECOVERY_DELAY_MS,
            KEY_UNKNOWN_POLICY,
            KEY_NO_FACE_POLICY,
            KEY_LOW_BATTERY_BEHAVIOR,
        )
    }
}
