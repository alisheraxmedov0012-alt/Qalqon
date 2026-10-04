package uz.faceguard.app.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.billing.BillingErrorMapper
import uz.faceguard.app.core.billing.BillingPurchaseState
import uz.faceguard.app.core.billing.PurchaseProcessor
import uz.faceguard.app.domain.billing.BillingError
import uz.faceguard.app.domain.billing.EntitlementSource
import uz.faceguard.app.domain.billing.EntitlementState
import uz.faceguard.app.domain.billing.ProductCatalog

/** Pure mapping from Google Play purchase data to the entitlement state. */
class PurchaseProcessorTest {

    private val dayMs = 24L * 60 * 60 * 1000
    private val base = 1_000_000_000L

    private fun purchases(vararg p: uz.faceguard.app.core.billing.BillingPurchase) = p.toList()

    @Test
    fun aFreshPurchaseInsideTheTrialWindowIsTrial() {
        val ent = PurchaseProcessor.toEntitlement(
            purchases = purchases(purchased(day = base)),
            now = base + dayMs, // 1 day in
            source = EntitlementSource.PLAY_QUERY,
            hadEntitlementBefore = false,
        )
        assertEquals(EntitlementState.TRIAL, ent.state)
        assertTrue(ent.isTrial)
        assertTrue(ent.autoRenewing)
    }

    @Test
    fun aPurchasePastTheTrialWindowAutoRenewingIsActive() {
        val ent = PurchaseProcessor.toEntitlement(
            purchases = purchases(purchased(day = base, autoRenewing = true)),
            now = base + (ProductCatalog.TRIAL_DAYS + 1) * dayMs,
            source = EntitlementSource.PLAY_QUERY,
            hadEntitlementBefore = true,
        )
        assertEquals(EntitlementState.ACTIVE, ent.state)
        assertFalse(ent.isTrial)
    }

    @Test
    fun aCanceledButUnfinishedPurchaseKeepsAccessUntilPeriodEnd() {
        val ent = PurchaseProcessor.toEntitlement(
            purchases = purchases(purchased(day = base, autoRenewing = false)),
            now = base + (ProductCatalog.TRIAL_DAYS + 1) * dayMs,
            source = EntitlementSource.PLAY_QUERY,
            hadEntitlementBefore = true,
        )
        assertEquals(EntitlementState.CANCELED_ACTIVE, ent.state)
        assertTrue("canceled-active still grants premium", ent.grantsAccess)
    }

    @Test
    fun aPendingPurchaseIsPendingNotEntitled() {
        val ent = PurchaseProcessor.toEntitlement(
            purchases = purchases(pending()),
            now = base,
            source = EntitlementSource.PLAY_QUERY,
            hadEntitlementBefore = false,
        )
        assertEquals(EntitlementState.PENDING, ent.state)
        assertFalse(ent.grantsAccess)
    }

    @Test
    fun noPurchasesWithNoHistoryIsNone() {
        val ent = PurchaseProcessor.toEntitlement(emptyList(), base, EntitlementSource.PLAY_QUERY, false)
        assertEquals(EntitlementState.NONE, ent.state)
    }

    @Test
    fun noPurchasesAfterHavingOneIsExpired() {
        val ent = PurchaseProcessor.toEntitlement(emptyList(), base, EntitlementSource.PLAY_QUERY, true)
        assertEquals(EntitlementState.EXPIRED, ent.state)
    }

    @Test
    fun billingErrorCodesMapToUserActionableErrors() {
        assertEquals(BillingError.USER_CANCELED, BillingErrorMapper.fromResponseCode(BillingErrorMapper.USER_CANCELED))
        assertEquals(BillingError.BILLING_UNAVAILABLE, BillingErrorMapper.fromResponseCode(BillingErrorMapper.SERVICE_UNAVAILABLE))
        assertEquals(BillingError.BILLING_UNAVAILABLE, BillingErrorMapper.fromResponseCode(BillingErrorMapper.SERVICE_DISCONNECTED))
        assertEquals(BillingError.NETWORK_ERROR, BillingErrorMapper.fromResponseCode(BillingErrorMapper.NETWORK_ERROR))
        assertEquals(BillingError.ITEM_UNAVAILABLE, BillingErrorMapper.fromResponseCode(BillingErrorMapper.ITEM_UNAVAILABLE))
        assertEquals(BillingError.ITEM_ALREADY_OWNED, BillingErrorMapper.fromResponseCode(BillingErrorMapper.ITEM_ALREADY_OWNED))
        assertEquals(BillingError.UNEXPECTED, BillingErrorMapper.fromResponseCode(BillingErrorMapper.DEVELOPER_ERROR))
        assertTrue(BillingErrorMapper.isAuthoritative(BillingErrorMapper.OK))
        assertFalse(BillingErrorMapper.isAuthoritative(BillingErrorMapper.NETWORK_ERROR))
    }

    @Test
    fun anUnspecifiedPurchaseStateIsNotPurchased() {
        val dto = uz.faceguard.app.core.billing.BillingPurchase(
            productId = ProductCatalog.PRODUCT_ID,
            purchaseToken = "t",
            state = BillingPurchaseState.UNSPECIFIED,
            acknowledged = false,
            autoRenewing = false,
            purchaseTimeMillis = base,
        )
        val ent = PurchaseProcessor.toEntitlement(listOf(dto), base, EntitlementSource.PLAY_QUERY, false)
        assertEquals(EntitlementState.NONE, ent.state)
    }
}
