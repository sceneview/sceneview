package io.github.sceneview.demo.demos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.google.android.filament.LightManager
import io.github.sceneview.SceneView
import io.github.sceneview.audio.AudioFalloff
import io.github.sceneview.audio.AudioListener
import io.github.sceneview.audio.SpatialAudioNode
import io.github.sceneview.audio.rememberAudioSource
import io.github.sceneview.audio.setSpatialAudioListenerPose
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.GlassSurface
import io.github.sceneview.material.setColor
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.sample.rememberMaterialInstance
import io.github.sceneview.sample.rememberUnlitMaterialInstance
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Demonstrates positional 3D audio with [SpatialAudioNode].
 *
 * **What the scene shows** (#3332 — the first version showed two anonymous
 * spheres and no cue about where the sound came from):
 *  * The **big purple sphere is the sound source** — the looping bell is
 *    attached to it via [SpatialAudioNode]. Translucent shells pulse outward
 *    from it so the emitter is unmistakable even with the sound muted.
 *  * The **thin ring on the ground is its orbit path**, not a second object.
 *    The former tiny centre sphere is gone: it carried no meaning and read as
 *    "the other ball".
 *  * The **listener is the camera — the user**. It has no in-scene body by
 *    design (you are behind the screen), so the legend says so in words and a
 *    live readout reports the source-to-you distance and the resulting volume.
 *
 * Drag to orbit the camera — the audio bus updates every frame from the camera
 * pose, so circling the scene the sound rotates with you (because the source
 * stays anchored to the world while your ears move).
 *
 * Two falloff curves are pickable, and the readout's volume bar makes the
 * difference between them legible without headphones:
 *  * **Inverse** (default) — physically realistic; the bell becomes faint at
 *    long range but never quite silent.
 *  * **Linear** — drops to silence at the picked `maxDistance` (4 m here).
 *
 * Implementation notes:
 *  * The audio asset is `audio/bell.wav` (CC0, see `assets/audio/CREDITS.md`).
 *  * The sphere position is updated from a `LaunchedEffect` that ticks every
 *    frame via `withFrameNanos`, so the source moves in the recomposition
 *    domain (rather than via `Node.onFrame` imperative writes — the audio
 *    node reads the Compose `position` parameter through `SideEffect`).
 *  * The wave shells animate through `scale` and a per-frame alpha, never
 *    through `radius`: a radius change rebuilds the sphere geometry, a scale
 *    change is a transform push (#2653).
 *  * The camera listener pose is pushed every frame from the SceneView's
 *    `onFrame` callback so audio pan / gain track the camera in real time,
 *    even when the user orbits via touch.
 */
@Composable
fun SpatialAudioDemo(onBack: () -> Unit) {
    var falloffMode by remember { mutableStateOf(FalloffMode.Inverse) }
    val firstFrame = rememberFirstFrameState()

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)

    // Camera home — close enough that the orbiting sphere fills a comfortable
    // portion of the viewport (the orbit radius is only 0.6 m), but high enough
    // to show the ground plate for depth. The look-at hugs the orbit plane so
    // the sphere never disappears off-screen.
    val cameraNode = rememberCameraNode(engine) {
        position = Position(0f, 0.45f, 1.4f)
        lookAt(Position(0f, 0f, 0f))
    }

    val audioSource = rememberAudioSource("audio/bell.wav")

    // Orbit angle and wave phase tick via withFrameNanos — keeps the source
    // movement in the Compose recomposition domain so the SpatialAudioNode's
    // SideEffect picks up the new position and pushes it through the engine
    // each frame.
    var orbitAngleRad by remember { mutableFloatStateOf(0f) }
    var waveProgress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var lastNanos = 0L
        androidx.compose.runtime.withFrameNanos { lastNanos = it }
        while (true) {
            androidx.compose.runtime.withFrameNanos { nowNanos ->
                val dtSec = (nowNanos - lastNanos).coerceAtLeast(0L) / 1_000_000_000f
                lastNanos = nowNanos
                // ~0.5 revolution per second — slow enough to hear the pan, fast
                // enough that the demo's "thing" is visible immediately.
                orbitAngleRad =
                    (orbitAngleRad + dtSec * 2f * PI.toFloat() * 0.5f) % (2f * PI.toFloat())
                waveProgress = (waveProgress + dtSec / WAVE_PERIOD_SEC) % 1f
            }
        }
    }

    val spherePosition = Position(
        x = cos(orbitAngleRad) * ORBIT_RADIUS,
        y = 0f,
        z = sin(orbitAngleRad) * ORBIT_RADIUS,
    )

    val activeFalloff = remember(falloffMode) {
        when (falloffMode) {
            FalloffMode.Inverse -> AudioFalloff.Inverse(refDistance = 0.5f, maxDistance = 6f)
            FalloffMode.Linear -> AudioFalloff.Linear(refDistance = 0.2f, maxDistance = 4f)
        }
    }

    // Live source-to-listener distance, pushed from `onFrame` (the camera moves
    // under touch, outside recomposition). Quantised to the centimetre the
    // readout prints: an unfiltered float would recompose the overlay on every
    // frame for a value that renders identically.
    var listenerDistanceMeters by remember { mutableFloatStateOf(0f) }
    // The very gain the audio backend applies — same helper every SceneView
    // backend calls, so the bar cannot drift from what the ears hear.
    val gain = AudioFalloff.gainFor(activeFalloff, listenerDistanceMeters)

    DemoScaffold(
        title = stringResource(R.string.demo_spatial_audio_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        topOverlay = {
            SpatialAudioLegend(
                distanceMeters = listenerDistanceMeters,
                gain = gain,
            )
        },
        controls = {
            Text(
                stringResource(R.string.demo_spatial_audio_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.demo_spatial_audio_falloff_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)
            ) {
                FilterChip(
                    selected = falloffMode == FalloffMode.Inverse,
                    onClick = { falloffMode = FalloffMode.Inverse },
                    label = { Text(stringResource(R.string.demo_spatial_audio_inverse)) },
                )
                FilterChip(
                    selected = falloffMode == FalloffMode.Linear,
                    onClick = { falloffMode = FalloffMode.Linear },
                    label = { Text(stringResource(R.string.demo_spatial_audio_linear)) },
                )
            }
        }
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            materialLoader = materialLoader,
            cameraNode = cameraNode,
            cameraManipulator = rememberCameraManipulator(
                orbitHomePosition = cameraNode.worldPosition,
            ),
            onFrame = { nanos ->
                firstFrame.onFrame(nanos)
                // Drive the audio listener pose from the camera every frame —
                // even when the user is mid-orbit-drag this keeps pan / gain
                // synced to the live camera transform. The listener basis is
                // (position, forward, up) — the same convention on every
                // platform; the engine derives the right vector internally.
                val cam = cameraNode
                val pose = cam.worldTransform
                // kotlin-math Mat4 columns are .x .y .z .w. Camera looks down
                // -Z; up is +Y of the camera basis.
                val forward = Position(-pose.z.x, -pose.z.y, -pose.z.z)
                val up = Position(pose.y.x, pose.y.y, pose.y.z)
                setSpatialAudioListenerPose(
                    position = cam.worldPosition,
                    forward = forward,
                    up = up,
                )
                val camPosition = cam.worldPosition
                val dx = camPosition.x - spherePosition.x
                val dy = camPosition.y - spherePosition.y
                val dz = camPosition.z - spherePosition.z
                val distance = sqrt(dx * dx + dy * dy + dz * dz)
                if (kotlin.math.abs(distance - listenerDistanceMeters) >= READOUT_EPSILON_M) {
                    listenerDistanceMeters = distance
                }
            },
        ) {
            // Key light — warm directional that catches the orbiting sphere
            // from the upper-front so it always shows a lit face to the camera.
            LightNode(
                type = LightManager.Type.DIRECTIONAL,
                direction = io.github.sceneview.math.Direction(-0.3f, -1f, -0.5f),
                apply = {
                    color(1.0f, 0.97f, 0.92f)
                    intensity(12_000f)
                }
            )
            // Cool fill from the opposite side — lifts the shaded half of the
            // sphere so it never reads as a flat dark dot against the backdrop.
            LightNode(
                type = LightManager.Type.DIRECTIONAL,
                direction = io.github.sceneview.math.Direction(0.5f, -0.4f, 0.6f),
                apply = {
                    color(0.85f, 0.9f, 1.0f)
                    intensity(4_000f)
                }
            )

            // Ground "shadow" plate — a dim surface set just below the orbit
            // plane gives the sphere a grounded backdrop to read against.
            val groundMaterial = rememberMaterialInstance(
                materialLoader, SceneViewColors.SurfaceDim
            )
            PlaneNode(
                materialInstance = groundMaterial,
                size = Size(x = 2f, y = 0f, z = 2f),
                position = Position(y = -0.3f),
            )

            // The orbit path, drawn as a faint closed polyline in the orbit
            // plane. It replaces the old centre marker sphere: the user asked
            // what the second ball was, and the honest answer was "the circle
            // the first one travels on" — so the circle is now what is drawn.
            val orbitPathMaterial = rememberUnlitMaterialInstance(
                materialLoader, SceneViewColors.TintLight.copy(alpha = ORBIT_PATH_ALPHA)
            )
            PathNode(
                points = remember { circlePoints(ORBIT_RADIUS) },
                closed = true,
                materialInstance = orbitPathMaterial,
            )

            // Sound waves leaving the source — translucent unlit shells that
            // expand and fade, three of them a third of a period apart so a
            // wave is always on its way out. Animated by `scale` + alpha only.
            val waveMaterials = WAVE_PHASES.indices.map { index ->
                key(index) {
                    rememberUnlitMaterialInstance(
                        materialLoader,
                        SceneViewColors.TintSoft.copy(alpha = WAVE_ALPHA_MAX),
                    )
                }
            }
            SideEffect {
                WAVE_PHASES.forEachIndexed { index, phase ->
                    val t = (waveProgress + phase) % 1f
                    waveMaterials[index].setColor(
                        SceneViewColors.TintSoft.copy(alpha = (1f - t) * WAVE_ALPHA_MAX)
                    )
                }
            }
            WAVE_PHASES.forEachIndexed { index, phase ->
                val t = (waveProgress + phase) % 1f
                SphereNode(
                    radius = WAVE_BASE_RADIUS,
                    materialInstance = waveMaterials[index],
                    position = spherePosition,
                    scale = Scale(WAVE_MIN_SCALE + (WAVE_MAX_SCALE - WAVE_MIN_SCALE) * t),
                )
            }

            // The emitter itself — the one solid object in the scene, and the
            // node the SpatialAudioNode below is pinned to.
            val emitterMaterial = rememberMaterialInstance(
                materialLoader, SceneViewColors.TintSoft
            )
            SphereNode(
                radius = EMITTER_RADIUS,
                materialInstance = emitterMaterial,
                position = spherePosition,
            )

            // Listener — Camera (the default). Declared explicitly here for
            // readability; semantically equivalent to omitting the line.
            AudioListener()

            // Positional audio attached to the emitter — loops indefinitely.
            audioSource?.let { src ->
                SpatialAudioNode(
                    source = src,
                    position = spherePosition,
                    falloff = activeFalloff,
                    loop = true,
                    autoPlay = true,
                    volume = 1f,
                )
            }
        }
    }
}

