package io.github.sceneview.ar.node

import com.google.android.filament.Engine
import com.google.ar.core.Anchor
import com.google.ar.core.Anchor.RooftopAnchorState
import com.google.ar.core.Config
import com.google.ar.core.Earth
import com.google.ar.core.Future
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotTrackingException
import com.google.ar.core.exceptions.ResourceExhaustedException
import dev.romainguy.kotlin.math.Quaternion

/**
 * An [AnchorNode] positioned at a specified latitude/longitude with altitude relative to the
 * building rooftop at that location, using the ARCore Geospatial API.
 *
 * If no building exists at the given location, the altitude is relative to the terrain instead.
 * An altitude of 0 places the anchor directly on the rooftop; positive values place it above.
 *
 * Requires [Config.GeospatialMode.ENABLED] and a working internet connection. The anchor is
 * created asynchronously via [resolve] (companion function).
 *
 * @param engine                 The Filament [Engine].
 * @param anchor                 The resolved [Anchor] from the Geospatial API.
 * @param latitude               WGS84 latitude of the anchor.
 * @param longitude              WGS84 longitude of the anchor.
 * @param altitudeAboveRooftop   Altitude in meters above the rooftop (or terrain).
 * @param eusQuaternion          East-up-south rotation quaternion.
 * @param onTrackingStateChanged Callback invoked when tracking state changes.
 * @param onUpdated              Callback invoked each frame while the anchor is updated.
 */
open class RooftopAnchorNode private constructor(
    engine: Engine,
    anchor: Anchor,
    val latitude: Double,
    val longitude: Double,
    val altitudeAboveRooftop: Double,
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
         * Creates an anchor at a specified horizontal position and altitude relative to the
         * horizontal position's rooftop.
         * See the [Rooftop anchors developer guide](https://developers.google.com/ar/develop/geospatial/java/anchors#rooftop-anchors)
         * for more information.
         *
         * The specified `altitudeAboveRooftop` is interpreted to be relative to the top of a
         * building at the given horizontal location, rather than relative to the WGS84 ellipsoid.
         * If there is no building at the given location, then the altitude is interpreted to be
         * relative to the terrain instead. Specifying an altitude of 0 will position the anchor
         * directly on the rooftop whereas specifying a positive altitude will position the anchor
         * above the rooftop, against the direction of gravity.
         *
         * This launches an asynchronous operation used to query the Google Cloud ARCore API.
         * See [Future] for information on obtaining results and cancelling the operation.
         *
         * Rooftop anchor resolution hits Google Cloud and accrues a billing event whether
         * the caller is still listening or not. **Always cancel the returned future when the
         * UI scope is leaving** — e.g. from `DisposableEffect.onDispose { future?.cancel() }`
         * in a Compose host.
         *
         * You may resolve multiple anchors at a time, but a session cannot be tracking more than
         * 100 Terrain and Rooftop anchors at time. Attempting to resolve more than 100 Terrain or
         * Rooftop anchors will throw [ResourceExhaustedException].
         *
         * Creating a Rooftop anchor requires an active [Earth] which is
         * [Earth.EarthState.ENABLED]. If it is not, then this function throws
         * [java.lang.IllegalStateException]. This call also requires a working internet connection
         * to communicate with the ARCore API on Google Cloud. ARCore will continue to retry if it
         * is unable to establish a connection to the ARCore service.
         *
         * A Rooftop anchor's tracking state will be [TrackingState.PAUSED] while [Earth]'s
         * [Earth.getTrackingState] is [TrackingState.PAUSED]. Its tracking state will permanently
         * become [TrackingState.STOPPED] if [Session.configure] is used to set
         * [Config.GeospatialMode.DISABLED].
         *
         * Latitude and longitude are defined by the [WGS84 specification](https://en.wikipedia.org/wiki/World_Geodetic_System)
         *
         * The rotation quaternion provided is with respect to an east-up-south coordinate frame. An
         * identity rotation will have the anchor oriented such that X+ points to the east, Y+
         * points up away from the center of the earth, and Z+ points to the south.
         *
         * @param latitude The latitude of the anchor relative to the WGS84 ellipsoid.
         * @param longitude The longitude of the anchor relative to the WGS84 ellipsoid.
         * @param altitudeAboveRooftop The altitude of the anchor above the Earth's rooftop.
         * @param eusQuaternion The EUS (east-up-south) rotation quaternion.
         * @param onCompleted An optional callback, called when the operation is finished, unless
         * the future is cancelled. May be null.
         *
         * @return A handler that can be polled or cancelled.
         *
         * @throws java.lang.IllegalStateException if [Earth] is [TrackingState.STOPPED] due to
         * [Config.GeospatialMode.DISABLED] configured on the [Session].
         * Reacquire [Earth] if the Geospatial mode was re-enabled.
         * @throws java.lang.IllegalArgumentException if `latitude` is outside the allowable range.
         * @throws NotTrackingException when localisation is not yet available.
         * @throws ResourceExhaustedException if too many Terrain and Rooftop anchors have already
         * been held.
         */
        fun resolve(
            engine: Engine,
            session: Session,
            latitude: Double,
            longitude: Double,
            altitudeAboveRooftop: Double,
            eusQuaternion: Quaternion,
            onCompleted: (state: RooftopAnchorState, node: RooftopAnchorNode?) -> Unit
        ): com.google.ar.core.ResolveAnchorOnRooftopFuture? = session.earth?.resolveAnchorOnRooftopAsync(
            latitude,
            longitude,
            altitudeAboveRooftop,
            eusQuaternion.x,
            eusQuaternion.y,
            eusQuaternion.z,
            eusQuaternion.w
        ) { anchor, state ->
            onCompleted(state, anchor?.takeIf { !state.isError }?.let {
                RooftopAnchorNode(
                    engine = engine,
                    anchor = it,
                    latitude = latitude,
                    longitude = longitude,
                    altitudeAboveRooftop = altitudeAboveRooftop,
                    eusQuaternion = eusQuaternion
                )
            })
        }
    }
}