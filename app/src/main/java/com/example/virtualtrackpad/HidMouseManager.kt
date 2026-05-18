package com.example.virtualtrackpad

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 注: ログ出力は [AppLog] 経由で統一する（画面上のログオーバーレイにも反映される）。

/**
 * 可変長の Int を ByteArray に変換するヘルパー。
 *
 * HID Descriptor を書くときに 0x81 や 0xC0 のような「Byte の範囲を超える値」を
 * そのまま byteArrayOf(...) に渡せないため、Int を受けて変換する。
 * Kotlin の Byte は -128〜127 の符号付きだが、ビット表現としては 0x00〜0xFF と同じ。
 */
private fun bytes(vararg values: Int): ByteArray =
    ByteArray(values.size) { values[it].toByte() }

/**
 * Combo (3ボタンマウス + 縦/横ホイール + キーボード) の HID Descriptor。
 *
 * 2 つの Top-Level Collection を含み、Report ID で区別する：
 *
 * **Report ID 1: マウス (5 バイト)**
 *   - byte0: ボタン状態 (bit0=左, bit1=右, bit2=中, 上位5bitはパディング)
 *   - byte1: X 軸 相対移動量    (signed int8: -127〜+127)
 *   - byte2: Y 軸 相対移動量    (signed int8: -127〜+127)
 *   - byte3: Wheel 相対回転量   (signed int8: -127〜+127、正値=上方向スクロール)
 *   - byte4: AC Pan 相対回転量  (signed int8: -127〜+127、正値=右方向スクロール)
 *
 * **Report ID 2: キーボード (8 バイト)**
 *   - byte0: モディファイア (bit0=LCtrl, 1=LShift, 2=LAlt, 3=LGUI, 4=RCtrl, ...)
 *   - byte1: 予約 (0)
 *   - byte2〜7: 同時押しキーコード 最大6個 (HID Usage ID、0=未押下)
 *
 * キーボードを追加した主目的は「Ctrl+ホイール」でピンチズームを実現するため。
 * 将来的に 3本指ジェスチャー → Win+Tab などのショートカット送信にも使える。
 *
 * 詳細仕様: USB HID 1.11 / HUT 1.12
 */
private val MOUSE_HID_DESCRIPTOR: ByteArray = bytes(
    // ===== マウス TLC (Report ID 1) =====
    0x05, 0x01,        // Usage Page (Generic Desktop)
    0x09, 0x02,        // Usage (Mouse)
    0xA1, 0x01,        // Collection (Application)
    0x85, 0x01,        //   Report ID (1)
    0x09, 0x01,        //   Usage (Pointer)
    0xA1, 0x00,        //   Collection (Physical)
    0x05, 0x09,        //     Usage Page (Button)
    0x19, 0x01,        //     Usage Minimum (Button 1)
    0x29, 0x03,        //     Usage Maximum (Button 3)
    0x15, 0x00,        //     Logical Minimum (0)
    0x25, 0x01,        //     Logical Maximum (1)
    0x95, 0x03,        //     Report Count (3) — ボタン3個
    0x75, 0x01,        //     Report Size (1 bit)
    0x81, 0x02,        //     Input (Data, Variable, Absolute)
    0x95, 0x01,        //     Report Count (1)
    0x75, 0x05,        //     Report Size (5 bit) — パディング
    0x81, 0x03,        //     Input (Constant)
    0x05, 0x01,        //     Usage Page (Generic Desktop)
    0x09, 0x30,        //     Usage (X)
    0x09, 0x31,        //     Usage (Y)
    0x09, 0x38,        //     Usage (Wheel) — 縦スクロール用
    0x15, 0x81,        //     Logical Minimum (-127)
    0x25, 0x7F,        //     Logical Maximum (+127)
    0x75, 0x08,        //     Report Size (8 bit)
    0x95, 0x03,        //     Report Count (3) — X, Y, Wheel
    0x81, 0x06,        //     Input (Data, Variable, Relative)
    0x05, 0x0C,        //     Usage Page (Consumer Devices) — 横スクロール用
    0x0A, 0x38, 0x02,  //     Usage (AC Pan, 0x0238)
    0x15, 0x81,        //     Logical Minimum (-127)
    0x25, 0x7F,        //     Logical Maximum (+127)
    0x75, 0x08,        //     Report Size (8 bit)
    0x95, 0x01,        //     Report Count (1) — AC Pan
    0x81, 0x06,        //     Input (Data, Variable, Relative)
    0xC0,              //   End Collection
    0xC0,              // End Collection

    // ===== キーボード TLC (Report ID 2) =====
    0x05, 0x01,        // Usage Page (Generic Desktop)
    0x09, 0x06,        // Usage (Keyboard)
    0xA1, 0x01,        // Collection (Application)
    0x85, 0x02,        //   Report ID (2)
    // モディファイアキー: 8 bit、それぞれ独立フラグ
    0x05, 0x07,        //   Usage Page (Keyboard/Keypad)
    0x19, 0xE0,        //   Usage Minimum (Left Ctrl, 0xE0)
    0x29, 0xE7,        //   Usage Maximum (Right GUI, 0xE7)
    0x15, 0x00,        //   Logical Minimum (0)
    0x25, 0x01,        //   Logical Maximum (1)
    0x75, 0x01,        //   Report Size (1)
    0x95, 0x08,        //   Report Count (8) — モディファイア8個
    0x81, 0x02,        //   Input (Data, Variable, Absolute)
    // 予約バイト (1 byte)
    0x95, 0x01,        //   Report Count (1)
    0x75, 0x08,        //   Report Size (8)
    0x81, 0x01,        //   Input (Constant)
    // 同時押しキーコード: 6個まで
    0x95, 0x06,        //   Report Count (6)
    0x75, 0x08,        //   Report Size (8)
    0x15, 0x00,        //   Logical Minimum (0)
    0x25, 0x65,        //   Logical Maximum (101)
    0x05, 0x07,        //   Usage Page (Keyboard/Keypad)
    0x19, 0x00,        //   Usage Minimum (0)
    0x29, 0x65,        //   Usage Maximum (101)
    0x81, 0x00,        //   Input (Data, Array)
    0xC0               // End Collection
)

