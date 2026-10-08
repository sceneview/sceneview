package io.github.sceneview.demo.demos.internal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue

/**
 * Everything the user can change on [io.github.sceneview.demo.demos.GeometryDemo]: which shapes
 * are shown, the material they share, whether they turn, and where the camera calls home.
 *
 * Held outside the composable so the rules — what Reset restores, what Recenter leaves alone —
 * are plain Kotlin a JVM test can pin.
 */
@Stable
internal class GeometryDemoState(
    visibleShapes: Set<GeometryShape> = ALL_SHAPES,
    metallic: Float = DEFAULT_METALLIC,
    roughness: Float = DEFAULT_ROUGHNESS,
    spinning: Boolean = true,
) {
    /** The shapes on screen. A hidden shape keeps its slot — see [GeometryLayout]. */
    var visibleShapes: Set<GeometryShape> by mutableStateOf(visibleShapes)
        private set

    /** PBR metallic factor shared by every shape, 0 (dielectric) to 1 (metal). */
    var metallic: Float by mutableFloatStateOf(metallic)

    /** PBR roughness factor shared by every shape, 0 (mirror) to 1 (matte). */
    var roughness: Float by mutableFloatStateOf(roughness)

    /** Whether the shapes turn. */
    var spinning: Boolean by mutableStateOf(spinning)

    /**
     * Bumped whenever the camera has to return to its framing. The screen rebuilds its orbit on
     * this key — a Filament manipulator has no "go home" call.
     */
    var cameraHomeGeneration: Int by mutableIntStateOf(0)
        private set

    fun isVisible(shape: GeometryShape): Boolean = shape in visibleShapes

    /** Shows [shape] if it is hidden, hides it otherwise. */
    fun toggle(shape: GeometryShape) {
        visibleShapes = if (shape in visibleShapes) visibleShapes - shape else visibleShapes + shape
    }

    /** Brings the camera back to its framing; the scene itself is left as the user set it. */
    fun recenter() {
        cameraHomeGeneration++
    }

    /** Back to the screen as it opens: every shape, the default material, turning, camera home. */
    fun reset() {
        visibleShapes = ALL_SHAPES
        metallic = DEFAULT_METALLIC
        roughness = DEFAULT_ROUGHNESS
        spinning = true
        cameraHomeGeneration++
    }

    companion object {
        const val DEFAULT_METALLIC = 0.3f
        const val DEFAULT_ROUGHNESS = 0.5f

        val ALL_SHAPES: Set<GeometryShape> = GeometryShape.entries.toSet()

        /**
         * Survives a rotation: what the user hid and dialled in is theirs. The camera is not
         * saved — the rotated window has another framing, and takes it from home.
         */
        val Saver: Saver<GeometryDemoState, Any> = listSaver(
            save = { state ->
                listOf(
                    state.visibleShapes.map { it.name },
                    state.metallic,
                    state.roughness,
                    state.spinning,
                )
            },
            restore = { saved ->
                @Suppress("UNCHECKED_CAST")
                val names = saved[0] as List<String>
                GeometryDemoState(
                    visibleShapes = GeometryShape.entries.filterTo(mutableSetOf()) { it.name in names },
                    metallic = saved[1] as Float,
                    roughness = saved[2] as Float,
                    spinning = saved[3] as Boolean,
                )
            },
        )
    }
}
