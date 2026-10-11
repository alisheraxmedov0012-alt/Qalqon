package uz.faceguard.app.billing

import android.app.Activity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.billing.BillingGateway
import uz.faceguard.app.core.billing.BillingProductDetails
import uz.faceguard.app.core.billing.BillingProductQuery
import uz.faceguard.app.core.billing.BillingPurchase
import uz.faceguard.app.core.billing.BillingPurchaseQuery
import uz.faceguard.app.core.billing.SubscriptionManager
import uz.faceguard.app.domain.billing.BillingError
import uz.faceguard.app.domain.billing.EntitlementState
import uz.faceguard.app.domain.billing.ProductCatalog

/**
 * Release Block 4: two billing-lifecycle gaps the existing suite did not cover.
 *
 *  - **Restart with Play unreachable** (audit item C/#8): a fresh manager over the same
 *    cache must publish the cached entitlement when Play cannot be queried, so a paying
 *    user is never dropped by a restart while offline.
 *  - **Genuine parallelism** (audit items B/#6/#7): the manager is driven concurrently from
 *    the UI (Main) and its collectors (`Dispatchers.Default`), so the acknowledge bookkeeping
 *    must be thread-safe. This runs real worker threads; it is a regression smoke test that
 *    is stable on the fixed code.
 *
 * Real Google Play is NOT TESTED here (no Play/device).
 */
class Block4BillingLifecycleTest {

    private val dayMs = 24L * 60 * 60 * 1000
    private val base = 1_000_000_000L
    private val pastTrial = { base + (ProductCatalog.TRIAL_DAYS + 5) * dayMs }

    private fun manager(gateway: BillingGateway, store: InMemoryEntitlementStore) =
        SubscriptionManager(gateway, store, FakeAccountRepository()) { pastTrial() }

    // ---------------------------------------------- restart with Play unreachable

    @Test
    fun aRestartWithPlayUnavailableRestoresTheCachedEntitlement() = runBlocking {
        val store = InMemoryEntitlementStore()

        // First session: Play answers, the purchase is verified and cached.
        val online = FakeBillingGateway().apply { purchases = listOf(purchased(day = base)) }
        val first = manager(online, store)
        first.onAccountChanged(1L)
        assertEquals(EntitlementState.ACTIVE, store.stored(1L)?.state)

        // "Restart" with Play unreachable: a fresh manager over the same cache.
        val offline = FakeBillingGateway().apply { connectResult = false }
        val second = manager(offline, store)
        second.onAccountChanged(1L)

        assertEquals(
            "the cached entitlement must be published when Play is unreachable",
            EntitlementState.ACTIVE,
            second.entitlement.value?.state,
        )
        assertTrue("a paying user must keep premium offline after a restart", second.premiumActive.value)
    }

    // --------------------------------------------------- real parallel refreshes

    /** A gateway that holds the query/ack briefly so worker threads truly interleave. */
    private class DelayedGateway(
        private val purchases: List<BillingPurchase>,
    ) : BillingGateway {
        val acked = java.util.concurrent.ConcurrentLinkedQueue<String>()
        override val purchaseUpdates: Flow<List<BillingPurchase>> = flowOf(emptyList())
        override suspend fun connect(): Boolean = true
        override suspend fun queryProduct(productId: String): BillingProductQuery =
            BillingProductQuery(emptyList())
        override suspend fun queryActiveSubscriptions(): BillingPurchaseQuery {
            delay(2)
            return BillingPurchaseQuery(purchases, null, available = true)
        }
        override suspend fun launchPurchase(activity: Activity, details: BillingProductDetails): BillingError? = null
        override suspend fun acknowledge(purchaseToken: String): BillingError? {
            delay(2)
            acked += purchaseToken
            return null
        }
        override fun endConnection() = Unit
    }

    @Test
    fun parallelRefreshesAcrossWorkerThreadsConvergeAndDoNotThrow() = runBlocking {
        val gateway = DelayedGateway(listOf(purchased(day = base, acknowledged = false, token = "tok")))
        val store = InMemoryEntitlementStore()
        val manager = manager(gateway, store)
        manager.onAccountChanged(1L)

        // Many refreshes genuinely in parallel across Dispatchers.Default worker threads.
        coroutineScope {
            (1..16).map { async(Dispatchers.Default) { manager.refresh() } }.awaitAll()
        }

        assertEquals(EntitlementState.ACTIVE, manager.entitlement.value?.state)
        assertTrue(manager.premiumActive.value)
        assertEquals(EntitlementState.ACTIVE, store.stored(1L)?.state)
        // Only QALQON's own token is ever acknowledged (duplicates are idempotent on Play).
        assertEquals(setOf("tok"), gateway.acked.toSet())
    }
}
