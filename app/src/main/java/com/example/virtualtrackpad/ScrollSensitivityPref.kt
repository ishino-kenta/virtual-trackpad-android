package com.example.virtualtrackpad

import android.content.Context

/**
 * スクロール感度のユーザー設定を SharedPreferences で永続化するヘルパー。
 *
 * 値は「倍率」で扱う。内部では `30dp / 倍率` を 1 tick あたりの移動量に変換するので、
 *   - 1.0 = 30dp で 1 tick（デフォルト）
 *   - 2.0 = 15dp で 1 tick（より敏感）
 *   - 0.5 = 60dp で 1 tick（より穏やか）
 * となる。UI 表示は感度として直感的な「× 数値」で示す。
 */
object ScrollSensitivityPref {

    /** SharedPreferences ファイル名（他の設定と共用）。 */
    private const val PREFS_NAME = "virtual_trackpad_prefs"

    /** 保存キー。 */
    private const val KEY = "scroll_sensitivity"

    /** UI で許容する最小値。 */
    const val MIN = 0.5f

    /** UI で許容する最大値。 */
    const val MAX = 3.0f

    /** 初回起動時のデフォルト値。 */
    const val DEFAULT = 1.0f

    /**
     * 1 tick あたりの基準移動量 (dp)。倍率はこの値を分母に作用する。
     */
    const val BASE_DP_PER_TICK = 30f

    /**
     * 保存済みの感度を読み出す。未保存・不正値は [DEFAULT]。範囲外は安全のため clamp。
     */
    fun load(context: Context): Float =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY, DEFAULT)
            .coerceIn(MIN, MAX)

    /**
     * 感度を保存。範囲外の値は clamp してから書き込む。
     */
    fun save(context: Context, value: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY, value.coerceIn(MIN, MAX))
            .apply()
    }
}
