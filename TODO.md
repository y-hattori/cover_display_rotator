~~直前のビルドエラー修正(Graph.ktにimport 2行、SettingsScreen.ktにLaunchedEffectのimport+旧hostText/portText定義2行の削除)~~ → 確認済み。既にコードに反映済みで、`.\gradlew.bat assembleDebug` は BUILD SUCCESSFUL
~~adb自動接続(tryAutoConnectAdb)の実機検証~~ → 実施済み。一連の実機デバッグで以下を修正:
  - Graph.currentExecutor()にShizuku権限チェック漏れ(稼働確認のみで権限未確認のままShizuku経由を選んでいた)
  - AdbConnectionManagerにタイムアウト未設定(デフォルトLong.MAX_VALUEで無限ハングしうる) → 8秒に設定
  - connect/disconnect/executeCommand間の排他制御が無くStream競合 → Mutex導入、タイムアウト時はMutexごとインスタンス再生成
  - libadb-android既知の挙動(ストリームを読み切った直後にCLSEが来ると正常終了なのにIOException("Stream closed")を投げる)→ 自前読み取りループでEOF扱いに変更
  - 残課題(仕様として許容): 端末再起動直後はadbdのリッスン開始を待つため接続まで時間がかかる(15秒間隔リトライで自然復旧)。ステータス連打時は稀にタイムアウトすることがある(異常な使用パターンのため許容)
リリースに向けて未着手: 署名設定(keystore生成、signingConfigs)、versionCode/versionName運用、アプリアイコン(現在 @android:drawable 借用のまま)、minify/R8を有効にするかの判断