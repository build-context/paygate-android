package com.paygate.sdk

import android.content.Context

internal object SkipPersistence {
    private const val PREFS = "paygate_sdk"
    private const val KEY_SKIPPED = "paygate_skipped_gates"

    fun recordSkipped(context: Context, gateId: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getStringSet(KEY_SKIPPED, emptySet())?.toMutableSet() ?: mutableSetOf()
        existing.add(gateId)
        prefs.edit().putStringSet(KEY_SKIPPED, existing).apply()
    }

    @Suppress("unused")
    fun isSkipped(context: Context, gateId: String): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_SKIPPED, emptySet())?.contains(gateId) == true
    }
}
