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
        playStoreId = o.optStringOrNull("playStoreId"),
        playBasePlanId = o.optStringOrNull("playBasePlanId")
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
                // Round-trips through the launch cache; without it a cached
                // flow would fall back to Play's first offer on purchase.
                pr.playBasePlanId?.let { put("playBasePlanId", it) }
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
    val channels = mutableListOf<GateChannel>()
    o.optJSONArray("channels")?.let { arr ->
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            channels.add(
                GateChannel(
                    channel = c.getString("channel"),
                    // Absent means enabled. A gate that hides itself is the
                    // costlier reading of a field the server did not send.
                    enabled = c.optBoolean("enabled", true),
                    launchCache = PaygateLaunchCache.fromServerValue(c.optString("launchCache"))
                )
            )
        }
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
    val appearance = PaygateAppearance.fromServerValue(o.optString("appearance", "system"))
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
        channels = channels,
        requirePurchase = requirePurchase,
        appearance = appearance,
        id = o.getString("id"),
        name = o.optString("name", ""),
        pages = pages,
        bridgeScript = o.optString("bridgeScript", ""),
        productIds = productIds,
        products = products
    )
}