/**
 * HID マウスマネージャの現在状態を表す sealed class。
 *
 * Compose 側はこの状態を見て、ユーザーへの表示文言を切り替える。
 */
sealed class HidState {
    /** 初期状態。BluetoothAdapter 取得前。 */
    object Initializing : HidState()

    /** 端末が Bluetooth をサポートしていない、または Adapter を取得できなかった。 */
    object BluetoothUnavailable : HidState()

    /** Bluetooth が OFF になっている。ユーザーに ON を促す必要あり。 */
    object BluetoothDisabled : HidState()

    /** getProfileProxy 呼出し後、コールバック待ち。 */
    object ConnectingProfile : HidState()

    /** HID プロファイル取得済み、registerApp 呼出し後、登録完了待ち。 */
    object Registering : HidState()

    /** registerApp 成功。PC からの接続を待機中。 */
    object Registered : HidState()

    /** PC と接続中。引数は相手デバイス名（取得できなければアドレス）。 */
    data class Connected(val deviceLabel: String) : HidState()

    /** エラー発生。詳細メッセージを保持。 */
    data class Error(val message: String) : HidState()
}

/**
 * Android 端末を Bluetooth HID マウスとして PC に見せるための管理クラス。
 *
 * 主な責務：
 *   - BluetoothAdapter / BluetoothHidDevice プロファイルの取得とライフサイクル管理
 *   - HID Descriptor を含む SDP レコードでアプリを登録（registerApp）
 *   - 接続状態を [state] として公開（Compose から observe される）
 *   - 後続フェーズで実装する `sendReport()` のための土台
 *
 * 注意：
 *   - 本クラスのメソッドは BLUETOOTH_CONNECT 権限を前提とする。
 *     呼出し側で [BluetoothPermissionGate] を通っていることを保証する設計。
 *   - Foreground Service 化はしていない。アプリがバックグラウンドに行くと
 *     接続が切れる可能性あり（後フェーズで Service 化を検討）。
 *
 * @param context Application Context（Activity をそのまま渡すとリーク懸念があるため
 *                呼出し側で applicationContext を渡すことを推奨）
 */
class HidMouseManager(private val context: Context) {

