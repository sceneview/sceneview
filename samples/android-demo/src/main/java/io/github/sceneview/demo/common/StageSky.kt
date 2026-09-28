package io.github.sceneview.demo.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.google.android.filament.Engine
import com.google.android.filament.Skybox
import com.google.android.filament.View
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.theme.StageChrome
import io.github.sceneview.demo.theme.themedStageChrome
import io.github.sceneview.math.colorOf
import io.github.sceneview.math.toLinearSpace
import io.github.sceneview.safeDestroySkybox

/**
 * `DESIGN.md` — Themed stage, *stage sky* (#4089): the backdrop of a demo whose subjects stand on
 * an open floor, and the floor itself.
 *
 * A floor plane on an empty clear colour ends in a hard horizon with nothing above it: the black
 * half-screen the Camera & Gestures demo showed in every low view. The home hero solved the same
 * problem with a sky whose horizon colour is also its fog colour, so the far ground dissolves into
 * it. A camera you can orbit cannot use the hero's Compose gradient — the horizon moves up and
 * down the screen with the elevation — so here the sky is drawn *in* the scene, by two things:
 *
 * - a flat [zenith] skybox;
 * - height fog in [horizon]. Filament fogs the skybox too, and a ray that leaves the camera level
 *   crosses an endless layer of fog while one that looks up soon leaves it: the sky is [horizon] at
 *   the horizon and clears to [zenith] overhead, and the floor, which is all at the fog's densest,
 *   fades into the same [horizon] with distance.
 *
 * Floor, horizon glow and sky are one gradient at every camera angle, so there is no edge left to
 * find.
 *
 * Every colour is an existing token: the zenith is the themed stage's ground, the horizon is
 * `surface-container`, the floor is `surface-container-highest` in light and the dark
 * `surface-dim` grounding plane in dark. In both themes the horizon is the brightest band.
 */
@Immutable
class StageSky(
    /** Skybox colour, seen straight up. */
    val zenith: Color,
    /** Fog colour: the far floor and the sky just above the horizon. */
    val horizon: Color,
    /** Base colour of the floor plane the subjects stand on. */
    val floor: Color,
    /**
     * Measuring-grid lines drawn on [floor] (#4083): `outline` in light, where `outline-variant`
     * all but vanishes on the pale floor, and `outline-variant` in dark, where `outline` glares.
     */
    val grid: Color,
)

/** The [StageSky] for the current theme, matching [themedStageChrome]. */
@Composable
fun themedStageSky(): StageSky {
    val chrome = themedStageChrome()
    val light = chrome === StageChrome.Light
    return StageSky(
        zenith = chrome.ground,
        horizon = MaterialTheme.colorScheme.surfaceContainer,
        floor = if (light) SceneViewColors.SurfaceLight else SceneViewColors.SurfaceDim,
        grid = if (light) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * The flat [StageSky.zenith] skybox, created once and recoloured in place when the theme flips —
 * the demo activity handles `uiMode` itself, so a light/dark toggle must not destroy a skybox the
 * scene is still drawing. Destroyed with the screen, on the main thread.
 */
@Composable
fun rememberStageSkybox(engine: Engine, sky: StageSky, requestRender: () -> Unit): Skybox {
    val skybox = remember(engine) { Skybox.Builder().color(0f, 0f, 0f, 1f).build(engine) }
    DisposableEffect(skybox) { onDispose { engine.safeDestroySkybox(skybox) } }
    LaunchedEffect(skybox, sky.zenith) {
        val zenith = colorOf(sky.zenith).toLinearSpace()
        skybox.setColor(zenith.r, zenith.g, zenith.b, 1f)
        requestRender()
    }
    return skybox
}

/**
 * Applies [sky]'s height fog to [view], for a floor at `y = 0` watched from a few metres: the
 * subjects stay clear, the floor has become the horizon colour well before its edge, and the sky
 * clears towards the zenith over the upper half of a low view.
 */
@Composable
fun StageSkyFog(view: View, sky: StageSky, requestRender: () -> Unit) {
    LaunchedEffect(view, sky.horizon) {
        val horizon = colorOf(sky.horizon).toLinearSpace()
        view.fogOptions = view.fogOptions.also { fog ->
            fog.enabled = true
            fog.color[0] = horizon.r
            fog.color[1] = horizon.g
            fog.color[2] = horizon.b
            fog.distance = StageSkyFogDefaults.START
            fog.density = StageSkyFogDefaults.DENSITY
            fog.height = 0f
            fog.heightFalloff = StageSkyFogDefaults.HEIGHT_FALLOFF
            fog.maximumOpacity = 1f
            fog.fogColorFromIbl = false
            // No sun on these stages: no in-scattering glow.
            fog.inScatteringSize = -1f
        }
        requestRender()
    }
}

/** The fog constants behind [StageSkyFog]. */
object StageSkyFogDefaults {
    /** Metres from the camera before the fog begins: the subjects themselves stay clear. */
    const val START: Float = 5f

    /** Fog density at floor level, per metre. */
    const val DENSITY: Float = 0.2f

    /** 1 / the height, in metres, over which the fog thins by a factor e: the sky's gradient. */
    const val HEIGHT_FALLOFF: Float = 0.6f
}
