package uz.faceguard.app.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.billing.EntitlementState
import uz.faceguard.app.domain.billing.OfflineEntitlementPolicy
import uz.faceguard.app.domain.billing.PremiumAccessEvaluator
import uz.faceguard.app.domain.billing.PremiumEntitlement
import uz.faceguard.app.domain.billing.PremiumFeature
import uz.faceguard.app.domain.billing.grantsPremium

/**
 * Stage 9 subscription fortress: the exhaustive entitlement matrix.
 *
 * Every `EntitlementState` is checked against the two rules that decide premium access
 * (`grantsPremium` and the offline staleness policy), so a change to either can never
 * silently grant (or drop) premium for one state. Also pins the exact staleness boundary.
 */
class EntitlementMatrixTest {

    private val hour = 60L * 60 * 1000
    private val limit = OfflineEntitlementPolicy.OFFLINE_STALENESS_LIMIT_MS

    private val entitling = setOf(
        EntitlementState.TRIAL,
        EntitlementState.ACTIVE,
        EntitlementState.CANCELED_ACTIVE,
        EntitlementState.GRACE_PERIOD,
    )

    private fun entitlement(
        state: EntitlementState,
        verifiedAt: Long = 0L,
        expiry: Long? = null,
    ) = PremiumEntitlement(state = state, expiryTimeMillis = expiry, lastVerifiedAtMillis = verifiedAt)

    @Test
    fun grantsPremiumIsExactlyTheEntitlingStates() {
        EntitlementState.entries.forEach { state ->
            assertEquals("grantsPremium($state)", state in entitling, state.grantsPremium)
        }
    }

    @Test
    fun everyStateWithAFreshCacheGrantsPremiumOnlyWhenItShould() {
        val now = 1_000_000_000L
        EntitlementState.entries.forEach { state ->
            val active = OfflineEntitlementPolicy.isPremiumActive(entitlement(state, verifiedAt = now), now)
            assertEquals("fresh $state premium", state in entitling, active)
        }
        assertFalse("null entitlement is never premium", OfflineEntitlementPolicy.isPremiumActive(null, now))
    }

    @Test
    fun anEntitlingStateWithAKnownFutureExpiryIsActive() {
        val now = 1_000_000_000L
        entitling.forEach { state ->
            assertTrue(
                "$state with a future expiry must be active",
                OfflineEntitlementPolicy.isPremiumActive(
                    entitlement(state, verifiedAt = now, expiry = now + hour),
                    now,
                ),
            )
        }
    }

    @Test
    fun anEntitlingStateWithAKnownPastExpiryIsNeverActive() {
        val now = 1_000_000_000L
        entitling.forEach { state ->
            assertFalse(
                "$state with a past expiry must not be active",
                OfflineEntitlementPolicy.isPremiumActive(
                    entitlement(state, verifiedAt = now, expiry = now - 1),
                    now,
                ),
            )
        }
    }

    @Test
    fun theOfflineStalenessBoundaryIsExact() {
        val now = 1_000_000_000L
        val fresh = entitlement(EntitlementState.ACTIVE, verifiedAt = now - limit)
        val stale = entitlement(EntitlementState.ACTIVE, verifiedAt = now - limit - 1)

        assertTrue("at exactly the limit, access is still granted", OfflineEntitlementPolicy.isPremiumActive(fresh, now))
        assertFalse("one ms past the limit, access is denied", OfflineEntitlementPolicy.isPremiumActive(stale, now))
    }

    @Test
    fun nonEntitlingStatesNeverGrantPremiumEvenWithAFutureExpiry() {
        val now = 1_000_000_000L
        (EntitlementState.entries - entitling).forEach { state ->
            assertFalse(
                "$state must never grant premium",
                OfflineEntitlementPolicy.isPremiumActive(
                    entitlement(state, verifiedAt = now, expiry = now + hour),
                    now,
                ),
            )
        }
    }

    @Test
    fun coreProtectionIsAlwaysAvailableAndPremiumFeaturesFollowTheEntitlement() {
        val now = 1_000_000_000L
        assertTrue(PremiumFeature.CORE_PROTECTION.free)
        assertTrue(
            PremiumAccessEvaluator.isAvailable(PremiumFeature.CORE_PROTECTION, null, now),
        )
        EntitlementState.entries.forEach { state ->
            val expected = state in entitling
            val ent = entitlement(state, verifiedAt = now)
            assertEquals(
                "premium feature availability for $state",
                expected,
                PremiumAccessEvaluator.isAvailable(PremiumFeature.SCHEDULES, ent, now),
            )
        }
    }
}
