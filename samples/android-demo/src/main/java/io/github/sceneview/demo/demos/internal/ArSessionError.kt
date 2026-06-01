package io.github.sceneview.demo.demos.internal

/**
 * Maps an AR-session failure ([Throwable] from `ARSceneView.onSessionFailed`) to a
 * human-readable, honest, actionable status string for the demo UI (#2349).
 *
 * The Geospatial demos (Terrain / Rooftop / Streetscape) previously surfaced
 * `exception.message ?: exception.javaClass.simpleName` directly, so when ARCore threw a
 * `FatalException` with a null message — exactly what happens when a Geospatial session
 * fails to establish on a device without VPS coverage or an ARCore Cloud API key — the
 * banner read the raw class name **"AR session error: FatalException"**. That exposes an
 * implementation detail and reads like a crash to the user ("real product, not a school
 * project").
 *
 * This maps the ARCore exception class names we know about to friendly copy, and degrades
 * the unknown / null-message case to a generic but still actionable message rather than a
 * class name. Class names are matched by `simpleName` so the mapping holds even though the
 * concrete `com.google.ar.core.exceptions.*` types aren't all on the demo's compile
 * classpath.
 *
 * @param error    The throwable ARCore reported. `null` is tolerated (degenerate caller).
 * @param needsKey `true` for Geospatial demos that require an ARCore Cloud API key + VPS
 *   coverage, so the fallback copy can name that requirement honestly.
 * @return A user-facing sentence — never a bare exception class name.
 */
fun friendlyArSessionError(error: Throwable?, needsKey: Boolean = true): String {
    val keyHint =
        if (needsKey) " this needs a device with VPS coverage and an ARCore Cloud API key"
        else " your device may not support this AR feature"
    val simpleName = error?.javaClass?.simpleName.orEmpty()
    val rawMessage = error?.message?.takeIf { it.isNotBlank() }

    return when {
        // Device / OS can't run this AR configuration at all.
        simpleName.contains("Unavailable", ignoreCase = true) ||
            simpleName.contains("Unsupported", ignoreCase = true) ->
            "This AR feature isn't available here —$keyHint."

        // Camera couldn't be acquired (in use elsewhere, permission revoked mid-session).
        simpleName.contains("CameraNotAvailable", ignoreCase = true) ->
            "The camera isn't available right now — close other camera apps and try again."

        simpleName.contains("Security", ignoreCase = true) ->
            "AR can't start without the camera permission — grant it in Settings and retry."

        // FatalException (typically a null-message Geospatial/VPS failure) and anything
        // else we don't specifically recognise.
        simpleName.equals("FatalException", ignoreCase = true) || rawMessage == null ->
            "AR couldn't start —$keyHint."

        // We have a real, human-written message from ARCore — surface it (it's already
        // user-facing in these cases), not the class name.
        else -> rawMessage
    }
}
