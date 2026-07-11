~~直前のビルドエラー修正(Graph.ktにimport 2行、SettingsScreen.ktにLaunchedEffectのimport+旧hostText/portText定義2行の削除)~~ → 確認済み。既にコードに反映済みで、`.\gradlew.bat assembleDebug` は BUILD SUCCESSFUL
~~adb自動接続(tryAutoConnectAdb)の実機検証~~ → 実施済み。一連の実機デバッグで以下を修正:
  - Graph.currentExecutor()にShizuku権限チェック漏れ(稼働確認のみで権限未確認のままShizuku経由を選んでいた)
  - AdbConnectionManagerにタイムアウト未設定(デフォルトLong.MAX_VALUEで無限ハングしうる) → 1秒に設定(実機検証済み)
  - connect/disconnect/executeCommand間の排他制御が無くStream競合 → Mutex導入、タイムアウト時はMutexごとインスタンス再生成
  - libadb-android既知の挙動(ストリームを読み切った直後にCLSEが来ると正常終了なのにIOException("Stream closed")を投げる)→ 自前読み取りループでEOF扱いに変更
  - ワイヤレスデバッグのポート変更に追従: connectAuto()でmDNS(_adb-tls-connect._tcp)自動検出を主手段にし、検出できたホスト/ポートを設定画面にも反映。手動ポート入力は検出失敗時のフォールバックとして残置
  - WRITE_SECURE_SETTINGSを一度だけadb付与しておけば、再起動時にワイヤレスデバッグ自体も自動でON(WirelessDebugging.kt + BootReceiver)。設定画面から付与コマンドをコピー可能
  - メイン画面のステータスにShizuku/内蔵adbどちら経由で取得できたか表示
  - 残課題(仕様として許容): ステータス連打時、直列化されたMutexの待ち行列に入った呼び出しが稀にタイムアウトすることがある(異常な使用パターンのため許容。詰まった呼び出し自体は次の呼び出しがタイムアウト検知して自動リセットする設計)
~~リリース準備~~ → 実施済み:
  - 署名設定: keytoolで app/release.jks を新規生成(RSA 2048bit/有効期限10000日)。ストア/キーのパスワードは local.properties(gitignore済み)に保存し、app/build.gradle の signingConfigs.release から参照。*.jks は .gitignore で除外済み。assembleRelease + apksigner verify で署名を確認済み
  - アプリアイコン: 借用の@android:drawableから、自作のadaptive icon(傾いたスマホ+回転円弧のベクター、背景/前景/モノクロの3レイヤー)に変更。画像素材不要でXMLのみ
  - minify/R8: reflection依存(Shizuku, AdbConnectionのmPort, BouncyCastle)が多いため、今回は無効のまま維持する判断
  - versionCode/versionName: 初回リリース値としてcode=1/name="1.0"のまま(変更なし)。次回以降のリリースごとに両方インクリメントする運用とする
  - 未確認: Playストア等への実際の配布・審査対応は未着手