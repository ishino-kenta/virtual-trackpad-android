package com.example.virtualtrackpad.prefs

import android.content.Context

/**
 * 3 本指ドラッグの ON/OFF。
 *
 * ON にすると、3 本指でトラックパッドに触れたまま重心を動かすと、
 * 左ボタンを押下したままのドラッグとして送信される (macOS の "3 本指ドラッグ" 相当)。
 *
 * - commit までは [TapSlopPref] と同じ slop を使い、それを超えた重心移動で発火
 * - commit 後は前フレームと現フレーム両方で押されている指 (= 共通指) の平均移動量を使うので、
 *   途中で 1 本浮いてもカーソルがジャンプしない
 * - 全指が離れた時点でドラッグ終了 (左ボタンリリース)
 *
 * OFF の場合は 3 本指で触れても何も起きない (4 本指スワイプの邪魔にもならない)。
 */
object ThreeFingerDragPref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "three_finger_drag_enabled"
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
