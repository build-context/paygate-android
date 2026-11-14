package com.paygate.sdk

import org.json.JSONObject

/**
 * How a flow hears the outcome of `paygate.restore()`.
 *
 * Restore used to be silent whenever it did not unlock anything: no message,
 * and nothing the flow HTML could react to. A reader who tapped Restore with
 * nothing to restore saw nothing happen, which looks exactly like a broken
 * button.
 *
 * Now every outcome goes back to the page:
 *
 * ```js
 * paygate.onRestore = function (result) {
 *   // result.status: "restored" | "nothing_found" | "cancelled" | "error"
 *   // result.productId (restored), result.message (error)
 * };
 * // or
 * paygate.restore().then(function (result) { ... });
 * ```
 *
 * A flow that sets `paygate.onRestore` shows its own message. One that does
 * not gets a native dialog from the SDK for `nothing_found` and `error`, so
 * existing flows stop being silent without anyone editing them.
 *
 * Injected after the server's bridge script rather than added to it, so it
 * works against today's API with no deploy. Kept in step with
 * `RestoreBridge.swift` on iOS.
 */
internal object RestoreBridge {

    enum class Status(val wire: String) {
        RESTORED("restored"),
        NOTHING_FOUND("nothing_found"),
        CANCELLED("cancelled"),
        ERROR("error"),
    }

    /**
     * Wraps `paygate.restore` so it returns a Promise, and adds the
     * `_restoreResult` entry point native code calls with the outcome. It
     * returns true when the flow handled the result itself.
     */
    val script = """
        <script>
        (function () {
          var pg = window.paygate;
          if (!pg || pg.__restoreResultInstalled) return;
          pg.__restoreResultInstalled = true;
          var waiting = [];
          var originalRestore = pg.restore;
          pg.restore = function (overrideData) {
            var promise;
            if (typeof Promise !== 'undefined') {
              promise = new Promise(function (resolve) { waiting.push(resolve); });
            }
            originalRestore.apply(pg, arguments);
            return promise;
          };
          pg._restoreResult = function (result) {
            var resolvers = waiting;
            waiting = [];
            for (var i = 0; i < resolvers.length; i++) {
              try { resolvers[i](result); } catch (e) {}
            }
            if (typeof pg.onRestore === 'function') {
              try { pg.onRestore(result); } catch (e) {}
              return true;
            }
            return false;
          };
        })();
        </script>
    """.trimIndent()

    /** The JavaScript that delivers one result. Evaluates to `true` when the flow handled it. */
    fun deliverScript(status: Status, productId: String? = null, message: String? = null): String {
        val result = JSONObject().put("status", status.wire)
        productId?.let { result.put("productId", it) }
        message?.let { result.put("message", it) }
        return "(window.paygate && typeof window.paygate._restoreResult === 'function') " +
            "? window.paygate._restoreResult($result) === true : false"
    }

    /**
     * The SDK's own message when the flow shows none. Null for outcomes that
     * need none: a restore that worked closes the flow, and a cancel was the
     * reader's own choice. Copy states the fact and the next step.
     */
    fun fallbackAlert(status: Status): Pair<String, String>? = when (status) {
        Status.NOTHING_FOUND -> "No purchases to restore" to
            "This Google account has no purchases to restore for this app. " +
            "If you bought with a different Google account, switch to it in the Play Store and try again."
        Status.ERROR -> "Couldn't restore purchases" to "Check your connection and try again."
        Status.RESTORED, Status.CANCELLED -> null
    }
}
