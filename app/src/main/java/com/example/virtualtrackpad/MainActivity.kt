package com.example.virtualtrackpad

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.virtualtrackpad.ui.theme.VirtualTrackpadTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * アプリのエントリポイント。
 * Compose を使って画面を構築する Activity。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 保存済みの画面向き設定があれば、UI 表示の前に Activity に適用する。
        // configChanges に orientation を含めているので、ここで設定しても Activity は再生成されない。
        requestedOrientation = OrientationPref.load(this).flag
        // ステータスバー／ナビゲーションバーの裏まで描画領域を広げる（モダンな全画面表示）
        enableEdgeToEdge()
        // システムバー(ステータスバー＋ナビゲーションバー)を非表示にして、
        // 画面全体をトラックパッド面として使えるようにする。
        hideSystemBars()
        setContent {
            VirtualTrackpadTheme {
                // Scaffold はマテリアルデザインの土台。ここでは中身の余白(innerPadding)を
                // 受け取って、システムバーと被らないようにトラックパッド面を配置する。
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // BluetoothPermissionGate は権限が揃っているかをチェックし、
                    // 未取得なら要求ダイアログ → 説明画面を出してくれるラッパー。
                    // 揃っていれば中身(TrackpadSurface)をそのまま表示する。
                    BluetoothPermissionGate {
                        // 権限が揃ったあとは AppRoot を表示。
                        // AppRoot は HidMouseManager のライフサイクル管理と、
                        // トラックパッド／設定／ログの3画面切り替えを担当する。
                        AppRoot(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        )
                    }
                }
            }
        }
    }

    /**
     * ユーザーが他のアプリから戻ってきたとき、通知シェードを引き下げた後など、
     * フォーカスが戻るタイミングで再度システムバーを隠す。
     * これがないと、シーンによってはバーが復活したままになることがある。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    /**
     * システムバー(ステータスバー＋ナビゲーションバー)を非表示にする。
     *
     * - hide(systemBars()) で両方のバーを隠す
     * - BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE を指定すると、
     *   ユーザーが画面端からスワイプしたときだけ一時的にバーが半透明で表示される(オーバーレイ)。
     *   これによりホームに戻る等の操作は引き続き可能だが、通常時は完全に隠れる。
     */
    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

/**
 * アプリ内の画面種別。AppRoot で切り替えに使う。
 */
sealed class AppScreen {
    /** メイン画面：トラックパッド + 右上の長押しメニュー起動ボタン。 */
    object Trackpad : AppScreen()

    /** 設定画面：HID 状態表示・ペアリング・接続・切断、ログへのリンク。 */
    object Settings : AppScreen()

    /** ログ画面：AppLog のエントリ一覧。 */
    object Logs : AppScreen()
}

/**
 * アプリ全体のルート Composable。
 *
 * 責務：
 *   - [HidMouseManager] のライフサイクル管理（起動・停止）
 *   - 3画面 (Trackpad / Settings / Logs) の切り替え
 *   - Android のシステムバック操作でひとつ前の画面に戻る挙動
 *
 * @param modifier 外側から渡されるレイアウト調整用 Modifier。
 */
@Composable
fun AppRoot(modifier: Modifier = Modifier) {
    // applicationContext を渡してリーク回避。
    val appContext = LocalContext.current.applicationContext
    val manager = remember { HidMouseManager(appContext) }
    // クリック・コルーチン起動用のスコープ。AppRoot の生存期間に紐づく。
    val scope = rememberCoroutineScope()

    // 現在表示している画面。デフォルトはトラックパッド。
    var screen: AppScreen by remember { mutableStateOf<AppScreen>(AppScreen.Trackpad) }

    // カーソル感度。SharedPreferences の保存値で初期化し、設定画面のスライダーで更新する。
    // ここで保持することで、TrackpadView (=トラックパッド面) と SettingsScreen (=スライダー) が
    // 同じ値を共有する。
    var sensitivity by remember { mutableStateOf(SensitivityPref.load(appContext)) }

    // スクロール感度。倍率値で、内部で 30dp / 倍率 = 1 tick あたりの移動量に変換する。
    var scrollSensitivity by remember { mutableStateOf(ScrollSensitivityPref.load(appContext)) }

    // スクロール方向。true = ナチュラル (指とコンテンツが同方向)、false = 反転 (Windows 標準)。
    var naturalScroll by remember { mutableStateOf(ScrollDirectionPref.load(appContext)) }

    // タップ判定の最大押下時間 (ms)
    var tapTimeoutMs by remember { mutableStateOf(TapTimeoutPref.load(appContext)) }
    // タップ判定の移動許容 (dp)
    var tapSlopDp by remember { mutableStateOf(TapSlopPref.load(appContext)) }
    // ダブルタップ判定の最大間隔 (ms)
    var doubleTapIntervalMs by remember { mutableStateOf(DoubleTapIntervalPref.load(appContext)) }
    // メニュー起動ボタンの長押し時間 (ms)
    var menuHoldDurationMs by remember { mutableStateOf(MenuHoldDurationPref.load(appContext)) }
    // クリック送信時のボタン押下保持時間 (ms)
    var clickHoldMs by remember { mutableStateOf(ClickHoldPref.load(appContext)) }
    // Discoverable 持続時間 (秒)
    var discoverableSec by remember { mutableStateOf(DiscoverableDurationPref.load(appContext)) }
    // 慣性スクロール ON/OFF
    var inertialScroll by remember { mutableStateOf(InertialScrollPref.load(appContext)) }
    // 慣性スクロール: 摩擦係数 (フレーム毎の速度乗数)
    var inertialFriction by remember { mutableStateOf(InertialFrictionPref.load(appContext)) }
    // 慣性スクロール: 停止閾値 (px/ms)
    var inertialStopThreshold by remember { mutableStateOf(InertialStopThresholdPref.load(appContext)) }
    // 慣性スクロール: フリック発火閾値 (px/ms)
    var inertialFlickThreshold by remember { mutableStateOf(InertialFlickThresholdPref.load(appContext)) }
    // フリック・スタッキング ON/OFF (慣性中の再フリックで速度加算)
    var inertialStacking by remember { mutableStateOf(InertialStackingPref.load(appContext)) }
    // ピンチズーム ON/OFF (2本指の距離変化を Ctrl+wheel に変換)
    var pinchZoom by remember { mutableStateOf(PinchZoomPref.load(appContext)) }
    // ピンチ感度 (倍率)
    var pinchSensitivity by remember { mutableStateOf(PinchSensitivityPref.load(appContext)) }
    // ピンチ判定しきい値 (dp)
    var pinchSlopDp by remember { mutableStateOf(PinchSlopPref.load(appContext)) }
    // ピンチ中の2点間線描画 ON/OFF
    var pinchLine by remember { mutableStateOf(PinchLinePref.load(appContext)) }
    // 3本指スワイプで戻る/進む ON/OFF
    var threeFingerSwipe by remember { mutableStateOf(ThreeFingerSwipePref.load(appContext)) }
    // 3本指スワイプ発火しきい値 (dp)
    var threeFingerSwipeThresholdDp by remember {
        mutableStateOf(ThreeFingerSwipeThresholdPref.load(appContext))
    }
    // 3本指スワイプの予兆表示 (カーテン + 三角) ON/OFF
    var threeFingerSwipeIndicator by remember {
        mutableStateOf(ThreeFingerSwipeIndicatorPref.load(appContext))
    }
    // 指のタッチ位置を示す円ドット ON/OFF
    var pointerDots by remember { mutableStateOf(PointerDotsPref.load(appContext)) }

    DisposableEffect(Unit) {
        manager.start()
        onDispose { manager.stop() }
    }

    // システムのバックジェスチャーで一つ前の画面に戻る。
    // トラックパッド画面では BackHandler は無効化してデフォルト動作（アプリ終了）に任せる。
    BackHandler(enabled = screen != AppScreen.Trackpad) {
        screen = when (screen) {
            AppScreen.Logs -> AppScreen.Settings  // ログ → 設定に戻る
            else -> AppScreen.Trackpad            // 設定 → トラックパッドに戻る
        }
    }

    when (screen) {
        AppScreen.Trackpad -> TrackpadView(
            manager = manager,
            scope = scope,
            onOpenMenu = { screen = AppScreen.Settings },
            sensitivity = sensitivity,
            scrollSensitivity = scrollSensitivity,
            naturalScroll = naturalScroll,
            tapTimeoutMs = tapTimeoutMs,
            tapSlopDp = tapSlopDp,
            doubleTapIntervalMs = doubleTapIntervalMs,
            menuHoldDurationMs = menuHoldDurationMs,
            clickHoldMs = clickHoldMs,
            inertialScroll = inertialScroll,
            inertialFriction = inertialFriction,
            inertialStopThreshold = inertialStopThreshold,
            inertialFlickThreshold = inertialFlickThreshold,
            inertialStacking = inertialStacking,
            pinchZoom = pinchZoom,
            pinchSensitivity = pinchSensitivity,
            pinchSlopDp = pinchSlopDp,
            pinchLine = pinchLine,
            threeFingerSwipe = threeFingerSwipe,
            threeFingerSwipeThresholdDp = threeFingerSwipeThresholdDp,
            threeFingerSwipeIndicator = threeFingerSwipeIndicator,
            pointerDots = pointerDots,
            modifier = modifier
        )
        AppScreen.Settings -> SettingsScreen(
            manager = manager,
            sensitivity = sensitivity,
            onSensitivityChange = { newValue ->
                sensitivity = newValue
                SensitivityPref.save(appContext, newValue)
            },
            scrollSensitivity = scrollSensitivity,
            onScrollSensitivityChange = { newValue ->
                scrollSensitivity = newValue
                ScrollSensitivityPref.save(appContext, newValue)
            },
            naturalScroll = naturalScroll,
            onNaturalScrollChange = { newValue ->
                naturalScroll = newValue
                ScrollDirectionPref.save(appContext, newValue)
            },
            tapTimeoutMs = tapTimeoutMs,
            onTapTimeoutMsChange = { v ->
                tapTimeoutMs = v
                TapTimeoutPref.save(appContext, v)
            },
            tapSlopDp = tapSlopDp,
            onTapSlopDpChange = { v ->
                tapSlopDp = v
                TapSlopPref.save(appContext, v)
            },
            doubleTapIntervalMs = doubleTapIntervalMs,
            onDoubleTapIntervalMsChange = { v ->
                doubleTapIntervalMs = v
                DoubleTapIntervalPref.save(appContext, v)
            },
            menuHoldDurationMs = menuHoldDurationMs,
            onMenuHoldDurationMsChange = { v ->
                menuHoldDurationMs = v
                MenuHoldDurationPref.save(appContext, v)
            },
            clickHoldMs = clickHoldMs,
            onClickHoldMsChange = { v ->
                clickHoldMs = v
                ClickHoldPref.save(appContext, v)
            },
            discoverableSec = discoverableSec,
            onDiscoverableSecChange = { v ->
                discoverableSec = v
                DiscoverableDurationPref.save(appContext, v)
            },
            inertialScroll = inertialScroll,
            onInertialScrollChange = { v ->
                inertialScroll = v
                InertialScrollPref.save(appContext, v)
            },
            inertialFriction = inertialFriction,
            onInertialFrictionChange = { v ->
                inertialFriction = v
                InertialFrictionPref.save(appContext, v)
            },
            inertialStopThreshold = inertialStopThreshold,
            onInertialStopThresholdChange = { v ->
                inertialStopThreshold = v
                InertialStopThresholdPref.save(appContext, v)
            },
            inertialFlickThreshold = inertialFlickThreshold,
            onInertialFlickThresholdChange = { v ->
                inertialFlickThreshold = v
                InertialFlickThresholdPref.save(appContext, v)
            },
            inertialStacking = inertialStacking,
            onInertialStackingChange = { v ->
                inertialStacking = v
                InertialStackingPref.save(appContext, v)
            },
            pinchZoom = pinchZoom,
            onPinchZoomChange = { v ->
                pinchZoom = v
                PinchZoomPref.save(appContext, v)
            },
            pinchSensitivity = pinchSensitivity,
            onPinchSensitivityChange = { v ->
                pinchSensitivity = v
                PinchSensitivityPref.save(appContext, v)
            },
            pinchSlopDp = pinchSlopDp,
            onPinchSlopDpChange = { v ->
                pinchSlopDp = v
                PinchSlopPref.save(appContext, v)
            },
            pinchLine = pinchLine,
            onPinchLineChange = { v ->
                pinchLine = v
                PinchLinePref.save(appContext, v)
            },
            threeFingerSwipe = threeFingerSwipe,
            onThreeFingerSwipeChange = { v ->
                threeFingerSwipe = v
                ThreeFingerSwipePref.save(appContext, v)
            },
            threeFingerSwipeThresholdDp = threeFingerSwipeThresholdDp,
            onThreeFingerSwipeThresholdDpChange = { v ->
                threeFingerSwipeThresholdDp = v
                ThreeFingerSwipeThresholdPref.save(appContext, v)
            },
            threeFingerSwipeIndicator = threeFingerSwipeIndicator,
            onThreeFingerSwipeIndicatorChange = { v ->
                threeFingerSwipeIndicator = v
                ThreeFingerSwipeIndicatorPref.save(appContext, v)
            },
            pointerDots = pointerDots,
            onPointerDotsChange = { v ->
                pointerDots = v
                PointerDotsPref.save(appContext, v)
            },
            onBack = { screen = AppScreen.Trackpad },
            onOpenLogs = { screen = AppScreen.Logs },
            modifier = modifier
        )
        AppScreen.Logs -> LogsScreen(
            onBack = { screen = AppScreen.Settings },
            modifier = modifier
        )
    }
}

