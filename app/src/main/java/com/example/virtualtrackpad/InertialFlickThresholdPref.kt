package com.example.virtualtrackpad

import android.content.Context

/**
 * 慣性スクロールを発火させる最小フリック速度 (px/ms)。
 * 2本指スクロールを離した瞬間の平均速度がこれを上回らないと、慣性スクロールは起動しない。
 *
 *   - 0.10 (= 100 px/s) : 少しの動きでも慣性が発生（敏感）
 *   - 0.30 (= 300 px/s) : デフォルト
 *   - 0.50 (= 500 px/s) : はっきりとしたフリックのみ反応
 *   - 1.00 (= 1000 px/s): かなり強いフリックでのみ反応
 */
object InertialFlickThresholdPref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "inertial_flick_threshold"
    const val MIN = 0.10f
    const val MAX = 1.00f
    const val DEFAULT = 0.30f

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
