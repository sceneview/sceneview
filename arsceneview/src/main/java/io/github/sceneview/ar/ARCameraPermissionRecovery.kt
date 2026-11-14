package io.github.sceneview.ar

/** The actions to offer after an Android camera permission request is refused. */
enum class ARCameraPermissionRecovery {
    /** Android still offers the dialog. */
    Request,

    /** The answer is ambiguous: keep asking available, with settings as another way out. */
    RequestWithSettings,

    /** Android's rationale flag fell after a refusal: only settings can grant access. */
    Settings,
}

/**
 * Shared Android camera recovery policy for [ARSceneView] and hosts with their own gate.
 * Call only for a refused request; a grant clears the recovery state and refusal count.
 *
 * A dialog dismissed with Back and a request Android refuses without showing a dialog both
 * leave no rationale flag. Timing and repeated answers therefore only add a settings
 * fallback, never take away the request action or claim access was turned off (#4469).
 * [earlierUnexplainedRefusals] counts consecutive refused answers with no rationale flag;
 * reset it when the flag is raised or permission is granted.
 */
fun cameraPermissionRecovery(
    rationaleBefore: Boolean,
    rationaleAfter: Boolean,
    elapsedMs: Long,
    earlierUnexplainedRefusals: Int,
): ARCameraPermissionRecovery = when {
    rationaleAfter -> ARCameraPermissionRecovery.Request
    rationaleBefore -> ARCameraPermissionRecovery.Settings
    elapsedMs < CAMERA_PROMPT_INSTANT_RETURN_MS || earlierUnexplainedRefusals >= 1 ->
        ARCameraPermissionRecovery.RequestWithSettings
    else -> ARCameraPermissionRecovery.Request
}

/** A fast answer is ambiguous too; use it to offer settings, not to remove the dialog. */
internal const val CAMERA_PROMPT_INSTANT_RETURN_MS = 1000L
