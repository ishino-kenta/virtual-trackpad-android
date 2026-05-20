package com.example.virtualtrackpad.prefs

import android.content.Context

/**
 * フリック・スタッキングの ON/OFF 設定。
 * ON にすると慣性スクロール中に再度2本指フリックすると、残っている慣性速度に
 * 新しいフリック速度が加算されて、より速くスクロールが続くようになる。
 * （macOS / iOS のトラックパッド標準挙動）
 */
object InertialStackingPref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "inertial_stacking"
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
