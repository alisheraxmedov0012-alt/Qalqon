package uz.faceguard.app.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import uz.faceguard.app.data.prefs.SettingsStore
import uz.faceguard.app.domain.model.AppSettings
import uz.faceguard.app.domain.model.BlockPolicy
import uz.faceguard.app.domain.model.ScanMode
import uz.faceguard.app.domain.repository.SettingsRepository

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val store: SettingsStore,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = store.settings

    override suspend fun setProtectionEnabled(enabled: Boolean) =
        store.setProtectionEnabled(enabled)

    override suspend fun setScanMode(mode: ScanMode) =
        store.setScanMode(mode)

    override suspend fun setRecoveryDelayMs(delayMs: Long) =
        store.setRecoveryDelayMs(delayMs)

    override suspend fun setUnknownUserPolicy(policy: BlockPolicy) =
        store.setUnknownUserPolicy(policy)

    override suspend fun setNoFacePolicy(policy: BlockPolicy) =
        store.setNoFacePolicy(policy)

    override suspend fun setLowBatteryBehaviorEnabled(enabled: Boolean) =
        store.setLowBatteryBehaviorEnabled(enabled)
}
