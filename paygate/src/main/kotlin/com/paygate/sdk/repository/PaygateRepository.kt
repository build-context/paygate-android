package com.paygate.sdk.repository

import com.paygate.sdk.PaygateException
import com.paygate.sdk.PaygateHTTP
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal open class PaygateRepository(
    protected val baseURL: String,
    protected val apiKey: String,
    protected val appContext: android.content.Context
) {

    protected fun getJson(path: String): JSONObject {
        val trimmed = baseURL.trimEnd('/')
        val url = URL("$trimmed$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        PaygateHTTP.applyDefaultHeaders(conn, apiKey, appContext)
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.use { ins ->
            BufferedReader(InputStreamReader(ins, StandardCharsets.UTF_8)).readText()
        } ?: ""

        if (code == 403) {
            try {
                val json = JSONObject(body)
                if (json.optString("code") == "presentation_limit_exceeded") {
                    val used = if (json.has("used") && !json.isNull("used")) json.getInt("used") else null
                    val limit = if (json.has("limit") && !json.isNull("limit")) json.getInt("limit") else null
                    throw PaygateException.PresentationLimitExceeded(used, limit)
                }
            } catch (e: PaygateException.PresentationLimitExceeded) {
                throw e
            } catch (_: Exception) { /* fall through */ }
        }

        if (code !in 200..299) {
            val detail = try {
                val json = JSONObject(body)
                json.optString("detail").takeIf { it.isNotBlank() }
                    ?: json.optString("error").takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
            throw PaygateException.ServerError(detail)
        }

        return JSONObject(body)
    }
}
