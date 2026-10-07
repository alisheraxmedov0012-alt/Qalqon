package uz.faceguard.app.billing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.billing.SubscriptionManager
import uz.faceguard.app.domain.billing.BillingError
import uz.faceguard.app.domain.billing.EntitlementState
import uz.faceguard.app.domain.billing.ProductCatalog
import uz.faceguard.app.domain.billing.RefreshOutcome

/**
 * Stage 7 lifecycle tests for the subscription brain, driven through the *real*
 * [SubscriptionManager] with a fake Google Play gateway. These pin the invariants that
 * matter for a paying user and for account isolation.
 *
 * Real Google Play purchases are NOT TESTED (no Play/device here). Everything below is
 * MOCK VERIFIED.
 */
class SubscriptionManagerTest {

    private val dayMs = 24L * 60 * 60 * 1000
    private val base = 1_000_000_000L

    private fun manager(
        gateway: FakeBillingGateway,
        store: InMemoryEntitlementStore,
        clock: () -> Long,
    ) = SubscriptionManager(gateway, store, FakeAccountRepository(), clock)

    @Test
    fun anActivePurchaseGrantsPremium() = runBlocking {
        val gateway = FakeBillingGateway()
        // A purchase well past the trial window, auto-renewing -> ACTIVE.
        gateway.purchases = listOf(purchased(day = base))
        var now = base + (ProductCatalog.TRIAL_DAYS + 5) * dayMs
        val store = InMemoryEntitlementStore()
        val manager = manager(gateway, store, { now })

        manager.onAccountChanged(1L)
        now += 1

        assertEquals(EntitlementState.ACTIVE, manager.entitlement.value?.state)
        assertTrue(manager.premiumActive.value)
        assertEquals(EntitlementState.ACTIVE, store.stored(1L)?.state)
    }

    @Test
    fun noPurchaseAfterHistoryIsExpiredAndLosesPremium() = runBlocking {
        val gateway = FakeBillingGateway()
        gateway.purchases = listOf(purchased(day = base))
        var now = base + (ProductCatalog.TRIAL_DAYS + 5) * dayMs
        val store = InMemoryEntitlementStore()
        val manager = manager(gateway, store, { now })
        manager.onAccountChanged(1L)
        now += 1
        assertTrue(manager.premiumActive.value)

        // The subscription disappears from Play (expired / revoked).
        gateway.purchases = emptyList()
        now += 1
        manager.refresh()

        assertEquals(EntitlementState.EXPIRED, manager.entitlement.value?.state)
        assertFalse("expired must lose premium", manager.premiumActive.value)
    }

    @Test
    fun billingUnavailableDoesNotDowngradeAPayingUser() = runBlocking {
        val gateway = FakeBillingGateway()
        gateway.purchases = listOf(purchased(day = base))
        val now = { base + (ProductCatalog.TRIAL_DAYS + 5) * dayMs }
        val store = InMemoryEntitlementStore()
        val manager = manager(gateway, store) { now() }
        manager.onAccountChanged(1L)
        assertTrue(manager.premiumActive.value)

        // Play becomes unreachable; the previous state must be preserved.
        gateway.connectResult = false
        val outcome = manager.refresh()

        assertTrue(outcome is RefreshOutcome.Unavailable)
        assertEquals(EntitlementState.ACTIVE, manager.entitlement.value?.state)
        assertTrue("offline must not drop a paying user", manager.premiumActive.value)
    }

    @Test
    fun aQueryErrorKeepsTheLastVerifiedState() = runBlocking {
        val gateway = FakeBillingGateway()
        gateway.purchases = listOf(purchased(day = base))
        val now = { base + (ProductCatalog.TRIAL_DAYS + 5) * dayMs }
        val manager = manager(gateway, InMemoryEntitlementStore()) { now() }
        manager.onAccountChanged(1L)

        gateway.available = true
        gateway.queryError = BillingError.NETWORK_ERROR
        val outcome = manager.refresh()

        assertTrue(outcome is RefreshOutcome.Failed)
        assertEquals(EntitlementState.ACTIVE, manager.entitlement.value?.state)
    }

    @Test
    fun accountEntitlementsDoNotLeakAcrossAccounts() = runBlocking {
        val gateway = FakeBillingGateway()
        gateway.purchases = listOf(purchased(day = base))
        val now = { base + (ProductCatalog.TRIAL_DAYS + 5) * dayMs }
        val store = InMemoryEntitlementStore()
        val manager = manager(gateway, store) { now() }

        manager.onAccountChanged(1L)
        assertEquals(EntitlementState.ACTIVE, manager.entitlement.value?.state)
        assertTrue(manager.premiumActive.value)

        // A different QALQON account with no purchase on the Play account.
        gateway.purchases = emptyList()
        manager.onAccountChanged(2L)

        assertEquals("account 2 must not inherit account 1's entitlement", EntitlementState.NONE, manager.entitlement.value?.state)
        assertFalse(manager.premiumActive.value)
        // Account 1's cache is untouched.
        assertEquals(EntitlementState.ACTIVE, store.stored(1L)?.state)
    }

