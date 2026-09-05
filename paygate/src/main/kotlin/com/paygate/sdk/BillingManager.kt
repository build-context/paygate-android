package com.paygate.sdk

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * Google Play Billing wrapper; mirrors iOS StoreKitManager responsibilities.
 */
class BillingManager private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutex = Mutex()

    @Volatile
    private var client: BillingClient? = null
    private var purchaseCompleter: CompletableDeferred<String?>? = null

    @Volatile
    var activeSubscriptionProductIds: Set<String> = emptySet()
        private set

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        val pending = purchaseCompleter
        if (pending != null) {
            purchaseCompleter = null
            when (billingResult.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    val purchase = purchases?.firstOrNull()
                    val productId = purchase?.products?.firstOrNull()
                    if (productId != null) {
                        acknowledgeIfNeeded(purchase)
                        scope.launch(Dispatchers.IO) { refreshEntitlements() }
                        pending.complete(productId)
                    } else {
                        pending.complete(null)
                    }
                }
                BillingClient.BillingResponseCode.USER_CANCELED -> pending.complete(null)
                else -> pending.complete(null)
            }
        } else {
            purchases?.forEach { acknowledgeIfNeeded(it) }
            scope.launch(Dispatchers.IO) { refreshEntitlements() }
        }
    }

    private fun acknowledgeIfNeeded(purchase: Purchase) {
        if (!purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            client?.acknowledgePurchase(params) { }
        }
    }

    fun start() {
        if (client != null) return
        val c = BillingClient.newBuilder(appContext)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases()
            .build()
        client = c
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    scope.launch(Dispatchers.IO) { refreshEntitlements() }
                }
            }

            override fun onBillingServiceDisconnected() {
            }
        })
    }

    suspend fun loadPurchasedProducts() {
        refreshEntitlements()
    }

    suspend fun syncPurchases() {
        refreshEntitlements()
    }

    private suspend fun refreshEntitlements() = mutex.withLock {
        val c = client ?: return@withLock
        val active = linkedSetOf<String>()
        suspendCancellableCoroutine { cont ->
            c.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            ) { _, list ->
                list.forEach { p ->
                    if (p.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        p.products.forEach { active.add(it) }
                    }
                }
                cont.resume(Unit)
            }
        }
        suspendCancellableCoroutine { cont ->
            c.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            ) { _, list ->
                list.forEach { p ->
                    if (p.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        p.products.forEach { active.add(it) }
                    }
                }
                cont.resume(Unit)
            }
        }
        activeSubscriptionProductIds = active.toSet()
    }

    /**
     * Picks the offer token to launch the billing flow with.
     *
     * `subscriptionOfferDetails` spans every base plan on the subscription id
     * and every offer within each, so taking the first entry charges whichever
     * cadence Play happened to list first — and can select a promotional offer
     * in place of the standing price. Given a base plan we narrow to it and
     * prefer its plain price, which is the entry with no offerId.
     *
     * Play applies an offer only if the user is actually eligible, so choosing
     * the base price here never withholds a trial the user has coming; it just
     * stops us naming a trial as the thing being bought.
     */
    internal fun selectOfferToken(details: ProductDetails, basePlanId: String?): String? {
        val offers = details.subscriptionOfferDetails.orEmpty()
        if (offers.isEmpty()) return null

        val forBasePlan = basePlanId?.let { bp -> offers.filter { it.basePlanId == bp } }
        if (basePlanId != null && forBasePlan.isNullOrEmpty()) {
            // Naming a base plan Play does not have means the console and Play
            // Console disagree. Falling back would quietly bill the wrong
            // cadence, which is the exact failure the base plan id prevents.
            android.util.Log.e(
                "Paygate",
                "Base plan '$basePlanId' not found on ${details.productId}; " +
                    "available: ${offers.map { it.basePlanId }.distinct()}"
            )
            return null
        }

        val candidates = forBasePlan ?: offers
        return (candidates.firstOrNull { it.offerId == null } ?: candidates.first()).offerToken
    }

    suspend fun purchase(activity: Activity, storeProductId: String, basePlanId: String? = null): String? {
        val c = client ?: return null
        val details = queryProductDetails(c, storeProductId) ?: run {
            android.util.Log.e("Paygate", "No ProductDetails for $storeProductId")
            throw PaygateException.ProductNotFound
        }
        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .apply {
                    if (details.productType == BillingClient.ProductType.SUBS) {
                        val token = selectOfferToken(details, basePlanId)
                            ?: throw PaygateException.ProductNotFound
                        setOfferToken(token)
                    }
                }
                .build()
        )
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        val deferred = CompletableDeferred<String?>()
        purchaseCompleter = deferred
        val result = c.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            purchaseCompleter = null
            return null
        }
        return deferred.await()
    }

    private suspend fun queryProductDetails(c: BillingClient, productId: String): ProductDetails? {
        for (type in listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP)) {
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(productId)
                            .setProductType(type)
                            .build()
                    )
                )
                .build()
            val found = suspendCancellableCoroutine { cont ->
                c.queryProductDetailsAsync(params) { billingResult, list ->
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && list.isNotEmpty()) {
                        cont.resume(list.first())
                    } else {
                        cont.resume(null)
                    }
                }
            }
            if (found != null) return found
        }
        return null
    }

    companion object {
        @Volatile
        private var instance: BillingManager? = null

        fun get(context: Context): BillingManager {
            return instance ?: synchronized(this) {
                instance ?: BillingManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
