package io.github.sceneview.demo.demos

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.filament.Skybox
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.lookAt
import io.github.sceneview.SceneView
import io.github.sceneview.safeDestroySkybox
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.math.Transform
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.sample.rememberMaterialInstance
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * Demonstrates [VideoNode] — a flat quad inside a 3D scene that plays a streaming video
 * with audio.
 *
 * Three creative twists on top of the basic VideoNode:
 *
 *  - **Surface mode picker** — switch between a flat plane (default), a chrome
 *    sculpture-cube next to the screen for stylised reflections, and a polished
 *    reflective floor below the screen so the video bounces off the ground.
 *  - **Cinematic camera** — a punchy keyframed sequence (very wide → extreme close-up
 *    → side dolly → top-down) versus a wide steady orbit far back. The two modes look
 *    visibly different — distance, angle and pacing all change.
 *  - **Mute toggle** — start muted by default; tap the speaker icon to unmute.
 *
 * Source: Big Buck Bunny — © Blender Foundation, CC-BY 3.0.
 */
@Composable
fun VideoDemo(onBack: () -> Unit) {
    // The user's *desired* playback state. Survives config changes; the prepared
    // callback below must consult this so a pause issued before the player is
    // ready is not overridden by auto-play (#1707).
    var isPlaying by rememberSaveable { mutableStateOf(true) }
    var isReady by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(true) }
    var surfaceMode by remember { mutableStateOf(SurfaceMode.PLANE) }
    var cinematic by remember { mutableStateOf(true) }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // HDR environment for IBL — the studio_warm probe gives metallic surfaces a
    // believable reflection (warm key + cool fill).
    //
    // The HDR is loaded with `createSkybox = false` (we don't want the warm
    // studio panorama as the background), but an [Environment] with a `null`
    // skybox leaves every untouched pixel UNCLEARED on an opaque SceneView —
    // Filament only fills the background where a skybox is present. That left
    // the viewport showing the swap-chain's stale / uninitialised buffer (a
    // light near-white wash, or a leftover gradient from the previous screen),
    // which broke the dark theme every other demo uses (#1469).
    //
    // Fix: keep the HDR IBL for reflections but pair it with an explicit opaque
    // BLACK skybox so the background is always a clean neutral black — exactly
    // what the default `rememberEnvironment` does (`createEnvironment` builds a
    // black skybox), and what the demo's "video reads against neutral black"
    // intent always meant.
    val hdrEnvironment = rememberHDREnvironment(
        environmentLoader,
        "environments/studio_warm_2k.hdr",
        createSkybox = false,
    )
    val fallbackEnvironment = rememberEnvironment(environmentLoader)
    val blackSkybox = remember(engine) {
        Skybox.Builder()
            .color(floatArrayOf(0f, 0f, 0f, 1f))
            .build(engine)
    }
    DisposableEffect(blackSkybox) {
        onDispose { engine.safeDestroySkybox(blackSkybox) }
    }
    // While the HDR is still decoding, fall back to the default environment
    // (which already carries a black skybox). Once the HDR is ready, reuse its
    // IBL but swap in the guaranteed-black skybox so the background never
    // exposes the uncleared swap-chain.
    val activeEnvironment = hdrEnvironment?.let { hdr ->
        remember(hdr, blackSkybox) { hdr.copy(skybox = blackSkybox) }
    } ?: fallbackEnvironment

    // MediaPlayer reading from the BUNDLED `assets/videos/sample.mp4` (517 KB) — works
    // offline, in airplane mode, on Play Store reviewer's metered network, and
    // doesn't depend on a third-party mirror staying up (#886). Previous remote URL
    // (commondatastorage / w3schools) had broken multiple times historically.
    val context = LocalContext.current
    // Live read of the user's desired playback state for the prepared callback,
    // which is captured once at `remember` time and would otherwise see the
    // stale initial value (#1707).
    val playIntent = rememberUpdatedState(isPlaying)
    val player = remember {
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            isLooping = true
            // Start muted — auto-play with sound surprises users on mobile.
            setVolume(0f, 0f)
            // Bundled-asset path: AssetFileDescriptor lets MediaPlayer stream directly
            // from the APK without copying to internal storage first.
            context.assets.openFd("videos/sample.mp4").use { afd ->
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            setOnPreparedListener {
                isReady = true
                // Honour a pause the user may have issued while preparing —
                // only auto-play if their intended state is still "play".
                if (playIntent.value) start()
            }
            prepareAsync()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    // Sync mute state to player — re-applied whenever isMuted toggles.
    LaunchedEffect(isMuted, isReady) {
        if (isReady) {
            val v = if (isMuted) 0f else 1f
            player.setVolume(v, v)
        }
    }

    // Cinematic camera: a single stable manipulator instance whose providers read
    // the latest cinematic flag from a state ref. The camera animation coroutine
    // restarts cleanly when `cinematic` flips so the two modes are clearly distinct
    // (different keyframes, different pacing, different distances).
    val cinematicManipulator = rememberCinematicCameraManipulator(
        trigger = isReady,
        cinematic = cinematic,
    )

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_video_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            Text("Playback", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconButton(onClick = {
                    isPlaying = !isPlaying
                    if (isReady) {
                        if (isPlaying) player.start() else player.pause()
                    }
                }) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play"
                    )
                }
                IconButton(onClick = { isMuted = !isMuted }) {
                    Icon(
                        if (isMuted) Icons.AutoMirrored.Filled.VolumeOff
                        else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (isMuted) "Unmute" else "Mute"
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Surface", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SurfaceMode.values().forEach { mode ->
                    FilterChip(
                        selected = surfaceMode == mode,
                        onClick = { surfaceMode = mode },
                        label = { Text(mode.label) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Camera", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = cinematic,
                    onClick = { cinematic = true },
                    label = { Text("Cinematic") },
                )
                FilterChip(
                    selected = !cinematic,
                    onClick = { cinematic = false },
                    label = { Text("Orbit") },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Big Buck Bunny — © Blender Foundation (CC-BY 3.0)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            onFrame = firstFrame.onFrame,
            engine = engine,
            materialLoader = materialLoader,
            environmentLoader = environmentLoader,
            environment = activeEnvironment,
            cameraManipulator = cinematicManipulator,
        ) {
            // VideoNode auto-sizes the quad to the streaming video's aspect ratio.
            // The first frames render black until prepareAsync completes.
            VideoNode(player = player)

            // Companion geometry — wrapped in `key(surfaceMode)` so switching the
            // chip forces the whole branch to dispose + recreate, not just SideEffect-
            // update. Without the key, surface-mode changes that happen to share node
            // composables (none today, but defensive) would risk reusing stale nodes.
            //
            // The video plane is a single VideoNode — only one MediaPlayer can feed
            // one SurfaceTexture — so we add visible companion nodes around it
            // instead. Each surface mode positions the companion so it is clearly
            // visible from the cinematic / orbit camera paths (no occlusion behind
            // the video plane).
            key(surfaceMode) {
                when (surfaceMode) {
                    SurfaceMode.PLANE -> {
                        // No companion geometry — the original "video on a plane" demo.
                    }

                    SurfaceMode.CUBE -> {
                        // Chrome sculpture floating to the RIGHT of the video plane
                        // (not behind, where the plane would occlude it). The cube
                        // is large enough (1.0 m) to read at any camera distance,
                        // and slightly tilted so the IBL highlights catch the eye.
                        val chromeMaterial = rememberMaterialInstance(
                            materialLoader,
                            color = androidx.compose.ui.graphics.Color(
                                0.92f, 0.92f, 0.95f, 1f
                            ),
                            metallic = 1f,
                            roughness = 0.12f,
                            reflectance = 0.8f,
                        )
                        CubeNode(
                            size = Size(1.0f, 1.0f, 1.0f),
                            materialInstance = chromeMaterial,
                            position = Position(x = 1.6f, y = 0.0f, z = -0.2f),
                            rotation = Rotation(x = 15f, y = 35f, z = 12f),
                        )
                    }

                    SurfaceMode.REFLECTIVE_FLOOR -> {
                        // Polished black floor right under the screen, large enough
                        // (5×5 m) to fill the lower half of every camera angle. The
                        // very low roughness + high metallic gives a near-mirror
                        // bounce of the video and the IBL.
                        val floorMaterial = rememberMaterialInstance(
                            materialLoader,
                            color = androidx.compose.ui.graphics.Color(
                                0.04f, 0.04f, 0.05f, 1f
                            ),
                            metallic = 1f,
                            roughness = 0.06f,
                            reflectance = 0.95f,
                        )
                        PlaneNode(
                            size = Size(5f, 0.001f, 5f),
                            materialInstance = floorMaterial,
                            position = Position(x = 0f, y = -0.55f, z = 0f),
                        )
                    }
                }
            }
        }
    }
}

/** Surface variations the user can switch between. */
private enum class SurfaceMode(val label: String) {
    PLANE("Plane"),
    CUBE("Cube"),
    REFLECTIVE_FLOOR("Reflective"),
}

/**
 * Camera pose keyframe — `eye` is the camera position, `target` is the look-at
 * point. The cinematic sequence interpolates linearly between these in world
 * space using a smooth easing curve, which produces a natural dolly motion
 * (much closer to a real camera op than orbit-only).
 */
private data class CameraPose(val eye: Position, val target: Position)

/**
 * Cinematic keyframes — chosen to be VISIBLY different from the orbit fallback:
 *  - very-wide hero (back at z=4)
 *  - extreme close-up (z=0.6, near the plane)
 *  - dramatic side dolly (x=2.5)
 *  - top-down 3/4 (y=2)
 * These cover a large volume of camera positions; the orbit path stays at a
 * constant z=±3.5, y=0.5 so it reads as a smooth horizontal pan instead.
 */
private val CINEMATIC_POSES = listOf(
    CameraPose(eye = Position(0f, 0.4f, 4.0f), target = Position(0f, 0f, 0f)),
    CameraPose(eye = Position(0f, 0.0f, 0.6f), target = Position(0f, 0f, 0f)),
    CameraPose(eye = Position(2.5f, 0.3f, 0.8f), target = Position(0f, 0f, 0f)),
    CameraPose(eye = Position(-0.4f, 2.0f, 1.4f), target = Position(0f, 0f, 0f)),
)

/** Hold ~1.6 s and crossfade ~2.4 s — full cinematic loop ≈ 16 s. */
private const val POSE_HOLD_MS = 1_600
private const val POSE_TRANSITION_MS = 2_400

/** Orbit pacing — slow, steady, far-back so it reads as "a camera on rails". */
private const val ORBIT_PERIOD_MS = 22_000
private const val ORBIT_RADIUS = 3.5f
private const val ORBIT_HEIGHT = 0.5f

/**
 * A [CameraGestureDetector.CameraManipulator] driven by composable state. Keeping
 * a single instance across cinematic/orbit toggles is required because the per-
 * frame loop in [io.github.sceneview.SceneView] captures the manipulator at
 * composition and reads `getTransform()` on every frame; swapping the manipulator
 * reference would not propagate to that loop. Toggling the mode therefore happens
 * entirely inside the providers, which read the latest [Position] from animatables
 * driven by [rememberCinematicCameraManipulator].
 *
 * On first user gesture we hand off to a stock
 * [CameraGestureDetector.DefaultCameraManipulator] anchored at the current pose —
 * same hand-off semantics as `HeroOrbitCameraManipulator`.
 */
private class CinematicCameraManipulator(
    private val eyeProvider: () -> Position,
    private val targetProvider: () -> Position,
) : CameraGestureDetector.CameraManipulator {

    private var fallback: CameraGestureDetector.DefaultCameraManipulator? = null
    private var viewportW = 1
    private var viewportH = 1

    private fun cinematicTransform(): Transform {
        val eye = eyeProvider()
        val target = targetProvider()
        val mat = lookAt(eye = eye, target = target, up = Float3(0f, 1f, 0f))
        return Transform(mat)
    }

    private fun ensureFallback() {
        if (fallback == null) {
            fallback = CameraGestureDetector.DefaultCameraManipulator(
                orbitHomePosition = eyeProvider(),
                targetPosition = targetProvider(),
            ).also { it.setViewport(viewportW, viewportH) }
        }
    }

    override fun setViewport(width: Int, height: Int) {
        viewportW = width.coerceAtLeast(1)
        viewportH = height.coerceAtLeast(1)
        fallback?.setViewport(viewportW, viewportH)
    }

    override fun getTransform(): Transform =
        fallback?.getTransform() ?: cinematicTransform()

    override fun grabBegin(x: Int, y: Int, strafe: Boolean) {
        ensureFallback(); fallback?.grabBegin(x, y, strafe)
    }

    override fun grabUpdate(x: Int, y: Int) {
        fallback?.grabUpdate(x, y)
    }

    override fun grabEnd() {
        fallback?.grabEnd()
    }

    override fun scrollBegin(x: Int, y: Int, separation: Float) {
        ensureFallback(); fallback?.scrollBegin(x, y, separation)
    }

    override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) {
        fallback?.scrollUpdate(x, y, prevSeparation, currSeparation)
    }

    override fun scrollEnd() {
        fallback?.scrollEnd()
    }

    override fun update(deltaTime: Float) {
        fallback?.update(deltaTime)
    }
}

/**
 * Build the cinematic manipulator and drive its providers from a coroutine that
 * restarts cleanly whenever [cinematic] flips. The two modes use the SAME
 * underlying animatables (eyeX/Y/Z) so cross-mode transitions interpolate
 * smoothly from the last cinematic frame to the first orbit frame and back.
 *
 * The `update()` callback re-evaluates `eyeProvider` every frame, so the camera
 * picks up animatable changes immediately even when both modes use one
 * manipulator instance.
 */
@Composable
private fun rememberCinematicCameraManipulator(
    trigger: Boolean,
    cinematic: Boolean,
): CinematicCameraManipulator {
    // Cinematic mode drives eyeX/Y/Z directly via tween animateTo, which gives
    // smooth crossfades between hand-authored keyframes.
    val eyeX = remember { Animatable(CINEMATIC_POSES[0].eye.x) }
    val eyeY = remember { Animatable(CINEMATIC_POSES[0].eye.y) }
    val eyeZ = remember { Animatable(CINEMATIC_POSES[0].eye.z) }

    // Orbit mode drives a single phase animatable [0..1] linearly. The provider
    // computes (sin, cos) from this phase to keep the orbit on a perfect
    // circle without three independent tweens fighting each other.
    val orbitPhase = remember { Animatable(0f) }

    // rememberUpdatedState keeps the provider reading the LATEST cinematic flag
    // each frame — without this, the lambda baked at first composition would
    // always see `cinematic = true`.
    val cinematicState = rememberUpdatedState(cinematic)

    LaunchedEffect(trigger, cinematic, DemoSettings.qaMode) {
        if (!trigger || DemoSettings.qaMode) return@LaunchedEffect
        if (cinematic) {
            // Cinematic: jump straight into the first transition (no opening hold)
            // so the user sees the camera move within ~2.4 s of toggling. Then
            // hold/transition through the loop forever.
            var i = 0
            while (true) {
                val next = CINEMATIC_POSES[(i + 1) % CINEMATIC_POSES.size]
                val spec = tween<Float>(
                    durationMillis = POSE_TRANSITION_MS,
                    easing = FastOutSlowInEasing,
                )
                coroutineScope {
                    launch { eyeX.animateTo(next.eye.x, spec) }
                    launch { eyeY.animateTo(next.eye.y, spec) }
                    launch { eyeZ.animateTo(next.eye.z, spec) }
                }
                delay(POSE_HOLD_MS.toLong())
                i++
            }
        } else {
            // Orbit: continuous slow horizontal sweep at a wider radius and
            // higher elevation than any cinematic keyframe, so the difference
            // between modes is visually unambiguous (bigger arc, steady speed,
            // never punches in close). Drive a single phase animatable; the
            // provider derives the eye position from it.
            val orbitSpec = tween<Float>(
                durationMillis = ORBIT_PERIOD_MS,
                easing = LinearEasing,
            )
            while (true) {
                orbitPhase.snapTo(0f)
                orbitPhase.animateTo(1f, orbitSpec)
            }
        }
    }

    return remember {
        CinematicCameraManipulator(
            eyeProvider = {
                // Pick the eye source based on the latest cinematic flag. Both
                // sources are kept in sync by the LaunchedEffect above (only one
                // is animated at a time), so when the user toggles the chip the
                // provider switches feed cleanly without a pose snap.
                if (cinematicState.value) {
                    Position(eyeX.value, eyeY.value, eyeZ.value)
                } else {
                    val rad = (orbitPhase.value * 2.0 * Math.PI).toFloat()
                    Position(
                        x = sin(rad) * ORBIT_RADIUS,
                        y = ORBIT_HEIGHT,
                        z = cos(rad) * ORBIT_RADIUS,
                    )
                }
            },
            targetProvider = { Position(0f, 0f, 0f) },
        )
    }
}
