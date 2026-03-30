package com.paygate.sdk

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

internal fun parseProductData(o: JSONObject): ProductData =
    ProductData(
        id = o.getString("id"),
        name = o.optString("name", ""),
        appStoreId = o.optStringOrNull("appStoreId"),
        playStoreId = o.optStringOrNull("playStoreId")
    )

internal fun parseFlowPage(o: JSONObject): FlowPage =
    FlowPage(
        id = o.getString("id"),
        htmlContent = o.optString("htmlContent", "")
    )

internal fun FlowData.toJsonObject(): JSONObject {
    val pagesArr = JSONArray()
    pages.forEach { p ->
        pagesArr.put(
            JSONObject().apply {
                put("id", p.id)
                put("htmlContent", p.htmlContent)
            }
        )
    }
    val idsArr = JSONArray()
    productIds.forEach { idsArr.put(it) }
    val productsArr = JSONArray()
    products?.forEach { pr ->
        productsArr.put(
            JSONObject().apply {
                put("id", pr.id)
                put("name", pr.name)
                pr.appStoreId?.let { put("appStoreId", it) }
                pr.playStoreId?.let { put("playStoreId", it) }
            }
        )
    }
    return JSONObject().apply {
        put("id", id)
        put("name", name)
        put("pages", pagesArr)
        put("bridgeScript", bridgeScript)
        put("productIds", idsArr)
        if (products != null) put("products", productsArr)
    }
}

internal fun parseFlowData(o: JSONObject): FlowData {
    val pages = mutableListOf<FlowPage>()
    o.optJSONArray("pages")?.let { arr ->
        for (i in 0 until arr.length()) {
            pages.add(parseFlowPage(arr.getJSONObject(i)))
        }
    }
    val productIds = mutableListOf<String>()
    o.optJSONArray("productIds")?.let { arr ->
        for (i in 0 until arr.length()) {
            productIds.add(arr.getString(i))
        }
    }
    val products = o.optJSONArray("products")?.let { arr ->
        List(arr.length()) { parseProductData(arr.getJSONObject(it)) }
    }
    return FlowData(
        id = o.getString("id"),
        name = o.optString("name", ""),
        pages = pages,
        bridgeScript = o.optString("bridgeScript", ""),
        productIds = productIds,
        products = products
    )
}

internal fun parseGateFlowResponse(o: JSONObject): GateFlowResponse {
    val enabledChannels = mutableListOf<String>()
    o.optJSONArray("enabledChannels")?.let { arr ->
        for (i in 0 until arr.length()) enabledChannels.add(arr.getString(i))
    }
    val requirePurchase = when {
        o.has("requirePurchase") && !o.isNull("requirePurchase") -> {
            when (val v = o.get("requirePurchase")) {
                is Boolean -> v
                is String -> v.lowercase() == "true"
                else -> false
            }
        }
        else -> false
    }
    val launchCache = o.optString("launchCache", "cache_on_first_launch")
    val pages = mutableListOf<FlowPage>()
    o.optJSONArray("pages")?.let { arr ->
        for (i in 0 until arr.length()) pages.add(parseFlowPage(arr.getJSONObject(i)))
    }
    val productIds = mutableListOf<String>()
    o.optJSONArray("productIds")?.let { arr ->
        for (i in 0 until arr.length()) productIds.add(arr.getString(i))
    }
    val products = o.optJSONArray("products")?.let { arr ->
        List(arr.length()) { parseProductData(arr.getJSONObject(it)) }
    }
    return GateFlowResponse(
        gateId = o.getString("gateId"),
        selectedFlowId = o.getString("selectedFlowId"),
        enabledChannels = enabledChannels,
        requirePurchase = requirePurchase,
        launchCache = launchCache,
        id = o.getString("id"),
        name = o.optString("name", ""),
        pages = pages,
        bridgeScript = o.optString("bridgeScript", ""),
        productIds = productIds,
        products = products
    )
}
