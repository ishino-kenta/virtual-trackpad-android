package com.example.virtualtrackpad.prefs

import android.content.Context

/**
 * 4本指スワイプの予兆表示 (カーテン + 中央の三角) の ON/OFF。
 * OFF にするとジェスチャー自体は動くが、視覚フィードバックは出ない。
 */
object FourFingerSwipeIndicatorPref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "four_finger_swipe_indicator"
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
