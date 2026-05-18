package com.example.virtualtrackpad

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Bluetooth HID として動作するために必要なランタイムパーミッション。
 *
 * minSdk = 31 (Android 12) 以上前提のため、API 31 で導入された新方式のみを扱う：
 *   - BLUETOOTH_CONNECT  : ペアリング済みデバイスとの接続・通信に必須
 *   - BLUETOOTH_ADVERTISE: 自端末を Bluetooth で検出可能(discoverable)にするのに必須
 *
 * BLUETOOTH_SCAN は「相手を探す側(セントラル)」用なので、本アプリ(ペリフェラル)では不要。
 */
val REQUIRED_BLUETOOTH_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.BLUETOOTH_ADVERTISE,
)

/**
 * 必要な Bluetooth パーミッションがすべて付与済みかチェックする。
 *
 * @param context パーミッション状態を問い合わせる Context
 * @return すべて許可されていれば true
 */
fun hasBluetoothPermissions(context: Context): Boolean =
    REQUIRED_BLUETOOTH_PERMISSIONS.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

/**
 * Bluetooth パーミッションの取得状況に応じて表示を切り替えるゲート Composable。
 *
 * - 既に取得済み: そのまま [content] を表示する
 * - 未取得     : 初回コンポジション時に自動でパーミッション要求ダイアログを出す
 * - 拒否された : 説明画面と「許可する」ボタンを表示して再要求できるようにする
 *
 * 注: ユーザーが「次回から表示しない」で拒否した場合、ダイアログ自体が出なくなる。
 *     その場合は端末の設定画面から手動で許可してもらう必要がある(Phase A では未対応)。
 *
 * @param content 権限が揃った後に表示する本体 UI
 */
@Composable
fun BluetoothPermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current

    // 初期状態は現在の権限付与状況で決まる。
    // remember の初期ラムダはコンポジション中に1回だけ呼ばれるので、起動時に1回チェックされる。
    var granted by remember { mutableStateOf(hasBluetoothPermissions(context)) }

    // ランタイムパーミッション要求のためのランチャー。
    // RequestMultiplePermissions は複数の権限をまとめて要求でき、
    // 結果は (パーミッション名 -> 許可されたか) の Map で返ってくる。
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // 全部許可された場合のみ granted を true にする。
        // 一つでも拒否されればゲートは閉じたまま。
        granted = results.values.all { it }
    }

    // 初回コンポジション時に未取得なら自動で要求を投げる。
    // LaunchedEffect(Unit) は「このコンポーザブルが最初にコンポジションされた時に1回だけ」
    // 中身のコルーチンを起動する。キー(Unit)が変わらない限り再実行されない。
    LaunchedEffect(Unit) {
        if (!granted) {
            launcher.launch(REQUIRED_BLUETOOTH_PERMISSIONS)
        }
    }

    if (granted) {
        content()
    } else {
        PermissionRequestScreen(
            onRequestClick = { launcher.launch(REQUIRED_BLUETOOTH_PERMISSIONS) }
        )
    }
}

/**
 * 権限が未取得のときに表示する説明画面。
 * 中央に説明テキストと「許可する」ボタンを置く。
 *
 * @param onRequestClick 「許可する」ボタンが押された時のコールバック
 */
@Composable
private fun PermissionRequestScreen(onRequestClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))  // トラックパッド面と同色で雰囲気を揃える
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Bluetooth の権限が必要です",
                color = Color.White
            )
            Text(
                text = "PC へ Bluetooth マウスとして接続するために、\n" +
                        "Bluetooth の接続と発信の許可をお願いします。",
                color = Color(0xFFCCCCCC),
                textAlign = TextAlign.Center
            )
            Button(onClick = onRequestClick) {
                Text("許可する")
            }
        }
    }
}
