package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.sample.rememberMaterialInstance
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Demonstrates positional 3D audio with [SpatialAudioNode].
 *
 * A small sphere orbits a fixed centre at 0.6 m radius. A looping bell tone is
 * attached to the sphere via [SpatialAudioNode], so as the sphere swings past
 * you the audio pans from one ear to the other and grows softer / louder with
 * distance to the camera.
 *
 * Drag to orbit the camera — the audio bus updates every frame from the camera
 * pose, so circling the scene the sound rotates with you (because the source
 * stays anchored to the world while your ears move).
 *
 * Two falloff curves are pickable:
 *  * **Inverse** (default) — physically realistic; the bell becomes faint at
 *    long range but never quite silent.
 *  * **Linear** — drops to silence at the picked `maxDistance` (4 m here).
 *
 * Implementation notes:
 *  * The audio asset is `audio/bell.wav` (CC0, see `assets/audio/CREDITS.md`).
 *  * The sphere position is updated from a `LaunchedEffect` that ticks every
 *    16 ms via `withFrameNanos`, so the source moves in the recomposition
 *    domain (rather than via `Node.onFrame` imperative writes — the audio
 *    node reads the Compose `position` parameter through `SideEffect`).
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

    // Camera home — pulled back enough that the orbiting sphere sweeps a wide
    // stereo field on a typical phone. The look-at hugs the orbit plane so the
    // sphere never disappears off-screen.
    val cameraNode = rememberCameraNode(engine) {
        position = Position(0f, 0.6f, 2.2f)
        lookAt(Position(0f, 0f, 0f))
    }

    val audioSource = rememberAudioSource("audio/bell.wav")

    // Orbit angle ticks via withFrameNanos — keeps the source movement in the
    // Compose recomposition domain so the SpatialAudioNode's SideEffect picks
    // up the new position and pushes it through the engine each frame.
    var orbitAngleRad by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        var lastNanos = 0L
        androidx.compose.runtime.withFrameNanos { lastNanos = it }
        while (true) {
            androidx.compose.runtime.withFrameNanos { nowNanos ->
                val dtSec = (nowNanos - lastNanos).coerceAtLeast(0L) / 1_000_000_000f
                lastNanos = nowNanos
                // ~0.5 revolution per second — slow enough to hear the pan, fast
                // enough that the demo's "thing" is visible immediately.
                orbitAngleRad = (orbitAngleRad + dtSec * 2f * PI.toFloat() * 0.5f) % (2f * PI.toFloat())
            }
        }
    }

    val orbitRadius = 0.6f
    val spherePosition = Position(
        x = cos(orbitAngleRad) * orbitRadius,
        y = 0f,
        z = sin(orbitAngleRad) * orbitRadius,
    )

    val activeFalloff = remember(falloffMode) {
        when (falloffMode) {
            FalloffMode.Inverse -> AudioFalloff.Inverse(refDistance = 0.5f, maxDistance = 6f)
            FalloffMode.Linear -> AudioFalloff.Linear(refDistance = 0.2f, maxDistance = 4f)
        }
    }

    DemoScaffold(
        title = stringResource(R.string.demo_spatial_audio_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
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
                horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                // synced to the live camera transform. The "right" vector is
                // derived from the camera basis (column 0 of the transform).
                val cam = cameraNode
                val pose = cam.worldTransform
                // kotlin-math Mat4 columns are .x .y .z .w; right = -.x because
                // RealityKit / Filament conventions vary, but for our purposes
                // we pick the basis vector that makes "positive X relative to
                // camera" map to the right channel. Tested visually on the
                // emulator.
                val right = Position(pose.x.x, pose.x.y, pose.x.z)
                val forward = Position(-pose.z.x, -pose.z.y, -pose.z.z)
                setSpatialAudioListenerPose(
                    position = cam.worldPosition,
                    forward = forward,
                    right = right,
                )
            },
        ) {
            // Soft key light.
            LightNode(
                type = LightManager.Type.DIRECTIONAL,
                direction = io.github.sceneview.math.Direction(-0.3f, -1f, -0.5f),
                apply = { intensity(10_000f) }
            )

            // The orbiting sphere — visible carrier for the audio source.
            val orbiterMaterial = rememberMaterialInstance(
                materialLoader, SceneViewColors.Ramp4[2]
            )
            SphereNode(
                radius = 0.08f,
                materialInstance = orbiterMaterial,
                position = spherePosition,
            )

            // A small marker at the orbit centre so the user has a visual
            // anchor for where the camera is looking.
            val centerMaterial = rememberMaterialInstance(
                materialLoader, SceneViewColors.SurfaceDim
            )
            SphereNode(
                radius = 0.02f,
                materialInstance = centerMaterial,
                position = Position(x = 0f, y = 0f, z = 0f),
            )

            // Ground "shadow" plate.
            PlaneNode(
                materialInstance = centerMaterial,
                size = Size(x = 2f, y = 0f, z = 2f),
                position = Position(y = -0.5f),
            )

            // Listener — Camera (the default). Declared explicitly here for
            // readability; semantically equivalent to omitting the line.
            AudioListener()

            // Positional audio attached to the orbiter — loops indefinitely.
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

private enum class FalloffMode { Inverse, Linear }
