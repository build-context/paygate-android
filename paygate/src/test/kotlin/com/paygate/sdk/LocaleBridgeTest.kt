package com.paygate.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A paywall follows the app's language. Kept in step with the iOS tests. */
class LocaleBridgeTest {

    @Test
    fun `normalizes tags`() {
        assertEquals("es", LocaleBridge.normalize(" es "))
        assertEquals("pt-BR", LocaleBridge.normalize("pt_BR"))
        assertEquals("zh-Hant-TW", LocaleBridge.normalize("zh-Hant-TW"))
    }

    @Test
    fun `rejects what is not a language tag`() {
        assertNull(LocaleBridge.normalize(null))
        assertNull(LocaleBridge.normalize(""))
        assertNull(LocaleBridge.normalize("es\"><script>"))
        assertNull(LocaleBridge.normalize("e"))
    }

    @Test
    fun `no locale leaves the page as it was`() {
        assertNull(LocaleBridge.headScript(null))
        assertEquals("en", LocaleBridge.htmlLang(null))
        assertTrue(LocaleBridge.tailScript(null).contains("navigator.language"))
    }

    @Test
    fun `a locale overrides navigator language without creating the bridge`() {
        val head = LocaleBridge.headScript("es")!!
        assertTrue(head.contains("'language'"))
        assertTrue(head.contains("\"es\""))
        // The bridge installs its methods only while window.paygate is undefined.
        assertFalse(head.contains("window.paygate"))
        assertTrue(LocaleBridge.tailScript("es").contains("window.paygate.locale = \"es\""))
    }

    @Test
    fun `a string cannot close the script`() {
        assertEquals("\"a<\\/script>\"", LocaleBridge.jsString("a</script>"))
    }
}
