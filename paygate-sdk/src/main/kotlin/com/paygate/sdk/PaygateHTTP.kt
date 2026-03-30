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
    }
}