    companion object {
        /** マウスボタンビットマスク（HID Descriptor の Button 定義に対応）。 */
        const val MOUSE_BUTTON_LEFT = 0x01
        const val MOUSE_BUTTON_RIGHT = 0x02
        const val MOUSE_BUTTON_MIDDLE = 0x04

        /** HID Report ID。Descriptor の `0x85, n` と対応。 */
        const val REPORT_ID_MOUSE = 1
        const val REPORT_ID_KEYBOARD = 2

        /** キーボードモディファイアビットマスク（HID Usage 0xE0〜0xE7 に対応）。 */
        const val KEY_MOD_LEFT_CTRL = 0x01
        const val KEY_MOD_LEFT_SHIFT = 0x02
        const val KEY_MOD_LEFT_ALT = 0x04
        const val KEY_MOD_LEFT_GUI = 0x08  // Win / Cmd
        const val KEY_MOD_RIGHT_CTRL = 0x10
        const val KEY_MOD_RIGHT_SHIFT = 0x20
        const val KEY_MOD_RIGHT_ALT = 0x40
        const val KEY_MOD_RIGHT_GUI = 0x80

        /** HID キーコード (Keyboard Usage Page 0x07)。今は方向キーだけ定義。 */
        const val HID_KEY_RIGHT_ARROW = 0x4F
        const val HID_KEY_LEFT_ARROW = 0x50
        const val HID_KEY_DOWN_ARROW = 0x51
        const val HID_KEY_UP_ARROW = 0x52

        /** クリック時の押下→離脱の間隔（ミリ秒）。短すぎると PC 側で取りこぼされる可能性あり。 */
        private const val CLICK_HOLD_MS = 30L

        /** SharedPreferences ファイル名と最後の接続先アドレスのキー。 */
        private const val PREFS_NAME = "virtual_trackpad_prefs"
        private const val KEY_LAST_CONNECTED_ADDRESS = "last_connected_address"

        /** 想定外に登録解除された時に自動再登録を試みる最大回数。 */
        private const val MAX_REREGISTER_ATTEMPTS = 3

        /** 再登録までの待機 (ms)。BT スタックが後始末する時間を与える。 */
        private const val REREGISTER_DELAY_MS = 300L
    }

    /**
     * 現在の HID マネージャ状態。Compose の State として公開。
     * `private set` で外部からの直接更新を禁止。
     */
    var state: HidState by mutableStateOf<HidState>(HidState.Initializing)
        private set

    /** プロファイルプロキシ。getProfileProxy 経由で取得。 */
    private var hidDevice: BluetoothHidDevice? = null

    /** プロキシ取得元の BluetoothAdapter（closeProfileProxy に必要）。 */
    private var adapter: android.bluetooth.BluetoothAdapter? = null

    /** Bond 状態監視レシーバーを登録済みかどうか。stop() で二重 unregister を避けるため。 */
    private var bondReceiverRegistered = false

    /**
     * 現在 HID Host として接続中の相手デバイス。
     * sendReport を呼ぶ際にこのデバイスを宛先として指定する。
     * 切断時は null に戻す。
     */
    private var connectedDevice: BluetoothDevice? = null

    /**
     * 「アプリ起動 1 回につき自動接続は 1 度だけ」を保証するフラグ。
     * 接続が成立しなくても再試行で連鎖的に試行しないよう、最初の HID 登録完了時のみ走る。
     * stop() でリセットされ、次回起動時にまた1回分の自動接続権が復活する。
     */
    private var autoConnectAttempted = false

    /**
     * 最後に接続した PC のアドレスを保存しておく SharedPreferences。
     * 次回起動時の自動接続先決定に使う。
     */
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * `start()` で生成し `stop()` でキャンセルする内部用 CoroutineScope。
     * 主に「想定外の登録解除からの自動再登録」の遅延実行に使う。
     */
    private var managerScope: CoroutineScope? = null

    /**
     * 想定外の登録解除に対する自動再登録の試行回数カウンタ。
     * 登録成功で 0 にリセット。[MAX_REREGISTER_ATTEMPTS] に到達したらエラー扱いで諦める。
     */
    private var reregisterAttempts = 0

