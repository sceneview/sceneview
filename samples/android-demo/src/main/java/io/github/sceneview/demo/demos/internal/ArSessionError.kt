package io.github.sceneview.demo.demos.internal

/**
 * Maps an AR-session failure ([Throwable] from `ARSceneView.onSessionFailed`) to a
 * human-readable, honest, actionable status string for the demo UI (#2349).
 *
 * The Geospatial demos previously surfaced `exception.message ?: exception.javaClass.simpleName`
 * directly, so a `FatalException` with a null message put the raw class name on screen.
 *
 * **Nothing here blames the cloud.** A missing or rejected ARCore Cloud key, an exhausted
 * quota and missing VPS coverage never throw: they come back as an `Earth` state or a
 * coverage answer, and have their own banner (`CloudServiceStatus`, #3262). The first
 * version of this mapper said "needs outdoor visual positioning and a configured cloud
 * service" for every `FatalException`, because it was checked on an emulator — where
 * `Session.<init>` throws `FatalException` for an unrelated reason (the AVD has no camera
 * id `0`, #2754). On a phone it sent people to Google Cloud for a camera or ARCore failure.
 *
 * Class names are matched by `simpleName` so the mapping holds even though the concrete
 * `com.google.ar.core.exceptions.*` types aren't all on the demo's compile classpath.
 *
 * @param error The throwable ARCore reported. `null` is tolerated (degenerate caller).
 * @return A user-facing sentence — never a bare exception class name.
 */
fun friendlyArSessionError(error: Throwable?): String {
    val simpleName = error?.javaClass?.simpleName.orEmpty()
    val rawMessage = error?.message?.takeIf { it.isNotBlank() }

    return when {
        // Geospatial asks for fine location when the session is configured. Tested before
        // "Security": FineLocationPermissionNotGrantedException does not carry that word.
        simpleName.contains("LocationPermission", ignoreCase = true) ->
            "This needs your precise location — allow it in Settings and try again."

        // Device / OS can't run this AR configuration at all.
        simpleName.contains("Unavailable", ignoreCase = true) ||
            simpleName.contains("Unsupported", ignoreCase = true) ->
            "This AR feature isn't available on this phone. Try AR Placement instead."

        // Camera couldn't be acquired (in use elsewhere, permission revoked mid-session).
        simpleName.contains("CameraNotAvailable", ignoreCase = true) ->
            "The camera isn't available right now — close other camera apps and try again."

        simpleName.contains("Security", ignoreCase = true) ->
            "AR can't start without the camera permission — grant it in Settings and retry."

        // ARCore's own internal failure, and anything else that arrives without a message.
        simpleName.equals("FatalException", ignoreCase = true) || rawMessage == null ->
            "AR couldn't start on this phone. Close other camera apps, then reopen this screen."

        // We have a real, human-written message from ARCore — surface it (it's already
        // user-facing in these cases), not the class name.
        else -> rawMessage
    }
}
