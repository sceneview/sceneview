package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.filament.Skybox
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.lookAt
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.R
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.math.Transform
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.safeDestroySkybox
import io.github.sceneview.sample.rememberMaterialInstance
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * Unified "2D in 3D" demo — consolidates the retired `text`, `image`, `video`,
 * and `billboard` demos behind a single segmented-button toggle (#2239 Batch 1).
 *
 * Each sub-mode showcases one way to put 2D content into a 3D scene:
 *
 * - **Text** — `TextNode` labels stacked at three world positions, with
 *   editable string + size. (Formerly `text`.)
 * - **Image** — `ImageNode` photo gallery with three angled framed pictures.
 *   (Formerly `image`.)
 * - **Video** — `VideoNode` streaming an MP4 with surface variants, cinematic
 *   camera, and play/mute. (Formerly `video`.)
 * - **Billboard** — `BillboardNode` (always faces camera) vs fixed `ImageNode`,
 *   planted on a ground plane. (Formerly `billboard`.)
 *
 * Each sub-mode keeps its own `SceneView` (different cameras, different
 * environments — video needs an HDR IBL, billboard needs an angled ground
 * camera). Old deep links route through
 * [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES].
 */
@Composable
fun TwoDInThreeDDemo(onBack: () -> Unit) {
    var mode by remember { mutableStateOf(TwoDInThreeDMode.Text) }
    when (mode) {
        TwoDInThreeDMode.Text -> TextSection(onBack, mode) { mode = it }
        TwoDInThreeDMode.Image -> ImageSection(onBack, mode) { mode = it }
        TwoDInThreeDMode.Video -> VideoSection(onBack, mode) { mode = it }
        TwoDInThreeDMode.Billboard -> BillboardSection(onBack, mode) { mode = it }
    }
}

private enum class TwoDInThreeDMode(val label: String) {
    Text("Text"),
    Image("Image"),
    Video("Video"),
    Billboard("Billboard"),
}

@Composable
private fun ModeSelector(
    current: TwoDInThreeDMode,
    onModeChange: (TwoDInThreeDMode) -> Unit,
) {
    val modes = TwoDInThreeDMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, m ->
            SegmentedButton(
                selected = m == current,
                onClick = { onModeChange(m) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                label = { Text(m.label) },
            )
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
}

// ─── Text section ──────────────────────────────────────────────────────────

@Composable
private fun TextSection(
    onBack: () -> Unit,
    mode: TwoDInThreeDMode,
    onModeChange: (TwoDInThreeDMode) -> Unit,
) {
    var inputText by remember { mutableStateOf("Hello SceneView") }
    var fontSize by remember { mutableFloatStateOf(48f) }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_two_d_in_three_d_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            ModeSelector(mode, onModeChange)
            Text("Text Content", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                label = { Text("Display Text") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("Font Size: ${fontSize.toInt()}px", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = fontSize,
                onValueChange = { fontSize = it },
                valueRange = 16f..96f
            )
        }
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            materialLoader = materialLoader,
            onFrame = firstFrame.onFrame,
            cameraManipulator = rememberCameraManipulator(
                orbitHomePosition = Position(0f, 0f, 1.5f),
                targetPosition = Position(0f, 0f, 0f),
            ),
        ) {
            TextNode(
                text = inputText,
                fontSize = fontSize,
                textColor = android.graphics.Color.WHITE,
                backgroundColor = 0xCC000000.toInt(),
                widthMeters = 0.7f,
                heightMeters = 0.18f,
                position = Position(x = 0f, y = 0.22f),
                apply = { materialInstance.setDoubleSided(true) },
            )
            TextNode(
                text = "SceneView 4.0",
                fontSize = fontSize,
                textColor = 0xFFA4C1FF.toInt(),
                backgroundColor = 0xCC161B22.toInt(),
                widthMeters = 0.7f,
                heightMeters = 0.18f,
                position = Position(x = 0f, y = 0f),
                apply = { materialInstance.setDoubleSided(true) },
            )
            TextNode(
                text = "3D Text Labels",
                fontSize = fontSize,
                textColor = 0xFFD2A8FF.toInt(),
                backgroundColor = 0xCC161B22.toInt(),
                widthMeters = 0.7f,
                heightMeters = 0.18f,
                position = Position(x = 0f, y = -0.22f),
                apply = { materialInstance.setDoubleSided(true) },
            )
        }
    }
}

// ─── Image section ─────────────────────────────────────────────────────────

@Composable
private fun ImageSection(
    onBack: () -> Unit,
    mode: TwoDInThreeDMode,
    onModeChange: (TwoDInThreeDMode) -> Unit,
) {
    var scaleFactor by remember { mutableFloatStateOf(1f) }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)

    val sunsetPhoto = remember { createScenePhoto(ImageScene.SUNSET) }
    val noonPhoto = remember { createScenePhoto(ImageScene.NOON) }
    val nightPhoto = remember { createScenePhoto(ImageScene.NIGHT) }

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_two_d_in_three_d_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            ModeSelector(mode, onModeChange)
            Text(
                "Gallery scale: ${"%.1f".format(scaleFactor)}x",
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = scaleFactor,
                onValueChange = { scaleFactor = it },
                valueRange = 0.4f..2f
            )
        }
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            onFrame = firstFrame.onFrame,
            engine = engine,
            materialLoader = materialLoader,
            cameraManipulator = rememberCameraManipulator()
        ) {
            ImageNode(
                bitmap = noonPhoto,
                position = Position(x = 0f, y = 0.1f, z = 0f),
                scale = Scale(0.8f * scaleFactor)
            )
            ImageNode(
                bitmap = sunsetPhoto,
                position = Position(x = -0.85f, y = -0.05f, z = -0.45f),
                rotation = Rotation(y = 32f),
                scale = Scale(0.62f * scaleFactor)
            )
            ImageNode(
                bitmap = nightPhoto,
                position = Position(x = 0.85f, y = -0.05f, z = -0.45f),
                rotation = Rotation(y = -32f),
                scale = Scale(0.62f * scaleFactor)
            )
        }
    }
}

