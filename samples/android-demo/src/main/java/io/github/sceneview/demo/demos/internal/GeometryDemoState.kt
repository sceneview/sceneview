package io.github.sceneview.demo.demos.internal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import io.github.sceneview.demo.SceneViewColors

/**
 * Everything the user can change on [io.github.sceneview.demo.demos.GeometryDemo]: which shapes
 * are shown, what they are made of, whether they turn, and where the camera calls home.
 *
 * Held outside the composable so the rules — what Reset restores, what Recenter leaves alone —
 * are plain Kotlin a JVM test can pin.
 */
@Stable
internal class GeometryDemoState(
    visibleShapes: Set<GeometryShape> = ALL_SHAPES,
    finish: GeometryFinish = GeometryFinish.Studio,
    metallic: Float = DEFAULT_METALLIC,
    roughness: Float = DEFAULT_ROUGHNESS,
    spinning: Boolean = true,
) {
    /** The shapes on screen. A hidden shape keeps its slot — see [GeometryLayout]. */
    var visibleShapes: Set<GeometryShape> by mutableStateOf(visibleShapes)
        private set

    /** Whether each shape wears its own studio finish or all share [metallic] and [roughness]. */
    var finish: GeometryFinish by mutableStateOf(finish)

    /**
     * PBR metallic factor shared by every shape in [GeometryFinish.Uniform], 0 (dielectric) to 1
     * (metal). Setting it switches to that finish: a slider that moved nothing would read broken.
     */
    var metallic: Float by mutableFloatStateOf(metallic)
        private set

    /** PBR roughness factor shared in [GeometryFinish.Uniform], 0 (mirror) to 1 (matte). */
    var roughness: Float by mutableFloatStateOf(roughness)
        private set

    /** Drags the shared metallic factor, and switches every shape to it. */
    fun dialMetallic(value: Float) {
        metallic = value
        finish = GeometryFinish.Uniform
    }

    /** Drags the shared roughness factor, and switches every shape to it. */
    fun dialRoughness(value: Float) {
        roughness = value
        finish = GeometryFinish.Uniform
    }

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

    /** Back to the screen as it opens: every shape, the studio finishes, turning, camera home. */
    fun reset() {
        visibleShapes = ALL_SHAPES
        finish = GeometryFinish.Studio
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
                    state.finish.name,
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
                    finish = GeometryFinish.valueOf(saved[4] as String),
                )
            },
        )
    }
}

/**
 * What the shapes are made of on [io.github.sceneview.demo.demos.GeometryDemo].
 *
 * - [Studio]: each primitive wears a finish of its own from the *Materials* library — chrome,
 *   gold, glazed ceramic… — so the reflections of the studio light are what the eye reads first.
 * - [Uniform]: one metallic-roughness pair, dialled in Settings, on every shape: the whole PBR
 *   range from chalk to mirror, compared across seven silhouettes.
 */
internal enum class GeometryFinish { Studio, Uniform }

/**
 * The finish [GeometryFinish.Studio] puts on each shape, built on the *Materials* library so the
 * stage shows the range a product render has: a mirror, two polished metals, a satin one, a
 * glaze and two clear-coated lacquers.
 *
 * The round shapes take the metals, because a curved mirror is what shows the most of the
 * studio. The cube and the plane take the clear coat, whose second, sharp highlight runs across
 * flat faces a metal would only fill with one tone — in the two brand tints the stage was
 * designed for, since the library's `primary` car paint renders at about 2:1 on
 * `stage-background` and read as a dark hole. The library's brushed aluminium (roughness 0.55)
 * is taken down to a satin 0.3: at 0.55 a cylinder this size reads as grey plastic.
 */
internal val GeometryShape.studioFinish: StudioMaterial
    get() = when (this) {
        GeometryShape.Cube -> lacquer(SceneViewColors.TintLight)
        GeometryShape.Sphere -> MaterialStudio.material("chrome")
        GeometryShape.Cylinder -> MaterialStudio.material("aluminium").copy(roughness = SATIN_ROUGHNESS)
        GeometryShape.Cone -> MaterialStudio.material("ceramic")
        GeometryShape.Torus -> MaterialStudio.material("gold")
        GeometryShape.Capsule -> MaterialStudio.material("copper")
        GeometryShape.Plane -> lacquer(SceneViewColors.TintSoft)
    }

/** Roughness of the cylinder's satin aluminium. */
private const val SATIN_ROUGHNESS = 0.3f

/** Base roughness under the lacquer's coat: a soft diffuse body, the coat carries the gloss. */
private const val LACQUER_BASE_ROUGHNESS = 0.4f

private fun MaterialStudio.material(id: String): StudioMaterial = library.first { it.id == id }

/**
 * Glossy lacquered plastic: the library's car paint without its metallic flake — a dielectric
 * body in [tint] under a full, near-mirror clear coat.
 */
private fun lacquer(tint: Color): StudioMaterial = MaterialStudio.material("car-paint").copy(
    color = tint,
    metallic = 0f,
    roughness = LACQUER_BASE_ROUGHNESS,
)
