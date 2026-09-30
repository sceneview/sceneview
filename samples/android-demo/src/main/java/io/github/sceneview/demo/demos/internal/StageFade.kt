package io.github.sceneview.demo.demos.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Skybox
import com.google.android.filament.View
import io.github.sceneview.SceneScope
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Size
import io.github.sceneview.sample.rememberMaterialInstance

/**
 * The lighting stage's fade to infinity (#4072): the floor dissolves into whatever is behind
 * it, so the viewer never sees where it ends.
 *
 * Filament's height fog does the work, set up as a distance fade rather than as weather:
 *
 * - it starts just behind the orbit target ([LightingStage.stageFadeStart]), so the subject and
 *   the two probe balls are never touched;
 * - its density is solved so it is opaque before any ray can reach the floor's edge
 *   ([LightingStage.stageFadeDensity]);
 * - it is cut off past the floor ([LightingStage.STAGE_FADE_CUTOFF]), so the skybox — at
 *   infinity — is never fogged and stays the photograph it is;
 * - its colour is the background's: with a sky drawn, the sky's own picture sampled in the view
 *   direction (`skyColor`), so the floor runs into the photograph at the horizon without a seam
 *   — or, under a photographed room or street, that photograph's ground tone (see `ground`);
 *   with no sky, the stage colour, which the backdrop is painted in too ([stageBackdrop]).
 *
 * Every call is a raw `View` write — main thread, like every Filament call — and asks for no
 * frame: the callers already render on the change that brought them here.
 */
object StageFade {

    /** The stage colour as the linear RGB Filament works in — `stage-background` in DESIGN.md. */
    private val stageLinear: FloatArray = SceneViewTokens.Stage.background.toLinear()

    private fun Color.toLinear(): FloatArray =
        convert(ColorSpaces.LinearSrgb).let { floatArrayOf(it.red, it.green, it.blue) }

    /**
     * Applies the fade for an eye [cameraDistance] from the orbit target.
     *
     * @param sky the photographic skybox the frame draws behind the floor, or null when the
     *   background is the stage colour. The fog samples that skybox's own cubemap: Filament picks
     *   the mip from the fragment's distance, so the far floor takes the sky's sharp picture right
     *   below the horizon while the near fade stays soft. The blurred IBL (`fogColorFromIbl`
     *   alone) averages the whole environment instead, and meets a dark treeline or a bright
     *   studio wall at a hard line.
     * @param skyTint how much of that sky's brightness the far floor takes, from
     *   [LightingStage.stageFadeSkyTint]. Filament scales the fog's sample by the IBL's intensity
     *   and draws the skybox at that same intensity, so 1 would make the far floor as bright as
     *   the sky itself — and since a floor looks *down*, the sample is the photograph's lower
     *   half, whose blurred mips average in the sun and every lamp: at a full pinch-out the whole
     *   floor turned a flat, blown-out white, even at night. Under 1, the ground stays darker
     *   than the sky, as real ground does, and meets it at a horizon instead of dissolving into
     *   a glare. Ignored when [sky] is null.
     * @param ground a solid ground tone to fade into instead of the sky sample, for a sky that is
     *   a photograph of a room or a street rather than of the sky: their lower half holds windows
     *   and lamps many times brighter than the rest, no single [skyTint] keeps both the floor
     *   under a window and the floor under a wall readable, and facing the window at a full
     *   pinch-out turned the whole floor white. With a ground tone the floor meets the
     *   photograph at a horizon — which is what a floor under a photograph is. Ignored when
     *   [sky] is null.
     */
    fun apply(
        view: View,
        cameraDistance: Float,
        sky: Skybox?,
        skyTint: Float = LightingStage.STAGE_FADE_SKY_TINT,
        ground: Color? = null,
    ) {
        val skyTexture = sky?.texture?.takeIf { ground == null }
        val tone: FloatArray? = when {
            sky == null -> stageLinear
            ground != null -> ground.toLinear()
            else -> null
        }
        view.fogOptions = view.fogOptions.also { fog ->
            fog.enabled = true
            fog.distance = LightingStage.stageFadeStart(cameraDistance)
            fog.density = LightingStage.stageFadeDensity(cameraDistance)
            fog.cutOffDistance = LightingStage.STAGE_FADE_CUTOFF
            fog.maximumOpacity = 1f
            // Uniform: the fade is about distance along the floor, not about altitude.
            fog.heightFalloff = 0f
            fog.height = 0f
            fog.inScatteringSize = -1f
            fog.skyColor = skyTexture
            fog.fogColorFromIbl = skyTexture != null
            if (tone == null) {
                fog.color[0] = skyTint
                fog.color[1] = skyTint
                fog.color[2] = skyTint
            } else {
                fog.color[0] = tone[0]
                fog.color[1] = tone[1]
                fog.color[2] = tone[2]
            }
        }
    }

