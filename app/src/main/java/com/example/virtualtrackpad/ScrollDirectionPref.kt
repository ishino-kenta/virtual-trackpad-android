package com.example.virtualtrackpad

import android.content.Context

/**
 * スクロール方向（ナチュラルか否か）のユーザー設定を永続化するヘルパー。
 *
 * - true (ナチュラル): 指の動きと画面コンテンツが同じ方向に動く (macOS 風)。
 *                     指を下にスワイプ → コンテンツも下に → 上の内容が見える
 * - false (反転)    : 指の動きと逆方向（Windows 標準トラックパッド）
 *                     指を下にスワイプ → 下方向にスクロール（下の内容が見える）
 */
object ScrollDirectionPref {

    /** SharedPreferences ファイル名（他の設定と共用）。 */
    private const val PREFS_NAME = "virtual_trackpad_prefs"

    /** 保存キー。 */
    private const val KEY = "natural_scroll"

    /** 初回起動時のデフォルト。今はナチュラルを採用。 */
    const val DEFAULT = true

    /**
     * 保存値を読み出す。未保存なら [DEFAULT]。
     */
    fun load(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY, DEFAULT)

    /**
     * 設定を保存する。
     */
    fun save(context: Context, natural: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, natural)
            .apply()
    }
}
