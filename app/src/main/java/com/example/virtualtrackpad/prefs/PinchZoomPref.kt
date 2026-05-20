package com.example.virtualtrackpad.prefs

import android.content.Context

/**
 * ピンチズーム (2本指の距離変化を Ctrl+wheel に変換) の ON/OFF 設定。
 * ON にすると、2本指で指を広げる/縮めると PC 側でズームイン/アウトする。
 */
object PinchZoomPref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "pinch_zoom"
    const val DEFAULT = true

    fun load(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY, DEFAULT)

    fun save(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, enabled)
            .apply()
    }
}
