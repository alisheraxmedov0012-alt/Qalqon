package uz.faceguard.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import uz.faceguard.app.domain.security.PinAttemptPolicy
import uz.faceguard.app.domain.security.PinAttemptState

private val Context.securityStore by preferencesDataStore(name = "security")

/**
 * Phase 12: persisted, account-scoped PIN attempt state.
 *
 * Stores only counters and a lockout deadline — never the PIN, never a hash, never
 * key material. Survives process restarts (a security lockout must not be cleared
 * by killing the app) and is cleared on success and on a full reset.
 */
@Singleton
class PinAttemptStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store = context.securityStore

    suspend fun stateFor(accountId: Long): PinAttemptState {
        val prefs = store.data.first()
        return PinAttemptState(
            failedCount = prefs[failedKey(accountId)] ?: 0,
            lockedUntilMillis = prefs[lockedKey(accountId)] ?: 0L,
        )
    }

    /**
     * Records one failed attempt and returns the resulting state. A lockout is
     * measured against [nowMillis] with robust wall-clock handling (a clock that
     * moved backwards cannot extend a lockout).
     */
    suspend fun recordFailure(accountId: Long, nowMillis: Long): PinAttemptState {
        val current = stateFor(accountId).let { state ->
            // If a previous lockout already elapsed, the deadline is stale: drop it
            // so escalation continues from the failure count only.
            if (state.lockedUntilMillis in 1..nowMillis) state.copy(lockedUntilMillis = 0L) else state
        }
        val next = PinAttemptPolicy.onFailure(current, nowMillis)
        store.edit {
            it[failedKey(accountId)] = next.failedCount
            it[lockedKey(accountId)] = next.lockedUntilMillis
        }
        return next
    }

    suspend fun clear(accountId: Long) {
        store.edit {
            it.remove(failedKey(accountId))
            it.remove(lockedKey(accountId))
        }
    }

    /** Full reset (see ResetRepositoryImpl). */
    suspend fun clearAll() {
        store.edit { it.clear() }
    }

    private fun failedKey(accountId: Long) = intPreferencesKey("pin_failed_$accountId")

    private fun lockedKey(accountId: Long) = longPreferencesKey("pin_lock_until_$accountId")
}
