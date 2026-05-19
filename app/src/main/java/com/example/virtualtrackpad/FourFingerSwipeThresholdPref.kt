package com.example.virtualtrackpad

import android.content.Context

/**
 * 4本指スワイプの発火しきい値 (dp)。
 * 重心の総横移動量がこれを超えると「戻る/進む」のキー送信が発火する。
 *
 *   - 40dp : 軽い動きで発火（敏感）
 *   - 80dp : デフォルト
 *   - 200dp: はっきりとしたスワイプのみ反応（鈍感）
 */
object FourFingerSwipeThresholdPref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "four_finger_swipe_threshold_dp"
    const val MIN = 40f
    const val MAX = 200f
    const val DEFAULT = 80f

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
