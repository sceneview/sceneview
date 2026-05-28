package io.github.sceneview.gesture

import com.google.android.filament.utils.Manipulator
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.lookAt
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
 * @return this <code>Builder</code> object for chaining calls
 */
fun Manipulator.Builder.orbitHomePosition(position: Position) =
    orbitHomePosition(position.x, position.y, position.z)

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