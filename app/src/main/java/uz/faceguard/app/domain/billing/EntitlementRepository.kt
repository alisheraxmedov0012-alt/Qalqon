package uz.faceguard.app.domain.billing

import kotlinx.coroutines.flow.StateFlow

/**
 * The premium-entitlement source every screen asks for. It is the single, central
 * premium gate (INVARIANT: no screen reads a subscription flag of its own).
 */
interface EntitlementRepository {

    /** The current account's entitlement, or null when none is known yet. */
    val entitlement: StateFlow<PremiumEntitlement?>

    /** Whether premium is active right now, applying the offline policy. */
    val premiumActive: StateFlow<Boolean>

    /**
     * Re-verifies against Google Play. On success it replaces the cached state; when
     * Play is unreachable it keeps the previous state (no false downgrade).
     */
    suspend fun refresh(): RefreshOutcome
}

/** Convenience for the gate: true when premium is active for [entitlement] at [now]. */
fun EntitlementRepository.isPremiumActiveNow(now: Long = System.currentTimeMillis()): Boolean =
    PremiumAccessEvaluator.isPremiumActive(entitlement.value, now)
