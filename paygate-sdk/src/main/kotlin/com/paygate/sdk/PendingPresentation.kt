package com.paygate.sdk

import org.json.JSONArray
import org.json.JSONObject

internal data class PresentationEvent(
    val eventType: String,
    val occurredAt: Long,
    val metadata: Map<String, String>
)

internal data class PendingPresentation(
    val clientBatchId: String,
    val gateId: String,
    val flowId: String,
    val openedAt: Long,
    var closedAt: Long? = null,
    var dismissReason: String? = null,
    var events: MutableList<PresentationEvent>
) {
    fun toJsonObject(): JSONObject {
        val ev = JSONArray()
        events.forEach { e ->
            val meta = JSONObject()
            e.metadata.forEach { (k, v) -> meta.put(k, v) }
            ev.put(
                JSONObject().apply {
                    put("eventType", e.eventType)
                    put("occurredAt", e.occurredAt)
                    put("metadata", meta)
                }
            )
        }
        return JSONObject().apply {
            put("clientBatchId", clientBatchId)
            put("gateId", gateId)
            put("flowId", flowId)
            put("openedAt", openedAt)
            closedAt?.let { put("closedAt", it) }
            dismissReason?.let { put("dismissReason", it) }
            put("events", ev)
        }
    }
}
