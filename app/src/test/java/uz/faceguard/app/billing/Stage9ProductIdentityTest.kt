package uz.faceguard.app.billing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.billing.BillingPurchase
import uz.faceguard.app.core.billing.BillingPurchaseState
import uz.faceguard.app.core.billing.PurchaseProcessor
import uz.faceguard.app.core.billing.SubscriptionManager
import uz.faceguard.app.domain.billing.EntitlementSource
import uz.faceguard.app.domain.billing.EntitlementState
import uz.faceguard.app.domain.billing.ProductCatalog

/**
 * Stage 9: purchase **product identity**.
 *
 * `queryPurchasesAsync(SUBS)` returns every subscription purchase for the app, so the
 * entitlement/acknowledgement path must ignore any purchase that is not one of QALQON's
 * products. Without this, owning some other product in the app's catalog would grant
 * Premium — the "wrong product grants Premium" failure criterion.
 *
 * Pure + manager-level, driven through the real [SubscriptionManager] with the fake Play
 * gateway. Real Google Play purchases are NOT TESTED here.
 */
class Stage9ProductIdentityTest {

    private val dayMs = 24L * 60 * 60 * 1000
    private val base = 1_000_000_000L
    private val afterTrial = { base + (ProductCatalog.TRIAL_DAYS + 5) * dayMs }

    private val FOREIGN_PRODUCT = "some_other_subscription"

    private fun foreign(token: String = "foreign-token", state: BillingPurchaseState = BillingPurchaseState.PURCHASED) =
        BillingPurchase(
            productId = FOREIGN_PRODUCT,
            purchaseToken = token,
            state = state,
            acknowledged = false,
            autoRenewing = true,
            purchaseTimeMillis = base,
        )

    // ---------------------------------------------------------------- catalog

    @Test
    fun onlyTheKnownCatalogProductsAreQalqonProducts() {
        assertTrue(ProductCatalog.isQalqonProduct(ProductCatalog.PRODUCT_ID))
        assertFalse(ProductCatalog.isQalqonProduct(FOREIGN_PRODUCT))
        assertFalse(ProductCatalog.isQalqonProduct(null))
        assertFalse(ProductCatalog.isQalqonProduct(""))
        assertFalse(ProductCatalog.isQalqonProduct("   "))
        assertEquals(setOf(ProductCatalog.PRODUCT_ID), ProductCatalog.knownProductIds)
    }

    // ------------------------------------------------------------------- pure

    @Test
    fun entitleablePurchasesKeepsExactlyTheQalqonProducts() {
        val owned = PurchaseProcessor.entitleablePurchases(
            listOf(foreign(), purchased(day = base, token = "q1"), foreign(token = "f2")),
        )
        assertEquals(listOf("q1"), owned.map { it.purchaseToken })
    }

    @Test
    fun aWrongProductPurchaseDoesNotGrantEntitlement() {
        val ent = PurchaseProcessor.toEntitlement(
            purchases = listOf(foreign()),
            now = afterTrial(),
            source = EntitlementSource.PLAY_QUERY,
            hadEntitlementBefore = false,
        )
        assertEquals(EntitlementState.NONE, ent.state)
        assertFalse(ent.grantsAccess)
    }

    @Test
    fun aWrongProductDoesNotCountAsPriorEntitlementEither() {
        // A foreign purchase must not make the account look "previously subscribed".
        val ent = PurchaseProcessor.toEntitlement(
            purchases = listOf(foreign()),
            now = afterTrial(),
            source = EntitlementSource.PLAY_QUERY,
            hadEntitlementBefore = false,
        )
        assertEquals(EntitlementState.NONE, ent.state)
    }

    @Test
    fun aWrongPurchasedPurchaseIsIgnoredWhenAQalqonPurchaseIsPending() {
        val ent = PurchaseProcessor.toEntitlement(
            purchases = listOf(foreign(), pending(token = "q-pending")),
            now = afterTrial(),
            source = EntitlementSource.PLAY_QUERY,
            hadEntitlementBefore = false,
        )
        assertEquals(EntitlementState.PENDING, ent.state)
        assertFalse("pending never grants premium", ent.grantsAccess)
    }

