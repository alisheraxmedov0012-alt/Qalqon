package uz.faceguard.app.domain.protection

/**
 * The platform capabilities protection depends on, and which of them are missing.
 *
 * Pure and Android-free (booleans in, an enum set out), so "is protection degraded
 * and why" is unit-testable without a device and can be shared by the runtime, the
 * Home/Protection banners and the parent notification.
 *
 * "Degraded" is deliberately different from "not ready": blocking an app needs the
 * overlay, recognition needs the camera, and the fail-closed no-face policy still
 * protects without a frame. Degraded means protection is *partially* effective and
 * the parent should be told, not that it is inert.
 */
enum class ProtectionCapability {
    /** The accessibility service — the only mechanism that actually consumes touches. */
    ACCESSIBILITY,

    /** Draw-over-other-apps — the blocking window itself. */
    OVERLAY,

    /** Usage Access — how the foreground app is observed at all. */
    USAGE_ACCESS,

    /** Camera — how a face is recognised (and never mistaken for "everything is fine"). */
    CAMERA,
}

/**
 * Ordered by enforcement impact, so a single banner button can point at the most
 * consequential missing capability first: with no blocking window the block does
 * nothing at all, with no accessibility it is only a cosmetic scrim, with no usage
 * access no app is ever seen, and with no camera recognition is blind.
 */
val PROTECTION_CAPABILITY_PRIORITY: List<ProtectionCapability> = listOf(
    ProtectionCapability.OVERLAY,
    ProtectionCapability.ACCESSIBILITY,
    ProtectionCapability.USAGE_ACCESS,
    ProtectionCapability.CAMERA,
)

/**
 * The capabilities that are currently missing, in no particular order.
 *
 * Each flag is "the capability is available"; a false one is reported as missing.
 * This never decides whether protection should run — it only describes its health.
 */
fun missingProtectionCapabilities(
    accessibilityEnabled: Boolean,
    overlayGranted: Boolean,
    usageAccessGranted: Boolean,
    cameraGranted: Boolean,
): Set<ProtectionCapability> = buildSet {
    if (!accessibilityEnabled) add(ProtectionCapability.ACCESSIBILITY)
    if (!overlayGranted) add(ProtectionCapability.OVERLAY)
    if (!usageAccessGranted) add(ProtectionCapability.USAGE_ACCESS)
    if (!cameraGranted) add(ProtectionCapability.CAMERA)
}

/**
 * The missing capability to send the parent to first, or `null` when nothing is
 * missing. Uses [PROTECTION_CAPABILITY_PRIORITY], so the choice is deterministic.
 */
fun Set<ProtectionCapability>.highestPriorityMissing(): ProtectionCapability? =
    PROTECTION_CAPABILITY_PRIORITY.firstOrNull { it in this }

/**
 * A stable, order-independent identity for a set of missing capabilities.
 *
 * Used as the notification deduplication key so a *changed* set (a new capability
 * lost, or one restored) notifies again, while the same set does not repeat.
 */
fun Set<ProtectionCapability>.degradationKey(): String =
    PROTECTION_CAPABILITY_PRIORITY.filter { it in this }.joinToString(",") { it.name }

/**
 * Release Block 3 (ACC-03): whether opening the settings page that fixes [this] capability
 * must first pass through the Accessibility prominent-disclosure / affirmative-consent gate.
 *
 * Only [ProtectionCapability.ACCESSIBILITY] does: Google Play's Accessibility API policy
 * requires a clear in-app disclosure plus affirmative consent before the user is sent to
 * enable the service. Camera, Usage Access and Overlay are ordinary permission/special-access
 * flows with their own existing explanations, so they must **not** be routed through the
 * Accessibility disclosure.
 *
 * Pure and Android-free, so "which capability is gated" is unit-testable and every entry
 * point (requirements row, degraded banner, any future caller) can consult one rule instead
 * of re-implementing the decision.
 */
fun ProtectionCapability.requiresAccessibilityDisclosure(): Boolean =
    this == ProtectionCapability.ACCESSIBILITY