    /**
     * マネージャを起動する。
     * 1. BluetoothAdapter を取得
     * 2. Bluetooth が有効か確認
     * 3. HID_DEVICE プロファイルプロキシを要求
     */
    @SuppressLint("MissingPermission")  // 上位の Permission Gate で BLUETOOTH_CONNECT を保証
    fun start() {
        AppLog.d("HidMouseManager.start()")

        // start するたびに新しい scope を作る。前回 stop() でキャンセル済みのはず。
        managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        reregisterAttempts = 0

        val manager = context.getSystemService(BluetoothManager::class.java)
        val btAdapter = manager?.adapter
        if (btAdapter == null) {
            AppLog.w("BluetoothAdapter が取得できない（BT 非対応端末の可能性）")
            state = HidState.BluetoothUnavailable
            return
        }
        adapter = btAdapter

        if (!btAdapter.isEnabled) {
            AppLog.w("Bluetooth が OFF — 設定で ON にしてください")
            state = HidState.BluetoothDisabled
            return
        }

        // PC とのペアリング進行状況を可視化するため、Bond 状態変化のレシーバーを登録する。
        // ACTION_BOND_STATE_CHANGED はシステムからの protected broadcast なので、
        // 他アプリからのなりすましを心配する必要はない。RECEIVER_NOT_EXPORTED で受信する。
        if (!bondReceiverRegistered) {
            ContextCompat.registerReceiver(
                context,
                bondStateReceiver,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            bondReceiverRegistered = true
            AppLog.d("Bond 状態レシーバーを登録")
        }

        state = HidState.ConnectingProfile
        AppLog.d("HID_DEVICE プロファイルの取得を要求中...")
        // HID_DEVICE プロファイル取得を非同期で要求。
        // 成功すると serviceListener.onServiceConnected が呼ばれる。
        val requested = btAdapter.getProfileProxy(
            context,
            serviceListener,
            BluetoothProfile.HID_DEVICE
        )
        AppLog.d("getProfileProxy(HID_DEVICE) requested=$requested")
        if (!requested) {
            AppLog.e("HID_DEVICE プロファイル取得が拒否された")
            state = HidState.Error("HID_DEVICE プロファイル取得の要求が拒否された")
        }
    }

    /**
     * マネージャを停止する。
     * registerApp 済みなら unregisterApp して、プロファイルプロキシをクローズする。
     */
    @SuppressLint("MissingPermission")
    fun stop() {
        AppLog.d("HidMouseManager.stop()")
        // 内部スコープを先にキャンセルして、遅延中の再登録などを止める
        managerScope?.cancel()
        managerScope = null
        reregisterAttempts = 0
        if (bondReceiverRegistered) {
            // 登録時と対称にレシーバーを解除しないとリークになる。
            context.unregisterReceiver(bondStateReceiver)
            bondReceiverRegistered = false
        }
        hidDevice?.unregisterApp()
        adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        hidDevice = null
        adapter = null
        // 次回 start() でまた1回だけ自動接続を試せるようにリセット
        autoConnectAttempted = false
        state = HidState.Initializing
    }

    /**
     * 現在この端末とペアリング済みのデバイス一覧を取得する。
     * ユーザーが接続先 PC を選ぶダイアログ用。
     *
     * BluetoothClass で「コンピュータ系」だけにフィルタしてもよいが、
     * 端末によっては正しい Class を返さないことがあるので、ここではフィルタせず
     * 全部返してユーザーに選んでもらう方針にする。
     */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDevice> =
        adapter?.bondedDevices?.toList() ?: emptyList()

    /**
     * 指定のペア済みデバイスに対して、こちら(HID Device)側から能動的に接続要求を出す。
     *
     * 通常は PC(HID Host) 側が自動接続してくるが、Windows がサボったり
     * セッションがズレたりして繋がらないことがある。その時の救済手段。
     *
     * @return sendReport と同様、接続要求が受理されたか (キューに乗ったか)
     */
    @SuppressLint("MissingPermission")
    fun connectTo(device: BluetoothDevice): Boolean {
        val hid = hidDevice
        if (hid == null) {
            AppLog.w("connectTo: HID プロファイル未取得")
            return false
        }
        val label = device.name ?: device.address ?: "(不明)"
        AppLog.d("connectTo: $label へ接続要求")
        return hid.connect(device)
    }

    /**
     * 現在接続中の PC から能動的に切断する。デバッグ用途で使う想定。
     *
     * 成功すると `onConnectionStateChanged` で STATE_DISCONNECTED が通知され、
     * state が [HidState.Registered] に戻る（既存の自動切断検知と同じ経路）。
     *
     * @return disconnect 要求が受理されたか
     */
    @SuppressLint("MissingPermission")
    fun disconnect(): Boolean {
        val device = connectedDevice
        val hid = hidDevice
        if (device == null || hid == null) {
            AppLog.w("disconnect: 接続中デバイスなし")
            return false
        }
        val label = device.name ?: device.address ?: "(不明)"
        AppLog.d("disconnect: $label との接続を切断要求")
        return hid.disconnect(device)
    }

    /**
     * 接続中の PC にマウス Input Report を1つ送信する。
     *
     * 送信フォーマット (5 バイト、HID Descriptor で定義済み)：
     *   - byte0: ボタン状態 (bit0=左 / bit1=右 / bit2=中)
     *   - byte1: X 軸 相対移動量   (-127..+127)
     *   - byte2: Y 軸 相対移動量   (-127..+127)
     *   - byte3: Wheel 相対回転量  (-127..+127、正値=上方向スクロール)
     *   - byte4: AC Pan 相対回転量 (-127..+127、正値=右方向スクロール / 横ホイール)
     *
     * 範囲外の値は自動で clamp する。
     *
     * @param dx       X 方向の相対移動量 (PC 座標系で +は右)
     * @param dy       Y 方向の相対移動量 (PC 座標系で +は下)
     * @param buttons  ボタン状態のビットマスク (デフォルト 0 = どのボタンも押されていない)
     * @param wheel    縦ホイール回転量 (デフォルト 0 = なし、+ = 上スクロール、- = 下スクロール)
     * @param hWheel   横ホイール (AC Pan) 回転量 (デフォルト 0 = なし、+ = 右スクロール、- = 左スクロール)
     * @return sendReport が受理されたか (true = OS の送信キューに乗った)
     */
    @SuppressLint("MissingPermission")
    fun sendMouseMove(
        dx: Int,
        dy: Int,
        buttons: Int = 0,
        wheel: Int = 0,
        hWheel: Int = 0
    ): Boolean {
        // 未接続なら静かに false を返す。トラックパッド面のタッチ毎に呼ばれるため、
        // ここでログを出すと毎フレーム警告が出てログが埋まる。状態は画面上のステータスで分かる。
        val device = connectedDevice ?: return false
        val hid = hidDevice ?: return false

        // Byte の範囲に丸めてから .toByte() で符号付き8bitに変換する。
        // 0x81 (=129) のような Int は coerceIn で -127..127 にしてから .toByte()。
        val report = byteArrayOf(
            (buttons and 0xFF).toByte(),
            dx.coerceIn(-127, 127).toByte(),
            dy.coerceIn(-127, 127).toByte(),
            wheel.coerceIn(-127, 127).toByte(),
            hWheel.coerceIn(-127, 127).toByte()
        )
        // Descriptor で Report ID 1 = マウスと定義したので、ここでは 1 を渡す。
        return hid.sendReport(device, REPORT_ID_MOUSE, report)
    }

    /**
     * 1回分のキー押下を送る便利関数（押下 → 短い保持 → 離脱）。
     *
     * 例:
     *   sendKey(HID_KEY_LEFT_ARROW, modifier = KEY_MOD_LEFT_ALT)  // Alt+← (戻る)
     *   sendKey(HID_KEY_RIGHT_ARROW, modifier = KEY_MOD_LEFT_ALT) // Alt+→ (進む)
     *
     * @param key      HID Usage ID (例: [HID_KEY_LEFT_ARROW])
     * @param modifier モディファイアビットマスク (デフォルト 0 = なし)
     * @param holdMs   押下→離脱の間隔 (ms)。クリックの保持時間と同じ既定値。
     */
    suspend fun sendKey(key: Int, modifier: Int = 0, holdMs: Long = CLICK_HOLD_MS) {
        AppLog.d("キー送信 key=0x${"%02X".format(key)} mod=0x${"%02X".format(modifier)}")
        sendKeyboardReport(modifier, key)
        delay(holdMs)
        // モディファイア・キー両方をリリース
        sendKeyboardReport(0)
    }

    /**
     * キーボード Input Report を送信する (Report ID 2)。
     *
     * 送信フォーマット (8 バイト)：
     *   - byte0: モディファイアビットマスク (`KEY_MOD_LEFT_CTRL` 等の bitwise OR)
     *   - byte1: 予約 (常に 0)
     *   - byte2〜7: 同時押しキーコード 最大6個 (HID Usage ID)
     *
     * モディファイアだけ変えたい場合は keys を全部 0 のままで OK。
     * 「Ctrl 押下」「Ctrl リリース」のような状態切替に使う想定。
     *
     * @param modifier モディファイアキーのビットマスク
     * @param keys     同時押しするキーコード (最大 6 個)
     * @return sendReport が受理されたか
     */
    @SuppressLint("MissingPermission")
    fun sendKeyboardReport(modifier: Int, vararg keys: Int): Boolean {
        val device = connectedDevice ?: return false
        val hid = hidDevice ?: return false

        val report = ByteArray(8)
        report[0] = (modifier and 0xFF).toByte()
        report[1] = 0  // 予約
        keys.take(6).forEachIndexed { i, k ->
            report[2 + i] = (k and 0xFF).toByte()
        }
        return hid.sendReport(device, REPORT_ID_KEYBOARD, report)
    }

    /**
     * スクロール (縦/横ホイール) を1回送信する。
     *
     * カーソル位置・ボタン状態は変えず、ホイール量だけを送る薄いラッパー。
     *
     * @param vTicks 縦ホイールの単位回転量。+ = 上スクロール、- = 下スクロール。
     * @param hTicks 横ホイール (AC Pan) の単位回転量。+ = 右スクロール、- = 左スクロール。デフォルト 0。
     */
    fun sendScroll(vTicks: Int, hTicks: Int = 0): Boolean =
        sendMouseMove(dx = 0, dy = 0, buttons = 0, wheel = vTicks, hWheel = hTicks)

    /**
     * 左クリックを1回送信する（押下 → 短い保持 → 離脱）。
     *
     * dx=0, dy=0 で位置を変えずにボタン状態だけ送ることで、PC 側は現在カーソル位置で
     * クリックが起きたと解釈する。連続して呼べばダブルクリック扱いになる
     * （PC 側 OS のダブルクリック判定に任せる）。
     *
     * @param holdMs 押下→離脱の間隔。未指定なら [CLICK_HOLD_MS] (30ms)。
     */
    suspend fun sendLeftClick(holdMs: Long = CLICK_HOLD_MS) {
        AppLog.d("左クリック送信")
        sendMouseMove(0, 0, buttons = MOUSE_BUTTON_LEFT)
        // PC 側が押下を確実に検出できる時間だけ保持してから離す。
        delay(holdMs)
        sendMouseMove(0, 0, buttons = 0)
    }

    /**
     * 右クリックを1回送信する。基本構造は [sendLeftClick] と同じで、
     * 違いは押下するボタンビット（[MOUSE_BUTTON_RIGHT]）だけ。
     *
     * Windows ではコンテキストメニュー（右クリックメニュー）を出すための操作。
     */
    suspend fun sendRightClick(holdMs: Long = CLICK_HOLD_MS) {
        AppLog.d("右クリック送信")
        sendMouseMove(0, 0, buttons = MOUSE_BUTTON_RIGHT)
        delay(holdMs)
        sendMouseMove(0, 0, buttons = 0)
    }

    /**
     * `onAppStatusChanged(registered=false)` を受けた時の処理。
     *
     * 自分が [stop] を呼んだ結果（= hidDevice == null）なら通常の終了。
     * そうでない場合は **OS 側が勝手に登録解除した想定外ケース** で、
     * 一部 Android 端末では PC との切断時に発生する。自動的に再登録を試みる。
     *
     * 上限 [MAX_REREGISTER_ATTEMPTS] 回まで試して、それでもダメならエラー表示。
     */
    private fun handleUnexpectedUnregister() {
        val hid = hidDevice
        if (hid == null) {
            // stop() による正常な解除
            state = HidState.Initializing
            return
        }
        if (reregisterAttempts >= MAX_REREGISTER_ATTEMPTS) {
            AppLog.e("HID アプリ登録解除が続いた (試行 $reregisterAttempts 回到達)。エラー表示")
            state = HidState.Error("HID アプリ登録に失敗")
            return
        }

        AppLog.w(
            "HID アプリ登録が想定外に解除された。" +
                    "${REREGISTER_DELAY_MS}ms 後に再登録を試行 " +
                    "(${reregisterAttempts + 1}/$MAX_REREGISTER_ATTEMPTS)"
        )
        // ユーザーには「再登録中」として見せる (Error より状況が伝わる)
        state = HidState.Registering

        // 再登録の前に少し待つ (BT スタックの後始末を待たないと拒否されることがある)
        managerScope?.launch {
            delay(REREGISTER_DELAY_MS)
            // 待ってる間に stop() された場合、hidDevice は null、scope もキャンセル済みなので
            // この block は呼ばれない (cancel)。念のため再チェック。
            if (hidDevice == null) return@launch
            reregisterAttempts++
            // 再登録後にもう一度自動再接続を試みられるよう、フラグもリセットしておく
            autoConnectAttempted = false
            registerHidApp()
        }
    }

    /**
     * 最後に接続成功した相手のアドレスを SharedPreferences に保存する。
     * 次回 [tryAutoConnect] で読み出される。
     */
    private fun saveLastConnectedAddress(address: String) {
        prefs.edit().putString(KEY_LAST_CONNECTED_ADDRESS, address).apply()
        AppLog.d("最終接続先を保存: $address")
    }

    /**
     * 保存されている「前回の接続先」を読み出して、現在ペア済みであれば能動接続を要求する。
     *
     * 呼び出しタイミングは HID プロファイル登録完了直後（[onAppStatusChanged] の registered=true）。
     * 保存がない・端末がペア解除済み・Adapter 取得失敗のどれかなら静かに何もしない。
     */
    @SuppressLint("MissingPermission")
    private fun tryAutoConnect() {
        val savedAddress = prefs.getString(KEY_LAST_CONNECTED_ADDRESS, null)
        if (savedAddress == null) {
            AppLog.d("自動接続: 保存された接続先なし（スキップ）")
            return
        }
        val a = adapter
        if (a == null) {
            AppLog.w("自動接続: BluetoothAdapter 未取得（スキップ）")
            return
        }
        // ペア済みデバイスから保存アドレスと一致するものを探す。
        // ペアが解除されていた場合は null が返り、自動接続は不発で終わる（ユーザーの手動操作待ち）。
        val device = a.bondedDevices?.firstOrNull { it.address == savedAddress }
        if (device == null) {
            AppLog.w("自動接続: 保存先 $savedAddress がペア済み一覧に見つからない")
            return
        }
        val label = device.name ?: device.address ?: "(不明)"
        AppLog.d("自動接続を試行: $label")
        connectTo(device)
    }

    /**
     * 短時間表示のトースト通知を出す。
     * BluetoothHidDevice.Callback は MainExecutor で呼ばれているのでメインスレッド前提でよい。
     */
    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * ペアリング(Bond)状態の変化を監視するレシーバー。
     * PC からのペアリング要求 → 完了までの流れを画面ログで追えるようにする。
     */
    private val bondStateReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return

            // API 33+ では型指定版の getParcelableExtra が推奨される。
            // 本プロジェクトは minSdk = 31 なのでバージョン分岐が必要。
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val bondState = intent.getIntExtra(
                BluetoothDevice.EXTRA_BOND_STATE,
                BluetoothDevice.ERROR
            )
            val stateName = when (bondState) {
                BluetoothDevice.BOND_NONE -> "BOND_NONE (未ペア)"
                BluetoothDevice.BOND_BONDING -> "BOND_BONDING (ペアリング中)"
                BluetoothDevice.BOND_BONDED -> "BOND_BONDED (ペア完了)"
                else -> "UNKNOWN($bondState)"
            }
            val label = device?.let { it.name ?: it.address } ?: "(不明)"
            AppLog.d("Bond 状態変化: $label → $stateName")
        }
    }

    /**
     * プロファイルプロキシ取得結果を受け取るリスナー。
     * 端末/OS によっては onServiceConnected が呼ばれるまで数百ms かかることがある。
     */
    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            AppLog.d("onServiceConnected: HID_DEVICE プロキシ取得成功")
            hidDevice = proxy as BluetoothHidDevice
            registerHidApp()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            AppLog.w("onServiceDisconnected: HID_DEVICE プロキシ切断")
            hidDevice = null
            state = HidState.Initializing
        }
    }

    /**
     * HID アプリとして自分を登録する。
     * SDP レコード（デバイス名・記述子）を渡し、PC 側にどう見えるかを定義する。
     */
    @SuppressLint("MissingPermission")
    private fun registerHidApp() {
        val device = hidDevice ?: return

        // SDP レコード: PC 側 Bluetooth スタックがこの情報を読んで「Combo (マウス+キーボード)」と認識する。
        val sdp = BluetoothHidDeviceAppSdpSettings(
            /* name        = */ "VirtualTrackpad",
            /* description = */ "Android virtual trackpad",
            /* provider    = */ "VirtualTrackpad",
            /* subclass    = */ BluetoothHidDevice.SUBCLASS1_COMBO,
            /* descriptors = */ MOUSE_HID_DESCRIPTOR
        )

        // QoS は null 渡しで OS デフォルトに任せる（マウス用途なら問題ない）。
        val executor = ContextCompat.getMainExecutor(context)

        state = HidState.Registering
        AppLog.d("registerApp 呼び出し中...")
        val accepted = device.registerApp(
            sdp,
            /* inQos  = */ null,
            /* outQos = */ null,
            executor,
            hidCallback
        )
        AppLog.d("registerApp accepted=$accepted")
        if (!accepted) {
            // 端末が HID Device Role に未対応の場合、ここで false が返るケースがある。
            AppLog.e("registerApp が拒否された（HID Device Role 未対応の可能性）")
            state = HidState.Error("registerApp が拒否された（HID Device Role 未対応の可能性）")
        }
    }

    /**
     * HID デバイスとしての状態変化を受け取るコールバック。
     * Executor に MainExecutor を渡しているので、ここはメインスレッドで呼ばれる。
     */
    private val hidCallback = object : BluetoothHidDevice.Callback() {
        /**
         * アプリ登録状態が変わったとき呼ばれる。
         * @param pluggedDevice 既に接続中の HID Host があればそのデバイス、なければ null
         * @param registered    登録に成功したか
         */
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            super.onAppStatusChanged(pluggedDevice, registered)
            AppLog.d("onAppStatusChanged registered=$registered pluggedDevice=$pluggedDevice")
            if (registered) {
                AppLog.d("HID アプリ登録成功 — PC からの接続を待機")
                // 登録成功 → 再登録試行カウンタはリセット
                reregisterAttempts = 0
                state = HidState.Registered
                // 起動 1 回につき 1 度だけ、保存しておいた最後の接続先に自動接続を試みる。
                // 再登録経由でここに来た場合は autoConnectAttempted を false に戻してあるので
                // 自動再接続も走る。
                if (!autoConnectAttempted) {
                    autoConnectAttempted = true
                    tryAutoConnect()
                }
            } else {
                handleUnexpectedUnregister()
            }
        }

        /**
         * PC との接続状態が変わったとき呼ばれる。
         * @param device 相手のデバイス
         * @param state  BluetoothProfile.STATE_*（CONNECTED / DISCONNECTED など）
         */
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            super.onConnectionStateChanged(device, state)
            val stateLabel = when (state) {
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                else -> "UNKNOWN($state)"
            }
            AppLog.d("onConnectionStateChanged state=$stateLabel device=$device")
            this@HidMouseManager.state = when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    val label = device.name ?: device.address ?: "不明"
                    connectedDevice = device  // sendReport の宛先として記憶
                    // 次回起動時の自動接続のため、最後に成功した相手アドレスを永続化
                    saveLastConnectedAddress(device.address)
                    // ユーザーへの「繋がった」フィードバック
                    showToast("接続しました: $label")
                    HidState.Connected(label)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevice = null
                    HidState.Registered
                }
                else -> this@HidMouseManager.state  // CONNECTING / DISCONNECTING は今の状態を維持
            }
        }
    }
}