    @Test
    fun aMixedListEntitlesFromTheQalqonPurchaseOnly() {
        val ent = PurchaseProcessor.toEntitlement(
            purchases = listOf(foreign(), purchased(day = base, token = "q1")),
            now = afterTrial(),
            source = EntitlementSource.PLAY_QUERY,
            hadEntitlementBefore = false,
        )
        assertEquals(EntitlementState.ACTIVE, ent.state)
        assertEquals(ProductCatalog.PRODUCT_ID, ent.productId)
    }

    @Test
    fun aBlankProductIdPurchaseIsIgnored() {
        val blank = BillingPurchase(
            productId = "",
            purchaseToken = "blank",
            state = BillingPurchaseState.PURCHASED,
            acknowledged = false,
            autoRenewing = true,
            purchaseTimeMillis = base,
        )
        val ent = PurchaseProcessor.toEntitlement(listOf(blank), afterTrial(), EntitlementSource.PLAY_QUERY, false)
        assertEquals(EntitlementState.NONE, ent.state)
    }

    // ---------------------------------------------------------------- manager

    private fun manager(gateway: FakeBillingGateway, store: InMemoryEntitlementStore) =
        SubscriptionManager(gateway, store, FakeAccountRepository()) { afterTrial() }

    @Test
    fun aWrongProductPurchaseDoesNotGrantPremiumThroughTheManager() = runBlocking {
        val gateway = FakeBillingGateway()
        gateway.purchases = listOf(foreign())
        val store = InMemoryEntitlementStore()
        val manager = manager(gateway, store)

        manager.onAccountChanged(1L)

        assertEquals(EntitlementState.NONE, manager.entitlement.value?.state)
        assertFalse("a foreign product must never grant premium", manager.premiumActive.value)
    }

    @Test
    fun aWrongProductPurchaseIsNeverAcknowledged() = runBlocking {
        val gateway = FakeBillingGateway()
        gateway.purchases = listOf(foreign(token = "foreign-token"))
        val manager = manager(gateway, InMemoryEntitlementStore())

        manager.onAccountChanged(1L)

        assertTrue("a foreign purchase must not be acknowledged", gateway.acknowledged.isEmpty())
    }

    @Test
    fun aMixedListAcknowledgesOnlyTheQalqonPurchase() = runBlocking {
        val gateway = FakeBillingGateway()
        gateway.purchases = listOf(
            foreign(token = "foreign-token"),
            purchased(day = base, acknowledged = false, token = "q1"),
        )
        val manager = manager(gateway, InMemoryEntitlementStore())

        manager.onAccountChanged(1L)

        assertEquals(listOf("q1"), gateway.acknowledged)
        assertEquals(EntitlementState.ACTIVE, manager.entitlement.value?.state)
        assertTrue(manager.premiumActive.value)
    }

    @Test
    fun aWrongProductPushedByThePurchaseListenerDoesNotGrantPremium() = runBlocking {
        val gateway = FakeBillingGateway()
        gateway.purchases = emptyList()
        val manager = manager(gateway, InMemoryEntitlementStore())
        manager.onAccountChanged(1L)
        assertEquals(EntitlementState.NONE, manager.entitlement.value?.state)

        // A purchase update arrives for a product QALQON does not sell.
        manager.onPurchaseUpdate(listOf(foreign()))

        assertEquals(EntitlementState.NONE, manager.entitlement.value?.state)
        assertFalse(manager.premiumActive.value)
        assertTrue(gateway.acknowledged.isEmpty())
    }

    @Test
    fun aQalqonPurchaseStillWinsWhenAForeignProductIsAlsoPresent() = runBlocking {
        val gateway = FakeBillingGateway()
        gateway.purchases = listOf(foreign(), purchased(day = base, acknowledged = true, token = "q1"))
        val manager = manager(gateway, InMemoryEntitlementStore())

        manager.onAccountChanged(1L)

        assertEquals(EntitlementState.ACTIVE, manager.entitlement.value?.state)
        assertEquals(ProductCatalog.PRODUCT_ID, manager.entitlement.value?.productId)
    }
}
