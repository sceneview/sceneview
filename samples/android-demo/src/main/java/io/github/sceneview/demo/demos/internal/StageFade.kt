package io.github.sceneview.demo.demos.internal

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Skybox
import com.google.android.filament.View
import io.github.sceneview.SceneScope
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.math.Size

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
 *   direction (`skyColor`), so the floor runs into the photograph at the horizon without a seam;
 *   with no sky, the stage colour, which the backdrop is painted in too ([stageBackdrop]).
 *
 * Every call is a raw `View` write — main thread, like every Filament call — and asks for no
 * frame: the callers already render on the change that brought them here.
 */
object StageFade {

    /** The stage colour as the linear RGB Filament works in — `stage-background` in DESIGN.md. */
    private val stageLinear: FloatArray = SceneViewTokens.Stage.background
        .convert(ColorSpaces.LinearSrgb)
        .let { floatArrayOf(it.red, it.green, it.blue) }

    /**
     * Applies the fade for an eye [cameraDistance] from the orbit target.
     *
     * @param sky the photographic skybox the frame draws behind the floor, or null when the
     *   background is the stage colour. The fog samples that skybox's own cubemap: Filament picks
     *   the mip from the fragment's distance, so the far floor takes the sky's sharp picture right
     *   below the horizon while the near fade stays soft. The blurred IBL (`fogColorFromIbl`
     *   alone) averages the whole environment instead, and meets a dark treeline or a bright
     *   studio wall at a hard line. The tint is neutral: Filament scales the fog's sample by the
     *   IBL's intensity, and it draws the skybox at that same intensity whenever an IBL is set
     *   (the skybox's own `intensity` is ignored then), so the two already agree.
     */
    fun apply(
        view: View,
        cameraDistance: Float,
        sky: Skybox?,
    ) {
        val skyTexture = sky?.texture
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
            fog.fogColorFromIbl = sky != null
            if (sky != null) {
                fog.color[0] = 1f
                fog.color[1] = 1f
                fog.color[2] = 1f
            } else {
                fog.color[0] = stageLinear[0]
                fog.color[1] = stageLinear[1]
                fog.color[2] = stageLinear[2]
            }
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
 * The lighting stage's floor, identical on both lighting screens: a 240 m floor that the stage
 * fade dissolves before its edge, with the shadow-receiving inset on top of it (see
 * [LightingStage.SHADOW_FLOOR_SIZE] for why the two are split). Neither casts: there is nothing
 * under the floor to shade.
 */
@Composable
fun SceneScope.LightingStageFloor(materialInstance: MaterialInstance) {
    CubeNode(
        size = Size(LightingStage.FLOOR_SIZE, LightingStage.FLOOR_THICKNESS, LightingStage.FLOOR_SIZE),
        materialInstance = materialInstance,
        position = LightingStage.outerFloorCenter,
        apply = {
            isShadowCaster = false
            isShadowReceiver = false
        },
    )
    CubeNode(
        size = Size(
            LightingStage.SHADOW_FLOOR_SIZE,
            LightingStage.FLOOR_THICKNESS,
            LightingStage.SHADOW_FLOOR_SIZE,
        ),
        materialInstance = materialInstance,
        position = LightingStage.floorCenter,
        apply = { isShadowCaster = false },
    )
}
