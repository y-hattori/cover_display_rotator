package net.hasumi.coverrotator.executor

import kotlinx.coroutines.flow.Flow

/**
 * Interface for executing shell commands via different backends (Shizuku, ADB, etc.)
 */
interface ShellExecutor {
    /**
     * Returns the current connection status of the executor.
     */
    fun getConnectionStatus(): Flow<net.hasumi.coverrotator.executor.ConnectionStatus>

    /**
     * Executes a single shell command and returns the output as a Result.
     * [command] The command to execute.
     * @return Success containing stdout, or Failure containing stderr/exception.
     */
    suspend fun executeCommand(command: String): Result<String>

    /**
     * Checks if the executor has the necessary permissions to run commands.
     */
    suspend fun checkPermission(): Boolean
}

enum class ConnectionStatus {
    CONNECTED,
    DISCONNECTED,
    PERMISSION_DENIED,
    ERROR
}