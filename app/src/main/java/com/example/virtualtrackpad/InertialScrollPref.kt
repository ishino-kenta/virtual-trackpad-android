package com.example.virtualtrackpad

import android.content.Context

/**
 * 慣性スクロール (フリック後に減衰しながらスクロールが続く挙動) の ON/OFF 設定。
 * デフォルトは ON。
 */
object InertialScrollPref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "inertial_scroll"
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
