package uz.faceguard.app.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.core.security.AndroidKeystoreKeyProvider
import uz.faceguard.app.data.prefs.PinAttemptStore
import uz.faceguard.app.data.prefs.SessionManager
import uz.faceguard.app.data.prefs.SettingsStore
import uz.faceguard.app.domain.repository.ResetRepository

/**
 * Full local reset: clears every Room table, DataStore preferences, and the
 * persisted session. After this the app behaves as a fresh install.
 */
@Singleton
class ResetRepositoryImpl @Inject constructor(
    private val db: FaceGuardDatabase,
    private val settingsStore: SettingsStore,
    private val sessionManager: SessionManager,
    private val pinAttemptStore: PinAttemptStore,
    private val keyProvider: AndroidKeystoreKeyProvider,
) : ResetRepository {

    override suspend fun resetAll() {
        db.activityEventDao().deleteAll()
        db.childAppPolicyDao().deleteAll()
        db.parentRequestDao().deleteAll()
        db.notificationRecordDao().deleteAll()
        db.protectedAppDao().deleteAll()
        db.childProfileDao().deleteAll()
        db.parentProfileDao().deleteAll()
        db.userAccountDao().deleteAll()
        settingsStore.clearAll()
        pinAttemptStore.clearAll()
        sessionManager.clearSession()
        // Full wipe only: the biometric key is removed with the data it protects,
        // so no orphaned ciphertext (or a stale key) survives a reset.
        keyProvider.deleteKey()
    }
}
