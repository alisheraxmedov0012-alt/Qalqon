package uz.faceguard.app.billing

import android.app.Activity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import uz.faceguard.app.core.billing.BillingGateway
import uz.faceguard.app.core.billing.BillingProductDetails
import uz.faceguard.app.core.billing.BillingProductQuery
import uz.faceguard.app.core.billing.BillingPurchase
import uz.faceguard.app.core.billing.BillingPurchaseQuery
import uz.faceguard.app.core.billing.BillingPurchaseState
import uz.faceguard.app.core.billing.EntitlementStore
import uz.faceguard.app.domain.billing.BillingError
import uz.faceguard.app.domain.billing.PremiumEntitlement
import uz.faceguard.app.domain.billing.ProductCatalog
import uz.faceguard.app.domain.model.AuthResult
import uz.faceguard.app.domain.model.UserAccount
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.security.PinVerification

/**
 * Deterministic fakes for the billing lifecycle tests. They implement the same seams
 * production uses, so the entitlement logic under test is the real code — only Google
 * Play is replaced. Real Google Play purchases are NOT TESTED here (see the report).
 */
class FakeBillingGateway : BillingGateway {

    @Volatile var connectResult: Boolean = true
    @Volatile var available: Boolean = true
    @Volatile var queryError: BillingError? = null
    @Volatile var purchases: List<BillingPurchase> = emptyList()
    @Volatile var launchError: BillingError? = null
    @Volatile var product: BillingProductDetails? = BillingProductDetails(
        productId = ProductCatalog.PRODUCT_ID,
        basePlanId = ProductCatalog.BASE_PLAN_ID,
        offerId = ProductCatalog.TRIAL_OFFER_ID,
        offerToken = "offer-token",
        formattedPrice = "$4.99",
        priceCurrencyCode = "USD",
        billingPeriod = "P1M",
        isFreeTrial = true,
        freeTrialPeriod = "P3D",
    )

    val acknowledged = mutableListOf<String>()
    val acknowledgeErrors = mutableMapOf<String, BillingError>()
    var connectCount = 0

    private val updates = MutableSharedFlow<List<BillingPurchase>>(extraBufferCapacity = 4)
    override val purchaseUpdates: Flow<List<BillingPurchase>> = updates.asSharedFlow()

    override suspend fun connect(): Boolean {
        connectCount++
        return connectResult
    }

    override suspend fun queryProduct(productId: String): BillingProductQuery =
        product?.let { BillingProductQuery(listOf(it)) }
            ?: BillingProductQuery(emptyList(), BillingError.ITEM_UNAVAILABLE)

    override suspend fun queryActiveSubscriptions(): BillingPurchaseQuery =
        BillingPurchaseQuery(purchases, queryError, available)

    override suspend fun launchPurchase(activity: Activity, details: BillingProductDetails): BillingError? =
        launchError

    override suspend fun acknowledge(purchaseToken: String): BillingError? {
        acknowledgeErrors[purchaseToken]?.let { return it }
        acknowledged += purchaseToken
        return null
    }

    override fun endConnection() = Unit

    fun emit(purchases: List<BillingPurchase>) {
        updates.tryEmit(purchases)
    }
}

/** In-memory entitlement cache, account-scoped like the DataStore one. */
class InMemoryEntitlementStore : EntitlementStore {
    private val map = mutableMapOf<Long, PremiumEntitlement>()

    override fun observe(accountId: Long): Flow<PremiumEntitlement?> = flowOf(map[accountId])

    override suspend fun save(accountId: Long, entitlement: PremiumEntitlement) {
        map[accountId] = entitlement
    }

    override suspend fun clear(accountId: Long) {
        map.remove(accountId)
    }

    fun stored(accountId: Long): PremiumEntitlement? = map[accountId]
}

/** Minimal AccountRepository; only [currentAccountId] is exercised by the manager. */
class FakeAccountRepository : AccountRepository {
    private val account = MutableStateFlow<Long?>(null)
    override val currentAccountId: Flow<Long?> = account
    override suspend fun register(fullName: String, phoneNumber: String, pin: String): AuthResult =
        TODO("not used")
    override suspend fun login(phoneNumber: String, pin: String): AuthResult = TODO("not used")
    override suspend fun getCurrentAccount(): UserAccount? = null
    override suspend fun logout() = Unit
    override suspend fun verifyPin(pin: String): PinVerification = TODO("not used")
}

fun purchased(
    day: Long,
    autoRenewing: Boolean = true,
    acknowledged: Boolean = true,
    token: String = "token-$day",
): BillingPurchase = BillingPurchase(
    productId = ProductCatalog.PRODUCT_ID,
    purchaseToken = token,
    state = BillingPurchaseState.PURCHASED,
    acknowledged = acknowledged,
    autoRenewing = autoRenewing,
    purchaseTimeMillis = day,
)

fun pending(token: String = "pending-token"): BillingPurchase = BillingPurchase(
    productId = ProductCatalog.PRODUCT_ID,
    purchaseToken = token,
    state = BillingPurchaseState.PENDING,
    acknowledged = false,
    autoRenewing = false,
    purchaseTimeMillis = 0L,
)
