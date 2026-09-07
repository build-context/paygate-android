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
    /**
     * Store country the flow's prices were resolved for, when known.
     *
     * Recorded at render time rather than read again on submit: this batch is
     * sent on dismissal, and a user who changed Play country in between would
     * otherwise be filed under a country whose prices they never saw.
     */
    var storefront: String? = null,
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
            storefront?.let { put("storefront", it) }
            put("events", ev)
        }
    }
}
