package net.hasumi.coverrotator.repository

import android.content.Context
import android.hardware.display.DisplayManager
import net.hasumi.coverrotator.executor.ShellExecutor
import net.hasumi.coverrotator.model.CoverDisplayRotationState
import net.hasumi.coverrotator.model.RotationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RotationRepositoryImpl(
    private val context: Context,
    private val executorProvider: () -> ShellExecutor
) : RotationRepository {

    private val shellExecutor: ShellExecutor
        get() = executorProvider()

    override fun getCoverDisplayId(): Int {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays
        // デフォルト(0)以外を探す。Z Flip 5のカバーディスプレイは displayId 1。
        return displays.firstOrNull { it.displayId != 0 }?.displayId ?: 1
    }

    override suspend fun getCurrentRotationState(): Result<CoverDisplayRotationState> =
        withContext(Dispatchers.IO) {
            val id = getCoverDisplayId()
            try {
                val dumpsysResult =
                    shellExecutor.executeCommand("dumpsys window displays --display $id")
                val wmResult = shellExecutor.executeCommand("wm user-rotation -d $id")

                if (dumpsysResult.isFailure) {
                    return@withContext Result.failure(dumpsysResult.exceptionOrNull()!!)
                }
                if (wmResult.isFailure) {
                    return@withContext Result.failure(wmResult.exceptionOrNull()!!)
                }

                val dumpsysOutput = dumpsysResult.getOrThrow()
                val wmOutput = wmResult.getOrThrow()

                val mode = when {
                    wmOutput.contains("free") -> RotationMode.FREE
                    wmOutput.contains("lock 0") -> RotationMode.LOCK_0
                    wmOutput.contains("lock 1") -> RotationMode.LOCK_90
                    wmOutput.contains("lock 2") -> RotationMode.LOCK_180
                    wmOutput.contains("lock 3") -> RotationMode.LOCK_270
                    else -> RotationMode.UNKNOWN
                }

                val isFixedToUserRotationEnabled =
                    dumpsysOutput.contains("mFixedToUserRotation=true")
                val isIgnoreOrientationRequestEnabled =
                    dumpsysOutput.contains("mIgnoreOrientationRequest=true")

                Result.success(
                    CoverDisplayRotationState(
                        mode = mode,
                        isFixedToUserRotationEnabled = isFixedToUserRotationEnabled,
                        isIgnoreOrientationRequestEnabled = isIgnoreOrientationRequestEnabled
                    )
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun setRotationMode(mode: RotationMode): Result<Unit> {
        val id = getCoverDisplayId()
        val command = when (mode) {
            RotationMode.FREE -> "wm user-rotation -d $id free"
            RotationMode.LOCK_0 -> "wm user-rotation -d $id lock 0"
            RotationMode.LOCK_90 -> "wm user-rotation -d $id lock 1"
            RotationMode.LOCK_180 -> "wm user-rotation -d $id lock 2"
            RotationMode.LOCK_270 -> "wm user-rotation -d $id lock 3"
            RotationMode.UNKNOWN -> return Result.failure(Exception("Unknown rotation mode"))
        }
        return executeShellCommand(command)
    }

    override suspend fun setFixedToUserRotation(enabled: Boolean): Result<Unit> {
        val id = getCoverDisplayId()
        val command = if (enabled) {
            "wm fixed-to-user-rotation -d $id enabled"
        } else {
            "wm fixed-to-user-rotation -d $id default"
        }
        return executeShellCommand(command)
    }

    override suspend fun setIgnoreOrientationRequest(enabled: Boolean): Result<Unit> {
        val id = getCoverDisplayId()
        val command = if (enabled) {
            "wm set-ignore-orientation-request -d $id true"
        } else {
            "wm set-ignore-orientation-request -d $id false"
        }
        return executeShellCommand(command)
    }

    private suspend fun executeShellCommand(command: String): Result<Unit> {
        return shellExecutor.executeCommand(command).map { }
    }
}
