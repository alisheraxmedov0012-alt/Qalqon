package uz.faceguard.app.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uz.faceguard.app.domain.model.ScanMode
import uz.faceguard.app.domain.policy.PolicySettings
import uz.faceguard.app.domain.policy.PolicySettingsRepository
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.policy.asBlockPolicy
import uz.faceguard.app.domain.policy.toPolicySettings
import uz.faceguard.app.domain.repository.SettingsRepository

/**
 * Adapter over the existing settings store: no second storage, no duplicated
 * keys. Writes go through [SettingsRepository] so validation/defaults live in
 * one place (`SettingsStore`).
 */
@Singleton
class PolicySettingsRepositoryImpl @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : PolicySettingsRepository {

    override fun observe(): Flow<PolicySettings> =
        settingsRepository.settings.map { it.toPolicySettings() }

    override suspend fun setProtectionEnabled(enabled: Boolean) =
        settingsRepository.setProtectionEnabled(enabled)

    override suspend fun setUnknownUserAction(action: ProtectionAction) =
        settingsRepository.setUnknownUserPolicy(action.asBlockPolicy())

    override suspend fun setNoFaceAction(action: ProtectionAction) =
        settingsRepository.setNoFacePolicy(action.asBlockPolicy())

    override suspend fun setRecoveryDelayMs(delayMs: Long) =
        settingsRepository.setRecoveryDelayMs(delayMs)

    override suspend fun setScanMode(mode: ScanMode) =
        settingsRepository.setScanMode(mode)

    override suspend fun setLowBatteryBehaviorEnabled(enabled: Boolean) =
        settingsRepository.setLowBatteryBehaviorEnabled(enabled)
}
