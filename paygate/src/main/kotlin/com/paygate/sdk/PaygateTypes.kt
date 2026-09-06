package com.paygate.sdk

/** Date-based API version; must match backend supported `Paygate-Version`. */
const val PAYGATE_API_VERSION = "2025-03-16"

enum class DistributionChannel {
    PRODUCTION,
    TESTFLIGHT,
    DEBUG
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

data class GateData(
    val enabledChannels: List<String>,
    val requirePurchase: Boolean,
    val launchCache: String,
    val appearance: PaygateAppearance = PaygateAppearance.SYSTEM
)

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
    val enabledChannels: List<String>,
    val requirePurchase: Boolean,
    val launchCache: String,
    val appearance: PaygateAppearance,
    val id: String,
    val name: String,
    val pages: List<FlowPage>,
    val bridgeScript: String,
    val productIds: List<String>,
    val products: List<ProductData>?
) {
    val gate: GateData
        get() = GateData(enabledChannels, requirePurchase, launchCache, appearance)

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
