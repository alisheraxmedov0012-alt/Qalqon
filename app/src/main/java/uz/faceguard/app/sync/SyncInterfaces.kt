package uz.faceguard.app.sync

import uz.faceguard.app.domain.model.AppSettings
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.UserAccount

/**
 * Optional future backend sync — abstractions only.
 *
 * Hard rule: the app is offline-first. Sync is a strictly additive layer that
 * may copy non-sensitive metadata to a server; it must never gate any core
 * behavior (login, enrollment, recognition, protection, activity log).
 *
 * What MAY be synced in a future version:
 *  - account metadata (fullName + normalized phone; NEVER the PIN or its hash)
 *  - child profiles (name + restriction level; NEVER face templates)
 *  - settings backup (protection/scan preferences)
 *
 * What MUST remain local-only:
 *  - PIN (raw, hash, salt)
 *  - face templates / embeddings and all recognition data
 *  - activity log entries
 *  - protected-apps catalog (device-specific)
 */
interface AccountSyncGateway {
    suspend fun push(account: UserAccount): SyncResult
}

interface ChildProfileSyncGateway {
    suspend fun pushAll(children: List<ChildProfile>): SyncResult
}

interface SettingsSyncGateway {
    suspend fun push(settings: AppSettings): SyncResult
    suspend fun pull(): AppSettings?
}

enum class SyncResult { DISABLED, QUEUED, SYNCED, FAILED }
