# VirtualTrackpad

**Android スマホを PC の Bluetooth トラックパッドに。**

ペアリングするだけで、お手元の Android 端末が macOS / Windows の Magic Trackpad ライクな操作面になります。
1〜3 本指のジェスチャーで、カーソル操作・スクロール・ピンチズーム・進む/戻るをワイヤレスで送信できます。

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![minSdk](https://img.shields.io/badge/minSdk-31%20%28Android%2012%29-brightgreen.svg)](#動作環境)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF.svg)](https://developer.android.com/jetpack/compose)
[![Release](https://img.shields.io/badge/release-v1.0-orange.svg)](https://github.com/ishino-kenta/virtual-trackpad-android/releases/tag/v1.0)

---

## 概要

Android の **Bluetooth HID Device API** を使い、スマホを「Bluetooth マウス」として PC に認識させます。
専用ドライバや受信側アプリは不要で、ペアリングするだけで動作します。

- **Bluetooth HID Mouse**（縦/横スクロールホイール対応）
- 接続が想定外に切れた場合の自動再登録
- 前回ペアリング先への自動再接続
- 視覚フィードバック（指のドット、ピンチの目盛り線、3 本指スワイプの予兆カーテン）

---

## 主な機能

### カーソル & クリック
- **1 本指ドラッグ** : カーソル移動
- **1 本指タップ** : 左クリック
- **2 本指タップ** : 右クリック
- **ダブルタップ → ドラッグ** : 範囲選択・ドラッグ＆ドロップ

### スクロール
- **2 本指ドラッグ** : 縦 + 横スクロール
- **慣性スクロール** : フリックで滑らかに継続。摩擦・停止しきい値を調整可
- **フリック・スタッキング** : 連続フリックで加速

### ズーム
- **2 本指ピンチ** : `Ctrl + ホイール` を送信（ブラウザ / 画像ビューア等で動作）
- ピンチ中の目盛り表示で操作量を視覚化

### 拡張ジェスチャー
- **3 本指スワイプ (左右)** : `Alt + ←` / `Alt + →`（ブラウザの戻る/進む）
- スワイプ予兆の「カーテン UI」が反対側からせり出し、しきい値到達で発火

### 設定
- 感度・タップ判定時間・タップ移動量上限・ダブルタップ間隔
- スクロール感度・スクロール方向（ナチュラル/従来）
- 慣性スクロールの摩擦・停止しきい値・フリック判定しきい値・スタッキング ON/OFF
- ピンチの感度・スロップ・線の表示
- 3 本指スワイプのしきい値・インジケータ表示
- 指のドット表示・画面向き（縦/横）
- 設定メニュー長押し時間・クリック保持時間・Discoverable 時間

---

## ジェスチャー一覧

| ジェスチャー | 動作 |
|---|---|
| 1 本指ドラッグ | カーソル移動 |
| 1 本指タップ | 左クリック |
| 2 本指タップ | 右クリック |
| ダブルタップ後ドラッグ | クリック&ドラッグ |
| 2 本指ドラッグ (縦) | 縦スクロール |
| 2 本指ドラッグ (横) | 横スクロール |
| 2 本指フリック | 慣性スクロール |
| 2 本指ピンチ | ズーム (`Ctrl + Wheel`) |
| 3 本指スワイプ (左→右) | 進む (`Alt + →`) |
| 3 本指スワイプ (右→左) | 戻る (`Alt + ←`) |
| 右上ドット長押し | 設定画面を開く |

---

## 動作環境

| 項目 | 要件 |
|---|---|
| Android バージョン | **Android 12 (API 31) 以上** |
| Bluetooth | Bluetooth HID Device プロファイル対応端末 |
| 接続先 | Bluetooth マウスを受け付ける PC・タブレット (Windows / macOS / Linux / iPadOS など) |

> **メモ:** 一部の端末・OS ベンダーは Bluetooth HID Device プロファイルを制限している場合があります。
> ペアリング画面で「Bluetooth マウス」として認識されれば動作します。

---

## インストール

### GitHub Releases から APK を入手

1. [Releases ページ](https://github.com/ishino-kenta/virtual-trackpad-android/releases) から最新の APK をダウンロード
2. Android 端末にコピーして開く（「提供元不明のアプリ」のインストールを許可）

### ソースからビルド

```bash
git clone https://github.com/ishino-kenta/virtual-trackpad-android.git
cd virtual-trackpad-android
./gradlew assembleDebug
# 生成された app/build/outputs/apk/debug/app-debug.apk を端末に転送
```

または Android Studio で開いて **Run**。

---

## 使い方

### 1. PC とペアリングする

1. アプリを起動し、Bluetooth の権限を許可
2. **Discoverable** ボタンをタップ（一定時間、PC から見える状態になります）
3. PC 側の Bluetooth 設定で、本アプリ名のデバイス（端末名）を見つけてペアリング
4. ペアリング完了後、アプリの画面に「接続済み」と表示されれば OK

### 2. 操作する

トラックパッド画面で指を動かすと PC のカーソルが動きます。
ジェスチャーは [ジェスチャー一覧](#ジェスチャー一覧) を参照。

### 3. 設定をカスタマイズ

画面右上のドットを **長押し** すると設定画面に入ります。
感度や各種しきい値、ビジュアルフィードバックの ON/OFF を細かく調整できます。

### 4. ログを確認する（任意）

設定画面の更に奥にログ画面があり、HID イベントや接続状態の履歴を確認できます。
うまく繋がらない時のデバッグに便利です。

---

## 技術詳細

### アーキテクチャ

- **言語/UI** : Kotlin + Jetpack Compose (Material3)
- **永続化** : SharedPreferences (`virtual_trackpad_prefs`)
- **HID** : `BluetoothHidDevice` API + 自前の Mouse HID Descriptor

### HID Descriptor

| サイズ | 内容 |
|---|---|
| 5 byte | ボタン (3bit) / dx / dy / wheel (縦) / hWheel (横スクロール, AC Pan) |

### 接続管理

- ペアリング情報を保存し、次回起動時に自動再接続
- BT スタックからの想定外な登録解除を検知 → 自動で再登録
- Discoverable 時間を設定で変更可

---

## 開発について

本プロジェクトの開発には AI コーディングアシスタント [Claude (Claude Code)](https://claude.com/claude-code) を活用しています。

---

## ライセンス

このソフトウェアは **MIT License** で公開されています。詳細は [LICENSE](LICENSE) を参照してください。

利用しているサードパーティライブラリのライセンスは [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) を参照。

---

## Author

[**ishino-kenta**](https://github.com/ishino-kenta)
