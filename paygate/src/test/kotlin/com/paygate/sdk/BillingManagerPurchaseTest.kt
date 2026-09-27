package com.paygate.sdk

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.QueryProductDetailsResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The Sep 2026 failure, end to end through the public [BillingManager.purchase].
 *
 * Uses only the public surface plus the `client` field, so it runs unchanged
 * against the SDK from before the reconnect fix — where it fails with "Billing
 * service not connected" — and after it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BillingManagerPurchaseTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `Buy on a dropped connection reconnects and reaches the product lookup`() = runTest {
        var ready = false
        val client = mockk<BillingClient>(relaxed = true)
        every { client.isReady } answers { ready }
        // Play accepts the reconnect straight away.
        every { client.startConnection(any()) } answers {
            ready = true
            firstArg<BillingClientStateListener>().onBillingSetupFinished(
                BillingResult.newBuilder().setResponseCode(BillingResponseCode.OK).build()
            )
        }
        // The lookup answers "no such product", which is enough to prove the
        // purchase got past the connection — the step that used to fail.
        val empty = mockk<QueryProductDetailsResult>()
        every { empty.productDetailsList } returns emptyList()
        every { empty.unfetchedProductList } returns emptyList()
        every { client.queryProductDetailsAsync(any(), any()) } answers {
            secondArg<ProductDetailsResponseListener>().onProductDetailsResponse(
                BillingResult.newBuilder().setResponseCode(BillingResponseCode.OK).build(),
                empty
            )
        }

        val manager = freshBillingManager()
        BillingManager::class.java.getDeclaredField("client").apply { isAccessible = true }.set(manager, client)

        val error = runCatching { manager.purchase(mockk<Activity>(), "premium_1") }.exceptionOrNull()

        assertEquals(PaygateException.ProductNotFound, error)
    }

    @Test
    fun `a product lookup Play never answers still times out as unanswered`() = runTest {
        val client = mockk<BillingClient>(relaxed = true)
        every { client.isReady } returns true
        // relaxed: queryProductDetailsAsync records nothing and never calls back.

        val manager = freshBillingManager()
        BillingManager::class.java.getDeclaredField("client").apply { isAccessible = true }.set(manager, client)

        val error = runCatching { manager.purchase(mockk<Activity>(), "premium_1") }.exceptionOrNull()

        assertEquals(
            PaygateException.BillingUnavailable(null, "Play did not answer the product lookup"),
            error
        )
    }
}
