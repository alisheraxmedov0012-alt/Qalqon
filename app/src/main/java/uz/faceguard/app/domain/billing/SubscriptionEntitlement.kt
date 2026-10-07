package uz.faceguard.app.domain.billing

/**
 * Which signal produced an entitlement. Deliberately explicit so a cached value can
 * never be mistaken for an authoritative Google Play result.
 */
enum class EntitlementSource {
    /** No entitlement is known. */
    NONE,

    /** Derived from a live Google Play query this session. */
    PLAY_QUERY,

    /** Restored from the local cache because Play could not be queried (offline). */
    CACHE,

    /** Google Play / the billing service could not be reached at all. */
    BILLING_UNAVAILABLE,
}

/**
 * The subscription/entitlement state.
 *
 * Client-only billing (this build) can authoritatively derive [NONE], [PENDING],
 * [TRIAL], [ACTIVE], [CANCELED_ACTIVE] and [EXPIRED]. [GRACE_PERIOD],
 * [ACCOUNT_HOLD] and [REVOKED] are Google Play server-side states that the client
 * library does not expose reliably; they are part of the model so a future
 * server/RTDN verification layer can map into it without a model change, and are
 * documented as client-only limitations.
 */
enum class EntitlementState {
    /** No subscription for this account. */
    NONE,

    /** A purchase is pending (e.g. deferred payment); not yet entitled. */
    PENDING,

    /** Free trial active. */
    TRIAL,

    /** Paid subscription active and auto-renewing. */
    ACTIVE,

    /** Canceled, but still inside the paid period (access continues). */
    CANCELED_ACTIVE,

    /** Payment failed but Google Play still grants access (grace period). */
    GRACE_PERIOD,

    /** Payment failed and Google Play has paused the subscription (no access). */
    ACCOUNT_HOLD,

    /** The subscription ended. */
    EXPIRED,

    /** Refunded or otherwise revoked. */
    REVOKED,

    /** Google Play / the billing service is unavailable; entitlement unknown. */
    BILLING_UNAVAILABLE,

    /** Could not be determined. */
    UNKNOWN,
}

/**
 * True when this state grants premium access *by itself*, ignoring cache staleness.
 * [GRACE_PERIOD] counts (Google Play continues access during grace).
 */
val EntitlementState.grantsPremium: Boolean
    get() = this == EntitlementState.TRIAL ||
        this == EntitlementState.ACTIVE ||
        this == EntitlementState.CANCELED_ACTIVE ||
        this == EntitlementState.GRACE_PERIOD

/**
 * A resolved premium entitlement for one account.
 *
 * [expiryTimeMillis] is nullable on purpose: client-only billing does not expose an
 * absolute subscription expiry, so it is usually unknown and the offline staleness
 * window governs instead. The purchase token is deliberately **not** part of this
 * model — it never reaches the UI or the logs.
 */
data class PremiumEntitlement(
    val state: EntitlementState,
    val productId: String? = null,
    val basePlanId: String? = null,
    val offerId: String? = null,
    val isTrial: Boolean = false,
    val autoRenewing: Boolean = false,
    val acknowledged: Boolean = false,
    val expiryTimeMillis: Long? = null,
    val lastVerifiedAtMillis: Long = 0L,
    val source: EntitlementSource = EntitlementSource.NONE,
) {
    /** True when the state itself grants access (staleness not considered). */
    val grantsAccess: Boolean get() = state.grantsPremium
}

/**
 * The offline/staleness policy for cached entitlements. Documented and centralized so
 * it is a single, testable rule rather than an implicit behaviour.
 *
 * `OFFLINE_STALENESS_LIMIT_MS` is how long an entitling entitlement is honored after
 * the last successful verification when no absolute expiry is known. It bounds
 * INVARIANT 5 (the cache never extends entitlement indefinitely) while honoring
 * INVARIANT 4 (a paying user who goes offline is not dropped immediately).
 */
object OfflineEntitlementPolicy {

    /** 72 hours: a paying user offline over a weekend keeps premium; beyond that, re-verify. */
    const val OFFLINE_STALENESS_LIMIT_MS: Long = 72L * 60 * 60 * 1000

    /**
     * Whether [entitlement] grants premium access at [now], applying the offline policy.
     *
     * - A non-entitling state never grants access.
     * - With a known absolute expiry, access runs until that expiry.
     * - Without one, access runs until the last verification is older than the limit.
     */
    fun isPremiumActive(entitlement: PremiumEntitlement?, now: Long): Boolean {
        if (entitlement == null) return false
        if (!entitlement.grantsAccess) return false
        val expiry = entitlement.expiryTimeMillis
        if (expiry != null) return now < expiry
        val age = now - entitlement.lastVerifiedAtMillis
        return age in 0..OFFLINE_STALENESS_LIMIT_MS
    }
}