    /**
     * Hands the view's fog over to another writer — the Lab's `FogNode` — by clearing what only
     * the stage fade sets: `FogNode` writes its own colour, density and distances, but not the
     * sky texture, the opacity cap or the height, and a sky texture left behind would keep
     * colouring its fog with the photograph.
     */
    fun release(view: View) {
        view.fogOptions = view.fogOptions.also { fog ->
            fog.skyColor = null
            fog.fogColorFromIbl = false
            fog.maximumOpacity = 1f
            fog.height = 0f
            fog.inScatteringSize = -1f
        }
    }

    /**
     * A flat backdrop in the stage colour, for the frames that draw no sky. Without it the
     * background is the renderer's clear — black — and a floor fading into the stage colour
     * would stop at a visible band where the fog meets the clear. Fog and backdrop are the same
     * linear value, so the floor runs into the background with no seam. The caller owns it and
     * destroys it with [Engine.destroySkybox].
     */
    fun stageBackdrop(engine: Engine): Skybox = Skybox.Builder()
        .color(stageLinear[0], stageLinear[1], stageLinear[2], 1f)
        .build(engine)
}

/**
 * The lighting stage's floor, identical on both lighting screens: a 240 m slab that the stage
 * fade dissolves before its edge, and the shadow-receiving inset lying on it (see
 * [LightingStage.SHADOW_FLOOR_SIZE] for why the two are split and why the inset is a plane).
 * Neither casts: there is nothing under the floor to shade.
 *
 * Both wear the floor material ([LightingStage.FLOOR_COLOR]); the inset has its own instance
 * because the polygon offset that lets it win the depth test is set per instance.
 */
@Composable
fun SceneScope.LightingStageFloor() {
    val floorMaterial = rememberFloorMaterial(materialLoader)
    val insetInstance = rememberFloorMaterial(materialLoader)
    val insetMaterial = remember(insetInstance) {
        insetInstance.apply {
            setPolygonOffset(
                LightingStage.SHADOW_FLOOR_DEPTH_OFFSET,
                LightingStage.SHADOW_FLOOR_DEPTH_OFFSET,
            )
        }
    }
    CubeNode(
        size = Size(LightingStage.FLOOR_SIZE, LightingStage.FLOOR_THICKNESS, LightingStage.FLOOR_SIZE),
        materialInstance = floorMaterial,
        position = LightingStage.floorCenter,
        apply = {
            isShadowCaster = false
            isShadowReceiver = false
        },
    )
    PlaneNode(
        size = Size(x = LightingStage.SHADOW_FLOOR_SIZE, y = 0f, z = LightingStage.SHADOW_FLOOR_SIZE),
        materialInstance = insetMaterial,
        position = LightingStage.shadowFloorCenter,
        apply = { isShadowCaster = false },
    )
}

@Composable
private fun rememberFloorMaterial(materialLoader: MaterialLoader): MaterialInstance =
    rememberMaterialInstance(
        materialLoader,
        color = LightingStage.FLOOR_COLOR,
        metallic = 0f,
        roughness = LightingStage.FLOOR_ROUGHNESS,
        reflectance = LightingStage.FLOOR_REFLECTANCE,
    )
