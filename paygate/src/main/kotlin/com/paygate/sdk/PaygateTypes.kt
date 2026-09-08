package com.paygate.sdk

/** Date-based API version; must match backend supported `Paygate-Version`. */
const val PAYGATE_API_VERSION = "2026-09-07"

/**
 * Which kind of build this is, as far as a gate's per-channel settings care.
 *
 * Three, and deliberately not one per store. [TESTING] was called `testflight`
 * until this SDK could take money and the name stopped being true — Play has no
 * TestFlight, so an Android build could never match that entry and the setting
 * was unreachable from half the clients. The server no longer sends or accepts
 * the old name.
 */
enum class DistributionChannel(val apiValue: String) {
    /** A shipped build, installed from Play (or the App Store on iOS). */
    PRODUCTION("production"),

    /**
     * A build under test.
     *
     * On iOS that is TestFlight, which the OS makes plain. On Android it is
     * whatever [Paygate.channelOverride] says, or failing that a release build
     * that did not come from Play — see [Paygate.currentChannel] for why that
     * is the best inference available.
     */
    TESTING("testing"),

    /** A debuggable build: `FLAG_DEBUGGABLE` here, `#if DEBUG` on iOS. */
    DEBUG("debug");

    companion object {
        /**
         * Parses a server value, or null for one this build does not know.
         *
         * Null rather than a default, because the callers want opposite things
         * from an unrecognized channel and neither wants a guess.
         */
        @JvmStatic
        fun fromServerValue(raw: String?): DistributionChannel? =
            entries.firstOrNull { it.apiValue == raw?.lowercase() }
    }
}

/**
 * Which color scheme a flow renders in.
 *
 * A WebView's `prefers-color-scheme` follows the system night mode, not your
 * app — so an app with its own light/dark setting shows a paywall that can
 * disagree with the screen behind it. Pinning this fixes that.
 *
 * Set on the gate in the Paygate console, and overridable per launch: an
 * appearance passed to [Paygate.launchGate] wins, because only the app knows
 * whether it has a theme preference of its own.
 */
enum class PaygateAppearance {
    /** Follow the device's night mode. The default. */
    SYSTEM,

    /** Render light regardless of the device setting. */
    LIGHT,

    /** Render dark regardless of the device setting. */
    DARK;

    companion object {
        /**
         * Parses a server value, falling back to [SYSTEM] for anything
         * unrecognized — an API that grows a fourth value must not break a
         * paywall built against three.
         */
        @JvmStatic
        fun fromServerValue(raw: String?): PaygateAppearance =
            when (raw?.lowercase()) {
                "light" -> LIGHT
                "dark" -> DARK
                else -> SYSTEM
            }
    }
}

/** How the SDK caches a gate's content on a given channel. */
enum class PaygateLaunchCache(val apiValue: String) {
    /** Fetch once, then reuse for the rest of the process. The default. */
    CACHE_ON_FIRST_LAUNCH("cache_on_first_launch"),

    /** Re-fetch on every launch, so console edits appear without a reinstall. */
    REFRESH_ON_LAUNCH("refresh_on_launch");

    companion object {
        /**
         * Parses a server value, falling back to [CACHE_ON_FIRST_LAUNCH] for
         * anything unrecognized — an API that grows a third value must not
         * break a paywall built against two.
         */
        @JvmStatic
        fun fromServerValue(raw: String?): PaygateLaunchCache =
            when (raw?.lowercase()) {
                "refresh_on_launch" -> REFRESH_ON_LAUNCH
                else -> CACHE_ON_FIRST_LAUNCH
            }
    }
}

/**
 * One distribution channel's settings on a gate: whether the gate shows there,
 * and how it caches there.
 *
 * Caching is per channel because that is where it varies. A debug build wants
 * [PaygateLaunchCache.REFRESH_ON_LAUNCH] so flow edits appear immediately;
 * production wants [PaygateLaunchCache.CACHE_ON_FIRST_LAUNCH] so the paywall
 * does not wait on the network.
 */
data class GateChannel(
    val channel: String,
    val enabled: Boolean,
    val launchCache: PaygateLaunchCache
)

