package uz.faceguard.app.core.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import uz.faceguard.app.domain.billing.BillingError

/**
 * Google Play Billing implementation of [BillingGateway].
 *
 * QALQON talks to the Play Store over IPC, so no `INTERNET` permission is required; the
 * offline-first manifest (INTERNET removed) is preserved. The purchase token is passed
 * only to Play (acknowledge/re-query) and is never logged.
 *
 * Client-only limitations (documented in `docs/STAGE7_SUBSCRIPTION_BILLING.md`): the
 * client library does not expose the absolute expiry date, grace period or account
 * hold — those require the Play Developer API / RTDN on a server.
 */
@Singleton
class GooglePlayBillingGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) : BillingGateway {

    private val _purchaseUpdates = MutableSharedFlow<List<BillingPurchase>>(extraBufferCapacity = 4)
    override val purchaseUpdates: Flow<List<BillingPurchase>> = _purchaseUpdates.asSharedFlow()

    @Volatile
    private var connected = false

    /** The most recent raw product details, needed to launch the purchase flow. */
    @Volatile
    private var lastProductDetails: ProductDetails? = null

    private val client: BillingClient by lazy {
        BillingClient.newBuilder(context)
            .setListener(
                PurchasesUpdatedListener { result, purchases ->
                    if (result.responseCode == BillingErrorMapper.OK && !purchases.isNullOrEmpty()) {
                        _purchaseUpdates.tryEmit(purchases.map { it.toDto() })
                    }
                },
            )
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
            )
            .enableAutoServiceReconnection()
            .build()
    }

    override suspend fun connect(): Boolean {
        if (connected) return true
        return suspendCancellableCoroutine { cont ->
            client.startConnection(
                object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        connected = result.responseCode == BillingErrorMapper.OK
                        if (cont.isActive) cont.resume(connected)
                    }

                    override fun onBillingServiceDisconnected() {
                        connected = false
                    }
                },
            )
        }
    }

    override suspend fun queryProduct(productId: String): BillingProductQuery {
        if (!connect()) return BillingProductQuery(emptyList(), BillingError.BILLING_UNAVAILABLE)
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                ),
            )
            .build()
        return suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { result, queryResult ->
                if (result.responseCode != BillingErrorMapper.OK) {
                    if (cont.isActive) {
                        cont.resume(
                            BillingProductQuery(
                                emptyList(),
                                BillingErrorMapper.fromResponseCode(result.responseCode),
                            ),
                        )
                    }
                    return@queryProductDetailsAsync
                }
                val detailsList = queryResult.productDetailsList
                lastProductDetails = detailsList.firstOrNull()
                if (cont.isActive) {
                    cont.resume(BillingProductQuery(detailsList.mapNotNull { it.toDto() }))
                }
            }
        }
    }

    override suspend fun queryActiveSubscriptions(): BillingPurchaseQuery {
        if (!connect()) {
            return BillingPurchaseQuery(emptyList(), BillingError.BILLING_UNAVAILABLE, available = false)
        }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        return suspendCancellableCoroutine { cont ->
            client.queryPurchasesAsync(params) { result, purchases ->
                val available = result.responseCode == BillingErrorMapper.OK
                val error = if (available) null else BillingErrorMapper.fromResponseCode(result.responseCode)
                if (cont.isActive) {
                    cont.resume(
                        BillingPurchaseQuery(
                            purchases = purchases.map { it.toDto() },
                            error = error,
                            available = available,
                        ),
                    )
                }
            }
        }
    }

    override suspend fun launchPurchase(activity: Activity, details: BillingProductDetails): BillingError? {
        if (!connect()) return BillingError.BILLING_UNAVAILABLE
        // Ensure we hold the raw ProductDetails for the requested product.
        val productDetails = lastProductDetails?.takeIf { it.productId == details.productId }
            ?: run {
                queryProduct(details.productId)
                lastProductDetails?.takeIf { it.productId == details.productId }
            }
            ?: return BillingError.ITEM_UNAVAILABLE

        val offers = productDetails.subscriptionOfferDetails
        val selectedOffer = offers?.getOrNull(
            SubscriptionOfferSelection.indexOfPreferredOffer(offers.map { it.offerId }),
        )
        val offerToken = selectedOffer?.offerToken ?: details.offerToken

        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .apply { offerToken?.let { setOfferToken(it) } }
                        .build(),
                ),
            )
            .build()

        val result = client.launchBillingFlow(activity, params)
        return if (result.responseCode == BillingErrorMapper.OK) {
            null
        } else {
            BillingErrorMapper.fromResponseCode(result.responseCode)
        }
    }

    override suspend fun acknowledge(purchaseToken: String): BillingError? {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()
        return suspendCancellableCoroutine { cont ->
            client.acknowledgePurchase(params) { result ->
                if (cont.isActive) {
                    cont.resume(
                        if (result.responseCode == BillingErrorMapper.OK) {
                            null
                        } else {
                            BillingErrorMapper.fromResponseCode(result.responseCode)
                        },
                    )
                }
            }
        }
    }

    override fun endConnection() {
        runCatching { client.endConnection() }
            .onFailure { Log.w(TAG, "billing endConnection failed", it) }
        connected = false
    }

    private fun Purchase.toDto(): BillingPurchase {
        return BillingPurchase(
            productId = products.firstOrNull() ?: "",
            purchaseToken = purchaseToken,
            state = when (purchaseState) {
                Purchase.PurchaseState.PURCHASED -> BillingPurchaseState.PURCHASED
                Purchase.PurchaseState.PENDING -> BillingPurchaseState.PENDING
                else -> BillingPurchaseState.UNSPECIFIED
            },
            acknowledged = isAcknowledged,
            autoRenewing = isAutoRenewing,
            purchaseTimeMillis = purchaseTime,
        )
    }

    private fun ProductDetails.toDto(): BillingProductDetails? {
        // A base plan can expose several offers; the advertised price and the offer that is
        // launched must be the same offer, so the trial offer is preferred over "the first".
        val offers = subscriptionOfferDetails ?: return null
        val offer = offers.getOrNull(
            SubscriptionOfferSelection.indexOfPreferredOffer(offers.map { it.offerId }),
        ) ?: return null
        val phases = offer.pricingPhases.pricingPhaseList
        val firstPhase = phases.firstOrNull()
        val paidPhase = phases.lastOrNull()
        val isFreeTrial = firstPhase != null && firstPhase.priceAmountMicros == 0L
        return BillingProductDetails(
            productId = productId,
            basePlanId = offer.basePlanId,
            offerId = offer.offerId,
            offerToken = offer.offerToken,
            formattedPrice = paidPhase?.formattedPrice,
            priceCurrencyCode = paidPhase?.priceCurrencyCode,
            billingPeriod = paidPhase?.billingPeriod,
            isFreeTrial = isFreeTrial,
            freeTrialPeriod = if (isFreeTrial) firstPhase?.billingPeriod else null,
        )
    }

    private companion object {
        const val TAG = "Subscription"
    }
}
