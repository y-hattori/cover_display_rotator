package net.hasumi.coverrotator.executor

import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

class ShizukuExecutor(
    @Suppress("unused") private val scope: CoroutineScope
) : net.hasumi.coverrotator.executor.ShellExecutor {

    private val _connectionStatus = MutableStateFlow(net.hasumi.coverrotator.executor.ConnectionStatus.DISCONNECTED)
    override fun getConnectionStatus(): StateFlow<net.hasumi.coverrotator.executor.ConnectionStatus> = _connectionStatus.asStateFlow()

    init {
        try {
            Shizuku.addBinderReceivedListenerSticky { updateConnectionStatus() }
            Shizuku.addBinderDeadListener {
                _connectionStatus.value = net.hasumi.coverrotator.executor.ConnectionStatus.DISCONNECTED
            }
        } catch (_: Throwable) {
            // Shizukuが未インストール等でクラス初期化に失敗しても落とさない
        }
        updateConnectionStatus()
    }

    private fun updateConnectionStatus() {
        _connectionStatus.value = try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    net.hasumi.coverrotator.executor.ConnectionStatus.CONNECTED
                } else {
                    net.hasumi.coverrotator.executor.ConnectionStatus.PERMISSION_DENIED
                }
            } else {
                net.hasumi.coverrotator.executor.ConnectionStatus.DISCONNECTED
            }
        } catch (_: Throwable) {
            net.hasumi.coverrotator.executor.ConnectionStatus.DISCONNECTED
        }
    }

    override suspend fun checkPermission(): Boolean {
        updateConnectionStatus()
        return _connectionStatus.value == net.hasumi.coverrotator.executor.ConnectionStatus.CONNECTED
    }

    override suspend fun executeCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!Shizuku.pingBinder()) {
                return@withContext Result.failure(Exception("Shizuku not running"))
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return@withContext Result.failure(Exception("Shizuku permission not granted"))
            }

            val process = newProcess(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }

            val errorOutput = StringBuilder()
            while (errorReader.readLine().also { line = it } != null) {
                errorOutput.append(line).append("\n")
            }

            val exitCode = process.waitFor()
            if (exitCode == 0) {
                Result.success(output.toString().trim())
            } else {
                Result.failure(
                    Exception("Command failed with exit code $exitCode: ${errorOutput.toString().trim()}")
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Shizuku API 13 では Shizuku.newProcess が private のため、リフレクションで呼ぶ。
     * (公式推奨はUserServiceだが、単発のシェルコマンド用途にはこれで十分)
     */
    private fun newProcess(cmd: Array<String>): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(null, cmd, null, null) as Process
    }
}
