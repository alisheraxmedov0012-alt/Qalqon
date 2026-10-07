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
        // Phase 4 Step 1B-6: a snapshot baseline is meaningless without the account it
        // belongs to, so it goes with the rest of the wipe.
        db.usageSnapshotCheckpointDao().deleteAll()
        // Stage 4: every remaining table is cleared too, so a "full reset" really does
        // leave no residual child data behind. These were previously missed, which left
        // screen-time history and per-child schedule/eye-safety configuration on disk
        // after the UI had told the user all local data was deleted.
        db.dailyAppUsageDao().deleteAll()
        db.childScreenTimeLimitDao().deleteAll()
        db.scheduleDao().deleteAll()
        db.scheduleDao().deleteAllTargets()
        db.childEyeSafetyDao().deleteAll()
        settingsStore.clearAll()
        pinAttemptStore.clearAll()
        sessionManager.clearSession()
        // Full wipe only: the biometric key is removed with the data it protects,
        // so no orphaned ciphertext (or a stale key) survives a reset.
        keyProvider.deleteKey()
    }
}
