package com.example.virtualtrackpad.prefs

import android.content.Context

/**
 * クリック送信時のボタン押下保持時間（ミリ秒）。
 * 短すぎると PC 側で押下が認識されない可能性、長いとクリック反応が遅く感じる。
 */
object ClickHoldPref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "click_hold_ms"
    const val MIN = 10f
    const val MAX = 100f
    const val DEFAULT = 30f

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
