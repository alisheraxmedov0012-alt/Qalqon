package uz.faceguard.app.domain.protection

/**
 * Stage 8: the honest answer to "is QALQON actually protecting right now?".
 *
 * This is deliberately **not** the same question as "did the parent switch protection
 * on" ([ProtectionReadiness.OFF] vs the rest). A parent-facing "Protection: ON" is a
 * *user setting*; [ProtectionReadiness] is the *runtime capability health* derived from
 * real probes. Keeping them separate is what stops the app from claiming "active" while
 * a critical capability (accessibility, overlay, usage access or camera) is missing.
 *
 * Pure and Android-free, so the whole contract is unit-testable on the JVM.
 */
enum class ProtectionReadiness {
    /** The parent has not switched protection on. Not a fault, and never nagged about. */
    OFF,

    /**
     * Protection is requested but cannot block at all yet: no signed-in session, no
     * parent face, or no protected app. The parent still has setup to finish.
     */
    NOT_READY,

    /**
     * Protection runs, but at least one critical capability is missing, so it is only
     * **partially** effective. Distinct from [READY] on purpose — the UI must never
     * present this as full protection.
     */
    LIMITED,

    /** Every critical capability is held and the session is active: full protection. */
    READY,
}

/**
 * The readiness verdict.
 *
 * [missingCapabilities] is exactly [missingProtectionCapabilities] (the same set the
 * degraded banner and the parent notification already use) — so "degraded", "limited"
 * and "not ready" can never disagree. Every capability in that set is treated as
 * *required for full protection*, including the accessibility guardrail: it is the only
 * mechanism that actually consumes the child's touches, so its absence is limited
 * protection, never "ready".
 */
fun protectionReadiness(
    enabled: Boolean,
    active: Boolean,
    parentFaceEnrolled: Boolean,
    protectedAppCount: Int,
    missingCapabilities: Set<ProtectionCapability>,
): ProtectionReadiness = when {
    !enabled -> ProtectionReadiness.OFF
    !active || !parentFaceEnrolled || protectedAppCount <= 0 -> ProtectionReadiness.NOT_READY
    missingCapabilities.isNotEmpty() -> ProtectionReadiness.LIMITED
    else -> ProtectionReadiness.READY
}

/**
 * Whether a missing capability means protection is only partially effective (LIMITED)
 * rather than completely blocked (NOT_READY).
 *
 * All current capabilities are enforcement-affecting, so a missing one always yields
 * LIMITED once the session itself is active and set up. Kept as an explicit function so
 * a future "recommended-only" capability has one place to declare itself non-blocking.
 */
fun ProtectionCapability.blocksFullReadiness(): Boolean = true
