package uz.faceguard.app.core.protection

import uz.faceguard.app.domain.model.AppSettings
import uz.faceguard.app.domain.model.BlockPolicy
import uz.faceguard.app.domain.model.ScanMode

/**
 * Snapshot of everything the protection engine needs at runtime. Derived from
 * persisted [AppSettings] so settings changes take effect live.
 */
data class ProtectionSettings(
    val enabled: Boolean = false,
    val unknownUserPolicy: BlockPolicy = BlockPolicy.SOFT_BLOCK,
    val noFacePolicy: BlockPolicy = BlockPolicy.ALLOW,
    val recoveryDelayMs: Long = 30_000L,
    val scanMode: ScanMode = ScanMode.BALANCED,
    val lowBatteryBehaviorEnabled: Boolean = true,
) {
    companion object {
        fun from(settings: AppSettings) = ProtectionSettings(
            enabled = settings.protectionEnabled,
            unknownUserPolicy = settings.unknownUserPolicy,
            noFacePolicy = settings.noFacePolicy,
            recoveryDelayMs = settings.recoveryDelayMs,
            scanMode = settings.scanMode,
            lowBatteryBehaviorEnabled = settings.lowBatteryBehaviorEnabled,
        )
    }
}
