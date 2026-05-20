package com.example.virtualtrackpad.prefs

import android.content.Context

/**
 * トラックパッド面のうち「タッチを受け付ける対象領域」を端末の向き別に保存する設定。
 *
 * - 値は画面に対する 0.0〜1.0 の比率。`top < bottom`、`left < right` の制約を持つ。
 * - 縦持ち (Portrait) と横持ち (Landscape) で別管理 (アスペクト比 / 持ち方が違うため)。
 * - デフォルトは全画面 (`top=0, bottom=1, left=0, right=1`)。
 * - 領域の最小幅は [MIN_AXIS_SPAN] で各軸保証 (= 完全に潰れて操作不能になる事故を防ぐ)。
 *
 * 値の意味:
 *   - top    : 上端の境界。0=画面の一番上、1=画面の一番下
 *   - bottom : 下端の境界。同上
 *   - left   : 左端の境界。0=画面の一番左、1=画面の一番右
 *   - right  : 右端の境界。同上
 *   有効領域は (left*W, top*H) から (right*W, bottom*H) までの矩形。
 */
object ActiveAreaPref {
    private const val PREFS_NAME = "virtual_trackpad_prefs"

    // 各軸でこれ以上は詰められない最小幅 (画面比率)。
    // 例えば top=0.4, bottom=0.6 のとき span=0.2 で下限。これ以上 top を下げたり bottom を上げたりできない。
    const val MIN_AXIS_SPAN = 0.2f

    /** 4 辺の境界値をまとめた不変データ。 */
    data class Bounds(
        val top: Float,
        val bottom: Float,
        val left: Float,
        val right: Float
    ) {
        companion object {
            /** 全画面が有効領域 (= 初期値、リセット時の値)。 */
            val FULL = Bounds(top = 0f, bottom = 1f, left = 0f, right = 1f)
        }
    }

    // 縦持ち用のキー
    private const val KEY_P_TOP = "active_area_p_top"
    private const val KEY_P_BOTTOM = "active_area_p_bottom"
    private const val KEY_P_LEFT = "active_area_p_left"
    private const val KEY_P_RIGHT = "active_area_p_right"

    // 横持ち用のキー
    private const val KEY_L_TOP = "active_area_l_top"
    private const val KEY_L_BOTTOM = "active_area_l_bottom"
    private const val KEY_L_LEFT = "active_area_l_left"
    private const val KEY_L_RIGHT = "active_area_l_right"

    /**
     * 現在の向き向けに保存された [Bounds] を読み出す。
     * @param isLandscape true なら横持ち用、false なら縦持ち用
     */
    fun load(context: Context, isLandscape: Boolean): Bounds {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (isLandscape) {
            Bounds(
                top = prefs.getFloat(KEY_L_TOP, Bounds.FULL.top),
                bottom = prefs.getFloat(KEY_L_BOTTOM, Bounds.FULL.bottom),
                left = prefs.getFloat(KEY_L_LEFT, Bounds.FULL.left),
                right = prefs.getFloat(KEY_L_RIGHT, Bounds.FULL.right)
            )
        } else {
            Bounds(
                top = prefs.getFloat(KEY_P_TOP, Bounds.FULL.top),
                bottom = prefs.getFloat(KEY_P_BOTTOM, Bounds.FULL.bottom),
                left = prefs.getFloat(KEY_P_LEFT, Bounds.FULL.left),
                right = prefs.getFloat(KEY_P_RIGHT, Bounds.FULL.right)
            )
        }.coerceValid()
    }

    /**
     * 現在の向き向けに保存する。値は [MIN_AXIS_SPAN] 制約で自動補正される。
     */
    fun save(context: Context, isLandscape: Boolean, bounds: Bounds) {
        val v = bounds.coerceValid()
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (isLandscape) {
            editor.putFloat(KEY_L_TOP, v.top)
                .putFloat(KEY_L_BOTTOM, v.bottom)
                .putFloat(KEY_L_LEFT, v.left)
                .putFloat(KEY_L_RIGHT, v.right)
        } else {
            editor.putFloat(KEY_P_TOP, v.top)
                .putFloat(KEY_P_BOTTOM, v.bottom)
                .putFloat(KEY_P_LEFT, v.left)
                .putFloat(KEY_P_RIGHT, v.right)
        }
        editor.apply()
    }

    /**
     * 値域 0..1 への clamp + MIN_AXIS_SPAN 確保。
     * 不正値が保存されていた場合 (旧バージョン / 想定外の編集) の安全網としても使う。
     */
    private fun Bounds.coerceValid(): Bounds {
        val t = top.coerceIn(0f, 1f - MIN_AXIS_SPAN)
        val b = bottom.coerceIn(t + MIN_AXIS_SPAN, 1f)
        val l = left.coerceIn(0f, 1f - MIN_AXIS_SPAN)
        val r = right.coerceIn(l + MIN_AXIS_SPAN, 1f)
        return Bounds(t, b, l, r)
    }
}
