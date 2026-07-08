package com.charles.meshtalk.app.billing

import android.app.Activity
import android.content.Context
import android.util.Log
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
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Play Billing Library v7 implementation. Play itself is the source of truth for entitlement —
 * no external backend is needed for this flavor (unlike the github/Stripe flavor, which has
 * nothing else to ask). Product/subscription IDs below must be created manually in Play Console
 * (Monetize → Products) before purchases can be tested; see the walkie-talkie feature plan for
 * the exact steps (subscription needs a base plan with a 3-day free-trial pricing phase).
 */
class PlayBillingRepository(private val appContext: Context) : BillingRepository, PurchasesUpdatedListener {
    companion object {
        private const val TAG = "PlayBillingRepository"
        const val PRODUCT_ID_LIFETIME = "walkietalkie_lifetime"
        const val PRODUCT_ID_SUBSCRIPTION = "walkietalkie_monthly"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val billingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    private val _entitlementState = MutableStateFlow<EntitlementState>(EntitlementState.Unknown)
    override val entitlementState: StateFlow<EntitlementState> = _entitlementState.asStateFlow()

    private var lifetimeProduct: ProductDetails? = null
    private var subscriptionProduct: ProductDetails? = null
    private var subscriptionOfferToken: String? = null

    init {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    scope.launch {
                        queryProductDetails()
                        refreshEntitlement()
                    }
                } else {
                    Log.w(TAG, "Billing setup failed: ${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected; will reconnect on next refresh")
            }
        })
    }

    private suspend fun queryProductDetails() {
        // Billing Library v7 requires every queryProductDetails() call to list products of a
        // single type only (mixing INAPP + SUBS in one call throws IllegalArgumentException) — so
        // the lifetime (INAPP) and subscription (SUBS) lookups have to be two separate queries.
        val inappParams = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID_LIFETIME)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        val inappResult = billingClient.queryProductDetails(inappParams)
        if (inappResult.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            lifetimeProduct = inappResult.productDetailsList.orEmpty().firstOrNull()
        } else {
            Log.w(TAG, "queryProductDetails (INAPP) failed: ${inappResult.billingResult.debugMessage}")
        }

        val subsParams = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID_SUBSCRIPTION)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()
        val subsResult = billingClient.queryProductDetails(subsParams)
        if (subsResult.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            subscriptionProduct = subsResult.productDetailsList.orEmpty().firstOrNull()
            // The base plan's offer already carries the 3-day free-trial pricing phase configured
            // in Play Console — no separate "start trial" call is needed; Play grants it
            // automatically the first time this offer token is used, and refuses to re-grant a
            // trial to a user who already had one.
            subscriptionOfferToken = subscriptionProduct?.subscriptionOfferDetails?.firstOrNull()?.offerToken
        } else {
            Log.w(TAG, "queryProductDetails (SUBS) failed: ${subsResult.billingResult.debugMessage}")
        }
    }

    override suspend fun refreshEntitlement() {
        if (billingClient.connectionState != BillingClient.ConnectionState.CONNECTED) return

        val lifetimeResult = billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        )
        val hasLifetime = lifetimeResult.purchasesList.any {
            it.products.contains(PRODUCT_ID_LIFETIME) && it.purchaseState == Purchase.PurchaseState.PURCHASED
        }
        if (hasLifetime) {
            acknowledgeIfNeeded(lifetimeResult.purchasesList.first { it.products.contains(PRODUCT_ID_LIFETIME) })
            _entitlementState.value = EntitlementState.LifetimePurchased
            return
        }

        val subsResult = billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        )
        val activeSub = subsResult.purchasesList.firstOrNull {
            it.products.contains(PRODUCT_ID_SUBSCRIPTION) && it.purchaseState == Purchase.PurchaseState.PURCHASED
        }
        if (activeSub != null) {
            acknowledgeIfNeeded(activeSub)
            // Play doesn't expose renewal/expiry timestamps via queryPurchasesAsync — the presence
            // of an active, acknowledged purchase is itself the entitlement signal; Play stops
            // returning it once the subscription lapses/cancels-and-expires.
            _entitlementState.value = EntitlementState.Subscribed(renewsOrExpiresAtEpochMs = 0L)
            return
        }

        _entitlementState.value = EntitlementState.NotEntitled
    }

    private fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        val params = com.android.billingclient.api.AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "acknowledgePurchase failed: ${result.debugMessage}")
            }
        }
    }

    override suspend fun startOneTimePurchase(activity: Activity) {
        val product = lifetimeProduct ?: run {
            Log.w(TAG, "startOneTimePurchase: product details not loaded yet")
            return
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .build()
                )
            )
            .build()
        billingClient.launchBillingFlow(activity, params)
    }

    override suspend fun startSubscriptionPurchase(activity: Activity) {
        val product = subscriptionProduct ?: run {
            Log.w(TAG, "startSubscriptionPurchase: product details not loaded yet")
            return
        }
        val offerToken = subscriptionOfferToken ?: run {
            Log.w(TAG, "startSubscriptionPurchase: no offer token available")
            return
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
            .build()
        billingClient.launchBillingFlow(activity, params)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            scope.launch { refreshEntitlement() }
        } else if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
            Log.w(TAG, "onPurchasesUpdated: ${result.debugMessage}")
        }
    }
}
