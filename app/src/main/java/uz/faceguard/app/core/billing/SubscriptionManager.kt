package uz.faceguard.app.core.billing

import android.app.Activity
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import uz.faceguard.app.domain.billing.BillingError
import uz.faceguard.app.domain.billing.EntitlementRepository
import uz.faceguard.app.domain.billing.EntitlementSource
import uz.faceguard.app.domain.billing.OfflineEntitlementPolicy
import uz.faceguard.app.domain.billing.PremiumEntitlement
import uz.faceguard.app.domain.billing.ProductCatalog
import uz.faceguard.app.domain.billing.RefreshOutcome
import uz.faceguard.app.domain.billing.grantsPremium
import uz.faceguard.app.domain.repository.AccountRepository

/**
 * The app-scoped subscription brain. It is the single place that turns Google Play
 * purchases into the account's premium entitlement, and the only writer of the
 * entitlement cache.
 *
 * Lifecycle rules (the reason this stage exists):
 * - Only an **authoritative** Play result replaces the stored entitlement. A failed or
 *   unavailable query keeps the previous state, so a paying user is never downgraded by
 *   a transient billing/network error (INVARIANT 3/4, no ACTIVE→EXPIRED regression).
 * - The cache is account-scoped and never leaks across QALQON accounts (INVARIANT 6).
 * - Acknowledge failures are logged and retried on the next refresh, never silently
 *   dropped and never treated as "entitlement done" (INVARIANT 8).
 * - The purchase token is never logged or persisted (INVARIANT 11).
 */
@Singleton
class SubscriptionManager @Inject constructor(
    private val gateway: BillingGateway,
    private val store: EntitlementStore,
    private val accountRepository: AccountRepository,
    private val clock: () -> Long,
) : EntitlementRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _entitlement = MutableStateFlow<PremiumEntitlement?>(null)
    override val entitlement: StateFlow<PremiumEntitlement?> = _entitlement.asStateFlow()

    private val _premiumActive = MutableStateFlow(false)
    override val premiumActive: StateFlow<Boolean> = _premiumActive.asStateFlow()

    private val _product = MutableStateFlow<BillingProductDetails?>(null)
    val product: StateFlow<BillingProductDetails?> = _product.asStateFlow()

    private var started = false
    private var accountId: Long? = null
    private var purchaseJob: Job? = null

    /** Has this account ever held an entitlement? Distinguishes NONE from EXPIRED. */
    @Volatile
    private var everEntitled = false

    /** Purchase tokens acknowledged during this session, so a duplicate never re-acks. */
    private val acknowledgedTokens = mutableSetOf<String>()

    fun start() {
        if (started) return
        started = true
        scope.launch {
            accountRepository.currentAccountId.collect { id -> onAccountChanged(id) }
        }
    }

    /**
     * Handles a signed-in-account change: loads that account's cached entitlement and
     * re-verifies. Public so the lifecycle can also be driven on explicit sign-in.
     */
    suspend fun onAccountChanged(id: Long?) {
        if (id == accountId) return
        accountId = id
        // Account isolation: never carry one account's entitlement into another.
        everEntitled = false
        if (id == null) {
            publish(null)
            return
        }
        val cached = store.observe(id).first()
        everEntitled = cached?.state?.grantsPremium == true
        publish(cached)
        // Play is per Google account, not per QALQON account; re-verify for this session.
        refresh()
    }

    private fun publish(entitlement: PremiumEntitlement?) {
        _entitlement.value = entitlement
        _premiumActive.value = OfflineEntitlementPolicy.isPremiumActive(entitlement, clock())
    }

    /** Re-verifies against Play. Keeps the cached state when Play cannot be reached. */
    override suspend fun refresh(): RefreshOutcome {
        val id = accountId ?: return RefreshOutcome.Failed(BillingError.BILLING_UNAVAILABLE)
        if (!gateway.connect()) {
            // Unavailable: preserve whatever we have; do not downgrade.
            markUnavailable()
            return RefreshOutcome.Unavailable
        }
        val result = gateway.queryActiveSubscriptions()
        if (!result.available) {
            markUnavailable()
            return RefreshOutcome.Unavailable
        }
        val error = result.error
        if (error != null) {
            markUnavailable()
            return RefreshOutcome.Failed(error)
        }
        return applyVerified(id, result.purchases)
    }

    /** Restores purchases: a real Play query, not a UI toast (PHASE Q). */
    suspend fun restore(): RefreshOutcome = refresh()

    private suspend fun applyVerified(
        id: Long,
        purchases: List<BillingPurchase>,
    ): RefreshOutcome {
        val now = clock()
        val ent = PurchaseProcessor.toEntitlement(
            purchases = purchases,
            now = now,
            source = EntitlementSource.PLAY_QUERY,
            hadEntitlementBefore = everEntitled,
        )
        if (ent.grantsAccess) everEntitled = true

        // Acknowledge any purchased-but-unacknowledged purchase (INVARIANT 8). A token
        // already acknowledged this session is not acknowledged again (idempotent).
        purchases
            .filter { it.state == BillingPurchaseState.PURCHASED && !it.acknowledged }
            .filterNot { it.purchaseToken in acknowledgedTokens }
            .forEach { purchase ->
                val ackError = gateway.acknowledge(purchase.purchaseToken)
                if (ackError != null) {
                    // Not fatal, not ignored: retried on the next refresh/reconnect.
                    Log.w(TAG, "purchase acknowledgement deferred: $ackError")
                } else {
                    acknowledgedTokens += purchase.purchaseToken
                }
            }

        store.save(id, ent)
        // Publish only if this result still belongs to the signed-in account. A refresh
        // that was in flight when the user switched (or signed out) must persist to that
        // account's own cache but must never surface its entitlement in the new session
        // (cross-account entitlement leak, INVARIANT 6).
        if (id == accountId) publish(ent)
        return RefreshOutcome.Verified(ent)
    }

    private fun markUnavailable() {
        // Keep the entitlement; record that the last check could not reach Play.
        val current = _entitlement.value
        if (current == null) {
            publish(null)
        } else {
            _premiumActive.value = OfflineEntitlementPolicy.isPremiumActive(current, clock())
        }
    }

    /** Loads dynamic product/offer details (price, trial) from Play. */
    suspend fun loadProduct(): BillingProductQuery {
        gateway.connect()
        val query = gateway.queryProduct(ProductCatalog.PRODUCT_ID)
        _product.value = query.products.firstOrNull()
        return query
    }

    /** Launches the Play purchase sheet; null on launch success. */
    suspend fun launchPurchase(activity: Activity, details: BillingProductDetails): BillingError? =
        gateway.launchPurchase(activity, details)

    /** Applies a purchase update pushed by Play (from the purchase listener). */
    suspend fun onPurchaseUpdate(purchases: List<BillingPurchase>): RefreshOutcome {
        val id = accountId ?: return RefreshOutcome.Failed(BillingError.BILLING_UNAVAILABLE)
        return applyVerified(id, purchases)
    }

    /** Wire the gateway's purchase-update stream into this manager (called by `start`). */
    fun observePurchaseUpdates() {
        purchaseJob?.cancel()
        purchaseJob = scope.launch {
            gateway.purchaseUpdates.collect { purchases -> onPurchaseUpdate(purchases) }
        }
    }

    private companion object {
        const val TAG = "Subscription"
    }
}
