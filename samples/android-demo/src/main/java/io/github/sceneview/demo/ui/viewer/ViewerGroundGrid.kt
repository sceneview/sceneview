// Named for its composable, `ViewerGroundGrid`; `ViewerGridMath` is the material's Kotlin side.
@file:Suppress("MatchingDeclarationName")

package io.github.sceneview.demo.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.google.android.filament.MaterialInstance
import io.github.sceneview.SceneScope
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * The ground grid every 3D viewer of the demo shares (#4651) — `viewer_grid.mat`'s side in
 * Kotlin: what it is fed, and the same level-of-detail rule the shader applies, for the tests.
 *
 * The grid is endless and metric: a minor line every power of ten that suits the zoom, a major
 * one every tenth minor line, both fading out with the distance from the eye — Blender's viewport
 * grid, on whatever floor the viewer already has.
 */
internal object ViewerGridMath {
    /** `viewer_grid.mat`, compiled by `tools/GenerateFilamat.sh`. */
    const val MATERIAL = "materials/viewer_grid.filamat"

    /** Where the fade starts, in eye-to-target distances from the eye. */
    const val FADE_START = 1.1f

    /** Where the grid has faded out, in eye-to-target distances from the eye. Also its reach. */
    const val FADE_END = 4f

    /** About how many minor cells fit in one eye-to-target distance at the finest level. */
    const val CELLS_PER_DISTANCE = 30f

    /** Width of a major line, in pixels; minor lines are one pixel. */
    const val MAJOR_WIDTH_PX = 1.5f

    /**
     * Draw order among the translucent renderables (Filament's default is 4): before the contact
     * shadows, so the shadow darkens the lines under the subject instead of the lines cutting it.
     */
    const val PRIORITY = 2

    /**
     * The finest cell, in metres, for an eye [eyeDistance] metres from its target, and how far it
     * has faded out (`0` fully drawn, towards `1` gone): the shader's level of detail. The minor
     * lines are ten of these, the major lines a hundred.
     */
    fun finestCell(eyeDistance: Float): Pair<Float, Float> {
        val d = eyeDistance.takeIf { it.isFinite() && it > MIN_DISTANCE } ?: MIN_DISTANCE
        val level = log10(d / CELLS_PER_DISTANCE)
        val base = floor(level)
        return 10f.pow(base) to (level - base)
    }

    /** Minor cell size, in metres, at [eyeDistance]. */
    fun minorCell(eyeDistance: Float): Float = finestCell(eyeDistance).first * 10f

    /** Major cell size, in metres, at [eyeDistance]. */
    fun majorCell(eyeDistance: Float): Float = finestCell(eyeDistance).first * 100f

    /**
     * The grid's alpha at [distanceFromEye] metres from an eye [eyeDistance] metres from its
     * target: `1` near the eye, smoothstep to `0` from [FADE_START] to [FADE_END] distances.
     */
    fun fade(distanceFromEye: Float, eyeDistance: Float): Float {
        val start = FADE_START * eyeDistance
        val end = FADE_END * eyeDistance
        if (end <= start) return 0f
        val t = ((distanceFromEye - start) / (end - start)).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)
    }

    /**
     * The `float4` to give the material for a line in [token]: the linear colour the tone mapper
     * brings back to [token], and the token's own alpha, not premultiplied. Filament does not
     * expose an unlit base colour (only emissive light is), so, like the backdrop's skybox colour,
     * it is not divided by the camera's exposure.
     */
    fun lineColor(token: Color): FloatArray {
        val linear = ViewerBackdrop.linearBeforeToneMap(token.copy(alpha = 1f))
        return floatArrayOf(linear[0], linear[1], linear[2], token.alpha)
    }

    /** The minor and major line tokens for the theme: `stage-grid-minor` / `stage-grid-major`. */
    fun tokens(isDark: Boolean): Pair<Color, Color> = if (isDark) {
        SceneViewTokens.ViewerGrid.DarkMinor to SceneViewTokens.ViewerGrid.DarkMajor
    } else {
        SceneViewTokens.ViewerGrid.LightMinor to SceneViewTokens.ViewerGrid.LightMajor
    }

    private const val MIN_DISTANCE = 1e-4f
}

/**
 * The endless, fading ground grid of a 3D viewer (#4651), on the floor at [floorY] under a camera
 * that orbits [target].
 *
 * One quad, re-centred under the eye by the material every frame, so the grid never shows an
 * edge; its cells follow the zoom in powers of ten and it fades out with the distance. Drawn over
 * the viewer's own floor — the showroom's lit cyclorama, the Explore sheet — never instead of it:
 * the view's fog, where there is one, carries the far lines into the horizon with the floor.
 *
 * @param isDark   Which pair of `stage-grid-*` tokens: the theme the floor under it is drawn in.
 * @param requestRender Asks an on-demand render loop for the frame that shows a new colour.
 */
@Composable
internal fun SceneScope.ViewerGroundGrid(
    floorY: Float,
    target: Position,
    isDark: Boolean,
    requestRender: () -> Unit = {},
) {
    val grid = remember(materialLoader) {
        ViewerGridMaterial(materialLoader.createInstance(materialLoader.createMaterial(ViewerGridMath.MATERIAL)))
    }
    SideEffect {
        if (grid.dress(target, isDark)) requestRender()
    }
    PlaneNode(
        // A unit quad: the material stretches it to the fade's reach around the eye.
        size = Size(1f, 0f, 1f),
        materialInstance = grid.instance,
        position = Position(target.x, floorY, target.z),
        apply = {
            isShadowCaster = false
            isShadowReceiver = false
            isTouchable = false
            // The quad follows the eye in the vertex stage: its own box says nothing about where.
            setCulling(false)
            engine.renderableManager.let { it.setPriority(it.getInstance(entity), ViewerGridMath.PRIORITY) }
        },
    )
}

/** The grid's material instance, freed by the `MaterialLoader` that made it. */
private class ViewerGridMaterial(val instance: MaterialInstance) {
    /** What [dress] last wrote, so an unchanged frame writes nothing. */
    private var dressed: List<Any>? = null

    /** Writes the grid's parameters; `true` when anything changed. */
    fun dress(target: Position, isDark: Boolean): Boolean {
        val key = listOf(target.x, target.y, target.z, isDark)
        if (dressed == key) return false
        dressed = key
        val (minorToken, majorToken) = ViewerGridMath.tokens(isDark)
        val minor = ViewerGridMath.lineColor(minorToken)
        val major = ViewerGridMath.lineColor(majorToken)
        instance.setParameter("minorColor", minor[0], minor[1], minor[2], minor[3])
        instance.setParameter("majorColor", major[0], major[1], major[2], major[3])
        instance.setParameter("target", target.x, target.y, target.z)
        instance.setParameter(
            "shape",
            ViewerGridMath.FADE_START,
            ViewerGridMath.FADE_END,
            ViewerGridMath.CELLS_PER_DISTANCE,
            ViewerGridMath.MAJOR_WIDTH_PX,
        )
        return true
    }
}
