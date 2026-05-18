package com.example.virtualtrackpad

import android.content.Context

/**
 * ピンチモード中に2本指間を線で結ぶ視覚表示の ON/OFF 設定。
 * ON にすると、ピンチ確定 (距離変化が slop 超え) 後、指を離すまで2点間に線が描画される。
 */
object PinchLinePref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "pinch_line_visualization"
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
