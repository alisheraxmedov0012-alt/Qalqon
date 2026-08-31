package uz.faceguard.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sessionStore by preferencesDataStore(name = "session")

/**
 * Session persistence (DataStore): stores the active local account id.
 * Survives restarts; cleared on logout. The raw PIN never passes through here.
 */
@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store = context.sessionStore

    /** null = no active session. */
    val currentAccountId: Flow<Long?> =
        store.data.map { it[KEY_CURRENT_ACCOUNT_ID] }

    suspend fun setCurrentAccountId(id: Long) {
        store.edit { it[KEY_CURRENT_ACCOUNT_ID] = id }
    }

    suspend fun clearSession() {
        store.edit { it.remove(KEY_CURRENT_ACCOUNT_ID) }
    }

    companion object {
        private val KEY_CURRENT_ACCOUNT_ID = longPreferencesKey("current_account_id")

        fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun randomSalt(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
