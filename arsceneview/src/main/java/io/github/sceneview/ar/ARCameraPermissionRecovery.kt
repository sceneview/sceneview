package io.github.sceneview.ar

/** The actions to offer after an Android camera permission request is refused. */
enum class ARCameraPermissionRecovery {
    /** Offer another permission request; Android may still suppress its dialog. */
    Request,

    /** The answer is ambiguous: lead with settings and keep another request available. */
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
 * action, never take away the request action or claim access was turned off (#4469).
 * The timing threshold and refusal heuristic may evolve; callers should use the returned
 * recovery instead of duplicating the heuristic.
 *
 * @param rationaleBefore Android's `shouldShowRequestPermissionRationale(CAMERA)` immediately
 *   before launching the request: `true` means Android recommends showing a rationale.
 *   This is the **inverse** of [ARPermissionHandler.shouldShowPermissionRationale].
 * @param rationaleAfter The same Android rationale flag immediately after the refused answer,
 *   also the inverse of [ARPermissionHandler.shouldShowPermissionRationale].
 * @param elapsedMs Monotonic elapsed milliseconds between launching and answering the request.
 *   A fast answer is ambiguous, not proof that Android suppressed the dialog.
 * @param earlierUnexplainedRefusals Consecutive earlier refused answers with no rationale flag,
 *   excluding this answer. Reset to zero on a grant or a raised rationale flag.
 * @return The recovery actions to offer for this refused request; settings leads when available.
 */
fun resolveCameraPermissionRecovery(
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
