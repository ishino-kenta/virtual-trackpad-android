package com.example.virtualtrackpad.prefs

import android.content.Context

/**
 * タップ判定の移動許容量（dp）。これを超えるとタップではなくドラッグ扱いになる。
 * 小さいほど厳しい、大きいほど指のブレを許容する。
 */
object TapSlopPref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "tap_slop_dp"
    const val MIN = 8f
    const val MAX = 32f
    const val DEFAULT = 16f

    fun load(context: Context): Float =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY, DEFAULT)
            .coerceIn(MIN, MAX)

    fun save(context: Context, value: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY, value.coerceIn(MIN, MAX))
            .apply()
    }
}
