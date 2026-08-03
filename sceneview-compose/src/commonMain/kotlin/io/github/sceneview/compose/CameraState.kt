package io.github.sceneview.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.romainguy.kotlin.math.Float3

/**
 * Orbit camera state for a [SceneViewer].
 *
 * The camera looks at [target] from [distance] away, at the spherical angles [azimuth]
 * and [elevation]. Read the properties to observe what the user is doing; write them to
 * drive the camera yourself. Both work in the same frame — a write during a drag simply
 * wins.
 *
 * Angles are in **degrees**. [elevation] is clamped to (-90°, 90°) so the camera never
 * reaches the poles, where the orbit basis degenerates and the view rolls unpredictably.
 * [distance] is clamped to be strictly positive.
 *
 * Create one with [rememberCameraState].
 */
@Stable
public class CameraState internal constructor(
    target: Float3,
    distance: Float,
    azimuth: Float,
    elevation: Float,
) {
    /** The point the camera orbits and looks at, in world space. */
    public var target: Float3 by mutableStateOf(target)

    private var _distance by mutableFloatStateOf(distance.coerceAtLeast(MIN_DISTANCE))

    /** Distance from [target] to the camera, in scene units. Always strictly positive. */
    public var distance: Float
        get() = _distance
        set(value) {
            _distance = value.coerceAtLeast(MIN_DISTANCE)
        }

    private var _azimuth by mutableFloatStateOf(azimuth)

    /** Horizontal orbit angle, in degrees. Wraps, so it is never clamped. */
    public var azimuth: Float
        get() = _azimuth
        set(value) {
            _azimuth = value
        }

    private var _elevation by mutableFloatStateOf(elevation.coerceIn(MIN_ELEVATION, MAX_ELEVATION))

    /** Vertical orbit angle, in degrees, clamped to (-90°, 90°). */
    public var elevation: Float
        get() = _elevation
        set(value) {
            _elevation = value.coerceIn(MIN_ELEVATION, MAX_ELEVATION)
        }

    /** Whether the user may orbit, pan and zoom with touch or mouse gestures. */
    public var gesturesEnabled: Boolean by mutableStateOf(true)

    internal companion object {
        const val MIN_DISTANCE = 0.01f

        // Stop just short of the poles: at exactly ±90° the up vector and the view
        // direction become colinear and the orbit basis is undefined.
        const val MIN_ELEVATION = -89.9f
        const val MAX_ELEVATION = 89.9f

        // Saves every mutable property, `gesturesEnabled` included — restoring a state
        // that silently re-enabled gestures after a rotation would be worse than not
        // restoring at all.
        val Saver: Saver<CameraState, List<Float>> = Saver(
            save = {
                listOf(
                    it.target.x, it.target.y, it.target.z,
                    it.distance, it.azimuth, it.elevation,
                    if (it.gesturesEnabled) 1f else 0f,
                )
            },
            restore = {
                CameraState(
                    target = Float3(it[0], it[1], it[2]),
                    distance = it[3],
                    azimuth = it[4],
                    elevation = it[5],
                ).apply { gesturesEnabled = it[6] != 0f }
            },
        )
    }
}

/**
 * Creates a [CameraState] that survives configuration changes and process death.
 *
 * The initial values are used only the first time; afterwards the restored state wins,
 * so rotating the device does not throw away the user's viewpoint.
 *
 * @param target the point to orbit around.
 * @param distance the initial distance from [target].
 * @param azimuth the initial horizontal angle, in degrees.
 * @param elevation the initial vertical angle, in degrees.
 */
@Composable
public fun rememberCameraState(
    target: Float3 = Float3(0f, 0f, 0f),
    distance: Float = 4f,
    azimuth: Float = 0f,
    elevation: Float = 15f,
): CameraState = rememberSaveable(saver = CameraState.Saver) {
    CameraState(target, distance, azimuth, elevation)
}

/**
 * Creates a [CameraState] that is *not* saved across configuration changes.
 *
 * Use this when the camera is driven entirely by your own state — an animation, or a
 * value you persist yourself — and restoring a stale viewpoint would fight it.
 */
@Composable
public fun rememberUnsavedCameraState(
    target: Float3 = Float3(0f, 0f, 0f),
    distance: Float = 4f,
    azimuth: Float = 0f,
    elevation: Float = 15f,
): CameraState = remember { CameraState(target, distance, azimuth, elevation) }
