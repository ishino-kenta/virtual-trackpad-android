package com.example.virtualtrackpad

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 1 行分のログエントリ。
 *
 * @param timestamp 表示用のタイムスタンプ文字列（例 "12:34:56.789"）
 * @param level     ログレベルを示す1文字（D/W/E）。テキスト色分けの判断に使う
 * @param message   ログ本文
 */
data class LogEntry(
    val timestamp: String,
    val level: Char,
    val message: String
)

/**
 * アプリ全体で共有するログバッファ。
 *
 * - logcat へも同時に出力（adb logcat -s VirtualTrackpad で取れる）
 * - Compose 側からは [entries] を読むだけで自動で再描画される
 *   （mutableStateListOf は Snapshot 機構で観測可能）
 * - メモリリーク防止のため最大 [MAX_ENTRIES] 件まで保持
 *
 * 用途は開発時の動作確認。リリース時は呼び出しを除去するか、Log のみ残す想定。
 */
object AppLog {

    /** logcat 用タグ。 */
    private const val TAG = "VirtualTrackpad"

    /** 保持する最大エントリ数。これを超えたら古いものから捨てる。 */
    private const val MAX_ENTRIES = 200

    /** タイムスタンプフォーマッタ。スレッドセーフではないがメインスレッドからしか呼ばない想定。 */
    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * 観測可能なログエントリのリスト。
     * mutableStateListOf を使うことで Compose が自動的に変更を検知して再描画する。
     */
    private val _entries = mutableStateListOf<LogEntry>()

    /** 外部公開用（読み取り専用ビュー）。 */
    val entries: List<LogEntry> get() = _entries

    /** デバッグレベル: 通常の動作ログ。 */
    fun d(message: String) {
        Log.d(TAG, message)
        addEntry('D', message)
    }

    /** 警告レベル: 想定外だが致命的でない事象。 */
    fun w(message: String) {
        Log.w(TAG, message)
        addEntry('W', message)
    }

    /** エラーレベル: 致命的なエラー。 */
    fun e(message: String) {
        Log.e(TAG, message)
        addEntry('E', message)
    }

    /**
     * 内部実装: エントリを追加し、上限を超えたら先頭から削除する。
     */
    private fun addEntry(level: Char, message: String) {
        val timestamp = timeFormatter.format(Date())
        _entries.add(LogEntry(timestamp, level, message))
        // バッファ溢れ防止
        while (_entries.size > MAX_ENTRIES) {
            _entries.removeAt(0)
        }
    }
}
