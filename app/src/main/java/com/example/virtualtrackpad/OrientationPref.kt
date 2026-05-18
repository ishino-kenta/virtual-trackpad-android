package com.example.virtualtrackpad

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo

/**
 * 画面の向きのユーザー設定を保存・読込・適用するヘルパー。
 *
 * SharedPreferences に「縦 / 横 / 画面に合わせる」のいずれかを永続化し、
 * 起動時と設定変更時に Activity の [Activity.setRequestedOrientation] に反映する。
 */
object OrientationPref {

    /** SharedPreferences ファイル名（[HidMouseManager] と共用）。 */
    private const val PREFS_NAME = "virtual_trackpad_prefs"

    /** 保存キー。 */
    private const val KEY_ORIENTATION = "screen_orientation"

    /**
     * 画面の向きの設定モード。
     *
     * @param flag  ActivityInfo.SCREEN_ORIENTATION_* の対応定数
     * @param label UI 表示用の日本語ラベル
     */
    enum class Mode(val flag: Int, val label: String) {
        /** 縦固定。端末を 180° 回しても両方向の縦を許可。 */
        PORTRAIT(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, "縦"),

        /** 横固定。端末を 180° 回しても両方向の横を許可。 */
        LANDSCAPE(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, "横"),

        /** 端末の物理的な向きに追従。ユーザーの回転ロック設定も尊重する。 */
        AUTO(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, "画面に合わせる")
    }

    /**
     * 保存済みのモードを読み出す。未保存・パース失敗時は [Mode.AUTO]。
     */
    fun load(context: Context): Mode {
        val saved = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ORIENTATION, Mode.AUTO.name) ?: Mode.AUTO.name
        return runCatching { Mode.valueOf(saved) }.getOrDefault(Mode.AUTO)
    }

    /**
     * モードを保存し、見つかれば Activity の向きにも即座に反映する。
     * configChanges で orientation を吸収しているので、Activity は再生成されない。
     */
    fun save(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ORIENTATION, mode.name)
            .apply()
        applyTo(context.findActivity(), mode)
    }

    /**
     * 指定 Activity の requestedOrientation に反映する。
     * Activity が null（取得失敗）の時は何もしない。
     */
    fun applyTo(activity: Activity?, mode: Mode) {
        activity?.requestedOrientation = mode.flag
    }
}

/**
 * Compose の `LocalContext.current` は ContextWrapper 経由で Activity を返すことが多い。
 * 階層を辿って Activity を取り出すヘルパー。
 */
fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
