package com.paygate.sdk


/**
 * Hands the app's language to a flow. Kept in step with `LocaleBridge.swift`.
 *
 * A paywall is a web page, so it sees the device's language, not the app's,
 * and a reader who picked Español in the app got an English paywall on an
 * English phone. Flows already pick their text from `navigator.language`, so
 * that is what this sets: every flow follows the app with no HTML change.
 *
 * Two scripts, because of where the bridge runs. The page HTML comes first and
 * its inline scripts run as it is parsed, before the bridge defines
 * `window.paygate`. So [headScript] makes `navigator.language` report the
 * app's language before any flow script, and must **not** create
 * `window.paygate` (the bridge installs its methods only while that is still
 * undefined). [tailScript] runs after the bridge and sets `paygate.locale`.
 */
internal object LocaleBridge {

    /** Null when the app set no language: the page keeps the device's. */
    fun headScript(locale: String?): String? {
        if (locale == null) return null
        val l = jsString(locale)
        return """
            <script>
            (function () {
              var l = $l;
              try {
                Object.defineProperty(navigator, 'language', { get: function () { return l; }, configurable: true });
                Object.defineProperty(navigator, 'languages', { get: function () { return [l]; }, configurable: true });
              } catch (e) {}
              document.documentElement.lang = l;
            })();
            </script>
        """.trimIndent()
    }

    fun tailScript(locale: String?): String {
        val value = locale?.let(::jsString) ?: "(navigator.language || 'en')"
        return "<script>if (window.paygate) { window.paygate.locale = $value; }</script>"
    }

    /** The page's `lang`: the app's language, else English as before. */
    fun htmlLang(locale: String?): String = locale ?: "en"

    /**
     * A double-quoted JS string literal. The tag is already validated, but a
     * literal never relies on that: quotes, backslashes, control characters,
     * the two JS line separators and `</` are all escaped.
     */
    fun jsString(value: String): String {
        val out = StringBuilder("\"")
        for (c in value) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == ' ' || c == ' ' || c < ' ' -> out.append(String.format("\\u%04x", c.code))
                else -> out.append(c)
            }
        }
        return out.append('"').toString().replace("</", "<\\/")
    }

    private val TAG = Regex("^[A-Za-z]{2,8}([-_][A-Za-z0-9]{1,8})*$")

    /**
     * A language tag as sent: trimmed, `_` turned into `-`, and null when it is
     * empty or not a plausible tag, so a typo falls back to the device language.
     */
    fun normalize(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        if (trimmed.isEmpty() || trimmed.length > 35 || !TAG.matches(trimmed)) return null
        return trimmed.replace('_', '-')
    }
}
