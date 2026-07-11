# CoverDisplayRotator

Galaxy Z Flip 5のカバーディスプレイを回転制御するアプリ。
`wm user-rotation -d <displayId> lock 0/1/2/3` をShizukuまたは内蔵adb経由で実行する。

## ビルド
- Groovy DSL (build.gradle)。Kotlin DSL構文(val等)を書かないこと
- Kotlin 1.9.22 / Compose Compiler 1.5.8 / Compose BOM 2024.02.01 / compileSdk 34
  - この世代で整合が取れている。安易にBOMやKotlinを上げないこと
- 検証コマンド: .\gradlew.bat assembleDebug

## 依存関係の罠(実際に踏んだもの)
- Shizuku: Maven座標は dev.rikka.shizuku:api:13.1.5 だが
  Javaパッケージは rikka.shizuku。API 13でnewProcessはprivate → リフレクションで呼んでいる
- libadb-android 3.1.2ではなく3.1.1 (com.github.MuntashirAkon:libadb-android, JitPack)
- BouncyCastleは bcpkix-jdk15to18:1.81 を使用。
  jdk18on系を追加するとlibadb側のbcprov-jdk15to18とクラス重複でビルドが割れる
- maven.rikka.dev は死んでいる。リポジトリはgoogle/mavenCentral/jitpackのみ

## アーキテクチャ
- DIはHilt不使用。di/Graph.kt の手動DI(object)
- Graph.currentExecutor(): Shizuku稼働+権限付与済みならShizuku、それ以外は内蔵adb
- パッケージ名は net.hasumi.coverrotator (com.exampleから改名済み)
  shortcuts.xml とEXECUTE_ROTATIONアクション文字列にフルパス直書きがあるので改名時注意

## 既知の注意点
- カバーディスプレイ(720x748)前提のUI。縦方向の余白は増やさない
- adb接続ポートはワイヤレスデバッグのトグルで変わる。自動再接続は同一ポート時のみ有効
- Samsung電池最適化でサービスが死ぬ → 設定画面から除外リクエスト可能
