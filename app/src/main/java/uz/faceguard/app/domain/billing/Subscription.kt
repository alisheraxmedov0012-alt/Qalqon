package uz.faceguard.app.domain.billing

/**
 * The single subscription product QALQON sells.
 *
 * These identifiers must match the Google Play Console configuration exactly; they are
 * declared once here so a UI string can never drift from the billing product. Play
 * Console setup (product + base plan + 3-day trial offer) is documented in
 * `docs/STAGE7_SUBSCRIPTION_BILLING.md`.
 */
data class BillingProduct(
    val productId: String,
    val basePlanId: String,
    val trialOfferId: String,
)

/** QALQON's product catalog. One monthly auto-renewing base plan with a 3-day trial. */
object ProductCatalog {
    const val PRODUCT_ID = "qalqon_premium"
    const val BASE_PLAN_ID = "monthly"
    const val TRIAL_OFFER_ID = "trial-3-day"

    /** 3-day free trial, then the monthly base plan. */
    const val TRIAL_DAYS = 3

    val premium = BillingProduct(PRODUCT_ID, BASE_PLAN_ID, TRIAL_OFFER_ID)

    /**
     * Stage 9: every subscription product QALQON sells.
     *
     * `queryPurchasesAsync(SUBS)` returns *all* of this app's subscription purchases, so
     * an entitlement must be derived only from a purchase of a product QALQON actually
     * sells. Without this check a purchase of any other (e.g. legacy/deprecated) product
     * in the app's catalog would grant Premium — the "wrong product grants Premium"
     * failure criterion. Extend this set when a new QALQON product is added on Play.
     */
    val knownProductIds: Set<String> = setOf(PRODUCT_ID)

    /** True when [productId] is one of QALQON's own subscription products. */
    fun isQalqonProduct(productId: String?): Boolean =
        !productId.isNullOrBlank() && productId in knownProductIds
}

/**
 * The premium boundary, defined centrally (never scattered as `if (subscribed)` in
 * screens). [free] features are always available; every other feature requires an
 * active premium entitlement.
 *
 * Product decision: core protection stays FREE so a paying user can never lose
 * protection because of a billing state. Premium gates the advanced parental controls.
 */
enum class PremiumFeature(val free: Boolean) {
    /** Blocking protected apps, parent/child recognition, PIN, emergency unlock. Always free. */
    CORE_PROTECTION(free = true),

    /** More than one child profile. */
    MULTIPLE_CHILDREN(free = false),

    /** Per-child schedules. */
    SCHEDULES(free = false),

    /** Screen-time limits. */
    SCREEN_TIME_LIMITS(free = false),

    /** Eye-safety configuration. */
    EYE_SAFETY(free = false),

    /** The child's "request extra time" flow. */
    EXTRA_TIME_REQUESTS(free = false),

    /** The activity history view. */
    ACTIVITY_HISTORY(free = false),
}

/** Central premium gate. Every screen asks this, never the raw entitlement. */
object PremiumAccessEvaluator {

    /** True when [feature] is usable with [entitlement] at [now]. */
    fun isAvailable(feature: PremiumFeature, entitlement: PremiumEntitlement?, now: Long): Boolean =
        feature.free || OfflineEntitlementPolicy.isPremiumActive(entitlement, now)

    /** True when premium is active at [now] (convenience for the subscription screen). */
    fun isPremiumActive(entitlement: PremiumEntitlement?, now: Long): Boolean =
        OfflineEntitlementPolicy.isPremiumActive(entitlement, now)
}

/**
 * A user-actionable billing failure. Localized in the UI; the raw Play debug message
 * is never shown and never logged with a purchase token.
 */
enum class BillingError {
    /** The user dismissed the Play purchase sheet. */
    USER_CANCELED,

    /** Google Play / the billing service is not reachable or not available. */
    BILLING_UNAVAILABLE,

    /** The product or offer is not available (e.g. not configured on Play). */
    ITEM_UNAVAILABLE,

    /** The item is already owned by this Play account. */
    ITEM_ALREADY_OWNED,

    /** A network error occurred talking to Play. */
    NETWORK_ERROR,

    /** The purchase is pending (deferred payment). */
    PENDING,

    /** A generic, unexpected billing failure. */
    UNEXPECTED,
}

/**
 * The outcome of a Google Play connection/query attempt, without leaking Play types
 * into the domain.
 */
sealed interface RefreshOutcome {
    /** Play answered authoritatively; the stored entitlement was replaced. */
    data class Verified(val entitlement: PremiumEntitlement) : RefreshOutcome

    /** Play could not be reached; the previous (cached) entitlement was kept. */
    data object Unavailable : RefreshOutcome

    /** The user dismissed a Play sheet. */
    data object Canceled : RefreshOutcome

    /** A specific billing error. */
    data class Failed(val error: BillingError) : RefreshOutcome
}
