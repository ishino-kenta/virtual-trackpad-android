package com.example.virtualtrackpad

import android.content.Context

/**
 * タップ判定の最大押下時間（ミリ秒）。短いほどシビア（さっと押して離す）必要、長いほどゆるい。
 */
object TapTimeoutPref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "tap_timeout_ms"
    const val MIN = 100f
    const val MAX = 500f
    const val DEFAULT = 250f

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
