package com.example.virtualtrackpad.prefs

import android.content.Context

/**
 * トラックパッド面に指のタッチ位置を示す円ドットを描画するかの設定。
 * デバッグや視覚フィードバックに便利だが、シンプルな見た目にしたい時は OFF にできる。
 */
object PointerDotsPref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"
    private const val KEY = "pointer_dots"
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
