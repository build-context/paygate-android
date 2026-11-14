package com.paygate.sdk

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the user owns, as Restore and gate suppression see it.
 *
 * The bug this guards: a Play query that failed replaced the owned set with an
 * empty one, so Restore reported nothing and an app trusting an empty answer
 * revoked a paying user.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BillingManagerEntitlementsTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun ok() = BillingResult.newBuilder().setResponseCode(BillingResponseCode.OK).build()

    private fun purchase(
        product: String,
        state: Int = Purchase.PurchaseState.PURCHASED,
        acknowledged: Boolean = true
    ): Purchase = mockk {
        every { purchaseState } returns state
        every { products } returns listOf(product)
        every { isAcknowledged } returns acknowledged
        every { purchaseToken } returns "token-$product"
    }

    /** A connected client whose purchase queries answer SUBS first, then INAPP (the order queried). */
    private fun clientAnswering(
        subs: Pair<BillingResult, List<Purchase>>,
        inapp: Pair<BillingResult, List<Purchase>>
    ): BillingClient {
        val client = mockk<BillingClient>(relaxed = true)
        every { client.isReady } returns true
        var call = 0
        every { client.queryPurchasesAsync(any<com.android.billingclient.api.QueryPurchasesParams>(), any()) } answers {
            val (result, list) = if (call++ == 0) subs else inapp
            secondArg<PurchasesResponseListener>().onQueryPurchasesResponse(result, list)
        }
        return client
    }

    private fun managerWith(client: BillingClient): BillingManager {
        val manager = freshBillingManager()
        BillingManager::class.java.getDeclaredField("client").apply { isAccessible = true }.set(manager, client)
        return manager
    }

    private fun seedOwned(manager: BillingManager, ids: Set<String>) {
        BillingManager::class.java.getDeclaredField("activeEntitlementProductIds")
            .apply { isAccessible = true }.set(manager, ids)
    }

    @Test
    fun `a lifetime one-time purchase counts alongside subscriptions`() = runTest {
        val manager = managerWith(
            clientAnswering(
                subs = ok() to listOf(purchase("monthly")),
                inapp = ok() to listOf(purchase("lifetime"))
            )
        )

        manager.syncPurchases()

        assertEquals(setOf("monthly", "lifetime"), manager.activeEntitlementProductIds)
    }

    @Test
    fun `a pending purchase is not owned yet`() = runTest {
        val manager = managerWith(
            clientAnswering(
                subs = ok() to emptyList(),
                inapp = ok() to listOf(purchase("lifetime", state = Purchase.PurchaseState.PENDING))
            )
        )

        manager.syncPurchases()

        assertTrue(manager.activeEntitlementProductIds.isEmpty())
    }

    @Test
    fun `a failed query throws and keeps what the user was known to own`() = runTest {
        val unavailable = BillingResult.newBuilder()
            .setResponseCode(BillingResponseCode.SERVICE_UNAVAILABLE).build()
        val manager = managerWith(
            clientAnswering(
                subs = ok() to emptyList(),
                inapp = unavailable to emptyList()
            )
        )
        seedOwned(manager, setOf("lifetime"))

        val error = runCatching { manager.syncPurchases() }.exceptionOrNull()

        assertTrue("expected BillingUnavailable, got $error", error is PaygateException.BillingUnavailable)
        assertEquals(setOf("lifetime"), manager.activeEntitlementProductIds)
    }

    @Test
    fun `startup loading never throws over a Play failure`() = runTest {
        val unavailable = BillingResult.newBuilder()
            .setResponseCode(BillingResponseCode.SERVICE_UNAVAILABLE).build()
        val manager = managerWith(
            clientAnswering(subs = unavailable to emptyList(), inapp = ok() to emptyList())
        )
        seedOwned(manager, setOf("monthly"))

        manager.loadPurchasedProducts()

        assertEquals(setOf("monthly"), manager.activeEntitlementProductIds)
    }

    @Test
    fun `a restored purchase that was never acknowledged gets acknowledged`() = runTest {
        val client = clientAnswering(
            subs = ok() to emptyList(),
            inapp = ok() to listOf(purchase("lifetime", acknowledged = false))
        )
        val manager = managerWith(client)

        manager.syncPurchases()

        verify(exactly = 1) { client.acknowledgePurchase(any(), any()) }
    }
}
