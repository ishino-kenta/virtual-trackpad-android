package com.example.virtualtrackpad.prefs

import android.content.Context

/**
 * 「ペアリングを許可」で端末を discoverable にする時間（秒）。
 * 短いと PC 側でペアリング操作している間に切れる可能性、長いと不要に発見可能のまま放置されるリスク。
 * 最大は Android の仕様上 3600 秒。
 */
object DiscoverableDurationPref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "discoverable_duration_sec"
    const val MIN = 60f
    const val MAX = 3600f
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
