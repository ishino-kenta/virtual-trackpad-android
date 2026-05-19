package com.example.virtualtrackpad

import android.content.Context

/**
 * 2本指タップで右クリックを送る機能の ON/OFF。
 * OFF にすると 2本指タップしても何も起きない (スクロール/ピンチは別途独立)。
 */
object RightClickPref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "right_click_enabled"
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
