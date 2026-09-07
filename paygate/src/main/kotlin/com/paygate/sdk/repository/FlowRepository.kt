package com.paygate.sdk.repository

import com.paygate.sdk.FlowData
import com.paygate.sdk.parseFlowData

internal class FlowRepository(
    baseURL: String,
    apiKey: String,
    appContext: android.content.Context
) : PaygateRepository(baseURL, apiKey, appContext) {

    fun getFlow(flowId: String, storefront: String? = null): FlowData {
        val json = getJson("/sdk/flows/$flowId", storefront)
        return parseFlowData(json)
    }
}
