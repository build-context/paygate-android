package com.paygate.sdk

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The connection half of [BillingManager], against a fake [BillingClient].
 *
 * Play's client is driven by hand here: `startConnection` only records its
 * listener, and each test decides when Play answers and what it says. That is
 * what makes a dropped connection reproducible off-device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BillingManagerConnectionTest {

    private val client = mockk<BillingClient>(relaxed = true)
    private var ready = false
    private val listeners = mutableListOf<BillingClientStateListener>()
    private lateinit var manager: BillingManager

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        manager = freshBillingManager()
        every { client.isReady } answers { ready }
        every { client.startConnection(any()) } answers { listeners += firstArg<BillingClientStateListener>() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a connected client is used as it is`() = runTest {
        ready = true

        manager.ensureConnected(client, "premium_1")

        verify(exactly = 0) { client.startConnection(any()) }
    }

    @Test
    fun `a connection Play drops mid-session is re-established on the next purchase`() = runTest {
        // Launch: connects normally.
        val launch = async { manager.ensureConnected(client, "premium_1") }
        runCurrent()
        ready = true
        listeners[0].onBillingSetupFinished(result(BillingResponseCode.OK))
        launch.await()

        // Play drops it — a Play Store update, the service reclaimed.
        ready = false
        listeners[0].onBillingServiceDisconnected()

        // The reader taps Buy. This used to throw "Billing service not
        // connected" on the spot; it must reconnect and carry on instead.
        val buy = async { manager.ensureConnected(client, "premium_1") }
        runCurrent()
        assertEquals("a second startConnection for the reconnect", 2, listeners.size)
        ready = true
        listeners[1].onBillingSetupFinished(result(BillingResponseCode.OK))
        buy.await()
    }

    @Test
    fun `callers during one connection attempt share it rather than starting another`() = runTest {
        val a = async { manager.ensureConnected(client, "premium_1") }
        val b = async { manager.ensureConnected(client, "premium_1") }
        runCurrent()

        // Play answers a second startConnection during CONNECTING with
        // DEVELOPER_ERROR, so there must only ever be one in flight.
        assertEquals(1, listeners.size)
        ready = true
        listeners.single().onBillingSetupFinished(result(BillingResponseCode.OK))
        a.await()
        b.await()
    }

    @Test
    fun `a device Play refuses fails with Play's own reason`() = runTest {
        val call = async { runCatching { manager.ensureConnected(client, "premium_1") } }
        runCurrent()
        listeners.single().onBillingSetupFinished(
            result(BillingResponseCode.BILLING_UNAVAILABLE, "Billing is unavailable for this account")
        )

        val error = call.await().exceptionOrNull()
        assertTrue("expected BillingUnavailable, got $error", error is PaygateException.BillingUnavailable)
        error as PaygateException.BillingUnavailable
        assertEquals(BillingResponseCode.BILLING_UNAVAILABLE, error.responseCode)
        assertEquals("Billing is unavailable for this account", error.detail)
    }

    @Test
    fun `Play never answering gives up after the timeout instead of hanging`() = runTest {
        val call = async { runCatching { manager.ensureConnected(client, "premium_1") } }
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()

        val error = call.await().exceptionOrNull()
        assertTrue("expected BillingUnavailable, got $error", error is PaygateException.BillingUnavailable)
        error as PaygateException.BillingUnavailable
        assertNull(error.responseCode)
        assertEquals("Billing service not connected", error.detail)
    }

    private fun result(code: Int, message: String = "") =
        BillingResult.newBuilder().setResponseCode(code).setDebugMessage(message).build()
}

/** A [BillingManager] with nothing left over from another test: it is a singleton. */
internal fun freshBillingManager(): BillingManager {
    BillingManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    val context = mockk<Context>()
    every { context.applicationContext } returns context
    return BillingManager.get(context)
}
