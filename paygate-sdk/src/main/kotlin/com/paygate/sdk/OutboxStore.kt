package com.paygate.sdk

import org.json.JSONObject
import java.io.File

internal object OutboxStore {
    private const val SUBPATH = "Paygate/pending"

    private fun dir(context: android.content.Context): File? {
        val base = context.filesDir ?: return null
        val d = File(base, SUBPATH)
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun save(context: android.content.Context, pending: PendingPresentation) {
        val d = dir(context) ?: return
        try {
            val f = File(d, "${pending.clientBatchId}.json")
            f.writeText(pending.toJsonObject().toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("Paygate", "OutboxStore save failed", e)
        }
    }

    fun listPendingClientBatchIds(context: android.content.Context): List<String> {
        val d = dir(context) ?: return emptyList()
        return d.listFiles { file -> file.extension == "json" }
            ?.sortedBy { it.lastModified() }
            ?.map { it.nameWithoutExtension }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    fun load(context: android.content.Context, clientBatchId: String): PendingPresentation? {
        val d = dir(context) ?: return null
        val f = File(d, "$clientBatchId.json")
        if (!f.exists()) return null
        return try {
            parsePendingPresentation(JSONObject(f.readText(Charsets.UTF_8)))
        } catch (_: Exception) {
            null
        }
    }

    fun delete(context: android.content.Context, clientBatchId: String) {
        val d = dir(context) ?: return
        File(d, "$clientBatchId.json").delete()
    }
}

internal fun parsePendingPresentation(o: JSONObject): PendingPresentation {
    val events = mutableListOf<PresentationEvent>()
    o.getJSONArray("events").let { arr ->
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val meta = mutableMapOf<String, String>()
            e.optJSONObject("metadata")?.let { mo ->
                val keys = mo.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    meta[k] = mo.optString(k, "")
                }
            }
            events.add(
                PresentationEvent(
                    eventType = e.getString("eventType"),
                    occurredAt = e.getLong("occurredAt"),
                    metadata = meta
                )
            )
        }
    }
    return PendingPresentation(
        clientBatchId = o.getString("clientBatchId"),
        gateId = o.getString("gateId"),
        flowId = o.getString("flowId"),
        openedAt = o.getLong("openedAt"),
        closedAt = if (o.has("closedAt") && !o.isNull("closedAt")) o.getLong("closedAt") else null,
        dismissReason = o.optStringOrNull("dismissReason"),
        events = events
    )
}
