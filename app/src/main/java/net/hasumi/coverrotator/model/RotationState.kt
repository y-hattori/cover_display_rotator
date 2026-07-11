package net.hasumi.coverrotator.model

/**
 * Represents the rotation state of a display.
 */
enum class RotationMode {
    FREE,      // user-rotation free
    LOCK_0,    // user 0 degree rotation (lock 0)
    LOCK_90,   // user 90 degree rotation (lock 1)
    LOCK_180,  // user 180 degree rotation (lock 2)
    LOCK_270,  // user 270 degree rotation (lock 3)
    UNKNOWN
}

/**
 * Represents the current rotation configuration of the cover display.
 */
data class CoverDisplayRotationState(
    val mode: net.hasumi.coverrotator.model.RotationMode,
    val isFixedToUserRotationEnabled: Boolean,
    val isIgnoreOrientationRequestEnabled: Boolean,
    val executorName: String
)