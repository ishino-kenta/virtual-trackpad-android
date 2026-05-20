package com.example.virtualtrackpad.prefs

import android.content.Context

/**
 * 2本指スクロール機能の ON/OFF。
 * OFF にすると 2本指で上下/左右にドラッグしてもスクロールが発火しない。
 * 慣性スクロールやフリック・スタッキングはスクロール本体に依存するので、
 * ここを OFF にすれば実質的に全部止まる。
 */
object ScrollPref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "scroll_enabled"
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
