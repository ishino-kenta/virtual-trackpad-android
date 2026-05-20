package com.example.virtualtrackpad.prefs

import android.content.Context

/**
 * ピンチ判定の発火しきい値 (dp)。
 * 2本指間の距離がこれ以上変化したらピンチモードに移行する。
 *
 *   - 5dp : 非常に敏感（パンのつもりが誤ピンチしやすい）
 *   - 15dp: デフォルト。スクロールしきい値(16dp)より少し小さく、非対称ピンチでも勝つ
 *   - 30dp: 鈍感。はっきりとしたピンチのみ反応
 */
object PinchSlopPref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "pinch_slop_dp"
    const val MIN = 5f
    const val MAX = 30f
    const val DEFAULT = 15f

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