/**
 * メインのトラックパッド画面。
 *
 * 全画面で [TrackpadSurface] を表示し、右上の長押しトグルで設定画面を開く。
 *
 * @param manager     マウス操作の送信を担当する [HidMouseManager]
 * @param scope       タップ／右クリックの suspend 関数を起動するスコープ
 * @param onOpenMenu  右上の長押しが満タンになった時に呼ばれるコールバック（設定画面へ遷移）
 * @param sensitivity         カーソル感度倍率（設定画面で変更可能）
 * @param scrollSensitivity   スクロール感度倍率（設定画面で変更可能）
 * @param naturalScroll       ナチュラルスクロール方向か（設定画面で変更可能）
 * @param tapTimeoutMs        タップ判定の最大押下時間 (ms)
 * @param tapSlopDp           タップ判定の移動許容 (dp)
 * @param doubleTapIntervalMs ダブルタップ判定の最大間隔 (ms)
 * @param menuHoldDurationMs  メニュー起動長押し時間 (ms)
 * @param clickHoldMs         クリック送信時のボタン押下保持時間 (ms)
 * @param inertialScroll        慣性スクロール ON/OFF
 * @param inertialFriction      慣性スクロールの摩擦係数 (0.80〜0.99)
 * @param inertialStopThreshold 慣性スクロールの停止閾値 (px/ms)
 * @param inertialFlickThreshold フリック発火の最小速度 (px/ms)
 * @param inertialStacking      フリック・スタッキング ON/OFF
 * @param pinchZoom             ピンチズーム ON/OFF
 * @param pinchSensitivity      ピンチ感度倍率
 * @param pinchSlopDp           ピンチ判定の発火しきい値 (dp)
 * @param pinchLine             ピンチ中に2点間を線で結ぶ視覚表示の ON/OFF
 * @param threeFingerSwipe              3本指の左右スワイプで戻る/進むを送る ON/OFF
 * @param threeFingerSwipeThresholdDp   3本指スワイプの発火しきい値 (dp)
 * @param threeFingerSwipeIndicator     3本指スワイプの予兆カーテン+三角の表示 ON/OFF
 * @param pointerDots                   指のタッチ位置を示す円ドットの表示 ON/OFF
 * @param modifier              配置調整用 Modifier
 */
@Composable
fun TrackpadView(
    manager: HidMouseManager,
    scope: CoroutineScope,
    onOpenMenu: () -> Unit,
    sensitivity: Float,
    scrollSensitivity: Float,
    naturalScroll: Boolean,
    tapTimeoutMs: Float,
    tapSlopDp: Float,
    doubleTapIntervalMs: Float,
    menuHoldDurationMs: Float,
    clickHoldMs: Float,
    inertialScroll: Boolean,
    inertialFriction: Float,
    inertialStopThreshold: Float,
    inertialFlickThreshold: Float,
    inertialStacking: Boolean,
    pinchZoom: Boolean,
    pinchSensitivity: Float,
    pinchSlopDp: Float,
    pinchLine: Boolean,
    threeFingerSwipe: Boolean,
    threeFingerSwipeThresholdDp: Float,
    threeFingerSwipeIndicator: Boolean,
    pointerDots: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        TrackpadSurface(
            modifier = Modifier.fillMaxSize(),
            sensitivity = sensitivity,
            scrollSensitivity = scrollSensitivity,
            naturalScroll = naturalScroll,
            inertialScroll = inertialScroll,
            inertialFriction = inertialFriction,
            inertialStopThreshold = inertialStopThreshold,
            inertialFlickThreshold = inertialFlickThreshold,
            inertialStacking = inertialStacking,
            pinchZoom = pinchZoom,
            pinchSensitivity = pinchSensitivity,
            pinchSlopDp = pinchSlopDp,
            pinchLine = pinchLine,
            threeFingerSwipe = threeFingerSwipe,
            threeFingerSwipeThresholdDp = threeFingerSwipeThresholdDp,
            threeFingerSwipeIndicator = threeFingerSwipeIndicator,
            pointerDots = pointerDots,
            tapTimeoutMs = tapTimeoutMs,
            tapSlopDp = tapSlopDp,
            doubleTapIntervalMs = doubleTapIntervalMs,
            onMove = { dx, dy ->
                manager.sendMouseMove(dx, dy)
            },
            onTap = {
                scope.launch { manager.sendLeftClick(clickHoldMs.toLong()) }
            },
            onTwoFingerTap = {
                scope.launch { manager.sendRightClick(clickHoldMs.toLong()) }
            },
            onScroll = { vTicks, hTicks ->
                manager.sendScroll(vTicks, hTicks)
            },
            // ドラッグ開始: 左ボタンを押下したまま (移動量 0) の Report を送る
            onDragStart = {
                manager.sendMouseMove(0, 0, buttons = HidMouseManager.MOUSE_BUTTON_LEFT)
            },
            // ドラッグ中の移動: 左ボタン押下中の状態で各フレームの移動量を送る
            onDrag = { dx, dy ->
                manager.sendMouseMove(dx, dy, buttons = HidMouseManager.MOUSE_BUTTON_LEFT)
            },
            // ドラッグ終了: ボタンを離した状態の Report で終わる
            onDragEnd = {
                manager.sendMouseMove(0, 0, buttons = 0)
            },
            // ピンチ開始: Ctrl 押下を送信
            onPinchStart = {
                manager.sendKeyboardReport(modifier = HidMouseManager.KEY_MOD_LEFT_CTRL)
            },
            // ピンチ中の距離変化: Ctrl 押下中のまま wheel ticks を送信 (= Ctrl+wheel = ズーム)
            onPinchZoom = { ticks ->
                manager.sendScroll(ticks)
            },
            // ピンチ終了: Ctrl をリリース (モディファイア 0)
            onPinchEnd = {
                manager.sendKeyboardReport(modifier = 0)
            },
            // 3本指左スワイプ → Alt+← (ブラウザの戻る)
            onThreeFingerSwipeLeft = {
                scope.launch {
                    manager.sendKey(
                        HidMouseManager.HID_KEY_LEFT_ARROW,
                        modifier = HidMouseManager.KEY_MOD_LEFT_ALT
                    )
                }
            },
            // 3本指右スワイプ → Alt+→ (ブラウザの進む)
            onThreeFingerSwipeRight = {
                scope.launch {
                    manager.sendKey(
                        HidMouseManager.HID_KEY_RIGHT_ARROW,
                        modifier = HidMouseManager.KEY_MOD_LEFT_ALT
                    )
                }
            }
        )

        // 右上の控えめなドット。長押し完了でメニュー(=設定画面)へ。
        HoldToToggleButton(
            onActivate = onOpenMenu,
            holdDurationMs = menuHoldDurationMs.toLong(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        )
    }
}

/**
 * 設定画面。HID 状態の確認とペアリング・接続管理、各種設定を行う。
 *
 * @param manager                    状態取得と接続操作を行う [HidMouseManager]
 * @param sensitivity                現在のカーソル感度（スライダーの表示位置に使う）
 * @param onSensitivityChange        カーソル感度スライダー操作時のコールバック
 * @param scrollSensitivity          現在のスクロール感度
 * @param onScrollSensitivityChange  スクロール感度スライダー操作時のコールバック
 * @param naturalScroll              現在のスクロール方向 (true: ナチュラル)
 * @param onNaturalScrollChange      方向トグル操作時のコールバック
 * @param onBack                     「戻る」ボタンで呼ばれるコールバック
 * @param onOpenLogs                 「ログを見る」ボタンで呼ばれるコールバック
 * @param modifier                   配置調整用 Modifier
 */
