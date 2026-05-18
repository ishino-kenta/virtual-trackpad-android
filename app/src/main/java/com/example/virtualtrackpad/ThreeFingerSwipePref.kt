package com.example.virtualtrackpad

import android.content.Context

/**
 * 3本指の左右スワイプを「戻る / 進む」(Alt+←/→) として送る機能の ON/OFF 設定。
 */
object ThreeFingerSwipePref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "three_finger_swipe"
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
