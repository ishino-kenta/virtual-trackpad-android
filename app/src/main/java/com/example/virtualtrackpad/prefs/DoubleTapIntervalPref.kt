package com.example.virtualtrackpad.prefs

import android.content.Context

/**
 * ダブルタップ判定の最大間隔（ミリ秒）。
 * 1回目のタップ完了から2回目の押下開始までこの時間内であればドラッグ候補になる。
 */
object DoubleTapIntervalPref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "double_tap_interval_ms"
    const val MIN = 150f
    const val MAX = 600f
    const val DEFAULT = 300f

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