@Composable
fun SettingsScreen(
    manager: HidMouseManager,
    sensitivity: Float,
    onSensitivityChange: (Float) -> Unit,
    scrollSensitivity: Float,
    onScrollSensitivityChange: (Float) -> Unit,
    naturalScroll: Boolean,
    onNaturalScrollChange: (Boolean) -> Unit,
    tapTimeoutMs: Float,
    onTapTimeoutMsChange: (Float) -> Unit,
    tapSlopDp: Float,
    onTapSlopDpChange: (Float) -> Unit,
    doubleTapIntervalMs: Float,
    onDoubleTapIntervalMsChange: (Float) -> Unit,
    menuHoldDurationMs: Float,
    onMenuHoldDurationMsChange: (Float) -> Unit,
    clickHoldMs: Float,
    onClickHoldMsChange: (Float) -> Unit,
    discoverableSec: Float,
    onDiscoverableSecChange: (Float) -> Unit,
    inertialScroll: Boolean,
    onInertialScrollChange: (Boolean) -> Unit,
    inertialFriction: Float,
    onInertialFrictionChange: (Float) -> Unit,
    inertialStopThreshold: Float,
    onInertialStopThresholdChange: (Float) -> Unit,
    inertialFlickThreshold: Float,
    onInertialFlickThresholdChange: (Float) -> Unit,
    inertialStacking: Boolean,
    onInertialStackingChange: (Boolean) -> Unit,
    pinchZoom: Boolean,
    onPinchZoomChange: (Boolean) -> Unit,
    pinchSensitivity: Float,
    onPinchSensitivityChange: (Float) -> Unit,
    pinchSlopDp: Float,
    onPinchSlopDpChange: (Float) -> Unit,
    pinchLine: Boolean,
    onPinchLineChange: (Boolean) -> Unit,
    threeFingerSwipe: Boolean,
    onThreeFingerSwipeChange: (Boolean) -> Unit,
    threeFingerSwipeThresholdDp: Float,
    onThreeFingerSwipeThresholdDpChange: (Float) -> Unit,
    threeFingerSwipeIndicator: Boolean,
    onThreeFingerSwipeIndicatorChange: (Boolean) -> Unit,
    pointerDots: Boolean,
    onPointerDotsChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 外側の Column は TopBar (固定) + 内側のスクロール可能 Column の親。
    // 横画面など縦幅が足りない時に下部のボタンが見切れないよう、
    // 設定項目側を verticalScroll で囲んでスクロール可能にする。
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
    ) {
        TopBar(title = "設定", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            // 現在の HID 接続状態を1行表示
            HidStatusOverlay(
                state = manager.state,
                modifier = Modifier.fillMaxWidth()
            )

            // ペアリング許可: 登録済み or 接続中で表示。設定値に応じた時間で発見可能にする。
            if (manager.state is HidState.Registered || manager.state is HidState.Connected) {
                DiscoverableButton(
                    durationSec = discoverableSec.toInt(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                )
            }
            // 能動接続: 待機中(=未接続)で表示
            if (manager.state is HidState.Registered) {
                ConnectButton(
                    manager = manager,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                )
            }
            // 切断: 接続中で表示
            if (manager.state is HidState.Connected) {
                DisconnectButton(
                    manager = manager,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                )
            }

            // 画面の向き設定（縦/横/画面に合わせる）
            OrientationSelector(modifier = Modifier.fillMaxWidth())

            // カーソル感度設定
            SliderSetting(
                title = "カーソル感度",
                value = sensitivity,
                onValueChange = onSensitivityChange,
                valueRange = SensitivityPref.MIN..SensitivityPref.MAX,
                modifier = Modifier.fillMaxWidth()
            )

            // スクロール感度設定
            SliderSetting(
                title = "スクロール感度",
                value = scrollSensitivity,
                onValueChange = onScrollSensitivityChange,
                valueRange = ScrollSensitivityPref.MIN..ScrollSensitivityPref.MAX,
                modifier = Modifier.fillMaxWidth()
            )

            // スクロール方向トグル
            NaturalScrollToggle(
                naturalScroll = naturalScroll,
                onNaturalScrollChange = onNaturalScrollChange,
                modifier = Modifier.fillMaxWidth()
            )

            // 慣性スクロールトグル
            InertialScrollToggle(
                enabled = inertialScroll,
                onEnabledChange = onInertialScrollChange,
                modifier = Modifier.fillMaxWidth()
            )

            // 摩擦係数（慣性が止まるまでの早さ）
            // 値は「速度乗数」: 高いほど減衰小さく長く滑る、低いほど早く止まる
            SliderSetting(
                title = "慣性: 摩擦係数",
                value = inertialFriction,
                onValueChange = onInertialFrictionChange,
                valueRange = InertialFrictionPref.MIN..InertialFrictionPref.MAX,
                valueLabel = { "%.2f".format(it) },
                leftLabel = "すぐ止まる",
                rightLabel = "長く滑る",
                modifier = Modifier.fillMaxWidth()
            )

            // 停止閾値（残速度がこれ未満で慣性終了）
            SliderSetting(
                title = "慣性: 停止閾値",
                value = inertialStopThreshold,
                onValueChange = onInertialStopThresholdChange,
                valueRange = InertialStopThresholdPref.MIN..InertialStopThresholdPref.MAX,
                valueLabel = { "%.2f".format(it) },
                leftLabel = "粘る",
                rightLabel = "すぐ切る",
                modifier = Modifier.fillMaxWidth()
            )

            // フリック発火閾値（指離脱時の速度がこれ以上なら慣性発火）
            SliderSetting(
                title = "慣性: フリック判定速度",
                value = inertialFlickThreshold,
                onValueChange = onInertialFlickThresholdChange,
                valueRange = InertialFlickThresholdPref.MIN..InertialFlickThresholdPref.MAX,
                valueLabel = { "%.2f".format(it) },
                leftLabel = "敏感",
                rightLabel = "鈍感",
                modifier = Modifier.fillMaxWidth()
            )

            // フリック・スタッキング (慣性中に再フリックで速度加算)
            InertialStackingToggle(
                enabled = inertialStacking,
                onEnabledChange = onInertialStackingChange,
                modifier = Modifier.fillMaxWidth()
            )

            // ピンチズーム ON/OFF
            PinchZoomToggle(
                enabled = pinchZoom,
                onEnabledChange = onPinchZoomChange,
                modifier = Modifier.fillMaxWidth()
            )

            // ピンチ感度
            SliderSetting(
                title = "ピンチ感度",
                value = pinchSensitivity,
                onValueChange = onPinchSensitivityChange,
                valueRange = PinchSensitivityPref.MIN..PinchSensitivityPref.MAX,
                modifier = Modifier.fillMaxWidth()
            )

            // ピンチ判定しきい値 (小さいほど発火しやすい)
            SliderSetting(
                title = "ピンチ判定しきい値",
                value = pinchSlopDp,
                onValueChange = onPinchSlopDpChange,
                valueRange = PinchSlopPref.MIN..PinchSlopPref.MAX,
                valueLabel = { "${it.toInt()}dp" },
                leftLabel = "敏感",
                rightLabel = "鈍感",
                modifier = Modifier.fillMaxWidth()
            )

            // ピンチ中の線表示 ON/OFF
            PinchLineToggle(
                enabled = pinchLine,
                onEnabledChange = onPinchLineChange,
                modifier = Modifier.fillMaxWidth()
            )

            // 3本指スワイプで戻る/進む
            ThreeFingerSwipeToggle(
                enabled = threeFingerSwipe,
                onEnabledChange = onThreeFingerSwipeChange,
                modifier = Modifier.fillMaxWidth()
            )

            // 3本指スワイプ発火しきい値
            SliderSetting(
                title = "3本指スワイプしきい値",
                value = threeFingerSwipeThresholdDp,
                onValueChange = onThreeFingerSwipeThresholdDpChange,
                valueRange = ThreeFingerSwipeThresholdPref.MIN..ThreeFingerSwipeThresholdPref.MAX,
                valueLabel = { "${it.toInt()}dp" },
                leftLabel = "敏感",
                rightLabel = "鈍感",
                modifier = Modifier.fillMaxWidth()
            )

            // 3本指スワイプの予兆カーテン+矢印 表示 ON/OFF
            ThreeFingerSwipeIndicatorToggle(
                enabled = threeFingerSwipeIndicator,
                onEnabledChange = onThreeFingerSwipeIndicatorChange,
                modifier = Modifier.fillMaxWidth()
            )

            // 指のタッチ位置ドット ON/OFF
            PointerDotsToggle(
                enabled = pointerDots,
                onEnabledChange = onPointerDotsChange,
                modifier = Modifier.fillMaxWidth()
            )

            // タップ判定の最大押下時間
            SliderSetting(
                title = "タップ判定時間",
                value = tapTimeoutMs,
                onValueChange = onTapTimeoutMsChange,
                valueRange = TapTimeoutPref.MIN..TapTimeoutPref.MAX,
                valueLabel = { "${it.toInt()}ms" },
                leftLabel = "短い",
                rightLabel = "長い",
                modifier = Modifier.fillMaxWidth()
            )

            // タップ判定の移動許容
            SliderSetting(
                title = "タップ移動許容",
                value = tapSlopDp,
                onValueChange = onTapSlopDpChange,
                valueRange = TapSlopPref.MIN..TapSlopPref.MAX,
                valueLabel = { "${it.toInt()}dp" },
                leftLabel = "厳しい",
                rightLabel = "ゆるい",
                modifier = Modifier.fillMaxWidth()
            )

            // ダブルタップ判定の最大間隔
            SliderSetting(
                title = "ダブルタップ間隔",
                value = doubleTapIntervalMs,
                onValueChange = onDoubleTapIntervalMsChange,
                valueRange = DoubleTapIntervalPref.MIN..DoubleTapIntervalPref.MAX,
                valueLabel = { "${it.toInt()}ms" },
                leftLabel = "短い",
                rightLabel = "長い",
                modifier = Modifier.fillMaxWidth()
            )

            // メニュー起動の長押し時間
            SliderSetting(
                title = "メニュー長押し時間",
                value = menuHoldDurationMs,
                onValueChange = onMenuHoldDurationMsChange,
                valueRange = MenuHoldDurationPref.MIN..MenuHoldDurationPref.MAX,
                valueLabel = { "${it.toInt()}ms" },
                leftLabel = "短い",
                rightLabel = "長い",
                modifier = Modifier.fillMaxWidth()
            )

            // クリック押下保持時間
            SliderSetting(
                title = "クリック押下保持",
                value = clickHoldMs,
                onValueChange = onClickHoldMsChange,
                valueRange = ClickHoldPref.MIN..ClickHoldPref.MAX,
                valueLabel = { "${it.toInt()}ms" },
                leftLabel = "短い",
                rightLabel = "長い",
                modifier = Modifier.fillMaxWidth()
            )

            // Discoverable 持続時間
            SliderSetting(
                title = "ペアリング許可時間",
                value = discoverableSec,
                onValueChange = onDiscoverableSecChange,
                valueRange = DiscoverableDurationPref.MIN..DiscoverableDurationPref.MAX,
                valueLabel = {
                    val sec = it.toInt()
                    if (sec >= 60) "${sec / 60}分" else "${sec}秒"
                },
                leftLabel = "短い",
                rightLabel = "長い",
                modifier = Modifier.fillMaxWidth()
            )

            // ログ画面への遷移ボタン
            Button(
                onClick = onOpenLogs,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Text("ログを見る")
            }
        }
    }
}

/**
 * 3本指スワイプの予兆表示（カーテン + 中央三角）の ON/OFF トグル。
 *
 * @param enabled         現在の状態
 * @param onEnabledChange 状態変化時に呼ばれる
 * @param modifier        配置調整用 Modifier
 */
@Composable
fun ThreeFingerSwipeIndicatorToggle(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onEnabledChange(!enabled) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "3本指スワイプの予兆表示", color = Color.White)
            Text(
                text = "進捗をカーテンで、閾値到達を中央の三角で示す",
                color = Color(0xFF888888),
                fontSize = 12.sp
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange
        )
    }
}

/**
 * 指のタッチ位置ドット表示の ON/OFF トグル。
 *
 * @param enabled         現在の状態
 * @param onEnabledChange 状態変化時に呼ばれる
 * @param modifier        配置調整用 Modifier
 */
@Composable
fun PointerDotsToggle(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onEnabledChange(!enabled) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "指のタッチ位置ドット", color = Color.White)
            Text(
                text = "触っている指の位置に小さな円を表示",
                color = Color(0xFF888888),
                fontSize = 12.sp
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange
        )
    }
}

/**
 * 3本指の左右スワイプを「戻る / 進む」(Alt+←/→) として送るトグル。
 *
 * @param enabled         現在の状態
 * @param onEnabledChange 状態変化時に呼ばれる
 * @param modifier        配置調整用 Modifier
 */
@Composable
fun ThreeFingerSwipeToggle(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onEnabledChange(!enabled) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "3本指スワイプで戻る/進む", color = Color.White)
            Text(
                text = "左へスワイプ=Alt+← / 右へスワイプ=Alt+→",
                color = Color(0xFF888888),
                fontSize = 12.sp
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange
        )
    }
}

/**
 * ピンチ中に2点間を線で結ぶ視覚表示の ON/OFF トグル。
 * ピンチが確定したことが分かりやすくなる開発・デバッグ寄りの機能。
 *
 * @param enabled         現在の状態
 * @param onEnabledChange 状態変化時に呼ばれる
 * @param modifier        配置調整用 Modifier
 */
@Composable
fun PinchLineToggle(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onEnabledChange(!enabled) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "ピンチ中の線表示", color = Color.White)
            Text(
                text = "ピンチ確定中に2本指の間を線で結ぶ",
                color = Color(0xFF888888),
                fontSize = 12.sp
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange
        )
    }
}

/**
 * ピンチズームの ON/OFF トグル。
 * ON: 2本指の距離変化で Ctrl+ホイールを送信、ブラウザ等でズーム動作。
 *
 * @param enabled         現在の状態
 * @param onEnabledChange 状態変化時に呼ばれる
 * @param modifier        配置調整用 Modifier
 */
@Composable
fun PinchZoomToggle(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onEnabledChange(!enabled) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "ピンチズーム", color = Color.White)
            Text(
                text = "2本指の距離変化でズームイン/アウト (Ctrl+ホイール送信)",
                color = Color(0xFF888888),
                fontSize = 12.sp
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange
        )
    }
}

