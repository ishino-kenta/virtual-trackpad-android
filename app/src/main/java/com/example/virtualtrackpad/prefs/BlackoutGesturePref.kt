package com.example.virtualtrackpad.prefs

import android.content.Context

/**
 * 「4本指ダブルタップで画面ブラックアウトを ON/OFF する」ジェスチャ自体の有効・無効。
 *
 * - ON にすると、トラックパッド画面で 4本指ダブルタップを検出するたびに
 *   ブラックアウトのトグルが発火する。
 * - OFF にすると、4本指ダブルタップを検出してもブラックアウトを発火しない (= 無効化)。
 *
 * 「うっかり 4本指で触ってブラックアウトに入ると困る」というユーザー向けの設定。
 */
object BlackoutGesturePref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "blackout_gesture_enabled"
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
