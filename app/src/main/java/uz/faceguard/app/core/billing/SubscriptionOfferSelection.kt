package uz.faceguard.app.core.billing

import uz.faceguard.app.domain.billing.ProductCatalog

/**
 * Chooses which Google Play subscription **offer** the app advertises and launches.
 *
 * A base plan can expose several offers, so "the first one" is not a safe rule: the price
 * the UI shows and the offer token sent to `BillingFlowParams` must be the *same* offer.
 * QALQON sells exactly one offer today — the 3-day free trial — so the trial offer is
 * preferred by id and the first offer is only a fallback for a catalog that does not
 * (yet) carry it.
 *
 * Pure and Android-free (operates on offer ids only), so it is unit-testable on the JVM.
 */
object SubscriptionOfferSelection {

    /**
     * Index of the offer to use, or `-1` when there are no offers.
     *
     * Prefers the offer whose id is [ProductCatalog.TRIAL_OFFER_ID]; otherwise the first
     * offer. `null`/blank ids never match the trial and never win.
     */
    fun indexOfPreferredOffer(offerIds: List<String?>): Int {
        if (offerIds.isEmpty()) return -1
        val trial = offerIds.indexOfFirst { it == ProductCatalog.TRIAL_OFFER_ID }
        return if (trial >= 0) trial else 0
    }
}
