package com.example.virtualtrackpad.prefs

import android.content.Context

/**
 * ドラッグ中の縁取り表示 (= [inDragMode] が立っている間、トラックパッド面の縁に出る
 * 薄グレーの枠) の ON/OFF。
 *
 * ON にすると、ダブルタップ→ドラッグ / 3本指ドラッグ どちらでも左ボタン保持中に
 * 枠が出る。OFF にすると枠の描画を完全に省略する (ドラッグ機能自体は無効化されない)。
 *
 * 「視覚フィードバックは要らない、画面を素のままにしたい」というユーザー向けの設定。
 */
object DragBorderPref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "drag_border_enabled"
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
