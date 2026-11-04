package com.paygate.sdk

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.util.UUID

/**
 * The install id has to survive the process: an id that is not persisted is
 * re-rolled on the next launch, and so is the reader's arm of every test.
 */
class InstallIdTest {

    /** A SharedPreferences backed by a map, which is all [InstallId] touches. */
    private val store = mutableMapOf<String, String?>()
    private val prefs = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()

    @Before
    fun setUp() {
        InstallId.resetForTesting()
        every { prefs.getString(any(), any()) } answers { store[firstArg()] ?: secondArg() }
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            store[firstArg()] = secondArg()
            editor
        }
        every { editor.commit() } returns true
    }

    @After
    fun tearDown() {
        InstallId.resetForTesting()
        Paygate.appUserId = null
    }

    @Test
    fun createsAndPersistsAnIdOnFirstUse() {
        val id = InstallId.resolve(prefs)
        assertNotNull(UUID.fromString(id))
        assertEquals(id, store[InstallId.KEY])
        verify(exactly = 1) { editor.commit() }
    }

    @Test
    fun returnsTheSameIdOnEveryLaterUse() {
        val first = InstallId.resolve(prefs)
        repeat(5) { assertEquals(first, InstallId.resolve(prefs)) }
        verify(exactly = 1) { editor.commit() }
    }

    @Test
    fun keepsAnIdAnEarlierLaunchStored() {
        store[InstallId.KEY] = "from-an-earlier-launch"
        assertEquals("from-an-earlier-launch", InstallId.resolve(prefs))
        verify(exactly = 0) { editor.commit() }
    }

    @Test
    fun readsPreferencesOnlyOncePerProcess() {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs

        val first = InstallId.get(context)
        repeat(5) { assertEquals(first, InstallId.get(context)) }
        verify(exactly = 1) { context.getSharedPreferences("paygate_sdk", Context.MODE_PRIVATE) }
    }

    @Test
    fun appUserIdIsSentOnlyWhenUsable() {
        Paygate.appUserId = null
        assertNull(Paygate.normalizedAppUserId())
        Paygate.appUserId = "  user_42  "
        assertEquals("user_42", Paygate.normalizedAppUserId())
        Paygate.appUserId = "   "
        assertNull(Paygate.normalizedAppUserId())
        Paygate.appUserId = "a\nb"
        assertNull(Paygate.normalizedAppUserId())
        Paygate.appUserId = "x".repeat(257)
        assertNull(Paygate.normalizedAppUserId())
    }

    @Test
    fun everyRequestCarriesTheInstallId() {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.packageName } returns "com.example.app"
        Paygate.channelOverride = DistributionChannel.DEBUG
        Paygate.appUserId = "user_42"
        val conn = mockk<HttpURLConnection>(relaxed = true)
        try {
            PaygateHTTP.applyDefaultHeaders(conn, "pk_test", context)
        } finally {
            Paygate.channelOverride = null
        }

        verify { conn.setRequestProperty("Paygate-Install-Id", InstallId.get(context)) }
        verify { conn.setRequestProperty("Paygate-App-User-Id", "user_42") }
    }
}
