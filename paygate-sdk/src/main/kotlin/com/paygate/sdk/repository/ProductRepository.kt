package com.paygate.sdk.repository

import com.paygate.sdk.ProductData
import com.paygate.sdk.parseProductData

internal class ProductRepository(
    baseURL: String,
    apiKey: String,
    appContext: android.content.Context
) : PaygateRepository(baseURL, apiKey, appContext) {

    fun getProduct(productId: String): ProductData {
        val json = getJson("/sdk/products/$productId")
        return parseProductData(json)
    }
}
