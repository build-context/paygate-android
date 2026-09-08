package com.paygate.sdk

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
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

    /**
     * The Cloud Run hostname, not `api.usepaygate.com`.
     *
     * The custom domain's DNS is in place (it CNAMEs to `ghs.googlehosted.com`)
     * but the mapping is not serving yet — TLS does not complete, so every
     * request fails before it reaches the API. This is compiled into shipped
     * apps and cannot be fixed remotely, so it stays on the hostname that
     * actually answers until the mapping is live.
     *
     * Switch back once `curl https://api.usepaygate.com/health` returns 200.
     * Cloud Run keeps serving this hostname indefinitely, so already-shipped
     * builds continue to work either way.
     */
    private const val DEFAULT_BASE_URL = "https://api-crtw3ydz4q-uc.a.run.app"

    private lateinit var appContext: Context
    private var apiKey: String? = null
    private var baseURL: String = DEFAULT_BASE_URL

    private val flowCache = ConcurrentHashMap<String, FlowData>()
    private val gateCache = ConcurrentHashMap<String, GateFlowResponse>()

    private var flows: FlowRepository? = null
    private var gates: GateRepository? = null
    private var products: ProductRepository? = null

    /**
     * Force a store country for previewing prices, e.g. "CA".
     *
     * **Testing only, and it stops working on the `production` channel.** The
     * server refuses an override on production and logs that it did — a preview
     * switch left on in a shipped build would show every reader a price nobody
     * is charged, which is the rejection storefront pricing exists to prevent
     * rather than cause.
     *
     * Set it before launching a gate; leave it null to use the real store
     * country.
     */
    @JvmStatic
    var storefrontOverride: String? = null

    /**
     * The reader's Play store country, e.g. "CA" — null until Play answers.
     *
     * Read fresh on every launch; see [BillingManager.currentStorefront].
     */
    @JvmStatic
    suspend fun currentStorefront(): String? =
        if (::appContext.isInitialized) BillingManager.get(appContext).currentStorefront() else null

    /**
     * Cache key for a fetched flow or gate.
     *
     * The storefront is part of the key because the server bakes resolved
     * prices into the HTML. Keyed by id alone, a gate set to
     * `cache_on_first_launch` would serve whatever country happened to open it
     * first to everyone afterwards — the same wrong-price bug, with a harder
     * repro.
     */
    private fun cacheKey(id: String, storefront: String?): String =
        // The platform is constant within a build, so it adds nothing here.
        "$id|${storefront ?: "-"}"

    /**
     * Force the distribution channel, whatever this build actually is.
     *
     * **This is the only reliable way to mark an Android build as `testing`,**
     * and the reason it exists. Play does not tell an installed app which track
     * served it: internal testing, closed, open and production all report the
     * same installer, and there is no track API. iOS has no such problem — a
     * TestFlight install carries a `sandboxReceipt` — so the asymmetry is
     * Google's, not this SDK's.
     *
     * An app therefore has to say so from something it knows at build time: a
     * build flavor, a `BuildConfig` field, a `--dart-define`. Set it before
     * [launchGate].
     *
     * Unlike [storefrontOverride] this is **not** refused in production. It
     * selects among your own gate settings; it cannot reprice anything, and it
     * cannot reveal a paywall a gate has switched off.
     */
    @JvmStatic
    var channelOverride: DistributionChannel? = null

    /**
     * Current distribution channel, for the gate's per-channel settings.
     *
     * In order:
     *
     * 1. [channelOverride], because an app that knows what it shipped is a
     *    better authority than anything inferred here.
     * 2. `FLAG_DEBUGGABLE` → [DistributionChannel.DEBUG].
     * 3. A release build that **did not come from Play** →
     *    [DistributionChannel.TESTING]. Sideloaded, `adb install`, a locally
     *    built AAB or APK: whatever it is, it is not a member of the public who
     *    installed your app from the store, and treating it as production is
     *    how a developer ends up staring at a cached paywall wondering why
     *    their console edit did not take.
     * 4. Otherwise [DistributionChannel.PRODUCTION].
     *
     * **Step 3 cannot see a Play testing track**, and no API can — an internal
     * testing install reports `com.android.vending` exactly like a production
     * one. That case needs [channelOverride]; this step only rules out the
     * builds Play never touched.
     *
     * Fails toward `PRODUCTION`: if the installer cannot be read at all, the
     * safe reading is a shipped app, because that is the one where a wrong
     * answer costs a user a network round trip in front of a purchase.
     */
    @JvmStatic
    fun currentChannel(context: Context): DistributionChannel {
        channelOverride?.let { return it }

        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) return DistributionChannel.DEBUG

        return if (installedFromPlay(context)) {
            DistributionChannel.PRODUCTION
        } else {
            DistributionChannel.TESTING
        }
    }

    /** Play's own package name, the installer on any store-delivered build. */
    private const val PLAY_STORE_PACKAGE = "com.android.vending"

    /**
     * Whether Play delivered this install.
     *
     * `getInstallSourceInfo` replaced `getInstallerPackageName`, which is
     * deprecated from API 30 and returns null on some newer devices rather than
     * the answer it used to. Both are read, newest first.
     *
     * Any throw — `NameNotFoundException`, an OEM that restricts this, a
     * SecurityException on a stricter profile — answers **true**. An install
     * whose source cannot be determined is treated as a store install, which
     * keeps the failure on the cautious side of the choice above.
     */
    private fun installedFromPlay(context: Context): Boolean {
        val pm = context.packageManager
        val name = context.packageName
        return try {
            val installer =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    pm.getInstallSourceInfo(name).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstallerPackageName(name)
                }
            // Null means nothing recorded an installer — a sideload or an adb
            // push. That is exactly the case step 3 is for, so it is not a
            // read failure and must not take the catch's benefit of the doubt.
            installer == PLAY_STORE_PACKAGE
        } catch (_: Throwable) {
            true
        }
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

        // Never block the launch on Play — see BillingManager.currentStorefront.
        val storefront = currentStorefront()
        val flowKey = cacheKey(flowId, storefront)

        val flowData = withContext(Dispatchers.IO) {
            flowCache[flowKey] ?: fr.getFlow(flowId, storefront).also { flowCache[flowKey] = it }
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
            storefront = storefront,
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

        val channel = currentChannel(activity)
        // Never block the launch on Play — see BillingManager.currentStorefront.
        val storefront = currentStorefront()
        val gateKey = cacheKey(gateId, storefront)

        val response: GateFlowResponse = try {
            withContext(Dispatchers.IO) {
                gateCache[gateKey] ?: gr.getGate(gateId, storefront).also { fetched ->
                    // Caching is decided by this build's channel, so a debug
                    // build set to refresh re-fetches while the shipped app
                    // still caches.
                    if (fetched.gate.launchCacheOn(channel) == PaygateLaunchCache.CACHE_ON_FIRST_LAUNCH) {
                        gateCache[gateKey] = fetched
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

        if (!response.gate.isEnabledOn(channel)) {
            return PaygateLaunchResult(PaygateLaunchStatus.CHANNEL_NOT_ENABLED)
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
            disableWebViewCache = response.gate.launchCacheOn(channel) == PaygateLaunchCache.REFRESH_ON_LAUNCH,
            // The caller wins. Only the app knows whether it has a theme
            // setting of its own; the gate's value is a default for those
            // that do not.
            appearance = appearance ?: response.appearance,
            storefront = storefront,
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
        storefront: String?,
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
                appearance = appearance,
                storefront = storefront
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
