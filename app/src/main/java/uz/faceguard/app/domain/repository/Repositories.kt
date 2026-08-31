package uz.faceguard.app.domain.repository

import kotlinx.coroutines.flow.Flow
import uz.faceguard.app.domain.model.AppSettings
import uz.faceguard.app.domain.model.AuthResult
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.EnrollmentStatus
import uz.faceguard.app.domain.model.ParentProfile
import uz.faceguard.app.domain.model.RestrictionLevel
import uz.faceguard.app.domain.model.ScanMode
import uz.faceguard.app.domain.model.BlockPolicy
import uz.faceguard.app.domain.model.UserAccount

/**
 * Local-first account repository. raw PINs never leave the ViewModel layer
 * — implementations salt+hash before persisting.
 */
/**
 * Account storage. Only non-sensitive metadata (name, phone) is eligible for
 * future backend sync via `sync.AccountSyncGateway`; PIN material never is.
 */
interface AccountRepository {
    val currentAccountId: Flow<Long?>
    suspend fun register(fullName: String, phoneNumber: String, pin: String): AuthResult
    suspend fun login(phoneNumber: String, pin: String): AuthResult
    suspend fun getCurrentAccount(): UserAccount?
    suspend fun logout()

    /** Salted PIN check for the emergency unlock; never exposes the hash. */
    suspend fun verifyPin(pin: String): Boolean
}

interface ParentProfileRepository {
    fun observe(accountId: Long): Flow<ParentProfile?>

    /** one profile per account; returns existing if present. */
    suspend fun createIfMissing(accountId: Long, displayName: String): ParentProfile
    suspend fun updateDisplayName(accountId: Long, displayName: String)
    suspend fun setFaceEnrolled(accountId: Long, enrolled: Boolean)

    /** Clears face enrollment metadata; the profile itself stays. */
    suspend fun deleteFaceData(accountId: Long)
}

/**
 * Child profiles. Name + restriction level are sync-eligible; face templates
 * and enrollment metadata stay on-device permanently.
 */
interface ChildProfileRepository {
    fun observeChildren(accountId: Long): Flow<List<ChildProfile>>
    suspend fun addChild(accountId: Long, childName: String, level: RestrictionLevel): Long
    suspend fun updateChild(accountId: Long, childId: Long, childName: String, level: RestrictionLevel)
    suspend fun deleteChild(accountId: Long, childId: Long)
    suspend fun setFaceEnrolled(childId: Long, enrolled: Boolean)

    /** Clears face enrollment metadata; the child profile itself stays. */
    suspend fun deleteFaceData(accountId: Long, childId: Long)
}

/**
 * Settings are sync-eligible as a whole (they contain no secrets); a future
 * `sync.SettingsSyncGateway` can back them up and restore them.
 */
interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setProtectionEnabled(enabled: Boolean)
    suspend fun setScanMode(mode: ScanMode)
    suspend fun setRecoveryDelayMs(delayMs: Long)
    suspend fun setUnknownUserPolicy(policy: BlockPolicy)
    suspend fun setNoFacePolicy(policy: BlockPolicy)
    suspend fun setLowBatteryBehaviorEnabled(enabled: Boolean)
}

/** Local activity log; newest first, capped by the DAO query. */
interface ActivityLogRepository {
    val recent: Flow<List<uz.faceguard.app.domain.model.ActivityEvent>>
    suspend fun log(type: uz.faceguard.app.domain.model.ActivityEventType, detail: String? = null)
    suspend fun clear()
}

/** Live installed-apps catalog with per-app protection selection. */
interface ProtectedAppsRepository {
    val protectedApps: Flow<List<uz.faceguard.app.domain.model.ProtectedApp>>
    suspend fun refreshFromDevice()
    suspend fun toggleProtection(packageName: String, isProtected: Boolean)
    suspend fun countProtected(): Int
}


/** Wipes every local table + preferences; returns the app to first-run state. */
interface ResetRepository {
    suspend fun resetAll()
}
