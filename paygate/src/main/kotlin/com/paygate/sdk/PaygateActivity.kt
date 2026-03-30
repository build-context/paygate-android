package com.paygate.sdk

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Full-screen WebView hosting server-driven paywall HTML (mirrors iOS PaygateViewController).
 */
class PaygateActivity : Activity() {

    private lateinit var flowData: FlowData
    private lateinit var apiKey: String
    private lateinit var baseURL: String
    private var bounces: Boolean = false
    private var gateId: String? = null
    private var purchaseRequired: Boolean = false
    private var disableWebViewCache: Boolean = false
    private var didComplete: Boolean = false

    private var eventBuffer: PresentationEventBuffer? = null
    private var webView: WebView? = null
    private var spinner: ProgressBar? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!parseIntent()) {
            finishWith(PaygateResult.Error(IllegalStateException("Invalid Paygate activity intent")))
            return
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.mediaPlaybackRequiresUserGesture = false
            if (disableWebViewCache) {
                settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            }
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (url.startsWith(baseURL.trimEnd('/'))) return false
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {
                    }
                    return true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    spinner?.let {
                        root.removeView(it)
                        spinner = null
                    }
                }
            }
            addJavascriptInterface(BridgeHandler(), "PaygateAndroidBridge")
        }
        webView = wv
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        root.addView(wv, lp)

        spinner = ProgressBar(this).apply { isIndeterminate = true }
        val slp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }
        root.addView(spinner, slp)

        setContentView(root)

        if (gateId != null) {
            eventBuffer = PresentationEventBuffer(
                gateId = gateId!!,
                flowId = flowData.id,
                apiKey = apiKey,
                baseURL = baseURL,
                appContext = applicationContext
            )
        }

        loadFlowHtml()
    }

    private fun parseIntent(): Boolean {
        val b = intent.extras ?: return false
        apiKey = b.getString(EXTRA_API_KEY) ?: return false
        baseURL = b.getString(EXTRA_BASE_URL) ?: return false
        bounces = b.getBoolean(EXTRA_BOUNCES, false)
        gateId = b.getString(EXTRA_GATE_ID)
        purchaseRequired = b.getBoolean(EXTRA_PURCHASE_REQUIRED, false)
        disableWebViewCache = b.getBoolean(EXTRA_DISABLE_CACHE, false)
        val flowJson = b.getString(EXTRA_FLOW_JSON) ?: return false
        flowData = try {
            parseFlowData(JSONObject(flowJson))
        } catch (_: Exception) {
            return false
        }
        return true
    }

    private fun loadFlowHtml() {
        val wv = webView ?: return
        val pageDivs = flowData.pages.mapIndexed { i, page ->
            val hidden = if (i > 0) " style=\"display:none\"" else ""
            "<div id=\"page_${page.id}\" class=\"paygate-page\"$hidden>${page.htmlContent}</div>"
        }.joinToString("\n")

        val bridgeShim = """
            (function(){
              window.webkit = window.webkit || {};
              window.webkit.messageHandlers = window.webkit.messageHandlers || {};
              window.webkit.messageHandlers.paygate = window.webkit.messageHandlers.paygate || {};
              window.webkit.messageHandlers.paygate.postMessage = function(msg) {
                try { PaygateAndroidBridge.postMessage(JSON.stringify(msg)); } catch(e) {}
              };
            })();
        """.trimIndent()

        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover">
            <title>Flow</title>
            <style>* { -webkit-user-select: none !important; user-select: none !important; }</style>
            </head>
            <body>
            $pageDivs
            <script>$bridgeShim</script>
            ${flowData.bridgeScript}
            </body>
            </html>
        """.trimIndent()

        wv.loadDataWithBaseURL(
            baseURL.trimEnd('/') + "/",
            html,
            "text/html",
            "UTF-8",
            null
        )
    }

    override fun onDestroy() {
        if (!didComplete) {
            invokeOnce(PaygateResult.Dismissed(null))
        }
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    private fun finishWith(result: PaygateResult) {
        invokeOnce(result)
        finish()
    }

    private fun invokeOnce(result: PaygateResult) {
        if (didComplete) return
        didComplete = true
        eventBuffer?.finalizeAndFlush(result)
        PaygatePresentationSession.complete(result)
    }

    private inner class BridgeHandler {
        @JavascriptInterface
        fun postMessage(json: String) {
            runOnUiThread {
                try {
                    val o = JSONObject(json)
                    val action = o.optString("action", "")
                    when (action) {
                        "close" -> handleClose(o)
                        "skip" -> handleSkip(o)
                        "purchase" -> handlePurchase(o)
                        "restore" -> handleRestore(o)
                        else -> android.util.Log.w("Paygate", "Unknown bridge action: $action")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Paygate", "Bridge parse error", e)
                }
            }
        }
    }

    private fun handleClose(o: JSONObject) {
        eventBuffer?.append("bridge_close", emptyMap())
        val data = o.optJSONObject("data")?.toStringMap()
        finishWith(PaygateResult.Dismissed(data))
    }

    private fun handleSkip(o: JSONObject) {
        eventBuffer?.append("bridge_skip", emptyMap())
        val data = o.optJSONObject("data")?.toStringMap()
        if (purchaseRequired) {
            finishWith(PaygateResult.Dismissed(data))
        } else {
            gateId?.let { SkipPersistence.recordSkipped(applicationContext, it) }
            finishWith(PaygateResult.Skipped(data))
        }
    }

    private fun handlePurchase(o: JSONObject) {
        val productId = o.optString("productId", "")
        if (productId.isEmpty()) {
            android.util.Log.w("Paygate", "purchase ignored: missing productId")
            return
        }
        val data = o.optJSONObject("data")?.toStringMap()
        val map = flowData.productIdMap
        val storeId = map[productId] ?: productId
        if (eventBuffer != null) {
            eventBuffer?.append("purchase_initiated", mapOf("productId" to productId))
        } else {
            trackFlowEvent(
                applicationContext, apiKey, baseURL, flowData.id,
                "purchase_initiated", mapOf("productId" to productId)
            )
        }
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val purchased = BillingManager.get(applicationContext)
                    .purchase(this@PaygateActivity, storeId)
                if (purchased != null) {
                    if (eventBuffer != null) {
                        eventBuffer?.append("purchase_completed", mapOf("productId" to purchased))
                    } else {
                        trackFlowEvent(
                            applicationContext, apiKey, baseURL, flowData.id,
                            "purchase_completed", mapOf("productId" to purchased)
                        )
                    }
                    finishWith(PaygateResult.Purchased(purchased, data))
                }
            } catch (e: Exception) {
                finishWith(PaygateResult.Error(e))
            }
        }
    }

    private fun handleRestore(o: JSONObject) {
        eventBuffer?.append("bridge_restore", emptyMap())
        val data = o.optJSONObject("data")?.toStringMap()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                BillingManager.get(applicationContext).syncPurchases()
                val active = BillingManager.get(applicationContext).activeSubscriptionProductIds
                val map = flowData.productIdMap
                for (paygateProductId in flowData.productIds) {
                    val sid = map[paygateProductId] ?: paygateProductId
                    if (active.contains(sid)) {
                        val meta = mapOf(
                            "productId" to paygateProductId,
                            "playStoreProductId" to sid
                        )
                        if (eventBuffer != null) {
                            eventBuffer?.append("restore_completed", meta)
                        } else {
                            trackFlowEvent(
                                applicationContext, apiKey, baseURL, flowData.id,
                                "restore_completed", meta
                            )
                        }
                        finishWith(PaygateResult.Purchased(sid, data))
                        return@launch
                    }
                }
                eventBuffer?.append("restore_no_entitlement", emptyMap())
            } catch (e: Exception) {
                eventBuffer?.append(
                    "restore_error",
                    mapOf("message" to (e.message ?: ""))
                )
            }
        }
    }

    private fun JSONObject.toStringMap(): Map<String, Any> {
        val out = mutableMapOf<String, Any>()
        val keys = keys()
        while (keys.hasNext()) {
            val k = keys.next()
            when (val v = get(k)) {
                is String, is Number, is Boolean -> out[k] = v
                else -> out[k] = v.toString()
            }
        }
        return out
    }

    companion object {
        const val EXTRA_API_KEY = "paygate_api_key"
        const val EXTRA_BASE_URL = "paygate_base_url"
        const val EXTRA_BOUNCES = "paygate_bounces"
        const val EXTRA_GATE_ID = "paygate_gate_id"
        const val EXTRA_PURCHASE_REQUIRED = "paygate_purchase_required"
        const val EXTRA_DISABLE_CACHE = "paygate_disable_cache"
        const val EXTRA_FLOW_JSON = "paygate_flow_json"

        fun createIntent(
            activity: Activity,
            flowData: FlowData,
            apiKey: String,
            baseURL: String,
            bounces: Boolean,
            gateId: String?,
            purchaseRequired: Boolean,
            disableWebViewCache: Boolean
        ): Intent {
            return Intent(activity, PaygateActivity::class.java).apply {
                putExtra(EXTRA_API_KEY, apiKey)
                putExtra(EXTRA_BASE_URL, baseURL)
                putExtra(EXTRA_BOUNCES, bounces)
                putExtra(EXTRA_GATE_ID, gateId)
                putExtra(EXTRA_PURCHASE_REQUIRED, purchaseRequired)
                putExtra(EXTRA_DISABLE_CACHE, disableWebViewCache)
                putExtra(EXTRA_FLOW_JSON, flowData.toJsonObject().toString())
            }
        }
    }
}
