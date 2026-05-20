package com.example.virtualtrackpad.prefs

import android.content.Context

/**
 * カーソル感度のユーザー設定を SharedPreferences で永続化するヘルパー。
 *
 * 値は `TrackpadSurface.sensitivity` に直接渡される倍率 (px → cursor px) で、
 * 1.0 = 等倍、1.5 = 1.5倍速、0.5 = 半速。
 */
object SensitivityPref {

    /** SharedPreferences ファイル名（他の設定と共用）。 */
    private const val PREFS_NAME = "virtual_trackpad_prefs"

    /** 保存キー。 */
    private const val KEY = "cursor_sensitivity"

    /** UI で許容する最小値。これより遅くするとほぼ動かない感覚になる。 */
    const val MIN = 0.5f

    /** UI で許容する最大値。これより速いとカクつき・行き過ぎが目立つ。 */
    const val MAX = 3.0f

    /** 初回起動時のデフォルト値。 */
    const val DEFAULT = 1.5f

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
