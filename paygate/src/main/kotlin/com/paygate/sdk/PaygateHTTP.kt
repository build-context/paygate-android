package com.paygate.sdk

import java.net.HttpURLConnection

internal object PaygateHTTP {
    fun bundleIdentifier(context: android.content.Context): String =
        context.packageName

    fun applyDefaultHeaders(conn: HttpURLConnection, apiKey: String, context: android.content.Context) {
        conn.setRequestProperty("X-API-Key", apiKey)
        conn.setRequestProperty("Paygate-Version", PAYGATE_API_VERSION)
        val bid = bundleIdentifier(context)
        if (bid.isNotEmpty()) {
            conn.setRequestProperty("Paygate-Bundle-Id", bid)
        }
        conn.setRequestProperty("Paygate-Platform", "play")
        conn.setRequestProperty("Paygate-Channel", Paygate.currentChannel(context).apiValue)
        // Sent, not applied locally, so the server can refuse it on production
        // and say so in its logs. A client that silently dropped it would leave
        // a developer staring at the wrong price with nothing to explain why.
        Paygate.storefrontOverride?.let {
            conn.setRequestProperty("Paygate-Storefront-Override", it)
        }
    }

    /**
     * Adds the reader's store country, when it is known.
     *
     * Separate from [applyDefaultHeaders] because reading it needs a connected
     * BillingClient and only the render calls need it.
     */
    fun applyStorefront(conn: HttpURLConnection, storefront: String?) {
        if (!storefront.isNullOrEmpty()) {
            conn.setRequestProperty("Paygate-Storefront", storefront)
        }
    }
}