    @Test
    fun signOutClearsTheEntitlement() = runBlocking {
        val gateway = FakeBillingGateway()
        gateway.purchases = listOf(purchased(day = base))
        val now = { base + (ProductCatalog.TRIAL_DAYS + 5) * dayMs }
        val manager = manager(gateway, InMemoryEntitlementStore()) { now() }
        manager.onAccountChanged(1L)

        manager.onAccountChanged(null)
        assertNull(manager.entitlement.value)
        assertFalse(manager.premiumActive.value)
    }

    @Test
    fun anUnacknowledgedPurchaseIsAcknowledgedOnceAndOnlyOnce() = runBlocking {
        val gateway = FakeBillingGateway()
        gateway.purchases = listOf(purchased(day = base, acknowledged = false, token = "t1"))
        val now = { base + (ProductCatalog.TRIAL_DAYS + 5) * dayMs }
        val manager = manager(gateway, InMemoryEntitlementStore()) { now() }

        manager.onAccountChanged(1L)
        assertEquals(listOf("t1"), gateway.acknowledged)

        // A duplicate refresh must not acknowledge the same token again.
        manager.refresh()
        assertEquals(listOf("t1"), gateway.acknowledged)
    }

    @Test
    fun anAcknowledgementFailureIsNotSilentlyTreatedAsDoneAndIsRetried() = runBlocking {
        val gateway = FakeBillingGateway()
        gateway.purchases = listOf(purchased(day = base, acknowledged = false, token = "t2"))
        gateway.acknowledgeErrors["t2"] = BillingError.BILLING_UNAVAILABLE
        val now = { base + (ProductCatalog.TRIAL_DAYS + 5) * dayMs }
        val manager = manager(gateway, InMemoryEntitlementStore()) { now() }

        manager.onAccountChanged(1L)
        assertTrue("failed ack must not be recorded", gateway.acknowledged.isEmpty())

        // Recovery: the next refresh (Play reachable again) acknowledges it.
        gateway.acknowledgeErrors.remove("t2")
        manager.refresh()
        assertEquals(listOf("t2"), gateway.acknowledged)
    }

    @Test
    fun aPurchaseUpdateFromTheListenerIsAppliedIdempotently() = runBlocking {
        val gateway = FakeBillingGateway()
        gateway.purchases = emptyList()
        val now = { base + (ProductCatalog.TRIAL_DAYS + 5) * dayMs }
        val manager = manager(gateway, InMemoryEntitlementStore()) { now() }
        manager.onAccountChanged(1L)
        assertEquals(EntitlementState.NONE, manager.entitlement.value?.state)

        // The user completes the purchase; Play pushes an update.
        manager.onPurchaseUpdate(listOf(purchased(day = base, acknowledged = false, token = "t3")))
        assertEquals(EntitlementState.ACTIVE, manager.entitlement.value?.state)
        assertTrue(manager.premiumActive.value)

        // Applying it again does not change the state or double-acknowledge.
        manager.onPurchaseUpdate(listOf(purchased(day = base, acknowledged = false, token = "t3")))
        assertEquals(listOf("t3"), gateway.acknowledged)
        assertEquals(EntitlementState.ACTIVE, manager.entitlement.value?.state)
    }

    @Test
    fun restoreIsARealPlayQueryNotAStub() = runBlocking {
        val gateway = FakeBillingGateway()
        gateway.purchases = emptyList()
        val now = { base + (ProductCatalog.TRIAL_DAYS + 5) * dayMs }
        val manager = manager(gateway, InMemoryEntitlementStore()) { now() }
        manager.onAccountChanged(1L)
        assertEquals(EntitlementState.NONE, manager.entitlement.value?.state)

        // The purchase exists on Play (e.g. after reinstall); restore must find it.
        gateway.purchases = listOf(purchased(day = base))
        val outcome = manager.restore()

        assertTrue(outcome is RefreshOutcome.Verified)
        assertEquals(EntitlementState.ACTIVE, manager.entitlement.value?.state)
    }

    @Test
    fun anOfflineCachedEntitlementDoesNotOutliveTheStalenessLimit() = runBlocking {
        val gateway = FakeBillingGateway()
        gateway.purchases = listOf(purchased(day = base))
        var now = base + (ProductCatalog.TRIAL_DAYS + 5) * dayMs
        val store = InMemoryEntitlementStore()
        val manager = manager(gateway, store) { now }
        manager.onAccountChanged(1L)
        assertTrue(manager.premiumActive.value)

        // Time passes well beyond the staleness limit with no successful reverification.
        now += 10L * dayMs
        // A refresh while Play is unreachable keeps the state but re-evaluates the policy.
        gateway.connectResult = false
        manager.refresh()

        assertFalse(
            "a stale cached entitlement must not grant premium forever",
            manager.premiumActive.value,
        )
    }
}
