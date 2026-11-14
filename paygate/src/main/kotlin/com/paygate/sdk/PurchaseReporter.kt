package com.paygate.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Tells Paygate which gate and paywall a purchase came from.
 *
 * Play's notification says what a purchase was worth; only the app knows which
 * paywall sold it, and only for the moment the purchase returns. This report is
 * that half (`POST /sdk/purchases`). Without it every purchase reaches the
 * revenue ledger as unattributed and a gate's price test cannot be read.
 *
 * It carries no price — money only ever comes from Play — and it is written to
 * disk first, so a killed or offline app still reports it on a later launch.
 * The server is idempotent per purchase token, so a resend is harmless.
 */
internal data class PendingPurchaseReport(
    val id: String,
    val purchaseToken: String,
    val orderId: String?,
    val gateId: String,
    val flowId: String,
    val storeProductId: String,
    val appUserId: String?,
    val clientBatchId: String?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("provider", "google")
        put("purchaseToken", purchaseToken)
        orderId?.let { put("orderId", it) }
        put("gateId", gateId)
        put("flowId", flowId)
        put("storeProductId", storeProductId)
        appUserId?.let { put("appUserId", it) }
        clientBatchId?.let { put("clientBatchId", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = PendingPurchaseReport(
            id = o.getString("id"),
            purchaseToken = o.getString("purchaseToken"),
            orderId = o.optString("orderId").takeIf { it.isNotEmpty() },
            gateId = o.getString("gateId"),
            flowId = o.getString("flowId"),
            storeProductId = o.getString("storeProductId"),
            appUserId = o.optString("appUserId").takeIf { it.isNotEmpty() },
            clientBatchId = o.optString("clientBatchId").takeIf { it.isNotEmpty() },
        )
    }
}

internal object PurchaseReporter {
    private const val SUBPATH = "Paygate/purchases"

    /** Queue a report and try to send it now. */
    fun report(context: android.content.Context, report: PendingPurchaseReport, apiKey: String, baseURL: String) {
        save(context, report)
        CoroutineScope(Dispatchers.IO).launch {
            if (submit(context, report, apiKey, baseURL)) delete(context, report.id)
        }
    }

    /** Send reports left from earlier launches. Called from `Paygate.initialize`. */
    fun flushPending(context: android.content.Context, apiKey: String, baseURL: String) {
        CoroutineScope(Dispatchers.IO).launch {
            for (report in loadAll(context)) {
                if (submit(context, report, apiKey, baseURL)) delete(context, report.id)
            }
        }
    }

    /**
     * True when the report is done with: accepted, or refused in a way a retry
     * cannot fix (a 4xx other than 429). False keeps it for the next launch.
     */
    fun submit(context: android.content.Context, report: PendingPurchaseReport, apiKey: String, baseURL: String): Boolean {
        return try {
            val conn = URL("${baseURL.trimEnd('/')}/sdk/purchases").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            PaygateHTTP.applyDefaultHeaders(conn, apiKey, context)
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(report.toJson().toString()) }
            when (val code = conn.responseCode) {
                in 200..299 -> true
                429 -> false
                in 400..499 -> {
                    android.util.Log.w("Paygate", "Purchase report refused (HTTP $code); not retrying")
                    true
                }
                else -> {
                    android.util.Log.w("Paygate", "Purchase report: HTTP $code; will retry")
                    false
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("Paygate", "Purchase report failed; will retry", e)
            false
        }
    }

    private fun dir(context: android.content.Context): File? {
        val base = context.filesDir ?: return null
        return File(base, SUBPATH).apply { if (!exists()) mkdirs() }
    }

    private fun save(context: android.content.Context, report: PendingPurchaseReport) {
        try {
            File(dir(context) ?: return, "${report.id}.json").writeText(report.toJson().toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("Paygate", "Purchase report save failed", e)
        }
    }

    private fun loadAll(context: android.content.Context): List<PendingPurchaseReport> =
        dir(context)?.listFiles { f -> f.extension == "json" }?.mapNotNull {
            try {
                PendingPurchaseReport.fromJson(JSONObject(it.readText(Charsets.UTF_8)))
            } catch (_: Exception) {
                null
            }
        } ?: emptyList()

    private fun delete(context: android.content.Context, id: String) {
        dir(context)?.let { File(it, "$id.json").delete() }
    }
}
