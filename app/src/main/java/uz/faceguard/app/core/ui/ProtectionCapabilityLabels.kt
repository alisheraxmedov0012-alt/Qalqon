package uz.faceguard.app.core.ui

import androidx.annotation.StringRes
import uz.faceguard.app.R
import uz.faceguard.app.domain.protection.ProtectionCapability

/**
 * The localized name of a protection capability.
 *
 * Shared by the degraded-protection banner (Home + Protection) and the parent
 * notification, so one capability can never be described two different ways. Pure
 * resource mapping — no composable, no Android dependency.
 */
@StringRes
fun protectionCapabilityLabelRes(capability: ProtectionCapability): Int = when (capability) {
    ProtectionCapability.ACCESSIBILITY -> R.string.protection_capability_accessibility
    ProtectionCapability.OVERLAY -> R.string.protection_capability_overlay
    ProtectionCapability.USAGE_ACCESS -> R.string.protection_capability_usage_access
    ProtectionCapability.CAMERA -> R.string.protection_capability_camera
}