data class GateData(
    /** One entry per channel the server knows about. */
    val channels: List<GateChannel>,
    val requirePurchase: Boolean,
    val appearance: PaygateAppearance = PaygateAppearance.SYSTEM
) {
    /** This build's channel entry, or null if the gate says nothing about it. */
    fun channelFor(channel: DistributionChannel): GateChannel? =
        channels.firstOrNull { it.channel == channel.apiValue }

    /**
     * Whether the gate shows on [channel].
     *
     * A gate that lists no channels at all shows everywhere. That is what an
     * empty `enabledChannels` meant before per-channel config, and it is the
     * only safe reading of a response this build does not understand: the
     * alternative is a paywall that silently never appears.
     */
    fun isEnabledOn(channel: DistributionChannel): Boolean {
        if (channels.isEmpty()) return true
        return channelFor(channel)?.enabled ?: true
    }

    /** How to cache on [channel]. */
    fun launchCacheOn(channel: DistributionChannel): PaygateLaunchCache =
        channelFor(channel)?.launchCache ?: PaygateLaunchCache.CACHE_ON_FIRST_LAUNCH
}

data class FlowPage(
    val id: String,
    val htmlContent: String
)

data class ProductData(
    val id: String,
    val name: String,
    val appStoreId: String?,
    val playStoreId: String?,
    /**
     * Which base plan on [playStoreId] this product means.
     *
     * A Play subscription id can carry several base plans, each with its own
     * billing period and its own offers, so the id alone does not identify a
     * cadence or a price. When this is set the purchase flow uses it to pick
     * the offer token; when it is null the flow falls back to Play's first
     * offer, whatever that happens to be.
     */
    val playBasePlanId: String? = null
)

data class FlowData(
    val id: String,
    val name: String,
    val pages: List<FlowPage>,
    val bridgeScript: String,
    val productIds: List<String>,
    val products: List<ProductData>?
) {
    /** Maps Paygate product IDs to Google Play product IDs. */
    val productIdMap: Map<String, String>
        get() = buildMap {
            products?.forEach { p ->
                val sid = p.playStoreId?.takeIf { it.isNotBlank() }
                if (sid != null) this[p.id] = sid
            }
        }

    /** Maps Paygate product IDs to the Play base plan the product refers to. */
    val basePlanIdMap: Map<String, String>
        get() = buildMap {
            products?.forEach { p ->
                val bp = p.playBasePlanId?.takeIf { it.isNotBlank() }
                if (bp != null) this[p.id] = bp
            }
        }
}

data class GateFlowResponse(
    val gateId: String,
    val selectedFlowId: String,
    val channels: List<GateChannel>,
    val requirePurchase: Boolean,
    val appearance: PaygateAppearance,
    val id: String,
    val name: String,
    val pages: List<FlowPage>,
    val bridgeScript: String,
    val productIds: List<String>,
    val products: List<ProductData>?
) {
    val gate: GateData
        get() = GateData(channels, requirePurchase, appearance)

    val flowData: FlowData
        get() = FlowData(id, name, pages, bridgeScript, productIds, products)
}

enum class PaygateLaunchStatus {
    PURCHASED,
    ALREADY_SUBSCRIBED,
    DISMISSED,
    SKIPPED,
    CHANNEL_NOT_ENABLED,
    PLAN_LIMIT_REACHED
}

data class PaygateLaunchResult(
    val status: PaygateLaunchStatus,
    val productId: String? = null,
    val data: Map<String, Any>? = null
)

enum class PaygatePresentationStyle {
    FULL_SCREEN,
    SHEET
}

internal sealed class PaygateResult {
    data class Dismissed(val data: Map<String, Any>?) : PaygateResult()
    data class Skipped(val data: Map<String, Any>?) : PaygateResult()
    data class Purchased(val productId: String, val data: Map<String, Any>?) : PaygateResult()
    data class Error(val error: Throwable) : PaygateResult()
}

sealed class PaygateException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    object NotInitialized : PaygateException("Paygate SDK not initialized. Call Paygate.initialize() first.")
    object InvalidUrl : PaygateException("Invalid API URL.")
    object NoData : PaygateException("No data received from server.")
    data class ServerError(val detail: String?) : PaygateException(
        detail?.takeIf { it.isNotBlank() }?.let { "Server returned an error: $it" } ?: "Server returned an error."
    )
    object NoActivity : PaygateException("No Activity available to present from.")
    object ProductNotFound : PaygateException("Product not found on Google Play.")
    data class PresentationLimitExceeded(val used: Int?, val limit: Int?) : PaygateException(
        buildString {
            append("Presentation limit reached for this billing period.")
            if (used != null && limit != null) append(" Used $used of $limit.")
        }
    )
}
