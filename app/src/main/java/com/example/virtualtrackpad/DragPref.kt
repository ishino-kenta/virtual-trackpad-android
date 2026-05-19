package com.example.virtualtrackpad

import android.content.Context

/**
 * ダブルタップ→ドラッグ機能の ON/OFF。
 * OFF にすると「タップ完了 → 短時間内に再タッチして移動」しても
 * 左ボタン押下のドラッグへ遷移しなくなる (= 範囲選択・D&D ができない)。
 * カーソル移動とタップだけ動かしたい場合に OFF にする。
 */
object DragPref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "drag_enabled"
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
