package com.example.virtualtrackpad

import android.content.Context

/**
 * 慣性スクロールの摩擦係数（フレーム毎に速度に掛ける倍率）。
 *
 * 値が高い (1 に近い) ほど減衰が小さく、長く滑り続ける。
 * 値が低いほど早く止まる。
 *   - 0.80: ~250ms で停止
 *   - 0.92: ~1秒で停止 (デフォルト)
 *   - 0.99: ~5秒で停止
 */
object InertialFrictionPref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "inertial_friction"
    const val MIN = 0.80f
    const val MAX = 0.99f
    const val DEFAULT = 0.92f

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
