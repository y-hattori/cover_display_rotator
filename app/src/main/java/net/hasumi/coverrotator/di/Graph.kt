package net.hasumi.coverrotator.di

import android.content.Context
import android.content.pm.PackageManager
import net.hasumi.coverrotator.executor.AdbExecutor
import net.hasumi.coverrotator.executor.ShellExecutor
import net.hasumi.coverrotator.executor.ShizukuExecutor
import net.hasumi.coverrotator.repository.RotationRepository
import net.hasumi.coverrotator.repository.RotationRepositoryImpl
import net.hasumi.coverrotator.repository.SettingsRepository
import net.hasumi.coverrotator.repository.SettingsRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import rikka.shizuku.Shizuku
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * 手動DIコンテナ。Application#onCreate で init() を呼ぶこと。
 * Hiltは使わない方針(TileService等との相性とビルド構成の単純化のため)。
 */
object Graph {

    private lateinit var appContext: Context

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val settingsRepository: net.hasumi.coverrotator.repository.SettingsRepository by lazy {
        net.hasumi.coverrotator.repository.SettingsRepositoryImpl(appContext)
    }

    val shizukuExecutor: net.hasumi.coverrotator.executor.ShizukuExecutor by lazy {
        net.hasumi.coverrotator.executor.ShizukuExecutor(appScope)
    }

    val adbExecutor: net.hasumi.coverrotator.executor.AdbExecutor by lazy {
        net.hasumi.coverrotator.executor.AdbExecutor(appContext)
    }

    val rotationRepository: net.hasumi.coverrotator.repository.RotationRepository by lazy {
        net.hasumi.coverrotator.repository.RotationRepositoryImpl(appContext) { currentExecutor() }
    }

    /** Shizukuが稼働中かつ権限付与済みならShizuku、そうでなければ内蔵adbを使う */
    fun currentExecutor(): net.hasumi.coverrotator.executor.ShellExecutor {
        val shizukuUsable = try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        return if (shizukuUsable) shizukuExecutor else adbExecutor
    }

    /**
     * バックグラウンドでadb接続を試みる。まずmDNSでワイヤレスデバッグの現在のポートを自動検出し、
     * (ポート変更後も手動でのポート再入力が不要になる。Shizuku等と同じ仕組み)
     * それが使えない場合のみ保存済みのhost/portにフォールバックする。
     */
    fun tryAutoConnectAdb() {
        appScope.launch {
            val autoResult = adbExecutor.connectAuto()
            if (autoResult.isSuccess) {
                // 設定画面のポート表示がmDNSで実際に繋がった値と食い違って混乱しないよう反映しておく
                adbExecutor.currentEndpoint()?.let { (host, port) ->
                    settingsRepository.setAdbHost(host)
                    settingsRepository.setAdbConnectionPort(port)
                }
                return@launch
            }
            android.util.Log.w("Graph", "adb mDNS auto-connect failed: ${autoResult.exceptionOrNull()?.message}, falling back to saved port")
            try {
                val port = settingsRepository.adbConnectionPort.first() ?: return@launch
                val host = settingsRepository.adbHost.first() ?: "127.0.0.1"
                adbExecutor.connect(host, port)
                    .onFailure {
                        android.util.Log.w("Graph", "adb auto-connect (saved port) failed: ${it.message}")
                    }
            } catch (e: Exception) {
                android.util.Log.w("Graph", "adb auto-connect error", e)
            }
        }
    }

    private const val ADB_RECONNECT_INTERVAL_MS = 15_000L

    /**
     * 内蔵adb接続がERRORになったら定期的に再接続を試みる。tryAutoConnectAdb()経由でmDNS自動検出を
     * 優先するため、ワイヤレスデバッグのポートが変わっていても基本的に自動で復旧する。
     */
    fun startAdbAutoReconnectLoop() {
        appScope.launch {
            while (true) {
                delay(ADB_RECONNECT_INTERVAL_MS)
                if (adbExecutor.getConnectionStatus().value == net.hasumi.coverrotator.executor.ConnectionStatus.ERROR) {
                    tryAutoConnectAdb()
                }
            }
        }
    }
}
