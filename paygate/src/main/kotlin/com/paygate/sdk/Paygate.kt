package com.paygate.sdk

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import com.paygate.sdk.repository.FlowRepository
import com.paygate.sdk.repository.GateRepository
import com.paygate.sdk.repository.ProductRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Public entry point for the Paygate Android SDK (mirrors iOS `Paygate`).
 */
object Paygate {

    @JvmStatic
    val apiVersion: String = PAYGATE_API_VERSION

    private const val DEFAULT_BASE_URL = "https://api-crtw3ydz4q-uc.a.run.app"

    private lateinit var appContext: Context
    private var apiKey: String? = null
    private var baseURL: String = DEFAULT_BASE_URL

    private val flowCache = ConcurrentHashMap<String, FlowData>()
    private val gateCache = ConcurrentHashMap<String, GateFlowResponse>()

    private var flows: FlowRepository? = null
    private var gates: GateRepository? = null
    private var products: ProductRepository? = null

    /** Current distribution channel for gate `enabledChannels` checks. */
    @JvmStatic
    fun currentChannel(context: Context): DistributionChannel {
        val debug =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return if (debug) DistributionChannel.DEBUG else DistributionChannel.PRODUCTION
    }

    private fun channelApiValue(channel: DistributionChannel): String = when (channel) {
        DistributionChannel.PRODUCTION -> "production"
        DistributionChannel.TESTFLIGHT -> "testflight"
        DistributionChannel.DEBUG -> "debug"
    }

    /**
     * Initialize SDK, connect Play Billing, load entitlements, flush analytics outbox.
     */
    suspend fun initialize(context: Context, apiKey: String, baseURL: String? = null) {
        this.appContext = context.applicationContext
        this.apiKey = apiKey
        if (baseURL != null) {
            this.baseURL = baseURL.trimEnd('/')
        }
        flows = FlowRepository(this.baseURL, apiKey, appContext)
        gates = GateRepository(this.baseURL, apiKey, appContext)
        products = ProductRepository(this.baseURL, apiKey, appContext)
        BillingManager.get(appContext).start()
        withContext(Dispatchers.IO) {
            BillingManager.get(appContext).loadPurchasedProducts()
        }
        PresentationAnalytics.flushPendingOutbox(appContext, apiKey, this.baseURL)
    }

    suspend fun getActiveSubscriptionProductIds(): Set<String> {
        if (!::appContext.isInitialized) throw PaygateException.NotInitialized
        return BillingManager.get(appContext).activeSubscriptionProductIds.toSet()
    }

    /**
     * @param appearance Color scheme to pin the flow to. Flows carry no
     *   appearance of their own — that setting lives on the gate — so this
     *   defaults to [PaygateAppearance.SYSTEM], which follows the device.
     */
    suspend fun launchFlow(
        activity: Activity,
        flowId: String,
        bounces: Boolean = false,
        presentationStyle: PaygatePresentationStyle = PaygatePresentationStyle.SHEET,
        appearance: PaygateAppearance = PaygateAppearance.SYSTEM
    ): PaygateLaunchResult {
        val key = apiKey ?: throw PaygateException.NotInitialized
        val fr = flows ?: throw PaygateException.NotInitialized

        val flowData = withContext(Dispatchers.IO) {
            flowCache[flowId] ?: fr.getFlow(flowId).also { flowCache[flowId] = it }
        }

        val active = BillingManager.get(appContext).activeSubscriptionProductIds
        for (storeId in flowData.productIdMap.values) {
            if (active.contains(storeId)) {
                return PaygateLaunchResult(
                    PaygateLaunchStatus.ALREADY_SUBSCRIBED,
                    productId = storeId
                )
            }
        }

        val raw = presentPaywall(
            activity = activity,
            flowData = flowData,
            apiKey = key,
            baseURL = baseURL,
            bounces = bounces,
            gateId = null,
            purchaseRequired = false,
            disableWebViewCache = false,
            appearance = appearance,
            presentationStyle = presentationStyle
        )
        return mapFlowLaunchResult(raw)
    }

