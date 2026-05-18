package com.example.virtualtrackpad

import android.content.Context

/**
 * ピンチズームの感度倍率。内部では 25dp / 倍率 = 1 ズーム tick あたりの距離変化量に換算する。
 *   - 1.0 = 25dp 距離変化で 1 ズーム step（デフォルト）
 *   - 2.0 = 12.5dp で 1 step（より敏感）
 *   - 0.5 = 50dp で 1 step（穏やか）
 */
object PinchSensitivityPref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "pinch_sensitivity"
    const val MIN = 0.5f
    const val MAX = 3.0f
    const val DEFAULT = 1.0f

    /** 1 ズーム tick あたりの基準距離変化量 (dp)。倍率はこの値を分母に作用。 */
    const val BASE_DP_PER_TICK = 25f

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
