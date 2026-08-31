package uz.faceguard.app.sync

import javax.inject.Inject
import javax.inject.Singleton
import uz.faceguard.app.domain.model.AppSettings
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.UserAccount

/**
 * Offline defaults: sync is permanently disabled until a future release
 * provides real gateways. Core flows never touch these.
 */
@Singleton
class NoOpAccountSyncGateway @Inject constructor() : AccountSyncGateway {
    override suspend fun push(account: UserAccount) = SyncResult.DISABLED
}

@Singleton
class NoOpChildProfileSyncGateway @Inject constructor() : ChildProfileSyncGateway {
    override suspend fun pushAll(children: List<ChildProfile>) = SyncResult.DISABLED
}

@Singleton
class NoOpSettingsSyncGateway @Inject constructor() : SettingsSyncGateway {
    override suspend fun push(settings: AppSettings) = SyncResult.DISABLED
    override suspend fun pull(): AppSettings? = null
}
