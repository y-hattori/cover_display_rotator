package net.hasumi.coverrotator.repository

import net.hasumi.coverrotator.model.CoverDisplayRotationState
import net.hasumi.coverrotator.model.RotationMode

/**
 * Interface for managing the rotation of the cover display.
 */
interface RotationRepository {
    /**
     * Gets the current rotation state of the detected cover/secondary display.
     * @return The current [CoverDisplayRotationState].
     */
    suspend fun getCurrentRotationState(): Result<CoverDisplayRotationState>

    /**
     * Sets the rotation mode for the cover display.
     * @param mode The desired [RotationMode].
     * @return Success if the command was executed successfully, failure otherwise.
     */
    suspend fun setRotationMode(mode: RotationMode): Result<Unit>

    /**
     * Enables or disables fixed-to-user-rotation for the cover display.
     * @param enabled True to enable, false to disable (default).
     * @return Success if the command was executed successfully, failure otherwise.
     */
    suspend fun setFixedToUserRotation(enabled: Boolean): Result<Unit>

    /**
     * Enables or disables ignoring orientation requests for the cover display.
     * @param enabled True to enable, false to disable (reset).
     * @return Success if the command was executed successfully, failure otherwise.
     */
    suspend fun setIgnoreOrientationRequest(enabled: Boolean): Result<Unit>

    /**
     * Gets the detected cover display ID. 
     * If detection fails, returns a fallback ID (usually 1).
     */
    fun getCoverDisplayId(): Int
}