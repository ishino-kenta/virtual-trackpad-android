package com.example.virtualtrackpad.prefs

import android.content.Context

/**
 * ダブルタップ→ドラッグへの「移行しきい値」 (dp)。
 *
 * - 1 回目のタップ完了後、`DoubleTapIntervalPref` 以内に始まった 2 回目のタッチが、
 *   この値を超えて動いた時点で「左ボタン押下中のドラッグ」へ切り替わる。
 * - リフト時にも同じしきい値で再判定 (late commit)。フレーム単位で取りこぼした
 *   "速くて短い" ジェスチャを救う。
 *
 * 0 にすると「少しでも動けばドラッグ」 (= 最も反応が良いが、指のブレで誤発火する可能性あり)。
 * 大きくすると「明確に動いたときだけドラッグ」になる代わりに、短いストロークで
 * 「ただのダブルタップ判定」に流れやすくなる。
 *
 * 通常のタップ移動許容 ([TapSlopPref]) とは別管理。タップ判定の slop は
 * 「微動はタップとみなす」用なので大きめ (16dp 等) で良いが、ここはユーザーが
 * 既に "タップ" でドラッグ意図を示している 2 回目のタッチに対する閾値なので、
 * 既定 0dp で振り切っておく。
 */
object DragCommitSlopPref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "drag_commit_slop_dp"
    const val MIN = 0f
    const val MAX = 16f
    const val DEFAULT = 0f

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
