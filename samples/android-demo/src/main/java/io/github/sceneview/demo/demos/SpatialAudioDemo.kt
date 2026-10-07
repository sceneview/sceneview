package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.sceneview.SceneView
import io.github.sceneview.audio.AudioFalloff
import io.github.sceneview.audio.AudioListener
import io.github.sceneview.audio.SpatialAudioNode
import io.github.sceneview.audio.rememberAudioSource
import io.github.sceneview.audio.setSpatialAudioListenerPose
import io.github.sceneview.createDefaultCameraManipulator
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.LocalDemoChromeBottomInset
import io.github.sceneview.demo.LocalDemoChromeTopInset
import io.github.sceneview.demo.LocalDemoSheetCover
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.common.rememberStageSkybox
import io.github.sceneview.demo.common.themedStageSky
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.material.setColor
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberRenderInvalidator
import io.github.sceneview.sample.rememberMaterialInstance
import io.github.sceneview.sample.rememberUnlitMaterialInstance
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sound stage: three procedural sources on a round floor, with independent CC0 loops.
 * A soft low sphere swells, a plucked mid column springs, and a high glassy cube spins.
 * Drag to orbit the listener (the camera), pinch closer, or mute a source by tap or dock.
 *
 * Each [rememberAudioSource] feeds one [SpatialAudioNode] at the visible object's centre.
 * [AudioListener] declares the intent; `onFrame` supplies the camera's world-space pose.
 * Floor rings show [AudioFalloff.gainFor] multiplied by the same mute volume as the player.
 * The independent idle animations evoke timbre, not note timing: MediaPlayer loops drift
 * and [io.github.sceneview.audio.AudioController] exposes no playback-position readout.
 * Geometry stays fixed; only transforms and material alpha animate (#2653).
 */
@Composable
fun SpatialAudioDemo(onBack: () -> Unit) {
    var falloffMode by remember { mutableStateOf(FalloffMode.Inverse) }
    val audible = remember { List(Emitter.entries.size) { mutableStateOf(true) } }
    val falloff = remember(falloffMode) {
        when (falloffMode) {
            FalloffMode.Inverse -> AudioFalloff.Inverse(refDistance = 0.4f, maxDistance = 12f)
            FalloffMode.Linear -> AudioFalloff.Linear(refDistance = 0.4f, maxDistance = 6f)
            FalloffMode.None -> AudioFalloff.None
        }
    }
    val engine = rememberEngine()
    val firstFrame = rememberFirstFrameState(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val invalidator = rememberRenderInvalidator()
    val sky = themedStageSky()
    val skybox = rememberStageSkybox(engine, sky, invalidator::requestRender)
    val baseEnvironment = rememberEnvironment(environmentLoader)
    val environment = remember(baseEnvironment, skybox) { baseEnvironment.copy(skybox = skybox) }
    val floorMaterial = rememberMaterialInstance(materialLoader, sky.floor, metallic = 0f)

    DemoScaffold(
        title = stringResource(R.string.demo_spatial_audio_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        themedStage = true,
        dock = Emitter.entries.map { emitter ->
            DockItem(
                icon = when (emitter) {
                    Emitter.Low -> Icons.Default.Circle
                    Emitter.Mid -> Icons.Default.MusicNote
                    Emitter.High -> Icons.Default.Star
                },
                label = stringResource(emitter.label),
                selected = audible[emitter.ordinal].value,
                onClick = { audible[emitter.ordinal].value = !audible[emitter.ordinal].value },
            )
        },
        controls = {
            Row(horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
                FalloffMode.entries.forEach { mode ->
                    FilterChip(
                        selected = falloffMode == mode,
                        onClick = { falloffMode = mode },
                        label = { Text(stringResource(mode.label)) },
                    )
                }
            }
            Text(
                text = stringResource(R.string.demo_spatial_audio_falloff_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        val safeArea = WindowInsets.safeDrawing.asPaddingValues()
        val top = LocalDemoChromeTopInset.current + safeArea.calculateTopPadding()
        val bottom = maxOf(
            LocalDemoChromeBottomInset.current + safeArea.calculateBottomPadding(),
            LocalDemoSheetCover.current,
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Fit a sphere enclosing the entire floor, including maximum wave reach.
            // The 16 mm lens has tan(vertical half-FOV) = 12 / 16; portrait width binds.
            val visibleHeight = (maxHeight - top - bottom).value.coerceAtLeast(1f)
            val aspect = maxWidth.value / visibleHeight
            val halfFovTangent = (12f / FOCAL_LENGTH.toFloat()) * minOf(aspect, 1f)
            val homeDistance = STAGE_RADIUS * FRAMING_MARGIN *
                sqrt(1f + halfFovTangent * halfFovTangent) / halfFovTangent
            val home = remember(homeDistance) {
                Position(0f, homeDistance * ELEVATION_SIN, homeDistance * ELEVATION_COS)
            }
            val target = remember { Position(0f) }
            val cameraNode = rememberCameraNode(engine) {
                focalLength = FOCAL_LENGTH
                position = home
                lookAt(target)
            }
            // Rebuild only when the available frame changes; SceneView eases the camera swap.
            val manipulator = rememberCameraManipulator(orbitRadius = homeDistance) {
                createDefaultCameraManipulator(eyePosition = home, targetPosition = target)
            }
            val distances = remember { List(Emitter.entries.size) { mutableFloatStateOf(homeDistance) } }
            var seconds by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(Unit) {
                val start = withFrameNanos { it }
                while (true) {
                    withFrameNanos { seconds = (it - start) / 1_000_000_000f }
                }
            }
            val circle = remember { circlePoints() }
            SceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                environment = environment,
                renderInvalidator = invalidator,
                autoCenterContent = false,
                cameraNode = cameraNode,
                cameraManipulator = manipulator,
                contentPadding = PaddingValues(top = top, bottom = bottom),
                onGestureListener = rememberOnGestureListener(
                    onSingleTapConfirmed = { _, node ->
                        Emitter.entries.firstOrNull { it.name == node?.name }?.let { emitter ->
                            val state = audible[emitter.ordinal]
                            state.value = !state.value
                        }
                    },
                ),
                onFrame = { nanos ->
                    firstFrame.onFrame(nanos)
                    val pose = cameraNode.worldTransform
                    val cameraPosition = cameraNode.worldPosition
                    setSpatialAudioListenerPose(
                        position = cameraPosition,
                        forward = Position(-pose.z.x, -pose.z.y, -pose.z.z),
                        up = Position(pose.y.x, pose.y.y, pose.y.z),
                    )
                    for (index in Emitter.entries.indices) {
                        val position = Emitter.entries[index].position
                        val dx = cameraPosition.x - position.x
                        val dy = cameraPosition.y - position.y
                        val dz = cameraPosition.z - position.z
                        val distance = sqrt(dx * dx + dy * dy + dz * dz)
                        // Sub-centimetre changes do not visibly change the floor rings.
                        if (abs(distance - distances[index].floatValue) >= READOUT_EPSILON_M) {
                            distances[index].floatValue = distance
                        }
                    }
                },
            ) {
                CylinderNode(
                    radius = STAGE_RADIUS,
                    height = FLOOR_THICKNESS,
                    sideCount = CIRCLE_SEGMENTS,
                    position = Position(y = -FLOOR_THICKNESS / 2f),
                    materialInstance = floorMaterial,
                    apply = { isHittable = false },
                )
                AudioListener()
                Emitter.entries.forEach { emitter ->
                    key(emitter) {
                        val index = emitter.ordinal
                        val volume = if (audible[index].value) 1f else 0f
                        val gain = AudioFalloff.gainFor(falloff, distances[index].floatValue) * volume
                        // A perceptual display curve keeps quiet sources legible; mute stays zero.
                        val waveStrength = sqrt(gain)
                        val tint = SceneViewColors.Ramp4[index]
                        val material = rememberMaterialInstance(materialLoader, tint)
                        val cycle = seconds / emitter.period
                        val pulse = (1f - cos(2f * PI.toFloat() * cycle)) / 2f
                        when (emitter) {
                            Emitter.Low -> SphereNode(
                                radius = SOURCE_RADIUS,
                                position = emitter.position,
                                scale = Scale(1f + 0.1f * pulse * volume),
                                materialInstance = material,
                                apply = { name = emitter.name },
                            )
                            Emitter.Mid -> CylinderNode(
                                radius = SOURCE_RADIUS,
                                height = SOURCE_HEIGHT,
                                position = emitter.position,
                                scale = Scale(1f, 1f + 0.2f * pulse * pulse * pulse * volume, 1f),
                                materialInstance = material,
                                apply = { name = emitter.name },
                            )
                            Emitter.High -> CubeNode(
                                size = Size(SOURCE_RADIUS * 1.5f),
                                position = emitter.position,
                                rotation = Rotation(45f, (cycle * 90f) % 360f, 45f),
                                materialInstance = material,
                                apply = { name = emitter.name },
                            )
                        }
                        repeat(WAVE_COUNT) { wave ->
                            val progress = (cycle + wave.toFloat() / WAVE_COUNT) % 1f
                            val reach = SOURCE_RADIUS +
                                (WAVE_REACH - SOURCE_RADIUS) * progress * waveStrength
                            // Seed a transparent material once; never re-key it on animated alpha.
                            val waveMaterial = rememberUnlitMaterialInstance(
                                materialLoader, tint.copy(alpha = 0f),
                            )
                            SideEffect {
                                waveMaterial.setColor(tint.copy(alpha = (1f - progress) * waveStrength))
                                invalidator.requestRender()
                            }
                            PathNode(
                                points = circle,
                                closed = true,
                                position = Position(emitter.position.x, RING_HEIGHT, emitter.position.z),
                                scale = Scale(reach),
                                materialInstance = waveMaterial,
                                apply = {
                                    isHittable = false
                                    isShadowCaster = false
                                },
                            )
                        }
                        rememberAudioSource(emitter.assetPath)?.let { source ->
                            SpatialAudioNode(
                                source = source,
                                position = emitter.position,
                                falloff = falloff,
                                loop = true,
                                autoPlay = true,
                                volume = volume,
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class FalloffMode(val label: Int) {
    Inverse(R.string.demo_spatial_audio_inverse),
    Linear(R.string.demo_spatial_audio_linear),
    None(R.string.demo_spatial_audio_none),
}

private enum class Emitter(val label: Int, val assetPath: String, val period: Float) {
    Low(R.string.demo_spatial_audio_low, "audio/stage_low.wav", 2f),
    Mid(R.string.demo_spatial_audio_mid, "audio/stage_mid.wav", 2.6f),
    High(R.string.demo_spatial_audio_high, "audio/stage_high.wav", 3.4f);

    val position: Position = run {
        val angle = -PI.toFloat() / 2f + ordinal * 2f * PI.toFloat() / 3f
        val height = if (ordinal == 0) SOURCE_RADIUS else SOURCE_HEIGHT / 2f
        Position(cos(angle) * SOURCE_SPREAD, height, sin(angle) * SOURCE_SPREAD)
    }
}

/** Unit circle in the floor's XZ plane; its geometry is shared by every wave. */
private fun circlePoints(): List<Position> = List(CIRCLE_SEGMENTS) { index ->
    val angle = 2f * PI.toFloat() * index / CIRCLE_SEGMENTS
    Position(cos(angle), 0f, sin(angle))
}

// World-space dimensions in metres, not UI spacing. Waves fit inside the floor at full gain.
private const val STAGE_RADIUS = 1.05f
private const val FLOOR_THICKNESS = 0.06f
private const val SOURCE_SPREAD = 0.56f
private const val SOURCE_RADIUS = 0.15f
private const val SOURCE_HEIGHT = 0.36f
private const val WAVE_REACH = 0.44f
private const val RING_HEIGHT = 0.006f
private const val CIRCLE_SEGMENTS = 64
private const val WAVE_COUNT = 3
private const val FOCAL_LENGTH = 16.0
private const val FRAMING_MARGIN = 1.12f
private const val ELEVATION_SIN = 0.642788f // 40 degrees above the floor.
private const val ELEVATION_COS = 0.766044f
private const val READOUT_EPSILON_M = 0.01f
