package com.paygate.sdk

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal object PaygatePresentationSession {
    private var pending: kotlin.coroutines.Continuation<PaygateResult>? = null

    suspend fun await(): PaygateResult = suspendCancellableCoroutine { cont ->
        pending = cont
        cont.invokeOnCancellation {
            if (pending === cont) pending = null
        }
    }

    fun complete(result: PaygateResult) {
        val c = pending ?: return
        pending = null
        c.resume(result)
    }
}
