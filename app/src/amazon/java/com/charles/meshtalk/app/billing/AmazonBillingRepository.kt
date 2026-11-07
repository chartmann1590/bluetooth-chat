package com.charles.meshtalk.app.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.amazon.device.iap.PurchasingListener
import com.amazon.device.iap.PurchasingService
import com.amazon.device.iap.model.FulfillmentResult
import com.amazon.device.iap.model.ProductDataResponse
import com.amazon.device.iap.model.ProductType
import com.amazon.device.iap.model.PurchaseResponse
import com.amazon.device.iap.model.PurchaseUpdatesResponse
import com.amazon.device.iap.model.Receipt
import com.amazon.device.iap.model.UserDataResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Amazon Appstore flavor: Amazon In-App Purchasing (Appstore SDK 3.x). Fire devices have no Google
 * Play services, and Amazon's policy requires its own IAP for digital unlocks (no Stripe, no
 * links to outside payment). Same two products and prices as the play flavor:
 *  - [SKU_LIFETIME]  entitlement, one-time ($9.99)
 *  - [SKU_SUBSCRIPTION_PARENT] / [SKU_SUBSCRIPTION_TERM] monthly subscription ($2.99, 3-day free trial)
 * Amazon is the source of truth: getPurchaseUpdates(reset=true) returns every active entitlement
 * and subscription for the signed-in Amazon user (and works offline from the Appstore's cache).
 * The last known state is also cached in prefs so the gate opens instantly on cold start.
 * SKUs must be created in the Amazon Developer Console (see AMAZON-IAP-SKUS.md); until they are,
 * purchase() answers INVALID_SKU / NOT_SUPPORTED and the app stays free with ads.
 */
class AmazonBillingRepository(private val appContext: Context) : BillingRepository, PurchasingListener {
    companion object {
        private const val TAG = "AmazonBillingRepository"
        const val SKU_LIFETIME = "walkietalkie_lifetime"
        const val SKU_SUBSCRIPTION_PARENT = "walkietalkie_monthly"
        const val SKU_SUBSCRIPTION_TERM = "walkietalkie_monthly_1m"
        private const val PREFS = "meshtalk_amazon_billing"
        private const val PREF_STATE = "state"
    }

    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _entitlementState = MutableStateFlow(cachedState())
    override val entitlementState: StateFlow<EntitlementState> = _entitlementState.asStateFlow()

    // getPurchaseUpdates pages through receipts (hasMore); collect across pages before deciding.
    private var pendingLifetime = false
    private var pendingSubscription = false
    private var registered = false

    init {
        registered = try {
            PurchasingService.registerListener(appContext, this)
            PurchasingService.getUserData()
            PurchasingService.getProductData(setOf(SKU_LIFETIME, SKU_SUBSCRIPTION_TERM))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Amazon IAP unavailable: ${t.message}")
            false
        }
        if (registered) requestUpdates()
    }

    private fun cachedState(): EntitlementState = when (prefs.getString(PREF_STATE, null)) {
        "lifetime" -> EntitlementState.LifetimePurchased
        "subscribed" -> EntitlementState.Subscribed(renewsOrExpiresAtEpochMs = 0L)
        else -> EntitlementState.Unknown
    }

    private fun save(state: EntitlementState) {
        val v = when (state) {
            EntitlementState.LifetimePurchased -> "lifetime"
            is EntitlementState.Subscribed -> "subscribed"
            else -> "none"
        }
        prefs.edit().putString(PREF_STATE, v).apply()
        _entitlementState.value = state
    }

    private fun requestUpdates() {
        pendingLifetime = false
        pendingSubscription = false
        try { PurchasingService.getPurchaseUpdates(true) } catch (t: Throwable) { Log.w(TAG, "getPurchaseUpdates: ${t.message}") }
    }

    override suspend fun refreshEntitlement() {
        if (registered) requestUpdates()
    }

    override suspend fun startOneTimePurchase(activity: Activity) {
        Log.i(TAG, "purchase($SKU_LIFETIME)")
        PurchasingService.purchase(SKU_LIFETIME)
    }

    override suspend fun startSubscriptionPurchase(activity: Activity) {
        Log.i(TAG, "purchase($SKU_SUBSCRIPTION_TERM)")
        PurchasingService.purchase(SKU_SUBSCRIPTION_TERM)
    }

    private fun isLifetime(r: Receipt) = r.productType == ProductType.ENTITLED && r.sku == SKU_LIFETIME
    private fun isSubscription(r: Receipt) = r.productType == ProductType.SUBSCRIPTION &&
        (r.sku == SKU_SUBSCRIPTION_PARENT || r.sku == SKU_SUBSCRIPTION_TERM || r.termSku == SKU_SUBSCRIPTION_TERM)

    override fun onUserDataResponse(response: UserDataResponse) {
        Log.i(TAG, "onUserDataResponse: ${response.requestStatus} marketplace=${response.userData?.marketplace}")
    }

    override fun onProductDataResponse(response: ProductDataResponse) {
        Log.i(TAG, "onProductDataResponse: ${response.requestStatus} products=${response.productData?.keys} unavailable=${response.unavailableSkus}")
    }

    override fun onPurchaseResponse(response: PurchaseResponse) {
        Log.i(TAG, "onPurchaseResponse: ${response.requestStatus}")
        when (response.requestStatus) {
            PurchaseResponse.RequestStatus.SUCCESSFUL -> {
                val r = response.receipt
                if (r != null && !r.isCanceled) {
                    if (isLifetime(r)) save(EntitlementState.LifetimePurchased)
                    else if (isSubscription(r)) save(EntitlementState.Subscribed(renewsOrExpiresAtEpochMs = 0L))
                    PurchasingService.notifyFulfillment(r.receiptId, FulfillmentResult.FULFILLED)
                }
            }
            PurchaseResponse.RequestStatus.ALREADY_PURCHASED -> requestUpdates()
            else -> Unit // FAILED (incl. user cancel), INVALID_SKU, NOT_SUPPORTED, PENDING
        }
    }

    override fun onPurchaseUpdatesResponse(response: PurchaseUpdatesResponse) {
        if (response.requestStatus != PurchaseUpdatesResponse.RequestStatus.SUCCESSFUL) {
            Log.w(TAG, "onPurchaseUpdatesResponse: ${response.requestStatus}")
            if (_entitlementState.value == EntitlementState.Unknown) _entitlementState.value = EntitlementState.NotEntitled
            return
        }
        for (r in response.receipts) {
            if (r.isCanceled) continue
            if (isLifetime(r)) pendingLifetime = true
            if (isSubscription(r)) pendingSubscription = true
        }
        if (response.hasMore()) {
            PurchasingService.getPurchaseUpdates(false)
            return
        }
        save(
            when {
                pendingLifetime -> EntitlementState.LifetimePurchased
                pendingSubscription -> EntitlementState.Subscribed(renewsOrExpiresAtEpochMs = 0L)
                else -> EntitlementState.NotEntitled
            }
        )
    }
}
