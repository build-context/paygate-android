package com.paygate.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal class PresentationEventBuffer(
    private val gateId: String,
    private val flowId: String,
    private val apiKey: String,
    private val baseURL: String,
    private val appContext: android.content.Context
) {
    private val lock = Any()
    private var pending: PendingPresentation

    init {
        val openedAt = System.currentTimeMillis()
        val batchId = java.util.UUID.randomUUID().toString()
        pending = PendingPresentation(
            clientBatchId = batchId,
            gateId = gateId,
            flowId = flowId,
            openedAt = openedAt,
            closedAt = null,
            dismissReason = null,
            events = mutableListOf(
                PresentationEvent(
                    eventType = "gate_opened",
                    occurredAt = openedAt,
                    metadata = mapOf("gateId" to gateId, "flowId" to flowId)
                )
            )
        )
        OutboxStore.save(appContext, pending)
    }

    fun append(eventType: String, metadata: Map<String, String> = emptyMap()) {
        synchronized(lock) {
            val ts = System.currentTimeMillis()
            pending.events.add(PresentationEvent(eventType, ts, metadata))
            OutboxStore.save(appContext, pending)
        }
    }

    fun finalizeAndFlush(result: PaygateResult) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val (reason, terminalType, meta) = terminalInfo(result)
            pending.closedAt = now
            pending.dismissReason = reason
            pending.events.add(PresentationEvent(terminalType, now, meta))
            OutboxStore.save(appContext, pending)
            val body = pending
            val key = apiKey
            val url = baseURL
            val ctx = appContext
            val batchId = pending.clientBatchId
            CoroutineScope(Dispatchers.IO).launch {
                val ok = PresentationAnalytics.submit(ctx, body, key, url)
                if (ok) OutboxStore.delete(ctx, batchId)
            }
        }
    }

    private fun terminalInfo(result: PaygateResult): Triple<String, String, Map<String, String>> =
        when (result) {
            is PaygateResult.Dismissed -> Triple("dismissed", "gate_dismissed", emptyMap())
            is PaygateResult.Skipped -> Triple("skipped", "gate_skipped", emptyMap())
            is PaygateResult.Purchased -> Triple("purchased", "gate_purchased", mapOf("productId" to result.productId))
            is PaygateResult.Error -> Triple("error", "gate_error", mapOf("message" to (result.error.message ?: "")))
        }
}

internal object PresentationAnalytics {
    fun flushPendingOutbox(context: android.content.Context, apiKey: String, baseURL: String) {
        CoroutineScope(Dispatchers.IO).launch {
            for (id in OutboxStore.listPendingClientBatchIds(context)) {
                val pending = OutboxStore.load(context, id) ?: continue
                if (submit(context, pending, apiKey, baseURL)) {
                    OutboxStore.delete(context, id)
                }
            }
        }
    }

    fun submit(context: android.content.Context, pending: PendingPresentation, apiKey: String, baseURL: String): Boolean {
        val trimmed = baseURL.trimEnd('/')
        return try {
            val url = URL("$trimmed/sdk/presentations")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            PaygateHTTP.applyDefaultHeaders(conn, apiKey, context)
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { w ->
                w.write(pending.toJsonObject().toString())
            }
            val code = conn.responseCode
            code in 200..299
        } catch (e: Exception) {
            android.util.Log.e("Paygate", "submit presentations failed", e)
            false
        }
    }
}

internal fun trackFlowEvent(
    context: android.content.Context,
    apiKey: String,
    baseURL: String,
    flowId: String,
    eventType: String,
    metadata: Map<String, String>
) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val trimmed = baseURL.trimEnd('/')
            val url = URL("$trimmed/sdk/flows/$flowId/events")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            PaygateHTTP.applyDefaultHeaders(conn, apiKey, context)
            val metaJson = org.json.JSONObject()
            metadata.forEach { (k, v) -> metaJson.put(k, v) }
            val body = org.json.JSONObject()
                .put("eventType", eventType)
                .put("metadata", metaJson)
                .toString()
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
            conn.responseCode
        } catch (e: Exception) {
            android.util.Log.e("Paygate", "trackFlowEvent failed", e)
        }
    }
}
