package com.example.virtualtrackpad

import android.content.Context

/**
 * 画面右上のメニュー起動ボタンを長押し完了とみなすまでの時間（ミリ秒）。
 * 短いと素早く開けるが誤発動しやすい、長いと意図的に押す必要がある。
 */
object MenuHoldDurationPref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "menu_hold_duration_ms"
    const val MIN = 300f
    const val MAX = 2000f
    const val DEFAULT = 750f

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
