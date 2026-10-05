package uz.faceguard.app.billing

import android.app.Activity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.billing.BillingGateway
import uz.faceguard.app.core.billing.BillingProductDetails
import uz.faceguard.app.core.billing.BillingProductQuery
import uz.faceguard.app.core.billing.BillingPurchase
import uz.faceguard.app.core.billing.BillingPurchaseQuery
import uz.faceguard.app.core.billing.SubscriptionManager
import uz.faceguard.app.domain.billing.EntitlementState
import uz.faceguard.app.domain.billing.ProductCatalog

/**
 * Stage 9 concurrency fortress for the subscription brain.
 *
 * These pin the lifecycle races that a single-threaded happy-path test cannot: a refresh
 * that is still in flight while the user switches account, and concurrent refreshes.
 * All deterministic — a controllable gateway gates the Play query explicitly, so no
 * timing/sleep is involved.
 *
 * The account-switch test locks the Stage 9 fix: an in-flight verification for account A
 * must persist to A's cache but must NOT surface A's entitlement after the session moved
 * to account B (cross-account entitlement leak).
 */
class SubscriptionConcurrencyTest {

    private val dayMs = 24L * 60 * 60 * 1000
    private val base = 1_000_000_000L
    private val pastTrial = base + (ProductCatalog.TRIAL_DAYS + 5) * dayMs

    /**
     * Gateway whose first `queryActiveSubscriptions` is gated, so a refresh can be held
     * in flight while the test performs an account switch. The first query (the first
     * signed-in account) returns [firstPurchases]; later queries (the second account)
     * return [laterPurchases] — modelling two QALQON accounts on two different Google
     * accounts, only one of which has the purchase.
     */
    private class GatedGateway(
        private val firstPurchases: List<BillingPurchase>,
        private val laterPurchases: List<BillingPurchase> = emptyList(),
    ) : BillingGateway {
        val firstQueryStarted = CompletableDeferred<Unit>()
        val releaseFirstQuery = CompletableDeferred<Unit>()
        private var queryCount = 0

        override val purchaseUpdates: Flow<List<BillingPurchase>> = flowOf(emptyList())

        override suspend fun connect(): Boolean = true
        override suspend fun queryProduct(productId: String): BillingProductQuery =
            BillingProductQuery(emptyList())

        override suspend fun queryActiveSubscriptions(): BillingPurchaseQuery {
            val n = ++queryCount
            if (n == 1) {
                firstQueryStarted.complete(Unit)
                releaseFirstQuery.await()
                return BillingPurchaseQuery(firstPurchases, null, available = true)
            }
            return BillingPurchaseQuery(laterPurchases, null, available = true)
        }

        override suspend fun launchPurchase(activity: Activity, details: BillingProductDetails) = null
        override suspend fun acknowledge(purchaseToken: String) = null
        override fun endConnection() = Unit
    }

    @Test
    fun accountSwitchDuringInFlightRefresh_doesNotLeakThePreviousAccountsEntitlement() = runBlocking {
        val gateway = GatedGateway(listOf(purchased(day = base, token = "token-a")))
        val store = InMemoryEntitlementStore()
        val manager = SubscriptionManager(gateway, store, FakeAccountRepository()) { pastTrial }

        // Account 1 signs in; its verification is now held in flight.
        val job = launch { manager.onAccountChanged(1L) }
        withTimeout(5_000) { gateway.firstQueryStarted.await() }

        // The user switches to account 2 while account 1's refresh is still running.
        // Account 2 has no purchase.
        manager.onAccountChanged(2L)
        assertEquals("account 2 starts with no entitlement", EntitlementState.NONE, manager.entitlement.value?.state)

        // Now let account 1's in-flight verification complete.
        gateway.releaseFirstQuery.complete(Unit)
        withTimeout(5_000) { job.join() }

        // Account 1's entitlement must be cached for account 1 …
        assertEquals(EntitlementState.ACTIVE, store.stored(1L)?.state)
        // … but must NOT be published into the current (account 2) session.
        assertTrue(
            "account 1's entitlement must not leak into account 2's session",
            manager.entitlement.value?.state != EntitlementState.ACTIVE,
        )
        assertFalse("account 2 must not be granted premium from account 1's purchase", manager.premiumActive.value)
    }

    @Test
    fun concurrentRefreshesConvergeOnTheAuthoritativeState() = runBlocking {
        val gateway = FakeBillingGateway().apply { purchases = listOf(purchased(day = base, token = "token-a")) }
        val store = InMemoryEntitlementStore()
        val manager = SubscriptionManager(gateway, store, FakeAccountRepository()) { pastTrial }
        manager.onAccountChanged(1L)

        // Two refreshes racing must not corrupt state or throw.
        val a = async { manager.refresh() }
        val b = async { manager.refresh() }
        a.await()
        b.await()

        assertEquals(EntitlementState.ACTIVE, manager.entitlement.value?.state)
        assertTrue(manager.premiumActive.value)
        assertEquals(EntitlementState.ACTIVE, store.stored(1L)?.state)
    }

    @Test
    fun duplicatePurchaseUpdatesDoNotStackOrCorruptState() = runBlocking {
        val gateway = FakeBillingGateway().apply { purchases = emptyList() }
        val manager = SubscriptionManager(gateway, InMemoryEntitlementStore(), FakeAccountRepository()) { pastTrial }
        manager.onAccountChanged(1L)
        assertEquals(EntitlementState.NONE, manager.entitlement.value?.state)

        val update = listOf(purchased(day = base, acknowledged = false, token = "token-x"))
        // The same purchase update delivered three times (listener retries / reconnects).
        repeat(3) { manager.onPurchaseUpdate(update) }

        assertEquals(EntitlementState.ACTIVE, manager.entitlement.value?.state)
        // Acknowledged once only.
        assertEquals(listOf("token-x"), gateway.acknowledged)
    }
}
