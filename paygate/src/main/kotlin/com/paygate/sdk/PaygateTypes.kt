package com.paygate.sdk

/** Date-based API version; must match backend supported `Paygate-Version`. */
const val PAYGATE_API_VERSION = "2025-03-16"

enum class DistributionChannel {
    PRODUCTION,
    TESTFLIGHT,
    DEBUG
}

data class GateData(
    val enabledChannels: List<String>,
    val requirePurchase: Boolean,
    val launchCache: String
)

data class FlowPage(
    val id: String,
    val htmlContent: String
)

data class ProductData(
    val id: String,
    val name: String,
    val appStoreId: String?,
    val playStoreId: String?
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
}

data class GateFlowResponse(
    val gateId: String,
    val selectedFlowId: String,
    val enabledChannels: List<String>,
    val requirePurchase: Boolean,
    val launchCache: String,
    val id: String,
    val name: String,
    val pages: List<FlowPage>,
    val bridgeScript: String,
    val productIds: List<String>,
    val products: List<ProductData>?
) {
    val gate: GateData
        get() = GateData(enabledChannels, requirePurchase, launchCache)

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