    /**
     * @param appearance Overrides the appearance configured on the gate. Pass
     *   this when your app has its own light/dark setting: a WebView follows
     *   the system night mode, not your app, so leaving it to the gate means
     *   the paywall can disagree with the screen behind it. `null` (the
     *   default) uses whatever the gate is set to.
     */
    suspend fun launchGate(
        activity: Activity,
        gateId: String,
        bounces: Boolean = false,
        presentationStyle: PaygatePresentationStyle = PaygatePresentationStyle.SHEET,
        appearance: PaygateAppearance? = null
    ): PaygateLaunchResult {
        val key = apiKey ?: throw PaygateException.NotInitialized
        val gr = gates ?: throw PaygateException.NotInitialized

        val response: GateFlowResponse = try {
            withContext(Dispatchers.IO) {
                gateCache[gateId] ?: gr.getGate(gateId).also { fetched ->
                    if (fetched.launchCache == "cache_on_first_launch") {
                        gateCache[gateId] = fetched
                    }
                }
            }
        } catch (e: PaygateException.PresentationLimitExceeded) {
            val data = mutableMapOf<String, Any>()
            e.used?.let { data["used"] = it }
            e.limit?.let { data["limit"] = it }
            return PaygateLaunchResult(
                PaygateLaunchStatus.PLAN_LIMIT_REACHED,
                data = data.ifEmpty { null }
            )
        }

        if (response.enabledChannels.isNotEmpty()) {
            val current = channelApiValue(currentChannel(activity))
            if (!response.enabledChannels.contains(current)) {
                return PaygateLaunchResult(PaygateLaunchStatus.CHANNEL_NOT_ENABLED)
            }
        }

        val flowData = response.flowData
        val active = BillingManager.get(appContext).activeSubscriptionProductIds
        for (storeId in flowData.productIdMap.values) {
            if (active.contains(storeId)) {
                return PaygateLaunchResult(
                    PaygateLaunchStatus.ALREADY_SUBSCRIBED,
                    productId = storeId
                )
            }
        }

        val raw = presentPaywall(
            activity = activity,
            flowData = flowData,
            apiKey = key,
            baseURL = baseURL,
            bounces = bounces,
            gateId = gateId,
            purchaseRequired = response.requirePurchase,
            disableWebViewCache = response.launchCache == "refresh_on_launch",
            // The caller wins. Only the app knows whether it has a theme
            // setting of its own; the gate's value is a default for those
            // that do not.
            appearance = appearance ?: response.appearance,
            presentationStyle = presentationStyle
        )
        return mapGateLaunchResult(raw)
    }

    suspend fun purchase(activity: Activity, productId: String): String? {
        val pr = products ?: throw PaygateException.NotInitialized
        val product = withContext(Dispatchers.IO) { pr.getProduct(productId) }
        val playId = product.playStoreId?.takeIf { it.isNotBlank() }
            ?: throw PaygateException.ProductNotFound
        val basePlanId = product.playBasePlanId?.takeIf { it.isNotBlank() }
        return BillingManager.get(appContext).purchase(activity, playId, basePlanId)
    }

    private suspend fun presentPaywall(
        activity: Activity,
        flowData: FlowData,
        apiKey: String,
        baseURL: String,
        bounces: Boolean,
        gateId: String?,
        purchaseRequired: Boolean,
        disableWebViewCache: Boolean,
        appearance: PaygateAppearance,
        @Suppress("UNUSED_PARAMETER") presentationStyle: PaygatePresentationStyle
    ): PaygateResult = withContext(Dispatchers.Main) {
        coroutineScope {
            val deferred = async(start = CoroutineStart.UNDISPATCHED) {
                PaygatePresentationSession.await()
            }
            val intent = PaygateActivity.createIntent(
                activity = activity,
                flowData = flowData,
                apiKey = apiKey,
                baseURL = baseURL,
                bounces = bounces,
                gateId = gateId,
                purchaseRequired = purchaseRequired,
                disableWebViewCache = disableWebViewCache,
                appearance = appearance
            )
            // TODO: sheet vs fullScreen — Android uses single Activity theme; host may wrap in BottomSheet if needed.
            activity.startActivity(intent)
            deferred.await()
        }
    }

    private fun mapFlowLaunchResult(raw: PaygateResult): PaygateLaunchResult = when (raw) {
        is PaygateResult.Dismissed ->
            PaygateLaunchResult(PaygateLaunchStatus.DISMISSED, data = raw.data)
        is PaygateResult.Skipped ->
            PaygateLaunchResult(PaygateLaunchStatus.DISMISSED, data = raw.data)
        is PaygateResult.Purchased ->
            PaygateLaunchResult(
                PaygateLaunchStatus.PURCHASED,
                productId = raw.productId,
                data = raw.data
            )
        is PaygateResult.Error -> throw raw.error
    }

    private fun mapGateLaunchResult(raw: PaygateResult): PaygateLaunchResult = when (raw) {
        is PaygateResult.Dismissed ->
            PaygateLaunchResult(PaygateLaunchStatus.DISMISSED, data = raw.data)
        is PaygateResult.Skipped ->
            PaygateLaunchResult(PaygateLaunchStatus.SKIPPED, data = raw.data)
        is PaygateResult.Purchased ->
            PaygateLaunchResult(
                PaygateLaunchStatus.PURCHASED,
                productId = raw.productId,
                data = raw.data
            )
        is PaygateResult.Error -> throw raw.error
    }
}
