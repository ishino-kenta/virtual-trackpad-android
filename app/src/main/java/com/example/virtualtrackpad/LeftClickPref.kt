package com.example.virtualtrackpad

import android.content.Context

/**
 * 1本指タップで左クリックを送る機能の ON/OFF。
 * OFF にすると 1本指タップしても何も起きない (カーソル移動だけ動く)。
 */
object LeftClickPref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "left_click_enabled"
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
