package com.paygate.sdk

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * A random identifier for this install of the host app, created on first use
 * and kept in SharedPreferences.
 *
 * It exists so the server can give one reader the same flow every time a gate
 * is launched. Without it, a gate's weighted choice was rolled afresh on every
 * fetch: a price test showed the same person different prices on different
 * days, and nobody was really in one arm.
 *
 * It is not an advertising or device identifier. It is a UUID this SDK made
 * up, it is never shared with another app, and it goes away when the app's
 * data is cleared or it is uninstalled — a reinstall is a new install, and may
 * land in a different arm.
 */
internal object InstallId {
    private const val PREFS = "paygate_sdk"
    internal const val KEY = "paygate_install_id"

    @Volatile
    private var cached: String? = null

    /** This install's id, creating and persisting one on first use. */
    fun get(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return resolve(prefs).also { cached = it }
        }
    }

    /**
     * Reads the stored id from [prefs], or creates and stores a new one.
     *
     * `commit()`, not `apply()`: this runs once per install, off the main
     * thread (it is called while building a request), and an id that was
     * handed out but never written would be replaced by a different one on
     * the next launch — the exact re-roll this exists to prevent.
     */
    internal fun resolve(prefs: SharedPreferences): String {
        prefs.getString(KEY, null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, fresh).commit()
        return fresh
    }

    /** For tests only: forget the in-memory copy. */
    internal fun resetForTesting() {
        cached = null
    }
}
