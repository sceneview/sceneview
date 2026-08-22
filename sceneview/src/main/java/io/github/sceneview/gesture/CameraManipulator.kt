package io.github.sceneview.gesture

import com.google.android.filament.utils.Manipulator
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.lookAt
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.DefaultCameraNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import java.util.WeakHashMap

/**
 * Sets world-space position of interest, which defaults to (0,0,0).
 *
 * @return this `Builder` object for chaining calls
 */
fun Manipulator.Builder.targetPosition(position: Position) =
    targetPosition(position.x, position.y, position.z)

/**
 * Sets initial eye position in world space for ORBIT mode.
 * This defaults to (0,0,1).
 *
 * Named after the Filament `Manipulator.Builder` method it wraps. Despite the name there is no
 * "home" gesture: the value is the camera's *initial* absolute eye position, nothing more — see
 * `rememberCameraManipulator`'s KDoc. Prefer `eyePosition` / `orbitRadius` on the SceneView
 * factories; this extension stays for code that builds a raw Filament [Manipulator].
 *
 * @return this <code>Builder</code> object for chaining calls
 */
fun Manipulator.Builder.orbitHomePosition(position: Position) =
    orbitHomePosition(position.x, position.y, position.z)

/**
 * Unit vector from the orbit target towards the camera, used by the `orbitRadius` overloads of
 * `rememberCameraManipulator`, `createDefaultCameraManipulator` and
 * [CameraGestureDetector.DefaultCameraManipulator].
 *
 * It is the direction of the default [io.github.sceneview.DefaultCameraNode] position
 * `(0, 0.4, 2.75)` — a gentle 3/4 angle looking slightly down on the target, ≈ 8.3° above the
 * horizontal — so `orbitRadius = 2.78f` with the default target reproduces the stock framing,
 * and every other radius only slides the camera along that same ray.
 */
val DEFAULT_ORBIT_DIRECTION: Position = normalize(
    Float3(0f, DefaultCameraNode.DEFAULT_Y, DefaultCameraNode.DEFAULT_Z)
)

/**
 * Derives the camera eye position for an orbit [orbitRadius] metres away from [targetPosition],
 * along [DEFAULT_ORBIT_DIRECTION].
 *
 * This is the pure maths behind the `orbitRadius` overloads:
 * `eye = targetPosition + DEFAULT_ORBIT_DIRECTION * orbitRadius`, so
 * `|eye − targetPosition| == orbitRadius` by construction.
 *
 * Under `SceneView`'s default `autoCenterContent = true` the content is re-centred on the world
 * origin, so [orbitRadius] is the camera-to-subject distance when [targetPosition] is the origin
 * (the default). With another target the camera is [orbitRadius] from that *pivot*, and the
 * auto-centred subject sits wherever the origin falls relative to it.
 *
 * @param orbitRadius    Distance from the camera to [targetPosition], in metres. Must be `> 0`.
 * @param targetPosition World-space point the camera orbits and looks at. Defaults to the origin.
 */
fun orbitEyePosition(orbitRadius: Float, targetPosition: Position = Position(0f)): Position {
    require(orbitRadius > 0f && orbitRadius.isFinite()) {
        "orbitRadius must be a finite value > 0, was $orbitRadius"
    }
    return targetPosition + DEFAULT_ORBIT_DIRECTION * orbitRadius
}

/**
 * Per-[Manipulator] scratch + memoized state for [Manipulator.transform].
 *
 * [Manipulator] is a final Filament class that cannot be extended, so the
 * persistent state lives in a [WeakHashMap] keyed by the manipulator instance.
 * A weak key means the entry is collected as soon as the manipulator is — no
 * leak, even when manipulators are recreated across recompositions.
 *
 * The three `FloatArray(3)` scratch buffers are reused across every frame
 * instead of being reallocated, and [lastTransform] caches the last computed
 * [Transform] so an unchanged camera (the common case — no input on 95%+ of
 * frames) returns the cached matrix without recomputing `lookAt` (#2272).
 */
private class CameraManipulatorState {
    val eyeScratch = FloatArray(3)
    val targetScratch = FloatArray(3)
    val upScratch = FloatArray(3)

    var lastEyeX = Float.NaN
    var lastEyeY = Float.NaN
    var lastEyeZ = Float.NaN
    var lastTargetX = Float.NaN
    var lastTargetY = Float.NaN
    var lastTargetZ = Float.NaN
    var lastTransform: Transform? = null

    /**
     * True when [eye] and [target] match the eye/target captured on the last
     * recompute — i.e. the camera hasn't moved and the cached [lastTransform] is
     * still valid. Initial `NaN` fields never match (`NaN == NaN` is false), so the
     * first call always recomputes.
     */
    fun matches(eye: FloatArray, target: FloatArray): Boolean =
        // Exact `==` float comparison is intentional, not a missing epsilon (#2303): a
        // false miss only costs one extra `lookAt` recompute (harmless), while an epsilon
        // tolerance would wrongly treat a genuine sub-epsilon camera move as "unchanged"
        // and return a stale matrix.
        eye[0] == lastEyeX && eye[1] == lastEyeY && eye[2] == lastEyeZ &&
            target[0] == lastTargetX && target[1] == lastTargetY && target[2] == lastTargetZ
}

private val cameraManipulatorStates = WeakHashMap<Manipulator, CameraManipulatorState>()

/**
 * The current camera [Transform] derived from the manipulator's look-at vectors.
 *
 * Called once per frame from the render loop. The eye/target/up vectors are read
 * into reused scratch buffers (no per-frame allocation), and the resulting
 * [Transform] is memoized: when the camera has not moved since the last frame the
 * cached matrix is returned directly, skipping the `lookAt` recompute entirely
 * (#2272). The `up` vector is fixed to world-up `(0, 1, 0)`, matching the previous
 * behaviour.
 */
val Manipulator.transform: Transform
    get() {
        val state = cameraManipulatorStates.getOrPut(this) { CameraManipulatorState() }
        val eye = state.eyeScratch
        val target = state.targetScratch
        // getLookAt fills the three scratch buffers in place — no allocation.
        getLookAt(eye, target, state.upScratch)

        val cached = state.lastTransform
        if (cached != null && state.matches(eye, target)) {
            return cached
        }

        val transform = lookAt(
            eye = Float3(eye[0], eye[1], eye[2]),
            target = Float3(target[0], target[1], target[2]),
            up = Float3(y = 1.0f)
        )
        state.lastEyeX = eye[0]
        state.lastEyeY = eye[1]
        state.lastEyeZ = eye[2]
        state.lastTargetX = target[0]
        state.lastTargetY = target[1]
        state.lastTargetZ = target[2]
        state.lastTransform = transform
        return transform
    }