/**
 * The floating legend: who is who in the scene, plus the live distance and the
 * gain the falloff curve is currently applying.
 *
 * Glass chrome over media, so it is theme-independent by construction (see
 * [GlassSurface]) — white on the viewport in both light and dark.
 */
@Composable
private fun SpatialAudioLegend(distanceMeters: Float, gain: Float) {
    GlassSurface(
        modifier = Modifier
            .padding(horizontal = SceneViewTokens.Space.md)
            .widthIn(max = LEGEND_MAX_WIDTH),
        shape = RoundedCornerShape(SceneViewTokens.Radius.md),
    ) {
        Column(
            modifier = Modifier.padding(SceneViewTokens.Space.md),
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
        ) {
            LegendRow(
                swatch = {
                    // Filled dot in the emitter's own colour.
                    Box(
                        Modifier
                            .size(LEGEND_SWATCH_SIZE)
                            .background(SceneViewColors.TintSoft, CircleShape)
                    )
                },
                label = stringResource(R.string.demo_spatial_audio_legend_source),
            )
            LegendRow(
                swatch = {
                    // Hollow ring — the listener has no body in the scene; it
                    // is the camera, i.e. the person holding the phone.
                    Box(
                        Modifier
                            .size(LEGEND_SWATCH_SIZE)
                            .border(
                                SceneViewTokens.Glass.borderWidth,
                                SceneViewTokens.Glass.onGlass,
                                CircleShape,
                            )
                    )
                },
                label = stringResource(R.string.demo_spatial_audio_legend_listener),
            )
            Text(
                text = stringResource(
                    R.string.demo_spatial_audio_readout,
                    String.format(Locale.getDefault(), "%.2f", distanceMeters),
                    (gain * 100f).roundToInt(),
                ),
                style = SceneViewTokens.Type.caption,
                color = SceneViewTokens.Glass.onGlass,
            )
            LinearProgressIndicator(
                progress = { gain.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LEGEND_METER_HEIGHT)
                    // The number above already carries the value for TalkBack.
                    .clearAndSetSemantics { },
                color = SceneViewTokens.Glass.onGlass,
                trackColor = SceneViewTokens.Glass.onGlassMuted.copy(alpha = LEGEND_TRACK_ALPHA),
            )
        }
    }
}

