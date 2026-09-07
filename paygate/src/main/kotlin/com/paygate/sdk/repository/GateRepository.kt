package com.paygate.sdk.repository

import com.paygate.sdk.GateFlowResponse
import com.paygate.sdk.parseGateFlowResponse

internal class GateRepository(
    baseURL: String,
    apiKey: String,
    appContext: android.content.Context
) : PaygateRepository(baseURL, apiKey, appContext) {

    fun getGate(gateId: String, storefront: String? = null): GateFlowResponse {
        val json = getJson("/sdk/gates/$gateId", storefront)
        return parseGateFlowResponse(json)
    }
}