private enum class ImageScene(
    val skyTop: Int,
    val skyBottom: Int,
    val sun: Int,
    val sunY: Float,
    val hillFront: Int,
    val hillBack: Int,
) {
    SUNSET(0xFF1B2A52.toInt(), 0xFFF4A259.toInt(), 0xFFFFE3A3.toInt(), 0.62f, 0xFF1E2A38.toInt(), 0xFF3A4A63.toInt()),
    NOON(0xFF2F6FB8.toInt(), 0xFFBFE3F5.toInt(), 0xFFFFFFFF.toInt(), 0.30f, 0xFF2F5D3A.toInt(), 0xFF4C8455.toInt()),
    NIGHT(0xFF05060F.toInt(), 0xFF1E2747.toInt(), 0xFFEDEFF7.toInt(), 0.26f, 0xFF0A1019.toInt(), 0xFF1A2436.toInt()),
}

private fun createScenePhoto(scene: ImageScene): Bitmap {
    val w = 512
    val h = 384
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val frameInset = 18f
    canvas.drawColor(0xFFEAE6DE.toInt())
    val photo = RectF(frameInset, frameInset, w - frameInset, h - frameInset)
    canvas.save()
    canvas.clipRect(photo)
    val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, photo.top, 0f, photo.bottom,
            scene.skyTop, scene.skyBottom, Shader.TileMode.CLAMP
        )
    }
    canvas.drawRect(photo, skyPaint)
    val sunX = photo.left + photo.width() * 0.68f
    val sunY = photo.top + photo.height() * scene.sunY
    val sunRadius = photo.width() * 0.16f
    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            sunX, sunY, sunRadius * 2.4f,
            intArrayOf(scene.sun, scene.sun and 0x00FFFFFF),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
    }
    canvas.drawCircle(sunX, sunY, sunRadius * 2.4f, glowPaint)
    canvas.drawCircle(sunX, sunY, sunRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = scene.sun
    })
    canvas.drawPath(hillPath(photo, 0.62f, 0.10f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = scene.hillBack
    })
    canvas.drawPath(hillPath(photo, 0.78f, 0.16f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = scene.hillFront
    })
    canvas.restore()
    canvas.drawRect(photo, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0x33000000
    })
    return bitmap
}

