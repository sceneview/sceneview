package io.github.sceneview.ar.node

import com.google.android.filament.Engine
import com.google.ar.core.Anchor
import com.google.ar.core.Anchor.TerrainAnchorState
import com.google.ar.core.Config
import com.google.ar.core.Earth
import com.google.ar.core.Earth.EarthState
import com.google.ar.core.Future
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.ResourceExhaustedException
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.ar.arcore.configure

/**
 * An [AnchorNode] positioned at a specified latitude/longitude with altitude relative to the
 * terrain (or floor) at that location, using the ARCore Geospatial API.
 *
 * An altitude of 0 places the anchor directly on the terrain; positive values place it above,
 * against the direction of gravity.
 *
 * Requires [Config.GeospatialMode.ENABLED] and a working internet connection. The anchor is
 * created asynchronously via [resolve] (companion function).
 *
 * @param engine                 The Filament [Engine].
 * @param anchor                 The resolved [Anchor] from the Geospatial API.
 * @param latitude               WGS84 latitude of the anchor.
 * @param longitude              WGS84 longitude of the anchor.
 * @param altitudeAboveTerrain   Altitude in meters above the terrain (or floor).
 * @param eusQuaternion          East-up-south rotation quaternion.
 * @param onTrackingStateChanged Callback invoked when tracking state changes.
 * @param onUpdated              Callback invoked each frame while the anchor is updated.
 */
open class TerrainAnchorNode private constructor(
    engine: Engine,
    anchor: Anchor,
    val latitude: Double,
    val longitude: Double,
    val altitudeAboveTerrain: Double,
    val eusQuaternion: Quaternion,
    onTrackingStateChanged: ((TrackingState) -> Unit)? = null,
    onUpdated: ((Anchor) -> Unit)? = null
) : AnchorNode(
    engine = engine,
    anchor = anchor,
    onTrackingStateChanged = onTrackingStateChanged,
    onUpdated = onUpdated
) {
    companion object {
        /**
         * Asynchronously creates a new [Anchor] at a specified horizontal position and altitude
         * relative to the horizontal position's terrain.
         * See the [Terrain anchors developer guide](https://developers.google.com/ar/develop/geospatial/java/anchors#terrain-anchors)
         * for more information.
         *
         * The specified `altitudeAboveTerrain` is interpreted to be relative to the Earth's terrain
         * (or floor) at the specified latitude/longitude geospatial coordinates, rather than
         * relative to the WGS84 ellipsoid. Specifying an altitude of 0 will position the anchor
         * directly on the terrain (or floor) whereas specifying a positive altitude will position
         * the anchor above the terrain (or floor), against the direction of gravity.
         *
         * This launches an asynchronous operation used to query the Google Cloud ARCore API.
         * See [Future] for information on obtaining results and cancelling the operation.
         *
         * Terrain anchor resolution hits Google Cloud and accrues a billing event whether
         * the caller is still listening or not. **Always cancel the returned future when the
         * UI scope is leaving** — e.g. from `DisposableEffect.onDispose { future?.cancel() }`
         * in a Compose host.
         *
         * You may resolve multiple anchors at a time, but a session cannot be tracking more than
         * 100 Terrain and Rooftop anchors at time. Attempting to resolve more than 100 Terrain or
         * Rooftop anchors will throw [ResourceExhaustedException].
         *
         * Creating a Terrain anchor requires an active [Earth] which is [EarthState.ENABLED].
         * If it is not, then this function throws [java.lang.IllegalStateException].
         * This call also requires a working internet connection to communicate with the ARCore API
         * on Google Cloud. ARCore will continue to retry if it is unable to establish a connection
         * to the ARCore service.
         *
         * A Terrain anchor's tracking state will be [TrackingState.PAUSED] while [Earth]'s
         * [Earth.getTrackingState] is [TrackingState.PAUSED].
         * Its tracking state will permanently become [TrackingState.STOPPED] if [Session.configure]
         * is used to set [Config.GeospatialMode.DISABLED].
         *
         * Latitude and longitude are defined by the [WGS84 specification](https://en.wikipedia.org/wiki/World_Geodetic_System).
         *
         * The rotation quaternion provided is with respect to an east-up-south coordinate frame. An
         * identity rotation will have the anchor oriented such that X+ points to the east, Y+
         * points up away from the center of the earth, and Z+ points to the south.
         *
         * @param latitude The latitude of the anchor relative to the WGS84 ellipsoid.
         * @param longitude The longitude of the anchor relative to the WGS84 ellipsoid.
         * @param altitudeAboveTerrain The altitude of the anchor above the Earth's terrain
         * (or floor).
         * @param eusQuaternion The EUS (east-up-south) rotation quaternion.
         * @param onCompleted An optional callback, called when the operation is finished, unless
         * the future is cancelled. May be null.
         *
         * @return A handler that can be polled or cancelled.
         *
         * @throws java.lang.IllegalStateException if [Earth] is [TrackingState.STOPPED] due to
         * [Config.GeospatialMode.DISABLED] configured on the [Session].
         * Reacquire [Earth] if geospatial mode was re-enabled.
         * @throws java.lang.IllegalArgumentException if `latitude` is outside the allowable range.
         * @throws ResourceExhaustedException if too many anchors (counting both Terrain and Rooftop
         * anchors) have already been held or are being resolved.
         */
        fun resolve(
            engine: Engine,
            session: Session,
            latitude: Double,
            longitude: Double,
            altitudeAboveTerrain: Double,
            eusQuaternion: Quaternion,
            onCompleted: (state: TerrainAnchorState, node: TerrainAnchorNode?) -> Unit
        ): com.google.ar.core.ResolveAnchorOnTerrainFuture? = session.earth?.resolveAnchorOnTerrainAsync(
            latitude,
            longitude,
            altitudeAboveTerrain,
            eusQuaternion.x,
            eusQuaternion.y,
            eusQuaternion.z,
            eusQuaternion.w
        ) { anchor, state ->
            onCompleted(state, anchor?.takeIf { !state.isError }?.let {
                TerrainAnchorNode(
                    engine = engine,
                    anchor = it,
                    latitude = latitude,
                    longitude = longitude,
                    altitudeAboveTerrain = altitudeAboveTerrain,
                    eusQuaternion = eusQuaternion
                )
            })
        }
    }
}