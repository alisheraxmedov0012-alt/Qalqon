package uz.faceguard.app.core.ui

import androidx.annotation.StringRes
import uz.faceguard.app.R
import uz.faceguard.app.domain.protection.ProtectionCapability
import uz.faceguard.app.domain.protection.ProtectionReadiness

/**
 * Stage 8: localized labels for the protection readiness verdict.
 *
 * Shared by the Protection screen (and any other surface that shows readiness), so the
 * four states can never be described two different ways. Pure resource mapping — no
 * composable, no Android dependency.
 */
@StringRes
fun protectionReadinessLabelRes(readiness: ProtectionReadiness): Int = when (readiness) {
    ProtectionReadiness.OFF -> R.string.protection_readiness_label_off
    ProtectionReadiness.NOT_READY -> R.string.protection_readiness_label_not_ready
    ProtectionReadiness.LIMITED -> R.string.protection_readiness_label_limited
    ProtectionReadiness.READY -> R.string.protection_readiness_label_ready
}

/** The one-line explanation of what the parent should do for this readiness state. */
@StringRes
fun protectionReadinessHintRes(readiness: ProtectionReadiness): Int = when (readiness) {
    ProtectionReadiness.OFF -> R.string.protection_readiness_hint_off
    ProtectionReadiness.NOT_READY -> R.string.protection_readiness_hint_not_ready
    ProtectionReadiness.LIMITED -> R.string.protection_readiness_hint_limited
    ProtectionReadiness.READY -> R.string.protection_readiness_hint_ready
}

/**
 * Stage 8: the plain-language "why does QALQON need this?" for each capability, in
 * parent language rather than the technical settings name. Shown next to the capability
 * so the parent understands the reason before being sent to a system settings page.
 */
@StringRes
fun protectionCapabilityWhyRes(capability: ProtectionCapability): Int = when (capability) {
    ProtectionCapability.CAMERA -> R.string.protection_capability_why_camera
    ProtectionCapability.USAGE_ACCESS -> R.string.protection_capability_why_usage_access
    ProtectionCapability.ACCESSIBILITY -> R.string.protection_capability_why_accessibility
    ProtectionCapability.OVERLAY -> R.string.protection_capability_why_overlay
}
