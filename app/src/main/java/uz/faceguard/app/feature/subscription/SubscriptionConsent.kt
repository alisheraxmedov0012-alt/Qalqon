package uz.faceguard.app.feature.subscription

import uz.faceguard.app.core.billing.BillingProductDetails

/**
 * Release Block 1: the subscription consent gate.
 *
 * Google Play policy requires an affirmative, informed consent before a subscription
 * (and its free trial) is started, and the terms must be shown together with the real
 * price. This object is the single decision point for "may billing start?".
 *
 * Deliberately pure (no Android), so it is unit-testable and so no screen can start a
 * purchase by accident. It is **not** the AccessibilityService consent
 * (`accessibility_disclosure_*`, shown by the protection screen under its own flow) — the
 * two consents are separate and must never be merged into one checkbox.
 */
object SubscriptionConsent {

    /**
     * True when Play returned a usable, localized price to disclose. A missing price must
     * never be shown as a number, and billing must not start without a price on screen.
     */
    fun hasDisclosablePrice(product: BillingProductDetails?): Boolean =
        !product?.formattedPrice.isNullOrBlank()

    /**
     * Billing may start only when the user gave affirmative consent **and** a real price is
     * available to disclose. Both conditions are required.
     */
    fun canStartPurchase(consentGiven: Boolean, product: BillingProductDetails?): Boolean =
        consentGiven && hasDisclosablePrice(product)
}
