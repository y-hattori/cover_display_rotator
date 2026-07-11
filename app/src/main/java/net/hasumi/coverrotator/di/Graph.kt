package net.hasumi.coverrotator.di

import android.content.Context
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

    /** Shizukuが生きていればShizuku、そうでなければ内蔵adbを使う */
    fun currentExecutor(): net.hasumi.coverrotator.executor.ShellExecutor {
        val shizukuAlive = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        return if (shizukuAlive) shizukuExecutor else adbExecutor
    }

    /** 保存済みのadb接続情報があればバックグラウンドで接続を試みる */
    fun tryAutoConnectAdb() {
        appScope.launch {
            try {
                val port = settingsRepository.adbConnectionPort.first() ?: return@launch
                val host = settingsRepository.adbHost.first() ?: "127.0.0.1"
                adbExecutor.connect(host, port)
                    .onFailure {
                        android.util.Log.w("Graph", "adb auto-connect failed: ${it.message}")
                    }
            } catch (e: Exception) {
                android.util.Log.w("Graph", "adb auto-connect error", e)
            }
        }
    }
}
