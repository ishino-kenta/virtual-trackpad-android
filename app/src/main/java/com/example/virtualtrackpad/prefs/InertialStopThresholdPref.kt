package com.example.virtualtrackpad.prefs

import android.content.Context

/**
 * 慣性スクロールの停止閾値 (px/ms)。
 * 減衰中の速度がこれを下回ると慣性スクロールが終了する。
 *
 *   - 0.01 (= 10 px/s) : 非常にゆっくりまで滑り続ける
 *   - 0.15 (= 150 px/s): デフォルト。終盤の離散 tick によるカクツキを避ける目安
 *   - 0.20 (= 200 px/s): 比較的早めに切り上げる
 */
object InertialStopThresholdPref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "inertial_stop_threshold"
    const val MIN = 0.01f
    const val MAX = 0.20f
    const val DEFAULT = 0.15f

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