private fun hillPath(photo: RectF, baseY: Float, amplitude: Float): Path {
    val crest = photo.top + photo.height() * baseY
    val amp = photo.height() * amplitude
    return Path().apply {
        moveTo(photo.left, photo.bottom)
        lineTo(photo.left, crest)
        cubicTo(
            photo.left + photo.width() * 0.30f, crest - amp,
            photo.left + photo.width() * 0.55f, crest + amp,
            photo.left + photo.width() * 0.78f, crest - amp * 0.4f
        )
        cubicTo(
            photo.left + photo.width() * 0.90f, crest - amp * 0.9f,
            photo.right, crest,
            photo.right, crest
        )
        lineTo(photo.right, photo.bottom)
        close()
    }
}

// ─── Video section ─────────────────────────────────────────────────────────

@Composable
private fun VideoSection(
    onBack: () -> Unit,
    mode: TwoDInThreeDMode,
    onModeChange: (TwoDInThreeDMode) -> Unit,
) {
    var isPlaying by rememberSaveable { mutableStateOf(true) }
    var isReady by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(true) }
    var surfaceMode by remember { mutableStateOf(VideoSurfaceMode.PLANE) }
    var cinematic by remember { mutableStateOf(true) }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

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
    val activeEnvironment = hdrEnvironment?.let { hdr ->
        remember(hdr, blackSkybox) { hdr.copy(skybox = blackSkybox) }
    } ?: fallbackEnvironment

    val context = LocalContext.current
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
            setVolume(0f, 0f)
            context.assets.openFd("videos/sample.mp4").use { afd ->
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            setOnPreparedListener {
                isReady = true
                if (playIntent.value) start()
            }
            prepareAsync()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(isMuted, isReady) {
        if (isReady) {
            val v = if (isMuted) 0f else 1f
            player.setVolume(v, v)
        }
    }

    val cinematicManipulator = rememberCinematicCameraManipulator(
        trigger = isReady,
        cinematic = cinematic,
    )

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_two_d_in_three_d_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            ModeSelector(mode, onModeChange)
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
                VideoSurfaceMode.values().forEach { m ->
                    FilterChip(
                        selected = surfaceMode == m,
                        onClick = { surfaceMode = m },
                        label = { Text(m.label) },
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
            VideoNode(player = player)

            key(surfaceMode) {
                when (surfaceMode) {
                    VideoSurfaceMode.PLANE -> {
                        // No companion geometry.
                    }
                    VideoSurfaceMode.CUBE -> {
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
                    VideoSurfaceMode.REFLECTIVE_FLOOR -> {
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

private enum class VideoSurfaceMode(val label: String) {
    PLANE("Plane"),
    CUBE("Cube"),
    REFLECTIVE_FLOOR("Reflective"),
}

private data class CameraPose(val eye: Position, val target: Position)

private val CINEMATIC_POSES = listOf(
    CameraPose(eye = Position(0f, 0.4f, 4.0f), target = Position(0f, 0f, 0f)),
    CameraPose(eye = Position(0f, 0.0f, 0.6f), target = Position(0f, 0f, 0f)),
    CameraPose(eye = Position(2.5f, 0.3f, 0.8f), target = Position(0f, 0f, 0f)),
    CameraPose(eye = Position(-0.4f, 2.0f, 1.4f), target = Position(0f, 0f, 0f)),
)

private const val POSE_HOLD_MS = 1_600
private const val POSE_TRANSITION_MS = 2_400
private const val ORBIT_PERIOD_MS = 22_000
private const val ORBIT_RADIUS = 3.5f
private const val ORBIT_HEIGHT = 0.5f

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

@Composable
private fun rememberCinematicCameraManipulator(
    trigger: Boolean,
    cinematic: Boolean,
): CinematicCameraManipulator {
    val eyeX = remember { Animatable(CINEMATIC_POSES[0].eye.x) }
    val eyeY = remember { Animatable(CINEMATIC_POSES[0].eye.y) }
    val eyeZ = remember { Animatable(CINEMATIC_POSES[0].eye.z) }
    val orbitPhase = remember { Animatable(0f) }
    val cinematicState = rememberUpdatedState(cinematic)

    LaunchedEffect(trigger, cinematic, DemoSettings.qaMode) {
        if (!trigger || DemoSettings.qaMode) return@LaunchedEffect
        if (cinematic) {
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

// ─── Billboard section ─────────────────────────────────────────────────────

@Composable
private fun BillboardSection(
    onBack: () -> Unit,
    mode: TwoDInThreeDMode,
    onModeChange: (TwoDInThreeDMode) -> Unit,
) {
    var showBillboard by remember { mutableStateOf(true) }
    var showFixed by remember { mutableStateOf(true) }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)

    val groundBitmap = remember { createGroundBitmap() }
    val billboardBitmap = remember {
        createSignBitmap("Billboard", "always faces you", 0xFF005BC1.toInt())
    }
    val fixedBitmap = remember {
        createSignBitmap("Fixed sign", "turns away as you orbit", 0xFF6446CD.toInt())
    }

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_two_d_in_three_d_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            ModeSelector(mode, onModeChange)
            Text(
                "Orbit the scene — the billboard stays readable, the fixed sign turns edge-on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Visible Nodes", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    showBillboard,
                    onClick = { showBillboard = !showBillboard },
                    label = { Text("Billboard Panel") }
                )
                FilterChip(
                    showFixed,
                    onClick = { showFixed = !showFixed },
                    label = { Text("Fixed Image") }
                )
            }
        }
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            onFrame = firstFrame.onFrame,
            engine = engine,
            materialLoader = materialLoader,
            cameraManipulator = rememberCameraManipulator(
                orbitHomePosition = Position(0f, 0.55f, 1.5f),
                targetPosition = Position(0f, -0.05f, -0.8f),
            )
        ) {
            ImageNode(
                bitmap = groundBitmap,
                position = Position(x = 0f, y = -0.42f, z = -0.8f),
                rotation = Rotation(x = -90f),
                scale = Scale(2.4f)
            )

            if (showBillboard) {
                BillboardNode(
                    bitmap = billboardBitmap,
                    widthMeters = 0.62f,
                    heightMeters = 0.40f,
                    position = Position(x = -0.5f, y = -0.05f, z = -0.8f)
                )
            }

            if (showFixed) {
                ImageNode(
                    bitmap = fixedBitmap,
                    position = Position(x = 0.5f, y = -0.05f, z = -0.8f),
                    scale = Scale(0.62f)
                )
            }
        }
    }
}

private fun createSignBitmap(title: String, caption: String, bgColor: Int): Bitmap {
    val width = 320
    val height = 200
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val postPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6B5B4A.toInt() }
    canvas.drawRect(width / 2f - 8f, 150f, width / 2f + 8f, height.toFloat(), postPaint)

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
    canvas.drawRoundRect(RectF(12f, 12f, width - 12f, 156f), 20f, 20f, bgPaint)

    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 44f
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    canvas.drawText(title, width / 2f, 78f, titlePaint)

    val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8E8F4.toInt()
        textSize = 26f
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText(caption, width / 2f, 122f, captionPaint)

    return bitmap
}

private fun createGroundBitmap(): Bitmap {
    val size = 256
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(0xFF2A2E3A.toInt())
    val tilePaint = Paint().apply { color = 0xFF353A4A.toInt() }
    val tiles = 8
    val tile = size / tiles
    for (row in 0 until tiles) {
        for (col in 0 until tiles) {
            if ((row + col) % 2 == 0) {
                canvas.drawRect(
                    (col * tile).toFloat(), (row * tile).toFloat(),
                    ((col + 1) * tile).toFloat(), ((row + 1) * tile).toFloat(),
                    tilePaint
                )
            }
        }
    }
    return bitmap
}
