package com.paygate.sdk

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.GetBillingConfigParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
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
import kotlinx.coroutines.withTimeoutOrNull
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
                // The one null that means what it says: they saw the sheet and
                // closed it. Left silent on purpose — it is not a fault, and
                // the paywall correctly stays up behind it.
                BillingClient.BillingResponseCode.USER_CANCELED -> pending.complete(null)
                else -> {
                    // Everything else is a failure wearing a cancellation's
                    // clothes. Still completed as null so the caller unblocks
                    // and the paywall stays up to retry from, but no longer
                    // invisible: this is the only trace a purchase that died
                    // inside Play's own sheet ever leaves.
                    android.util.Log.e(
                        "Paygate",
                        "Purchase failed in the Play sheet: " +
                            "${describeBillingCode(billingResult.responseCode)} (${billingResult.responseCode})" +
                            (billingResult.debugMessage?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
                    )
                    pending.complete(null)
                }
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
            // The no-arg `enablePendingPurchases()` was deprecated in Billing 6.2
            // and **removed in 8.0.0**. Calling it is not a compile error here —
            // this module builds against 7.1.1, where it still exists — it is a
            // `NoSuchMethodError` that kills the app's main thread at
            // `Paygate.initialize`, and only in host apps that drag a newer
            // billing library onto the classpath. Any app also using
            // `in_app_purchase` does: its Android package requires 8.0.0, Gradle
            // resolves to the highest, and this SDK gets a BillingClient.Builder
            // that no longer has the method it was compiled against.
            //
            // The params form exists from 6.2 onward, so it compiles here and
            // works on 7 and 8 alike.
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
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

    /**
     * Buys [storeProductId], returning the purchased product id — or **null for
     * a user cancellation, and only that**.
     *
     * Every other outcome throws. That distinction is the whole point: a null
     * used to mean "cancelled, or billing was never started, or Play refused to
     * open the sheet", and the caller cannot tell those apart. It left a reader
     * tapping Buy and getting nothing at all — no sheet, no error, no log —
     * which is indistinguishable from a dead button.
     */
    suspend fun purchase(activity: Activity, storeProductId: String, basePlanId: String? = null): String? {
        val c = client ?: run {
            // `start()` assigns the client synchronously, so this means
            // initialize() never ran or failed — not a transient state worth
            // retrying, and never the user's doing.
            android.util.Log.e(
                "Paygate",
                "purchase($storeProductId) with no BillingClient — Paygate.initialize() did not complete."
            )
            throw PaygateException.BillingUnavailable(null, "Billing was never started")
        }
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
            // The sheet never opened, so there is nothing the user cancelled.
            // Returning null here was the single most confusing failure in this
            // SDK: the paywall sat there having visibly done nothing, with no
            // log and no result, on the one tap that matters.
            android.util.Log.e(
                "Paygate",
                "launchBillingFlow refused $storeProductId: ${describeBillingCode(result.responseCode)} " +
                    "(${result.responseCode})${result.debugMessage?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""}"
            )
            throw PaygateException.BillingUnavailable(result.responseCode, result.debugMessage)
        }
        return deferred.await()
    }

    /**
     * Play's response codes, named.
     *
     * The integer alone sends a reader to a documentation page mid-debug, and
     * the two that actually happen here — a build Play does not recognise, and
     * a product that is not live for this account — look identical as bare
     * numbers.
     */
    private fun describeBillingCode(code: Int): String = when (code) {
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
            "BILLING_UNAVAILABLE (Play does not recognise this build, or the account cannot pay here)"
        BillingClient.BillingResponseCode.DEVELOPER_ERROR ->
            "DEVELOPER_ERROR (usually a signature mismatch — a locally-signed build of an app on Play App Signing)"
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE ->
            "ITEM_UNAVAILABLE (product not live for this account, country or track)"
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> "SERVICE_DISCONNECTED"
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE"
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> "ITEM_ALREADY_OWNED"
        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> "FEATURE_NOT_SUPPORTED"
        BillingClient.BillingResponseCode.USER_CANCELED -> "USER_CANCELED"
        else -> "response code $code"
    }

    /**
     * How long to wait for Play to answer a product query.
     *
     * There is a timeout at all because `queryProductDetailsAsync` does not
     * always call its listener. A client that never finished connecting drops
     * the callback entirely, and `suspendCancellableCoroutine` then waits for
     * it forever — which is not a slow purchase, it is a Buy button that does
     * nothing for the rest of the process, with nothing in the log to say so.
     *
     * Eight seconds is far past a healthy round trip (tens of milliseconds) and
     * still short enough that a reader who tapped Buy gets an answer rather
     * than a dead screen.
     */
    private val queryTimeoutMs = 8_000L

    private suspend fun queryProductDetails(c: BillingClient, productId: String): ProductDetails? {
        // Play's own precondition, checked rather than assumed. `start()`
        // assigns the client synchronously and connects asynchronously, so a
        // client can be non-null and unusable — the state this whole timeout
        // exists to survive. Saying so is better than waiting to find out.
        if (!c.isReady) {
            android.util.Log.e(
                "Paygate",
                "BillingClient is not connected; cannot look up $productId. " +
                    "Play refused the connection at startup — on a debug or locally-signed " +
                    "build of an app distributed through Play App Signing, that is expected."
            )
            throw PaygateException.BillingUnavailable(null, "Billing service not connected")
        }
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
            val found = withTimeoutOrNull(queryTimeoutMs) {
                suspendCancellableCoroutine<ProductDetails?> { cont ->
                    c.queryProductDetailsAsync(params) { billingResult, list ->
                        // `isActive` because the timeout may already have moved
                        // on; resuming a cancelled continuation throws.
                        if (!cont.isActive) return@queryProductDetailsAsync
                        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && list.isNotEmpty()) {
                            cont.resume(list.first())
                        } else {
                            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                                android.util.Log.w(
                                    "Paygate",
                                    "queryProductDetails($productId, $type): " +
                                        "${describeBillingCode(billingResult.responseCode)} (${billingResult.responseCode})"
                                )
                            }
                            cont.resume(null)
                        }
                    }
                }
            } ?: run {
                // Play never answered. Distinct from "no such product", and the
                // failure that used to hang here forever.
                android.util.Log.e(
                    "Paygate",
                    "Play did not answer queryProductDetails($productId, $type) within ${queryTimeoutMs}ms."
                )
                throw PaygateException.BillingUnavailable(null, "Play did not answer the product lookup")
            }
            if (found != null) return found
        }
        return null
    }

    /**
     * The reader's Play store country, e.g. "CA" — null when Play has not
     * answered.
     *
     * Needs a connected BillingClient, so it returns null rather than waiting
     * when one is not up yet. **Callers must never block a gate launch on
     * this**: the server falls back to the template's key, and a paywall that
     * renders late is worse than one showing the fallback price.
     *
     * Not cached. A user can change their Play country mid-session, and a stale
     * value here prices the paywall for a country they have left.
     */
    suspend fun currentStorefront(): String? {
        val c = client ?: return null
        if (!c.isReady) return null
        return try {
            suspendCancellableCoroutine { cont ->
                c.getBillingConfigAsync(GetBillingConfigParams.newBuilder().build()) { result, config ->
                    val code =
                        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                            config?.countryCode?.takeIf { it.isNotBlank() }
                        } else {
                            null
                        }
                    if (cont.isActive) cont.resume(code)
                }
            }
        } catch (_: Exception) {
            // Never fail a launch over a price hint. Falls back to the
            // template's key, which is what renders today.
            null
        }
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
