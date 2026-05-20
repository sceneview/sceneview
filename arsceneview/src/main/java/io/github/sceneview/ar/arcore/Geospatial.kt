package io.github.sceneview.ar.arcore

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import com.google.ar.core.Earth
import com.google.ar.core.GeospatialPose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.VpsAvailability
import com.google.ar.core.VpsAvailabilityFuture
import com.google.ar.core.exceptions.ResourceExhaustedException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Snapshot of every field a [GeospatialPose] exposes — addresses the audit finding (#1769) that
 * the existing `GeospatialPose.transform` extension projects only 3 of 7 fields (lat/lng/altitude)
 * into a SceneView [io.github.sceneview.math.Transform], dropping the four accuracy / heading
 * fields apps need to gate UI on signal quality.
 *
 * Every field is in WGS84 conventions:
 *
 * @property latitude WGS84 latitude in degrees ([-90, +90]).
 * @property longitude WGS84 longitude in degrees ([-180, +180]).
 * @property altitude Altitude above the WGS84 ellipsoid in meters.
 * @property heading Compass heading in degrees clockwise from north ([0, 360)). May be replaced by
 *   the more permissive [orientationYawAccuracy]-gated yaw in a future ARCore release; the value
 *   is retained for backward compatibility with `getHeading()`.
 * @property horizontalAccuracy Horizontal 1-sigma uncertainty in meters. Gate placement on
 *   `< ~2 m` for AR-grade Geospatial UX.
 * @property verticalAccuracy Vertical 1-sigma uncertainty in meters.
 * @property orientationYawAccuracy Yaw 1-sigma uncertainty in degrees. Below ~5° is "good
 *   enough" to point an arrow towards a target POI.
 */
data class GeospatialPoseSnapshot(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val heading: Double,
    val horizontalAccuracy: Double,
    val verticalAccuracy: Double,
    val orientationYawAccuracy: Double
)

/**
 * Captures every field of this [GeospatialPose] into a [GeospatialPoseSnapshot] (#1769).
 *
 * ARCore's `GeospatialPose` is a JNI handle whose native memory is freed as soon as the next
 * frame advances — apps that need to retain the values across frames must snapshot. The
 * existing `GeospatialPose.transform` extension only carries lat/lng/altitude; this helper
 * captures the four accuracy + heading fields too, so apps can render "GPS quality" indicators
 * or gate Terrain anchor placement on a sub-2-metre accuracy.
 */
@Suppress("DEPRECATION") // `getHeading()` is marked deprecated by ARCore in favour of the
// general orientation-yaw machinery, but the value remains available and is the only field that
// reports the compass direction the camera is looking — apps still need it to point arrows.
fun GeospatialPose.snapshot(): GeospatialPoseSnapshot = GeospatialPoseSnapshot(
    latitude = latitude,
    longitude = longitude,
    altitude = altitude,
    heading = heading,
    horizontalAccuracy = horizontalAccuracy,
    verticalAccuracy = verticalAccuracy,
    orientationYawAccuracy = orientationYawAccuracy
)

/**
 * Compose `State<GeospatialPose?>` that updates each frame with the live camera Geospatial pose
 * (#1769). Returns `null` whenever:
 *  - `session` is `null` (e.g. the `ARCore` lifecycle is paused),
 *  - the [Session]'s [Session.getEarth] is `null` (no Geospatial config / Cloud key missing),
 *  - the [Earth]'s tracking state is not [TrackingState.TRACKING] (initial GPS lock pending,
 *    indoor scene, etc.).
 *
 * Drives off `withFrameNanos` so it inherits Compose's natural cadence and pauses when the
 * composition pauses — no separate timer / coroutine to manage.
 *
 * ```kotlin
 * val cameraGeospatial by rememberCameraGeospatialPose(arSession)
 * cameraGeospatial?.let { pose ->
 *     Text("Lat: ${pose.latitude}, Lng: ${pose.longitude}")
 * }
 * ```
 *
 * For long-term retention across recomposition / frames, call [snapshot] on the returned pose to
 * capture every field. The raw `GeospatialPose` handle becomes invalid as soon as ARCore advances
 * past the frame on which it was read.
 */
@Composable
fun rememberCameraGeospatialPose(session: Session?): State<GeospatialPose?> {
    return produceState<GeospatialPose?>(initialValue = null, key1 = session) {
        if (session == null) {
            value = null
            return@produceState
        }
        while (true) {
            withFrameNanos {
                val earth = session.earth
                value = if (earth?.trackingState == TrackingState.TRACKING) {
                    earth.cameraGeospatialPose
                } else {
                    null
                }
            }
        }
    }
}

/**
 * Compose `State<Earth.EarthState?>` that updates each frame with the [Earth.EarthState] returned
 * by [Session.getEarth] (#1769).
 *
 * Surfaces every state ARCore exposes — `ENABLED`, `ERROR_INTERNAL`, `ERROR_NOT_AUTHORIZED`,
 * `ERROR_RESOURCE_EXHAUSTED`, `ERROR_APK_VERSION_TOO_OLD`, `ERROR_GEOSPATIAL_MODE_DISABLED` —
 * so apps can react in Compose to "Cloud API key missing", "out of quota", "ARCore APK too
 * old" without polling Earth manually.
 *
 * Returns `null` when [session] is `null` or [Session.getEarth] returns `null` (e.g. Geospatial
 * mode disabled at startup).
 *
 * ```kotlin
 * val earthState by rememberEarthState(arSession)
 * when (earthState) {
 *     Earth.EarthState.ENABLED -> /* ready */
 *     Earth.EarthState.ERROR_NOT_AUTHORIZED -> /* show "missing Cloud API key" */
 *     null -> /* loading */
 *     else -> /* show generic error */
 * }
 * ```
 */
@Composable
fun rememberEarthState(session: Session?): State<Earth.EarthState?> {
    return produceState<Earth.EarthState?>(initialValue = null, key1 = session) {
        if (session == null) {
            value = null
            return@produceState
        }
        while (true) {
            withFrameNanos {
                value = session.earth?.earthState
            }
        }
    }
}

/**
 * Suspend wrapper around [Session.checkVpsAvailabilityAsync] (#1769).
 *
 * Returns the [VpsAvailability] reported by Google's VPS coverage service for the given WGS84
 * coordinate. Lets apps gate "place Terrain anchor" buttons on actual VPS coverage instead of
 * guessing and surfacing a `ResourceExhaustedException` minutes later. The check itself is a
 * single Google Cloud round-trip.
 *
 * Cancellation: if the calling coroutine is cancelled before ARCore's callback fires the
 * underlying [VpsAvailabilityFuture] is cancelled.
 *
 * ```kotlin
 * scope.launch {
 *     val availability = arSession.awaitVpsAvailability(48.8584, 2.2945)
 *     when (availability) {
 *         VpsAvailability.AVAILABLE -> /* enable Terrain anchor button */
 *         VpsAvailability.UNAVAILABLE -> /* show "not covered" message */
 *         else -> /* network error / not authorized / etc. */
 *     }
 * }
 * ```
 *
 * @param latitude WGS84 latitude in degrees.
 * @param longitude WGS84 longitude in degrees.
 * @return The [VpsAvailability] enum value returned by the VPS service.
 * @throws ResourceExhaustedException if ARCore is rate-limiting VPS lookups for this app.
 */
suspend fun Session.awaitVpsAvailability(
    latitude: Double,
    longitude: Double
): VpsAvailability = suspendCancellableCoroutine { continuation ->
    val future: VpsAvailabilityFuture = checkVpsAvailabilityAsync(latitude, longitude) { result ->
        if (continuation.isActive) {
            continuation.resume(result)
        }
    }
    continuation.invokeOnCancellation {
        runCatching { future.cancel() }
    }
}
