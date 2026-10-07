package uz.faceguard.app.core.billing

import uz.faceguard.app.domain.billing.EntitlementSource
import uz.faceguard.app.domain.billing.EntitlementState
import uz.faceguard.app.domain.billing.PremiumEntitlement
import uz.faceguard.app.domain.billing.ProductCatalog

/**
 * Pure mapping from Google Play purchases to the domain entitlement. No Android, no
 * I/O — so every subscription state transition is unit-testable on the JVM.
 *
 * Client-only limitations (documented): the client library exposes purchased/pending
 * and auto-renewing, but not the absolute expiry date, grace period or account hold.
 * Grace/account-hold/revoked arrive only from a future server/RTDN layer.
 */
object PurchaseProcessor {

    /**
     * Stage 9: keeps only the purchases that belong to a QALQON product.
     *
     * `queryPurchasesAsync(SUBS)` returns every subscription purchase for the app, so
     * this is the single choke point that stops a purchase of some other product from
     * being entitled (or acknowledged). Pure and shared by the entitlement mapping and
     * the acknowledgement path, so the two can never disagree.
     */
    fun entitleablePurchases(purchases: List<BillingPurchase>): List<BillingPurchase> =
        purchases.filter { ProductCatalog.isQalqonProduct(it.productId) }

    /**
     * Chooses the entitlement for [purchases] (the active subscriptions Play returned).
     *
     * A pending purchase wins only when nothing is purchased; a purchased purchase is
     * authoritative. [hadEntitlementBefore] distinguishes "never subscribed" (NONE)
     * from "was subscribed, now gone" (EXPIRED), so the UI can say "expired".
     *
     * Only purchases of a QALQON product are considered: a purchase of any other product
     * is ignored (it neither grants Premium nor marks the account as ever-entitled).
     */
    fun toEntitlement(
        purchases: List<BillingPurchase>,
        now: Long,
        source: EntitlementSource,
        hadEntitlementBefore: Boolean,
    ): PremiumEntitlement {
        val owned = entitleablePurchases(purchases)
        val purchased = owned.firstOrNull { it.state == BillingPurchaseState.PURCHASED }
        if (purchased != null) return fromPurchase(purchased, now, source)

        val pending = owned.firstOrNull { it.state == BillingPurchaseState.PENDING }
        if (pending != null) {
            return PremiumEntitlement(
                state = EntitlementState.PENDING,
                productId = pending.productId,
                isTrial = isWithinTrial(pending.purchaseTimeMillis, now),
                autoRenewing = pending.autoRenewing,
                acknowledged = pending.acknowledged,
                lastVerifiedAtMillis = now,
                source = source,
            )
        }

        return PremiumEntitlement(
            state = if (hadEntitlementBefore) EntitlementState.EXPIRED else EntitlementState.NONE,
            lastVerifiedAtMillis = now,
            source = source,
        )
    }

    /** Maps one purchased subscription to its state. */
    fun fromPurchase(purchase: BillingPurchase, now: Long, source: EntitlementSource): PremiumEntitlement {
        val isTrial = isWithinTrial(purchase.purchaseTimeMillis, now)
        val state = when {
            isTrial -> EntitlementState.TRIAL
            purchase.autoRenewing -> EntitlementState.ACTIVE
            else -> EntitlementState.CANCELED_ACTIVE
        }
        return PremiumEntitlement(
            state = state,
            productId = purchase.productId,
            isTrial = isTrial,
            autoRenewing = purchase.autoRenewing,
            acknowledged = purchase.acknowledged,
            lastVerifiedAtMillis = now,
            source = source,
        )
    }

    /**
     * Best-effort trial detection. The modern Billing Library does not expose per-purchase
     * offer details, so a purchase is treated as the trial while it is inside the catalog
     * trial window. Documented client-only heuristic.
     */
    private fun isWithinTrial(purchaseTimeMillis: Long, now: Long): Boolean {
        if (purchaseTimeMillis <= 0L) return false
        val trialMs = ProductCatalog.TRIAL_DAYS * 24L * 60 * 60 * 1000
        val age = now - purchaseTimeMillis
        return age in 0 until trialMs
    }
}