@Composable
private fun LegendRow(swatch: @Composable () -> Unit, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
    ) {
        swatch()
        Text(
            text = label,
            style = SceneViewTokens.Type.caption,
            color = SceneViewTokens.Glass.onGlassMuted,
        )
    }
}

/** Points of a closed circle of [radius] in the XZ plane, centred on the origin. */
private fun circlePoints(radius: Float, segments: Int = 64): List<Position> =
    List(segments) { index ->
        val angle = 2f * PI.toFloat() * index / segments
        Position(x = cos(angle) * radius, y = 0f, z = sin(angle) * radius)
    }

private enum class FalloffMode { Inverse, Linear }

/** Radius (m) of the circle the sound source travels on. */
private const val ORBIT_RADIUS = 0.6f

/** Radius (m) of the solid sphere the audio node is attached to. */
private const val EMITTER_RADIUS = 0.16f

/** Geometry radius (m) of a wave shell — animated through `scale`, never here. */
private const val WAVE_BASE_RADIUS = 0.2f
private const val WAVE_MIN_SCALE = 1.0f
private const val WAVE_MAX_SCALE = 3.4f

/** One full expansion of a shell, in seconds. */
private const val WAVE_PERIOD_SEC = 1.6f

/** Alpha of a wave shell the instant it leaves the emitter; it fades to 0. */
private const val WAVE_ALPHA_MAX = 0.30f

/** Alpha of the orbit-path polyline — present, never competing with the source. */
private const val ORBIT_PATH_ALPHA = 0.45f

/** Three shells, evenly spread over the period. */
private val WAVE_PHASES = listOf(0f, 1f / 3f, 2f / 3f)

/** Distance change (m) below which the readout is not worth a recomposition. */
private const val READOUT_EPSILON_M = 0.01f

private val LEGEND_MAX_WIDTH = 320.dp
private val LEGEND_SWATCH_SIZE = 12.dp
private val LEGEND_METER_HEIGHT = 4.dp
private const val LEGEND_TRACK_ALPHA = 0.32f