/**
 * フリック・スタッキングの ON/OFF トグル。
 * ON: 慣性スクロール中に再度2本指フリックすると残速度に加算される (macOS / iOS 風)。
 *
 * @param enabled         現在の状態
 * @param onEnabledChange 状態変化時に呼ばれる
 * @param modifier        配置調整用 Modifier
 */
@Composable
fun InertialStackingToggle(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onEnabledChange(!enabled) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "フリック・スタッキング", color = Color.White)
            Text(
                text = "慣性中に再フリックすると速度が加算される",
                color = Color(0xFF888888),
                fontSize = 12.sp
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange
        )
    }
}

/**
 * 慣性スクロールの ON/OFF トグル。
 * ON: 2本指で素早くスワイプして指を離すと、減衰しながら自動でスクロールが続く。
 *
 * @param enabled         現在の状態
 * @param onEnabledChange 状態変化時に呼ばれる
 * @param modifier        配置調整用 Modifier
 */
@Composable
fun InertialScrollToggle(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onEnabledChange(!enabled) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "慣性スクロール", color = Color.White)
            Text(
                text = "素早くスワイプして指を離すと自動でスクロールが続く",
                color = Color(0xFF888888),
                fontSize = 12.sp
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange
        )
    }
}

/**
 * ナチュラルスクロールの ON/OFF トグル。
 *
 * - ON  (true)  : 指の動きと画面コンテンツが同じ方向に動く (macOS 風)
 * - OFF (false) : 指の動きと逆方向 (Windows 標準トラックパッド)
 *
 * 行全体タップでも切り替えできるように clickable を付ける。
 *
 * @param naturalScroll          現在の状態
 * @param onNaturalScrollChange  状態変化時に呼ばれる
 * @param modifier               配置調整用 Modifier
 */
@Composable
fun NaturalScrollToggle(
    naturalScroll: Boolean,
    onNaturalScrollChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onNaturalScrollChange(!naturalScroll) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "ナチュラルスクロール", color = Color.White)
            Text(
                text = "指の動きとコンテンツが同じ方向に動く",
                color = Color(0xFF888888),
                fontSize = 12.sp
            )
        }
        Switch(
            checked = naturalScroll,
            onCheckedChange = onNaturalScrollChange
        )
    }
}

/**
 * 数値設定用の共通 Slider UI。タイトル + 現在値 + スライダー + 両端ラベル の縦並び。
 *
 * - スライド中もリアルタイムで [onValueChange] が呼ばれる
 * - 表示フォーマットは [valueLabel] で差し替え可能（既定は 「1.5×」 形式）
 * - 両端ラベル（既定: 「遅い」「速い」）で方向を分かりやすく
 *
 * @param title         見出しテキスト（例: 「カーソル感度」）
 * @param value         現在値
 * @param onValueChange スライド時のコールバック
 * @param valueRange    値の最小/最大
 * @param modifier      配置調整用 Modifier
 * @param valueLabel    現在値の表示フォーマット (例: "%.1f×".format / "${it.toInt()}ms")
 * @param leftLabel     左端の補足ラベル
 * @param rightLabel    右端の補足ラベル
 */
@Composable
fun SliderSetting(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    valueLabel: (Float) -> String = { "%.1f×".format(it) },
    leftLabel: String = "遅い",
    rightLabel: String = "速い"
) {
    Column(modifier = modifier) {
        // 見出し + 現在値を1行にまとめて表示
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = title, color = Color.White)
            Text(
                text = valueLabel(value),
                color = Color(0xFFCCCCCC)
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        // スライダーの両端ラベル
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 0.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = leftLabel, color = Color(0xFF888888), fontSize = 12.sp)
            Text(text = rightLabel, color = Color(0xFF888888), fontSize = 12.sp)
        }
    }
}

/**
 * 画面の向き（縦／横／画面に合わせる）を選ぶラジオボタン群。
 * 選択は [OrientationPref] に永続化され、即座に Activity の向きに反映される。
 *
 * @param modifier 配置調整用 Modifier
 */
