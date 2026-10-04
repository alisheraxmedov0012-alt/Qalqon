package uz.faceguard.app.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.billing.EntitlementState
import uz.faceguard.app.domain.billing.OfflineEntitlementPolicy
import uz.faceguard.app.domain.billing.PremiumEntitlement
import uz.faceguard.app.domain.billing.PremiumAccessEvaluator
import uz.faceguard.app.domain.billing.PremiumFeature
import uz.faceguard.app.domain.billing.grantsPremium

/**
 * Stage 7: the entitlement state model and the offline/staleness policy — the two rules
 * that decide premium access. Pure JVM, no Google Play.
 */
class SubscriptionEntitlementTest {

    private fun entitlement(
        state: EntitlementState,
        verifiedAt: Long = 0L,
        expiry: Long? = null,
    ) = PremiumEntitlement(state = state, expiryTimeMillis = expiry, lastVerifiedAtMillis = verifiedAt)

    @Test
    fun onlyEntitlingStatesGrantPremium() {
        val entitling = setOf(
            EntitlementState.TRIAL,
            EntitlementState.ACTIVE,
            EntitlementState.CANCELED_ACTIVE,
            EntitlementState.GRACE_PERIOD,
        )
        EntitlementState.entries.forEach { state ->
            assertEquals(
                "$state entitlement",
                state in entitling,
                state.grantsPremium,
            )
        }
    }

    @Test
    fun accountHoldExpiredRevokedAndPendingDoNotGrantPremium() {
        listOf(
            EntitlementState.ACCOUNT_HOLD,
            EntitlementState.EXPIRED,
            EntitlementState.REVOKED,
            EntitlementState.PENDING,
            EntitlementState.NONE,
            EntitlementState.BILLING_UNAVAILABLE,
            EntitlementState.UNKNOWN,
        ).forEach { state ->
            assertFalse("$state must not grant premium", state.grantsPremium)
        }
    }

    @Test
    fun anActiveEntitlementWithinTheStalenessWindowGrantsAccessOffline() {
        val now = 1_000_000_000L
        val ent = entitlement(EntitlementState.ACTIVE, verifiedAt = now - 60_000)
        assertTrue(OfflineEntitlementPolicy.isPremiumActive(ent, now))
    }

    @Test
    fun aStaleEntitlementStopsGrantingAccessAfterTheLimit() {
        val now = 1_000_000_000L
        val tooOld = entitlement(
            EntitlementState.ACTIVE,
            verifiedAt = now - OfflineEntitlementPolicy.OFFLINE_STALENESS_LIMIT_MS - 1,
        )
        assertFalse("stale cache must not extend premium", OfflineEntitlementPolicy.isPremiumActive(tooOld, now))
    }

    @Test
    fun aKnownExpiryGovernsEvenIfRecentlyVerified() {
        val now = 1_000_000_000L
        val expired = entitlement(EntitlementState.ACTIVE, verifiedAt = now, expiry = now - 1)
        assertFalse(OfflineEntitlementPolicy.isPremiumActive(expired, now))

        val valid = entitlement(EntitlementState.ACTIVE, verifiedAt = now, expiry = now + 10_000)
        assertTrue(OfflineEntitlementPolicy.isPremiumActive(valid, now))
    }

    @Test
    fun nullEntitlementIsNeverPremium() {
        assertFalse(OfflineEntitlementPolicy.isPremiumActive(null, 1L))
    }

    @Test
    fun coreProtectionIsFreeButAdvancedFeaturesRequirePremium() {
        val now = 1_000L
        assertTrue(PremiumFeature.CORE_PROTECTION.free)
        assertTrue(
            "core protection must work with no entitlement",
            PremiumAccessEvaluator.isAvailable(PremiumFeature.CORE_PROTECTION, null, now),
        )
        assertFalse(
            "a premium feature needs an active entitlement",
            PremiumAccessEvaluator.isAvailable(PremiumFeature.SCHEDULES, null, now),
        )
        assertTrue(
            PremiumAccessEvaluator.isAvailable(
                PremiumFeature.SCHEDULES,
                entitlement(EntitlementState.ACTIVE, verifiedAt = now),
                now,
            ),
        )
    }
}