@Composable
fun OrientationSelector(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 現在選択中のモード。SharedPreferences の初期値で初期化、選択変更時に更新する。
    var current by remember { mutableStateOf(OrientationPref.load(context)) }

    Column(modifier = modifier) {
        Text(
            text = "画面の向き",
            color = Color.White,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
        )
        // 各モードを RadioButton + Text の Row で表示
        OrientationPref.Mode.values().forEach { mode ->
            val onSelect = {
                current = mode
                OrientationPref.save(context, mode)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 行全体をタップしても選択できるようにする（RadioButton 本体だけだと押しにくい）
                    .clickable(onClick = onSelect)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = current == mode,
                    onClick = onSelect
                )
                Text(
                    text = mode.label,
                    color = Color.White,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

/**
 * ログ画面。AppLog のエントリを全画面でスクロール表示する。
 *
 * @param onBack   「戻る」ボタンで呼ばれるコールバック
 * @param modifier 配置調整用 Modifier
 */
@Composable
fun LogsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
    ) {
        TopBar(title = "ログ", onBack = onBack)
        LogOverlay(
            logs = AppLog.entries,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * 画面上部の共通バー。左に戻るボタン、中央にタイトル。
 *
 * @param title  画面タイトル
 * @param onBack 戻るボタンのコールバック
 */
@Composable
fun TopBar(title: String, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF101010))
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        // 左端: 戻るボタン
        TextButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            Text("← 戻る", color = Color.White)
        }
        // 中央: タイトル
        Text(
            text = title,
            color = Color.White,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/**
 * 長押しで発動する控えめなトグルボタン。
 *
 * 見た目：
 *   - 通常: 半透明で目立たない小さな白い円
 *   - 押下中: 円のまわりに白いリングが進捗に応じて時計回りに増えていく
 *   - リング満タン (= 指定時間ホールド完了) で [onActivate] が呼ばれる
 *   - 途中で指を離すとリングは消えてキャンセル扱い
 *
 * ゲームの「長押しで確認」UI 風の挙動。誤タップで切り替わらないのでトラックパッド操作と干渉しにくい。
 *
 * @param onActivate     長押し成功時のコールバック
 * @param holdDurationMs 長押し完了とみなすまでの時間 (ms)
 * @param modifier       配置調整用 Modifier
 */
@Composable
fun HoldToToggleButton(
    onActivate: () -> Unit,
    holdDurationMs: Long = 750L,
    modifier: Modifier = Modifier
) {

    // 現在押下中か。pointerInput でジェスチャー監視中に更新する。
    var isPressed by remember { mutableStateOf(false) }
    // 進捗 0.0..1.0。Canvas のリング描画に使う。
    var progress by remember { mutableStateOf(0f) }

    // 押下中だけアニメーションループを走らせる。
    // LaunchedEffect の key を isPressed にすることで、押下開始/離脱ごとにループの開始/終了が起きる。
    LaunchedEffect(isPressed) {
        if (isPressed) {
            val startTime = System.currentTimeMillis()
            while (isPressed) {
                val elapsed = System.currentTimeMillis() - startTime
                progress = (elapsed.toFloat() / holdDurationMs).coerceIn(0f, 1f)
                if (progress >= 1f) {
                    // 満タンに到達したら発動して、自分で isPressed を false にしてループを抜ける
                    onActivate()
                    isPressed = false
                    break
                }
                // 60fps 程度の更新。Compose 側はこの value 変更で再描画される。
                delay(16)
            }
        }
        // 押下解除またはキャンセル時、リングをリセット
        progress = 0f
    }

    Box(
        modifier = modifier
            // 触れる/長押しの判定領域はこの size。リング外周より十分大きく取ると押しやすい。
            .size(48.dp)
            .pointerInput(Unit) {
                // 1回の「押し始め → 離す」サイクルごとに awaitEachGesture が回る。
                awaitEachGesture {
                    // 指が触れるまで待つ
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    // 指が離れる or キャンセルされるまで待つ
                    waitForUpOrCancellation()
                    isPressed = false
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val dotRadius = 6.dp.toPx()       // 真ん中の小さい円
            val ringRadius = 18.dp.toPx()     // リングの半径
            val ringStroke = 2.5.dp.toPx()    // リングの線の太さ

            // 中央の控えめな白いドット。常に出ているがあまり主張しない透明度。
            drawCircle(
                color = Color(0x66FFFFFF),
                radius = dotRadius,
                center = center
            )

            // 押下中のみリングを描画。-90度開始 = 12時方向から時計回り。
            if (progress > 0f) {
                drawArc(
                    color = Color.White,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(
                        center.x - ringRadius,
                        center.y - ringRadius
                    ),
                    size = Size(ringRadius * 2, ringRadius * 2),
                    style = Stroke(width = ringStroke, cap = StrokeCap.Round)
                )
            }
        }
    }
}

/**
 * 端末を一定時間「他デバイスから発見可能(discoverable)」にするボタン。
 *
 * Android のセキュリティ仕様で discoverable 化はユーザー同意が必要なため、
 * ACTION_REQUEST_DISCOVERABLE をシステムに投げてダイアログを出してもらう方式を取る。
 *   - 許可されると指定秒数の間 PC 等から発見可能になる
 *   - 期限切れ後は新規ペアリングはできなくなるが、ペア済み端末との接続は維持される
 *
 * @param durationSec 発見可能にする秒数 (Android の上限は 3600)
 * @param modifier    配置調整用 Modifier
 */
@Composable
fun DiscoverableButton(
    durationSec: Int = 300,
    modifier: Modifier = Modifier
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_CANCELED) {
            AppLog.w("Discoverable がキャンセルされた")
        } else {
            AppLog.d("Discoverable 有効 (${result.resultCode} 秒間)")
        }
    }

    // ボタン表示も渡された秒数に応じて変える
    val label = if (durationSec >= 60) {
        "PC からのペアリングを許可（${durationSec / 60}分間）"
    } else {
        "PC からのペアリングを許可（${durationSec}秒間）"
    }
    Button(
        onClick = {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, durationSec)
            }
            AppLog.d("Discoverable 要求ダイアログを表示 (${durationSec}秒)")
            launcher.launch(intent)
        },
        modifier = modifier
    ) {
        Text(label)
    }
}

/**
 * ペア済みデバイスから接続先を選んで能動接続を要求するボタン。
 *
 * 通常は PC 側 (HID Host) が自動で接続してくるが、Windows がサボったり
 * セッションがズレたりして繋がらない時のための救済手段。
 *
 * ボタンを押すとペア済みデバイス一覧をダイアログ表示し、選んだデバイスに対して
 * [HidMouseManager.connectTo] を呼ぶ。
 *
 * @param manager  能動接続を実行する [HidMouseManager]
 * @param modifier 配置調整用 Modifier
 */
@Composable
fun ConnectButton(manager: HidMouseManager, modifier: Modifier = Modifier) {
    // ダイアログ表示フラグ。タップで true、ダイアログ閉じで false に戻す。
    var showPicker by remember { mutableStateOf(false) }

    Button(
        onClick = { showPicker = true },
        modifier = modifier
    ) {
        Text("PC に接続")
    }

    if (showPicker) {
        BondedDevicePickerDialog(
            manager = manager,
            onDismiss = { showPicker = false }
        )
    }
}

/**
 * ペア済みデバイス一覧を表示し、選択したデバイスへの接続を要求するダイアログ。
 *
 * 一覧は `manager.bondedDevices()` で取得する。ダイアログを開いた瞬間に取得し、
 * 開いている間は固定（表示中に勝手に変わると操作が紛らわしいため）。
 *
 * @param manager   選択結果を受けて接続を要求する [HidMouseManager]
 * @param onDismiss ダイアログを閉じる時に呼ばれるコールバック
 */
@SuppressLint("MissingPermission")  // device.name 取得に BLUETOOTH_CONNECT 必要だが Gate で保証済み
@Composable
fun BondedDevicePickerDialog(
    manager: HidMouseManager,
    onDismiss: () -> Unit
) {
    val devices: List<BluetoothDevice> = remember { manager.bondedDevices() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("接続先 PC を選択") },
        text = {
            if (devices.isEmpty()) {
                Text("ペア済みデバイスが見つかりません。\nまず「ペアリングを許可」から PC とペアしてください。")
            } else {
                Column {
                    devices.forEach { device ->
                        val label = device.name ?: device.address ?: "(不明)"
                        // TextButton はタップ可能なテキスト行として機能する。
                        TextButton(
                            onClick = {
                                manager.connectTo(device)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        }
    )
}

/**
 * デバッグ用に PC との接続を能動的に切るボタン。
 *
 * タップすると [HidMouseManager.disconnect] が走り、PC 側で切断扱いになる。
 * 自動切断検知（圏外・PC 側電源 OFF 等）のテストや、再接続フローを試す際に使う。
 *
 * @param manager  切断を実行する [HidMouseManager]
 * @param modifier 配置調整用 Modifier
 */
@Composable
fun DisconnectButton(manager: HidMouseManager, modifier: Modifier = Modifier) {
    Button(
        onClick = { manager.disconnect() },
        modifier = modifier
    ) {
        Text("切断（デバッグ用）")
    }
}

/**
 * HID マネージャの現在状態を1行テキストで表示するヘッダー。
 *
 * 「今この瞬間どの状態か」をパッと確認できる場所。
 * 詳しいイベント履歴は下の [LogOverlay] を見てもらう。
 *
 * @param state    表示する状態
 * @param modifier 配置調整用 Modifier
 */
@Composable
fun HidStatusOverlay(state: HidState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            // 黒っぽい背景でテキストを読みやすくする
            .background(Color(0xFF101010))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = state.toDisplayText(),
            color = Color.White
        )
    }
}

/**
 * [AppLog] のエントリを古い順に縦に並べて表示するスクロール可能オーバーレイ。
 *
 * - 新しいエントリが入ると自動で最下行までスクロール（後追いができる）
 * - レベル(D/W/E) ごとに色分け
 * - 等幅フォントでタイムスタンプを揃えて読みやすく
 *
 * @param logs     表示するログエントリの一覧（最新が末尾）
 * @param modifier 配置調整用 Modifier
 */
@Composable
fun LogOverlay(logs: List<LogEntry>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    // ログが増えるたびに最下行(=最新エントリ)へ自動スクロールする。
    // key を logs.size にしているので、行が追加されたタイミングだけ発火する。
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .background(Color(0xFF0A0A0A))
            .padding(8.dp)
    ) {
        items(logs) { entry ->
            // レベルごとに色分け：D=薄グレー / W=黄 / E=赤
            val color = when (entry.level) {
                'W' -> Color(0xFFFFC107)
                'E' -> Color(0xFFFF5252)
                else -> Color(0xFFCCCCCC)
            }
            Text(
                text = "${entry.timestamp} ${entry.level} ${entry.message}",
                color = color,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * [HidState] をユーザー向けの表示テキストに変換する。
 */
private fun HidState.toDisplayText(): String = when (this) {
    HidState.Initializing -> "初期化中..."
    HidState.BluetoothUnavailable -> "Bluetooth 非対応端末"
    HidState.BluetoothDisabled -> "Bluetooth が OFF — ON にしてください"
    HidState.ConnectingProfile -> "プロファイル接続中..."
    HidState.Registering -> "HID アプリ登録中..."
    HidState.Registered -> "PC からの接続を待機中"
    is HidState.Connected -> "接続中: $deviceLabel"
    is HidState.Error -> "エラー: $message"
}

/**
 * トラックパッドの操作面となる Composable。
 *
 * 画面上のタッチ位置を検出して、押されている指の位置に円を描画する。
 * 複数本の指を同時に検出するマルチタッチ対応。
 * 1本指でドラッグしたときの移動量は [onMove] でコールバック通知する（マウス相対移動量として使う想定）。
 * 短時間・小移動の単一指タッチを [onTap] でコールバック通知する（左クリックとして使う想定）。
 *
 * @param modifier          外側から渡されるレイアウト調整用 Modifier。
 * @param sensitivity       指の移動 1px を何 px のマウス移動に換算するかの倍率。
 *                          値が大きいほどカーソルが速く動く。1.5 で「指の動きより少し大きい」感覚。
 * @param scrollSensitivity スクロール感度倍率。1.0 で 30dp 移動 = 1 tick、2.0 で 15dp = 1 tick。
 * @param naturalScroll     true: 指の動きとコンテンツが同方向 (macOS 風)。
 *                          false: 指と逆方向 (Windows 標準トラックパッド)。
 * @param tapTimeoutMs        タップ判定の最大押下時間 (ms)
 * @param tapSlopDp           タップ判定の移動許容 (dp)
 * @param doubleTapIntervalMs ダブルタップ判定の最大間隔 (ms)
 * @param onMove      1本指ドラッグの移動量 (dx, dy) を通知するコールバック。
 *                    画面の左→右 を +dx、上→下 を +dy として渡す（PC マウスと同じ座標系）。
 *                    複数指タッチ中・指が触れた瞬間（前フレーム未押下）には呼ばれない。
 * @param onTap          1本指タップ（短時間・小移動）を検出した時に呼ばれるコールバック。
 *                       左クリック相当として使う想定。
 * @param onTwoFingerTap 2本指タップを検出した時に呼ばれるコールバック。
 *                       右クリック相当として使う想定。
 *                       2本の指が同時期に触れて、ほとんど動かず、短時間で離れた場合に発火。
 * @param onScroll       2本指ドラッグのスクロールを検出した時に呼ばれるコールバック。
 *                       引数は (縦tick, 横tick) で、縦は正値=上スクロール、横は正値=右スクロール。
 *                       マウスホイール / 横ホイール (AC Pan) の単位回転量に相当（PC 側で約3行/tick）。
 *                       縦のみ・横のみ・両軸同時のいずれも有り得る。
 * @param onDragStart    タップ → 短時間内に再タップ → そのままドラッグ移行 のジェスチャー検出時、
 *                       「左ボタン押下を維持」状態を開始する直前に呼ばれる。
 * @param onDrag         ドラッグモード中のカーソル移動量。[onMove] と排他で、ドラッグ中は onDrag のみ発火。
 * @param onDragEnd      ドラッグモードの指が離れて終了するときに呼ばれる。左ボタンを離す処理を入れる想定。
 * @param onPinchStart   2本指ピンチの開始時に呼ばれる。Ctrl 押下を送る想定。
 * @param onPinchZoom    ピンチ中の距離変化を tick 単位で通知 (正値=広げる=ズームイン)。Ctrl+wheel に変換する想定。
 * @param onPinchEnd     ピンチ終了時に呼ばれる。Ctrl リリースを送る想定。
 * @param onThreeFingerSwipeLeft  3本指で左方向へスワイプ後、指を離した時。「戻る」(Alt+←) を送る想定。
 * @param onThreeFingerSwipeRight 3本指で右方向へスワイプ後、指を離した時。「進む」(Alt+→) を送る想定。
 */
@Composable
fun TrackpadSurface(
    modifier: Modifier = Modifier,
    sensitivity: Float = 1.5f,
    scrollSensitivity: Float = 1.0f,
    naturalScroll: Boolean = true,
    inertialScroll: Boolean = true,
    inertialFriction: Float = 0.92f,
    inertialStopThreshold: Float = 0.05f,
    inertialFlickThreshold: Float = 0.3f,
    inertialStacking: Boolean = true,
    pinchZoom: Boolean = true,
    pinchSensitivity: Float = 1.0f,
    pinchSlopDp: Float = 15f,
    pinchLine: Boolean = true,
    threeFingerSwipe: Boolean = true,
    threeFingerSwipeThresholdDp: Float = 80f,
    threeFingerSwipeIndicator: Boolean = true,
    pointerDots: Boolean = true,
    tapTimeoutMs: Float = 250f,
    tapSlopDp: Float = 16f,
    doubleTapIntervalMs: Float = 300f,
    onMove: (dx: Int, dy: Int) -> Unit = { _, _ -> },
    onTap: () -> Unit = {},
    onTwoFingerTap: () -> Unit = {},
    onScroll: (vTicks: Int, hTicks: Int) -> Unit = { _, _ -> },
    onDragStart: () -> Unit = {},
    onDrag: (dx: Int, dy: Int) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    onPinchStart: () -> Unit = {},
    onPinchZoom: (ticks: Int) -> Unit = {},
    onPinchEnd: () -> Unit = {},
    onThreeFingerSwipeLeft: () -> Unit = {},
    onThreeFingerSwipeRight: () -> Unit = {}
) {
    // 現在押されている指の状態を保持する。
    //   キー  : PointerId.value（OS が各指に振る一意な ID。指が離れる→再度触ると新しい ID になる）
    //   値    : その指のタッチ位置（このコンポーザブル左上を (0,0) とする座標）
    // remember + mutableStateOf にすることで、値が変わるたびに自動で再描画される。
    var pointers by remember { mutableStateOf<Map<Long, Offset>>(emptyMap()) }

    // ピンチモード中フラグ。pointerInput 内で更新され、Canvas が読んで線を描画する。
    var pinchActive by remember { mutableStateOf(false) }

    // 3本指スワイプ予兆表示用。pointerInput 内で更新され、Canvas が読んで矢印を描画する。
    // direction: -1 = 左 (戻る)、0 = 表示なし、+1 = 右 (進む)
    // progress : 0.0 = 開始位置、1.0 = 閾値到達、それ以上も許容
    var threeFingerSwipeDirection by remember { mutableStateOf(0) }
    var threeFingerSwipeProgress by remember { mutableStateOf(0f) }

    // 慣性スクロール (フリック後の自動継続) を起動・キャンセルするためのスコープと Job 保持。
    // remember で Composable の生存期間にバインドされる。
    val inertiaScope = rememberCoroutineScope()
    val inertiaJobHolder = remember { object { var job: Job? = null } }
    // 慣性ループが毎フレーム書き込む現在速度。スタッキング判定時にここを読んで加算する。
    // FloatArray(2) = [vx, vy]。Composable の生存期間にバインド。
    val inertiaVel = remember { FloatArray(2) }

    Box(
        modifier = modifier
            // 操作面と分かるように暗めの背景を敷く
            .background(Color(0xFF1E1E1E))
            // pointerInput はタッチイベントを受け取るための Modifier。
            // 第一引数の key (= Unit) が変わると検出ループが再起動される。
            // 今回は常に同じループでよいので Unit を渡している。
            .pointerInput(Unit) {
                // 累積小数誤差。指がゆっくり動くと sensitivity を掛けても 1px 未満になり
                // toInt() で切り捨てられて全く動かないという問題が起きる。
                // 小数部分を residual に貯めて次フレームに繰り越すことで、
                // 遅い動きでも徐々に確実にカーソルが進むようにする。
                var residualX = 0f
                var residualY = 0f

                // ─── タップ→ドラッグ検出用の状態 ───
                // 「直前に単一指タップ完了 → 短時間内に再度単一指タッチ開始」というシーケンスの
                // 2回目のジェスチャーを "dragArmed" と記録する。dragArmed なジェスチャーで
                // 移動量が slop を超えたら、左ボタン押下状態のドラッグへ移行する。
                val doubleTapMaxIntervalMsLong = doubleTapIntervalMs.toLong()
                var previousTapEndTime = 0L        // 最後の単一指タップ完了時刻。0 は「まだない」
                var dragArmed = false              // 現在のジェスチャーがドラッグ候補か
                var inDragMode = false             // ドラッグ送信中か (左ボタン保持中)

                /**
                 * 与えられた delta (px) に sensitivity を掛け、整数化した上で onMove / onDrag に渡す。
                 * 小数部分はクロージャの residualX/Y にためて次回に繰り越す。
                 * historical サブイベントごとに呼べるように関数化。
                 * ドラッグモード中は onDrag (左ボタン保持中の移動) 側に流す。
                 */
                fun emitMove(dx: Float, dy: Float) {
                    val targetX = dx * sensitivity + residualX
                    val targetY = dy * sensitivity + residualY
                    val ix = targetX.toInt()
                    val iy = targetY.toInt()
                    residualX = targetX - ix
                    residualY = targetY - iy
                    if (ix != 0 || iy != 0) {
                        if (inDragMode) onDrag(ix, iy) else onMove(ix, iy)
                    }
                }

                // ─── タップ検出用の状態 ───
                // 「最初の指が触れてから、全ての指が離れるまで」を1つのジェスチャーとして扱う。
                // ジェスチャー終了時に、最大同時押下数・経過時間・移動量を見てタップ種別を判定する。
                //   - tapTimeoutMs 以内 & tapSlopPx 未満の移動 → タップ
                //   - 最大押下数 1 → 1本指タップ (左クリック想定)
                //   - 最大押下数 2 → 2本指タップ (右クリック想定)
                //   - 3本以上 → 今は何もしない
                val tapTimeoutMsLong = tapTimeoutMs.toLong()
                val tapSlopPx = tapSlopDp.dp.toPx()
                var gestureStartTime = 0L
                var gestureCancelled = false
                var maxConcurrentFingers = 0
                var gestureActive = false
                val fingerStartPositions = mutableMapOf<Long, Offset>()

                // ─── スクロール検出用の状態 ───
                // 2本指ドラッグで一度 slop を超えると "scrollCommitted" になり、それ以降の
                // 縦/横移動を tick に変換して onScroll に流す。各軸独立に残差を累積して、
                // 1 tick 分 (scrollPixelsPerTick) を超えたら発火。
                // scrollSensitivity 倍率で 1 tick あたりの dp を割って px に換算する。
                val scrollPixelsPerTick =
                    (ScrollSensitivityPref.BASE_DP_PER_TICK / scrollSensitivity).dp.toPx()
                var scrollCommitted = false
                var scrollResidualY = 0f                // 縦軸の累積残差
                var scrollResidualX = 0f                // 横軸の累積残差

                // ─── 慣性スクロール用の速度追跡 ───
                // 2本指スクロール中、各フレームの (dx, dy, dt) を末尾 5 件まで保持する。
                // ジェスチャー終了時に平均速度を求めて、閾値超えなら慣性ループを起動する。
                val velocitySamples = ArrayDeque<Triple<Float, Float, Long>>()  // dx, dy, dtMs
                val velocitySampleLimit = 5
                var lastScrollFrameTime = 0L

                // ─── フリック・スタッキング用の carryover ───
                // 新ジェスチャー開始時に、走っていた慣性の現在速度を退避しておく。
                // ジェスチャー終了時にフリック条件を満たしたら carryover + 新フリック速度で慣性起動。
                // 上限 MAX_INERTIA_SPEED で爆速化を防ぐ。
                var carryoverVx = 0f
                var carryoverVy = 0f

                // ─── ピンチズーム用の状態 ───
                // 2本指の距離が pinchSlop を超えて変化したらピンチモードに移行。
                // ピンチモード中は距離変化を tick に換算して onPinchZoom に流す。
                val pinchSlopPx = pinchSlopDp.dp.toPx()
                val pinchPixelsPerTick =
                    (PinchSensitivityPref.BASE_DP_PER_TICK / pinchSensitivity).dp.toPx()
                var pinchCommitted = false
                var pinchReferenceDistance = 0f  // 直前フレームの2本指間距離
                var pinchResidual = 0f           // tick 換算前の累積距離変化

                // ─── 3本指スワイプ用の状態 ───
                // 3本指が触れた瞬間の重心を記録、ジェスチャー終了時に重心の総移動量で
                // 左右スワイプを判定。
                val threeFingerSwipeThresholdPx = threeFingerSwipeThresholdDp.dp.toPx()
                var threeFingerActive = false
                var threeFingerStartX = 0f
                var threeFingerStartY = 0f
                var threeFingerEndX = 0f
                var threeFingerEndY = 0f

                // awaitPointerEventScope の中では「次のイベントが来るまで待つ」という
                // サスペンド関数 awaitPointerEvent() が使える。
                awaitPointerEventScope {
                    // イベントが届くたびに状態を更新し続ける無限ループ。
                    // コンポーザブルが破棄されるとこのコルーチン自体がキャンセルされて止まる。
                    while (true) {
                        val event = awaitPointerEvent()
                        // event.changes には「このフレームで変化があったすべての指」の情報が入っている。
                        // - pressed == true  : 今この瞬間に画面に触れている指
                        // - pressed == false : 直前まで触れていて、今離れた指
                        // 押下中のものだけ拾って Map に作り直すと、離した指は自動的に消える。
                        pointers = event.changes
                            .filter { it.pressed }
                            .associate { it.id.value to it.position }

                        // 現在押下中の指リスト。後段の判定で何度も使うので一度作る。
                        val pressed = event.changes.filter { it.pressed }

                        // ─── ジェスチャー状態の更新 ───
                        // 押下開始時にジェスチャーを始め、最大同時押下数を更新。
                        // 各指の移動量がしきい値を超えたら gesture をキャンセル状態にする。
                        event.changes.forEach { change ->
                            val isPressStart = change.pressed && !change.previousPressed
                            val isRelease = !change.pressed && change.previousPressed

                            if (isPressStart) {
                                if (!gestureActive) {
                                    // 最初の指が触れた。新しいジェスチャーを開始。
                                    gestureActive = true
                                    gestureStartTime = change.uptimeMillis
                                    gestureCancelled = false
                                    maxConcurrentFingers = 0
                                    fingerStartPositions.clear()
                                    // 慣性スクロール中なら、現在速度を carryover に退避してから停止する。
                                    // フリック・スタッキング ON で、かつジェスチャー終了時にフリック条件を
                                    // 満たした場合のみこの carryover が新慣性に加算される。
                                    if (inertialStacking && inertiaJobHolder.job?.isActive == true) {
                                        carryoverVx = inertiaVel[0]
                                        carryoverVy = inertiaVel[1]
                                    } else {
                                        carryoverVx = 0f
                                        carryoverVy = 0f
                                    }
                                    inertiaJobHolder.job?.cancel()
                                    inertiaJobHolder.job = null
                                    inertiaVel[0] = 0f
                                    inertiaVel[1] = 0f
                                    // 速度サンプルもリセット
                                    velocitySamples.clear()
                                    lastScrollFrameTime = 0L
                                    // ─── ドラッグ判定の "腕利き" 設定 ───
                                    // 直前のタップ完了から短時間以内であれば、このジェスチャーは
                                    // ドラッグ移行の候補 (dragArmed)。一度使った "腕" は消費して
                                    // 同じ previousTapEndTime で複数ジェスチャーが乗らないようにする。
                                    val sinceLastTap = change.uptimeMillis - previousTapEndTime
                                    dragArmed = previousTapEndTime > 0L &&
                                            sinceLastTap < doubleTapMaxIntervalMsLong
                                    if (dragArmed) {
                                        previousTapEndTime = 0L
                                    }
                                }
                                fingerStartPositions[change.id.value] = change.position
                                // 今この瞬間の同時押下数で最大値を更新
                                maxConcurrentFingers = maxOf(maxConcurrentFingers, pressed.size)
                            }

                            if (isRelease && gestureActive) {
                                // この指の総移動量がしきい値を超えていたらジェスチャー全体をキャンセル
                                val startPos = fingerStartPositions[change.id.value]
                                if (startPos != null) {
                                    val distance = (change.position - startPos).getDistance()
                                    if (distance >= tapSlopPx) {
                                        gestureCancelled = true
                                    }
                                }
                            }
                        }

                        // ─── ドラッグモード移行チェック ───
                        // dragArmed な単一指ジェスチャーで、押下開始からの移動が slop を超えたら
                        // 左ボタン押下状態のドラッグへ移行する。同時に gestureCancelled を立てて
                        // ジェスチャー終了時のタップ発火を抑制する。
                        if (dragArmed && !inDragMode &&
                            pressed.size == 1 && maxConcurrentFingers == 1) {
                            val change = pressed.first()
                            val startPos = fingerStartPositions[change.id.value]
                            if (startPos != null) {
                                val movedDist = (change.position - startPos).getDistance()
                                if (movedDist > tapSlopPx) {
                                    inDragMode = true
                                    gestureCancelled = true
                                    AppLog.d("ドラッグモード開始")
                                    onDragStart()
                                }
                            }
                        }

                        // ─── マウス移動量の計算 ───
                        // 「1本指だけが触れている」かつ「今回のジェスチャーは終始1本指のみ」の時だけマウス移動扱いとする。
                        // maxConcurrentFingers <= 1 のチェックで、2本指タップ中に1本だけ離れる瞬間に
                        // 残りの1本でカーソルが動かないようにする。
                        //
                        // 滑らかさ改善のため、フレーム間の中間位置（PointerInputChange.historical）も
                        // 拾って各セグメントごとに sendReport を発行する。
                        // タッチセンサーは多くの端末で 120〜240Hz、Compose のイベントは 60〜120Hz なので、
                        // historical を捨てると本来の半分以下の解像度でしか送信できない。
                        //
                        // また dragArmed && !inDragMode (= タップ→再タッチ直後でまだドラッグ開始前) の
                        // 期間は移動を一切送らない。slop までの "助走" でカーソルが動いてしまうと、
                        // ドラッグ開始位置がズレて感じられるため。
                        when {
                            pressed.size == 1 && maxConcurrentFingers <= 1 -> {
                                val change = pressed.first()
                                if (change.previousPressed && !(dragArmed && !inDragMode)) {
                                    // previousPosition を基点にして、historical(時間順) → 現在 の順に
                                    // 各サブイベント区間の差分を順番に送る。
                                    var prevX = change.previousPosition.x
                                    var prevY = change.previousPosition.y
                                    for (h in change.historical) {
                                        emitMove(
                                            dx = h.position.x - prevX,
                                            dy = h.position.y - prevY
                                        )
                                        prevX = h.position.x
                                        prevY = h.position.y
                                    }
                                    // 最後に現在位置までの残りの差分
                                    emitMove(
                                        dx = change.position.x - prevX,
                                        dy = change.position.y - prevY
                                    )
                                }
                            }
                            pressed.isEmpty() -> {
                                // 全指離した瞬間。次のドラッグ開始時に古い誤差を引きずらないよう
                                // 累積をリセットする。
                                residualX = 0f
                                residualY = 0f
                            }
                            // それ以外（マルチタッチ中など）はマウス移動として扱わない
                        }

                        // ─── 3本指スワイプの重心トラッキング ───
                        // pressed.size == 3 になった瞬間の重心を記録し、3本指の間は重心を更新し続ける。
                        // ジェスチャー終了時に「総移動量」を見て左右スワイプを判定する。
                        // 同時に Canvas 用の予兆表示状態 (direction / progress) を更新。
                        if (pressed.size == 3) {
                            val cx =
                                (pressed[0].position.x + pressed[1].position.x + pressed[2].position.x) / 3f
                            val cy =
                                (pressed[0].position.y + pressed[1].position.y + pressed[2].position.y) / 3f
                            if (!threeFingerActive) {
                                threeFingerActive = true
                                threeFingerStartX = cx
                                threeFingerStartY = cy
                            }
                            threeFingerEndX = cx
                            threeFingerEndY = cy

                            // 予兆表示の direction / progress を更新
                            if (threeFingerSwipe) {
                                val dx = threeFingerEndX - threeFingerStartX
                                val dy = threeFingerEndY - threeFingerStartY
                                val absDx = kotlin.math.abs(dx)
                                val absDy = kotlin.math.abs(dy)
                                // 横移動が縦より支配的なときだけ矢印を出す
                                if (absDx > absDy * 1.5f) {
                                    threeFingerSwipeDirection = if (dx > 0) 1 else -1
                                    threeFingerSwipeProgress =
                                        absDx / threeFingerSwipeThresholdPx
                                } else {
                                    threeFingerSwipeDirection = 0
                                    threeFingerSwipeProgress = 0f
                                }
                            }
                        }

                        // ─── 2本指ジェスチャー検出 (ピンチ or スクロール) ───
                        // 2本指が触れている時、まずピンチかスクロールかを判別する。
                        // ピンチ: 2本指間の距離が slop を超えて変化 (かつ単一指移動より支配的)
                        // スクロール: いずれかの指が slop を超えて移動 (距離変化は支配的でない)
                        // 一度確定したらジェスチャー終了までモードを維持する。
                        if (pressed.size == 2) {
                            val c1 = pressed[0]
                            val c2 = pressed[1]
                            val s1 = fingerStartPositions[c1.id.value]
                            val s2 = fingerStartPositions[c2.id.value]

                            // maxConcurrentFingers > 2 = 過去に3本以上触れた → 2本指モードは抑止
                            // (3本指スワイプ用に予約。途中で 3→2 になっても scroll/pinch に流れ込まない)
                            if (s1 != null && s2 != null && !scrollCommitted && !pinchCommitted &&
                                maxConcurrentFingers <= 2) {
                                // 距離変化 (ピンチ判定用)
                                val initialDist = (s1 - s2).getDistance()
                                val currentDist = (c1.position - c2.position).getDistance()
                                val pinchChange = kotlin.math.abs(currentDist - initialDist)
                                // 個別指移動 (スクロール判定用)
                                val d1 = (c1.position - s1).getDistance()
                                val d2 = (c2.position - s2).getDistance()
                                val maxFingerMove = kotlin.math.max(d1, d2)

                                // ピンチ優先: 距離変化が pinchSlop 超え かつ
                                // 単一指移動より距離変化が「支配的」(50%以上を占める)
                                if (pinchZoom && pinchChange > pinchSlopPx &&
                                    pinchChange > maxFingerMove * 0.5f) {
                                    pinchCommitted = true
                                    pinchActive = true  // Canvas が線を描画開始
                                    gestureCancelled = true
                                    pinchReferenceDistance = currentDist
                                    pinchResidual = 0f
                                    AppLog.d("ピンチモード開始 initialDist=${initialDist.toInt()} currentDist=${currentDist.toInt()}")
                                    onPinchStart()
                                } else if (d1 > tapSlopPx || d2 > tapSlopPx) {
                                    scrollCommitted = true
                                    gestureCancelled = true
                                    AppLog.d("スクロールモード開始")
                                }
                            }
                            if (scrollCommitted) {
                                val c1 = pressed[0]
                                val c2 = pressed[1]
                                if (c1.previousPressed && c2.previousPressed) {
                                    // フレーム全体での速度サンプルを記録（慣性スクロール用）。
                                    // historical を巻き込まない総デルタで十分。
                                    val frameAvgDx =
                                        ((c1.position.x - c1.previousPosition.x) +
                                                (c2.position.x - c2.previousPosition.x)) / 2f
                                    val frameAvgDy =
                                        ((c1.position.y - c1.previousPosition.y) +
                                                (c2.position.y - c2.previousPosition.y)) / 2f
                                    val nowMs = c1.uptimeMillis
                                    val frameDt =
                                        if (lastScrollFrameTime == 0L) 16L
                                        else (nowMs - lastScrollFrameTime).coerceAtLeast(1L)
                                    velocitySamples.addLast(Triple(frameAvgDx, frameAvgDy, frameDt))
                                    while (velocitySamples.size > velocitySampleLimit) {
                                        velocitySamples.removeFirst()
                                    }
                                    lastScrollFrameTime = nowMs

                                    // historical を含めてサブフレーム区間ごとに2本指の平均デルタを計算する。
                                    // 2本の指の historical 数が一致しない場合があるので、短い方に合わせる。
                                    val hist1 = c1.historical
                                    val hist2 = c2.historical
                                    val commonHist = minOf(hist1.size, hist2.size)

                                    var prev1X = c1.previousPosition.x
                                    var prev1Y = c1.previousPosition.y
                                    var prev2X = c2.previousPosition.x
                                    var prev2Y = c2.previousPosition.y

                                    /**
                                     * 与えられた2本指の今回位置から、前回からの差分を計算してスクロール量に加算 & 発火。
                                     * 方向は naturalScroll に従う：
                                     *   - ナチュラル (true): 縦は avgDy と同符号、横は avgDx と逆符号 (macOS 風)
                                     *   - 反転 (false)     : 縦は avgDy と逆符号、横は avgDx と同符号 (Windows 標準)
                                     * dirSign で一括反転。
                                     */
                                    val dirSign = if (naturalScroll) 1f else -1f
                                    fun stepScroll(p1x: Float, p1y: Float, p2x: Float, p2y: Float) {
                                        val avgDx = ((p1x - prev1X) + (p2x - prev2X)) / 2f
                                        val avgDy = ((p1y - prev1Y) + (p2y - prev2Y)) / 2f
                                        scrollResidualY += dirSign * avgDy
                                        scrollResidualX -= dirSign * avgDx
                                        val vTicks = (scrollResidualY / scrollPixelsPerTick).toInt()
                                        val hTicks = (scrollResidualX / scrollPixelsPerTick).toInt()
                                        if (vTicks != 0) scrollResidualY -= vTicks * scrollPixelsPerTick
                                        if (hTicks != 0) scrollResidualX -= hTicks * scrollPixelsPerTick
                                        if (vTicks != 0 || hTicks != 0) {
                                            onScroll(vTicks, hTicks)
                                        }
                                        prev1X = p1x; prev1Y = p1y
                                        prev2X = p2x; prev2Y = p2y
                                    }

                                    // historical をペアで進める (短い方の長さまで)
                                    for (i in 0 until commonHist) {
                                        stepScroll(
                                            hist1[i].position.x, hist1[i].position.y,
                                            hist2[i].position.x, hist2[i].position.y
                                        )
                                    }
                                    // 最後に現在位置まで残り区間を1ステップで送る
                                    stepScroll(
                                        c1.position.x, c1.position.y,
                                        c2.position.x, c2.position.y
                                    )
                                }
                            }
                            // ─── ピンチズーム中の距離変化を tick 化 ───
                            if (pinchCommitted && c1.previousPressed && c2.previousPressed) {
                                val currentDist = (c1.position - c2.position).getDistance()
                                val delta = currentDist - pinchReferenceDistance
                                pinchResidual += delta
                                val ticks = (pinchResidual / pinchPixelsPerTick).toInt()
                                if (ticks != 0) {
                                    pinchResidual -= ticks * pinchPixelsPerTick
                                    // 正値 = 指を広げた = ズームイン
                                    // Ctrl 押下中なので wheel ticks は zoom として解釈される
                                    onPinchZoom(ticks)
                                }
                                pinchReferenceDistance = currentDist
                            }
                        }

                        // ─── タップ判定 & ドラッグ終了（ジェスチャー終了時） ───
                        // 全ての指が離れた瞬間に、最大同時押下数と経過時間でタップ種別を決める。
                        if (gestureActive && pressed.isEmpty()) {
                            // event.changes は離れた指の change を含む。uptimeMillis は同一フレーム内なら同じなので
                            // 先頭の change を使えば終了時刻として十分。
                            val endTime = event.changes.firstOrNull()?.uptimeMillis ?: gestureStartTime
                            val duration = endTime - gestureStartTime

                            // ドラッグ中だった場合は左ボタンを離す。タップ発火より先にやる。
                            if (inDragMode) {
                                AppLog.d("ドラッグモード終了")
                                onDragEnd()
                                inDragMode = false
                            }
                            // ピンチ中だった場合は Ctrl をリリース
                            if (pinchCommitted) {
                                AppLog.d("ピンチモード終了")
                                onPinchEnd()
                            }

                            // ─── 3本指スワイプ判定 ───
                            // 3本指が同時に触れた経緯があり、設定が ON、最大同時押下数 == 3 (4本以上なら無効) の時、
                            // 重心の総移動量を見て左右スワイプを判定。横移動が縦より十分支配的なら発火。
                            if (threeFingerSwipe && threeFingerActive && maxConcurrentFingers == 3) {
                                val totalDx = threeFingerEndX - threeFingerStartX
                                val totalDy = threeFingerEndY - threeFingerStartY
                                val absDx = kotlin.math.abs(totalDx)
                                val absDy = kotlin.math.abs(totalDy)
                                if (absDx > threeFingerSwipeThresholdPx && absDx > absDy * 1.5f) {
                                    if (totalDx > 0) {
                                        AppLog.d("3本指スワイプ: 右 → 進む")
                                        onThreeFingerSwipeRight()
                                    } else {
                                        AppLog.d("3本指スワイプ: 左 → 戻る")
                                        onThreeFingerSwipeLeft()
                                    }
                                }
                            }

                            if (!gestureCancelled && duration < tapTimeoutMsLong) {
                                when (maxConcurrentFingers) {
                                    1 -> {
                                        AppLog.d("1本指タップ検出: duration=${duration}ms")
                                        onTap()
                                        // 次のジェスチャーが dragArmed になれるよう、タップ完了時刻を記録
                                        previousTapEndTime = endTime
                                    }
                                    2 -> {
                                        AppLog.d("2本指タップ検出: duration=${duration}ms")
                                        onTwoFingerTap()
                                    }
                                    // 3本以上は今は何もしない
                                }
                            }
                            // ─── 慣性スクロールの発火判定 ───
                            // 直前まで scrollCommitted で動いていて、サンプルがあり、
                            // 設定で慣性 ON、平均速度が閾値超え、なら慣性ループ起動。
                            if (inertialScroll && scrollCommitted && velocitySamples.isNotEmpty()) {
                                // サンプルの平均速度 (px/ms)
                                val totalDt = velocitySamples.sumOf { it.third }
                                val totalDx = velocitySamples.sumOf { it.first.toDouble() }
                                val totalDy = velocitySamples.sumOf { it.second.toDouble() }
                                val vx = if (totalDt > 0) (totalDx / totalDt).toFloat() else 0f
                                val vy = if (totalDt > 0) (totalDy / totalDt).toFloat() else 0f
                                val speed = sqrt(vx * vx + vy * vy)
                                if (speed > inertialFlickThreshold) {
                                    val dirSign = if (naturalScroll) 1f else -1f
                                    // carryover (前回慣性の速度) を加算。上限でクランプ。
                                    val combinedVx =
                                        (carryoverVx + vx).coerceIn(-MAX_INERTIA_SPEED, MAX_INERTIA_SPEED)
                                    val combinedVy =
                                        (carryoverVy + vy).coerceIn(-MAX_INERTIA_SPEED, MAX_INERTIA_SPEED)
                                    inertiaJobHolder.job?.cancel()
                                    inertiaJobHolder.job = inertiaScope.launch {
                                        runInertialScroll(
                                            initialVx = combinedVx,
                                            initialVy = combinedVy,
                                            pixelsPerTick = scrollPixelsPerTick,
                                            dirSign = dirSign,
                                            friction = inertialFriction,
                                            stopThreshold = inertialStopThreshold,
                                            velocityOut = inertiaVel,
                                            onScroll = onScroll
                                        )
                                    }
                                }
                            }

                            // ジェスチャー完了 → 状態をクリアして次のジェスチャーを待つ
                            gestureActive = false
                            fingerStartPositions.clear()
                            scrollCommitted = false
                            scrollResidualY = 0f
                            scrollResidualX = 0f
                            dragArmed = false
                            velocitySamples.clear()
                            lastScrollFrameTime = 0L
                            // carryover はジェスチャー単位で使い切り。次のジェスチャー開始時に
                            // 改めて慣性中なら退避し直されるので、ここで 0 にしておく。
                            carryoverVx = 0f
                            carryoverVy = 0f
                            // ピンチ状態のリセット
                            pinchCommitted = false
                            pinchActive = false  // 線を消す
                            pinchReferenceDistance = 0f
                            pinchResidual = 0f
                            // 3本指トラッキング状態のリセット
                            threeFingerActive = false
                            threeFingerStartX = 0f
                            threeFingerStartY = 0f
                            threeFingerEndX = 0f
                            threeFingerEndY = 0f
                            // 予兆表示も消す
                            threeFingerSwipeDirection = 0
                            threeFingerSwipeProgress = 0f
                        }
                    }
                }
            }
    ) {
        // Canvas は描画専用のコンポーザブル。pointers が更新されると自動で再描画される。
        Canvas(modifier = Modifier.fillMaxSize()) {
            // ─── 下部インジケーター ───
            // 端末が回転しても「どっちが下か」が分かるように、画面下端付近に
            // 横一直線のバーを描く。Compose のレイアウトは回転に追従するので、
            // この描画は常に「視覚的な下側」に出る。
            val bottomInset = 18.dp.toPx()      // 画面下端からのオフセット
            val sideInset = 48.dp.toPx()        // 左右端からのオフセット（中央寄せの見た目）
            val lineY = size.height - bottomInset
            drawLine(
                color = Color(0xFFAAAAAA),      // 控えめなグレーで主張しすぎない
                start = Offset(sideInset, lineY),
                end = Offset(size.width - sideInset, lineY),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round           // 線の両端を丸めて柔らかい印象に
            )

            // ─── ピンチ中の2点間の線と目盛り ───
            // ピンチ確定中で、設定で有効、かつ実際に 2 本指が押されているときだけ描く。
            // 円より先に描いて、円が線の上に重なる見た目にする。
            // 目盛りは「各指が次の 1 zoom step までどれだけ動けばよいか」を可視化：
            //   pinchPxPerTick = 2本指距離が 1 tick 進むまでに必要な変化量
            //   対称に伸縮するなら各指が pinchPxPerTick/2 動けば 1 tick → 中心から半分間隔で印
            if (pinchLine && pinchActive && pointers.size == 2) {
                val pts = pointers.values.toList()
                val p1 = pts[0]
                val p2 = pts[1]
                val dx = p2.x - p1.x
                val dy = p2.y - p1.y
                val lineLength = kotlin.math.sqrt(dx * dx + dy * dy)

                if (lineLength > 0f) {
                    val color = Color(0xFFAAAAAA)  // 下部インジケーター線と同じ落ち着いたグレー

                    // 線本体
                    drawLine(
                        color = color,
                        start = p1,
                        end = p2,
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )

                    // 単位ベクトル (p1 → p2) と垂直ベクトル (目盛りの向き)
                    val unitX = dx / lineLength
                    val unitY = dy / lineLength
                    val perpX = -unitY
                    val perpY = unitX
                    val midX = (p1.x + p2.x) / 2f
                    val midY = (p1.y + p2.y) / 2f

                    // 目盛り間隔 = pinchPxPerTick (= 2本指距離が 1 tick 進むのに必要な変化量)。
                    // 「1 目盛り通過 = 1 zoom step に値する距離変化」と読める。
                    val pinchPxPerTick =
                        (PinchSensitivityPref.BASE_DP_PER_TICK / pinchSensitivity).dp.toPx()
                    val markInterval = pinchPxPerTick
                    val tickHalfLength = 6.dp.toPx()
                    val tickStroke = 2.dp.toPx()
                    // 線の中央から半分の長さに収まる目盛り数
                    val maxMarks = (lineLength / 2f / markInterval).toInt()

                    // 中心の目盛り（基準点）
                    drawLine(
                        color = color,
                        start = Offset(midX + perpX * tickHalfLength, midY + perpY * tickHalfLength),
                        end = Offset(midX - perpX * tickHalfLength, midY - perpY * tickHalfLength),
                        strokeWidth = tickStroke
                    )

                    for (k in 1..maxMarks) {
                        val dist = k * markInterval
                        // 中心から +方向 (p2 寄り) の目盛り
                        val cx1 = midX + unitX * dist
                        val cy1 = midY + unitY * dist
                        drawLine(
                            color = color,
                            start = Offset(cx1 + perpX * tickHalfLength, cy1 + perpY * tickHalfLength),
                            end = Offset(cx1 - perpX * tickHalfLength, cy1 - perpY * tickHalfLength),
                            strokeWidth = tickStroke
                        )
                        // 中心から -方向 (p1 寄り) の目盛り
                        val cx2 = midX - unitX * dist
                        val cy2 = midY - unitY * dist
                        drawLine(
                            color = color,
                            start = Offset(cx2 + perpX * tickHalfLength, cy2 + perpY * tickHalfLength),
                            end = Offset(cx2 - perpX * tickHalfLength, cy2 - perpY * tickHalfLength),
                            strokeWidth = tickStroke
                        )
                    }
                }
            }

            // ─── 3本指スワイプの予兆 (カーテン + 中央三角) ───
            // スワイプ方向の端から薄いグレーのカーテンが侵入する。
            // 幅 = 進捗 (画面幅 × progress)。閾値到達で不透明度が上がって「届いた」を示す。
            // 中央には背景色と同じダーク三角を描く。カーテンに覆われると「ダーク三角がグレーから抜けた」
            // 形で現れて、進捗が直感的に分かる。
            // threeFingerSwipeIndicator が OFF の場合はカーテン・三角ともに描画しない。
            if (threeFingerSwipeIndicator && threeFingerSwipeDirection != 0) {
                val progressClamped = threeFingerSwipeProgress.coerceIn(0f, 1f)
                val direction = threeFingerSwipeDirection
                val w = size.width
                val h = size.height

                // カーテンの不透明度: 閾値前は控えめ、閾値到達で 100% に跳ね上がる
                val curtainAlpha = if (threeFingerSwipeProgress >= 1f) 1f else 0.45f
                val curtainColor = Color(0xFFAAAAAA).copy(alpha = curtainAlpha)
                val curtainWidth = w * progressClamped

                if (direction > 0) {
                    // 右スワイプ → 左端から右へ伸びる (指の動きを追うカーテン)
                    drawRect(
                        color = curtainColor,
                        topLeft = Offset(0f, 0f),
                        size = Size(curtainWidth, h)
                    )
                } else {
                    // 左スワイプ → 右端から左へ伸びる
                    drawRect(
                        color = curtainColor,
                        topLeft = Offset(w - curtainWidth, 0f),
                        size = Size(curtainWidth, h)
                    )
                }

                // 中央の三角 (スワイプ方向を指す)。色はトラックパッド背景と同色。
                // 閾値到達時のみ表示。進捗途中で半分だけ出る半端な見え方を避けて、
                // 「届いた瞬間にバンと出る」確定的な表示にする。
                if (threeFingerSwipeProgress >= 1f) {
                    val triangleWidth = 80.dp.toPx()
                    val triangleHeight = 64.dp.toPx()
                    val cx = w / 2f
                    val cy = h / 2f
                    val trianglePath = Path().apply {
                        if (direction > 0) {
                            // ▶ 右向き
                            moveTo(cx - triangleWidth / 2f, cy - triangleHeight / 2f)
                            lineTo(cx + triangleWidth / 2f, cy)
                            lineTo(cx - triangleWidth / 2f, cy + triangleHeight / 2f)
                        } else {
                            // ◀ 左向き
                            moveTo(cx + triangleWidth / 2f, cy - triangleHeight / 2f)
                            lineTo(cx - triangleWidth / 2f, cy)
                            lineTo(cx + triangleWidth / 2f, cy + triangleHeight / 2f)
                        }
                        close()
                    }
                    drawPath(trianglePath, color = Color(0xFF1E1E1E))
                }
            }

            // ─── タッチポインタ ───
            // pointerDots が OFF の場合は描画しない。
            if (pointerDots) {
                pointers.forEach { (id, p) ->
                    drawCircle(
                        // 指(ID)ごとに色を変えると、どの指がどこにあるか視覚的に区別できる
                        color = pointerColor(id),
                        radius = 30f,    // 円の半径（ピクセル単位）
                        center = p       // 円の中心 = タッチ位置
                    )
                }
            }
        }
    }
}

/**
 * フリック・スタッキングで加算した結果の速度上限 (px/ms)。
 * これを超えるとカーソルが爆速で飛んでいくので clamp する。
 */
private const val MAX_INERTIA_SPEED = 5f

/**
 * 慣性スクロールループ。指が離れた瞬間の速度ベクトル (px/ms) を受け取り、
 * 摩擦で減衰させながら毎フレーム scroll tick を [onScroll] に流す。
 *
 * 毎フレーム現在速度を [velocityOut] に書き出すので、外側から「いま走っている慣性速度」が
 * 観測できる（フリック・スタッキングで前回速度の加算に使う）。
 *
 * - 16ms 毎に1ステップ (~60fps)
 * - 速度が [stopThreshold] を下回ったらループ終了
 * - 呼び出し側で Job をキャンセルすれば即座に停止できる
 *
 * @param initialVx     初期 X 速度 (px/ms)
 * @param initialVy     初期 Y 速度 (px/ms)
 * @param pixelsPerTick scroll の 1 tick あたりの px
 * @param dirSign       方向反転用係数 (ナチュラル時 +1、反転時 -1)。
 *                      ライブ scroll と同じ符号運用にする (縦は dirSign を掛ける、横は -dirSign)。
 * @param friction      フレーム毎の速度乗数 (0.80〜0.99)。小さいほど早く止まる。
 * @param stopThreshold 速度がこれを下回ると停止 (px/ms)。
 * @param velocityOut   サイズ 2 の Float 配列。毎フレーム [0]=vx, [1]=vy を書き込む。
 *                      呼び出し側でスタッキング用の現在速度として読まれる。
 * @param onScroll      tick を発火するコールバック (vTicks, hTicks)
 */
private suspend fun runInertialScroll(
    initialVx: Float,
    initialVy: Float,
    pixelsPerTick: Float,
    dirSign: Float,
    friction: Float,
    stopThreshold: Float,
    velocityOut: FloatArray,
    onScroll: (vTicks: Int, hTicks: Int) -> Unit
) {
    val frameMs = 16L

    var vx = initialVx
    var vy = initialVy
    var residualX = 0f
    var residualY = 0f

    AppLog.d(
        "慣性スクロール開始 vx=${"%.2f".format(vx)} vy=${"%.2f".format(vy)} px/ms"
    )

    while (true) {
        // 現在速度を外部に公開（スタッキングで読まれる）
        velocityOut[0] = vx
        velocityOut[1] = vy

        // このフレームでの移動量 (px)
        val dx = vx * frameMs
        val dy = vy * frameMs

        // ライブ scroll と同じ符号ルール
        residualY += dirSign * dy
        residualX -= dirSign * dx

        val vTicks = (residualY / pixelsPerTick).toInt()
        val hTicks = (residualX / pixelsPerTick).toInt()
        if (vTicks != 0) residualY -= vTicks * pixelsPerTick
        if (hTicks != 0) residualX -= hTicks * pixelsPerTick
        if (vTicks != 0 || hTicks != 0) {
            onScroll(vTicks, hTicks)
        }

        // 速度を減衰
        vx *= friction
        vy *= friction

        // 停止判定
        if (abs(vx) < stopThreshold && abs(vy) < stopThreshold) {
            AppLog.d("慣性スクロール終了")
            // 終了時はゼロを書き込んで外部に「もう走ってない」と伝える
            velocityOut[0] = 0f
            velocityOut[1] = 0f
            break
        }

        delay(frameMs)
    }
}

/**
 * 指ごとに割り当てる色の候補。
 * 同時に5本までは確実に別色になる。それ以上はローテーションで色が被る。
 */
private val pointerPalette = listOf(
    Color.White,
    Color(0xFF4FC3F7), // 水色
    Color(0xFFFF8A65), // オレンジ
    Color(0xFF81C784), // 緑
    Color(0xFFBA68C8), // 紫
)

/**
 * PointerId からパレット上の色を1つ選ぶ。
 * id をパレットサイズで割った余りをインデックスにすることで、
 * 同じ指が触れている間は色がちらつかないようにしている。
 * 万が一 id が負の値でも安全にインデックスへ収まるよう補正している。
 */
private fun pointerColor(id: Long): Color {
    val size = pointerPalette.size
    val index = ((id % size).toInt() + size) % size
    return pointerPalette[index]
}
