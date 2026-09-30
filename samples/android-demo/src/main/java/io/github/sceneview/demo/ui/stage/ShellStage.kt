package io.github.sceneview.demo.ui.stage

import android.app.ActivityManager
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.filament.View
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.RenderQuality
import io.github.sceneview.SceneScope
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.demo.theme.LocalMotionEnabled
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberRenderInvalidator
import io.github.sceneview.rememberView
import kotlin.math.cos
import kotlin.math.sin

/**
 * A small transparent 3D stage for the app shell — the About mark, the AR tab's hero.
 *
 * It follows the Home hero's rules ([io.github.sceneview.demo.ui.home.HomeHeroScene]) so a
 * shell surface never costs the app its cold start or its battery:
 *
 * - **Nothing before the first Compose frame**, and nothing while the hosting screen is not
 *   the resumed destination. Once started, the stage stays for the screen's life.
 * - **Its own lifecycle.** RESUMED only while [active] and the host is resumed; below that
 *   SceneView stops submitting frames and the last one stays in the TextureView. No reload
 *   when the stage scrolls back in.
 * - **A clock that only ticks while frames are presented** ([StageClock]); reduced motion holds
 *   the pose of [restSeconds] under [FrameRatePolicy.OnDemand].
 * - **Tiers.** `isLowRamDevice` gets the Performance preset and no HDR decode.
 * - **Fades in over its placeholder.** The TextureView is transparent until a few frames have
 *   been presented, then crossfades with [placeholder] (the launcher icon on About) over
 *   [SceneViewTokens.ShellStage.fadeInMillis].
 *
 * The scene is driven by [onFrame], called once per presented frame with the stage time in
 * seconds. Drive pivots with [PivotTransform] — it writes the matrix straight into Filament
 * from a preallocated array, so the frame loop allocates nothing.
 *
 * @param active The stage is at least partly on screen. False parks the render loop.
 * @param hdrPath Bundled `.hdr` used as image-based light only (no skybox: the stage is
 *                transparent over the page).
 * @param restSeconds The stage time drawn when motion is disabled.
 */
@Composable
internal fun ShellStage(
    active: Boolean,
    hdrPath: String,
    eye: Position,
    target: Position,
    modifier: Modifier = Modifier,
    keyLight: Direction = ShellStageDefaults.keyLight,
    keyLightLux: Float = ShellStageDefaults.KEY_LIGHT_LUX,
    restSeconds: Double = 0.0,
    placeholder: @Composable () -> Unit = {},
    onFrame: (seconds: Double) -> Unit,
    content: @Composable SceneScope.() -> Unit,
) {
    var firstFrameDrawn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        firstFrameDrawn = true
    }
    val hostLifecycle = LocalLifecycleOwner.current.lifecycle
    val hostState by hostLifecycle.currentStateFlow.collectAsState()
    val hostResumed = hostState.isAtLeast(Lifecycle.State.RESUMED)
    var started by remember { mutableStateOf(false) }
    if (firstFrameDrawn && hostResumed) started = true
    if (!started) {
        Box(modifier) { placeholder() }
    } else {
        ShellStageContent(
            active = active,
            hdrPath = hdrPath,
            eye = eye,
            target = target,
            keyLight = keyLight,
            keyLightLux = keyLightLux,
            restSeconds = restSeconds,
            placeholder = placeholder,
            onFrame = onFrame,
            content = content,
            modifier = modifier,
        )
    }
}

internal object ShellStageDefaults {
    /** Key light from the upper right, a little in front — top face brightest, left face darkest. */
    val keyLight: Direction = Direction(-0.45f, -0.82f, -0.36f)
    const val KEY_LIGHT_LUX = 70_000f

    /** Frames presented before the stage fades in: the first ones can land before the IBL. */
    const val FRAMES_BEFORE_REVEAL = 3
}

@Composable
private fun ShellStageContent(
    active: Boolean,
    hdrPath: String,
    eye: Position,
    target: Position,
    keyLight: Direction,
    keyLightLux: Float,
    restSeconds: Double,
    placeholder: @Composable () -> Unit,
    onFrame: (Double) -> Unit,
    content: @Composable SceneScope.() -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val cinematic = remember(context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        !am.isLowRamDevice
    }
    val motionEnabled = LocalMotionEnabled.current
    val moving = active && motionEnabled
    val clock = remember { StageClock(restSeconds) }
    val lifecycle = rememberStageLifecycle(active, clock)
    val currentOnFrame by rememberUpdatedState(onFrame)

    val engine = rememberEngine()
    val view = rememberView(engine)
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val fallbackEnvironment = rememberEnvironment(environmentLoader, isOpaque = false)
    val hdrEnvironment = if (cinematic) {
        rememberHDREnvironment(environmentLoader, hdrPath, createSkybox = false)
    } else {
        null
    }
    val cameraNode = rememberCameraNode(engine) {
        lookAt(eye = eye, center = target, up = Direction(0f, 1f, 0f))
    }
    val keyLightNode = rememberMainLightNode(engine) {
        lightDirection = keyLight
        intensity = keyLightLux
        isShadowCaster = false
    }
    val renderInvalidator = rememberRenderInvalidator()

    // Reveal: counted in presented frames (a plain holder, not state — the frame loop must
    // not recompose), flipped into state exactly once.
    val framesPresented = remember { IntArray(1) }
    var revealed by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (motionEnabled) SceneViewTokens.ShellStage.fadeInMillis else 0,
            easing = SceneViewTokens.Ease.expressive,
        ),
        label = "shellStageReveal",
    )

    LaunchedEffect(moving) {
        clock.pause()
        renderInvalidator.requestRender()
    }

    Box(modifier) {
        // The placeholder gives way as the stage comes in — never both at full strength.
        if (alpha < 1f) {
            Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = 1f - alpha }) { placeholder() }
        }
        SceneView(
            modifier = Modifier
                .fillMaxSize()
                .clearAndSetSemantics { }
                .graphicsLayer { this.alpha = alpha },
            surfaceType = SurfaceType.TextureSurface,
            isOpaque = false,
            engine = engine,
            view = view,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            environmentLoader = environmentLoader,
            environment = hdrEnvironment ?: fallbackEnvironment,
            cameraNode = cameraNode,
            mainLightNode = keyLightNode,
            fillLightNode = null,
            cameraManipulator = null,
            onGestureListener = null,
            autoCenterContent = false,
            lifecycle = lifecycle,
            renderQuality = if (cinematic) RenderQuality.Default else RenderQuality.Performance,
            frameRatePolicy = if (moving) {
                FrameRatePolicy.Continuous(maxFps = SceneViewTokens.ShellStage.maxFps)
            } else {
                FrameRatePolicy.OnDemand(maxFps = SceneViewTokens.ShellStage.maxFps)
            },
            renderInvalidator = renderInvalidator,
            onFrame = { nanos ->
                currentOnFrame(clock.frame(nanos, moving))
                if (!revealed) {
                    framesPresented[0]++
                    if (framesPresented[0] >= ShellStageDefaults.FRAMES_BEFORE_REVEAL) revealed = true
                    // On-demand (reduced motion) presents nothing unless asked.
                    if (!moving) renderInvalidator.requestRender()
                }
            },
            content = content,
        )
        // Raw Filament write on the View, after SceneView's own quality effect (#1078):
        // geometric edges are what these stages are made of, so they get MSAA.
        LaunchedEffect(view, cinematic) {
            if (cinematic) {
                view.multiSampleAntiAliasingOptions = view.multiSampleAntiAliasingOptions.also {
                    it.enabled = true
                    it.sampleCount = 4
                }
                view.antiAliasing = View.AntiAliasing.NONE
            }
            renderInvalidator.requestRender()
        }
    }
}

/**
 * Stage time that only advances while frames are presented and motion is wanted. The first
 * frame after a pause gets no delta, and no frame more than [MAX_FRAME_SECONDS]. No boxing:
 * `0L` stands for "no previous frame".
 */
internal class StageClock(initialSeconds: Double = 0.0) {
    var seconds: Double = initialSeconds
        private set
    private var previousNanos = 0L

    fun pause() {
        previousNanos = 0L
    }

    fun frame(nanos: Long, moving: Boolean): Double {
        if (!moving) {
            pause()
            return seconds
        }
        if (previousNanos != 0L) {
            seconds += ((nanos - previousNanos) / NANOS_PER_SECOND).coerceIn(0.0, MAX_FRAME_SECONDS)
        }
        previousNanos = nanos
        return seconds
    }

    companion object {
        const val MAX_FRAME_SECONDS = 0.1
        private const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}

/**
 * Writes a node's local transform — translation, yaw (Y), pitch (X), roll (Z), uniform
 * scale, composed as `T · Ry · Rx · Rz · S` — straight into Filament's TransformManager
 * from one preallocated column-major matrix. Node's own setters build `Position` /
 * `Quaternion` / `Transform` objects on every call; a stage that drives five pivots at
 * 60 fps would churn hundreds of objects a second for nothing.
 *
 * The node's own mirror is invalidated after each write ([Node.invalidateTransformCache]),
 * as its contract for out-of-band writers asks.
 */
internal class PivotTransform {
    private val matrix = FloatArray(MATRIX_SIZE)

    @Suppress("LongParameterList")
    fun write(
        node: Node,
        x: Float = 0f,
        y: Float = 0f,
        z: Float = 0f,
        yawDegrees: Float = 0f,
        pitchDegrees: Float = 0f,
        rollDegrees: Float = 0f,
        scale: Float = 1f,
    ) {
        compose(matrix, x, y, z, yawDegrees, pitchDegrees, rollDegrees, scale)
        node.transformManager.setTransform(node.transformInstance, matrix)
        node.invalidateTransformCache()
    }

    internal companion object {
        const val MATRIX_SIZE = 16
        private const val DEG_TO_RAD = (Math.PI / 180.0).toFloat()

        @Suppress("LongParameterList")
        fun compose(
            out: FloatArray,
            x: Float,
            y: Float,
            z: Float,
            yawDegrees: Float,
            pitchDegrees: Float,
            rollDegrees: Float,
            scale: Float,
        ) {
            val yaw = yawDegrees * DEG_TO_RAD
            val pitch = pitchDegrees * DEG_TO_RAD
            val roll = rollDegrees * DEG_TO_RAD
            val cy = cos(yaw)
            val sy = sin(yaw)
            val cx = cos(pitch)
            val sx = sin(pitch)
            val cz = cos(roll)
            val sz = sin(roll)
            // Column 0 = Ry·Rx·(cz, sz, 0)
            out[0] = (cy * cz + sy * sx * sz) * scale
            out[1] = (cx * sz) * scale
            out[2] = (-sy * cz + cy * sx * sz) * scale
            out[3] = 0f
            // Column 1 = Ry·Rx·(-sz, cz, 0)
            out[4] = (-cy * sz + sy * sx * cz) * scale
            out[5] = (cx * cz) * scale
            out[6] = (sy * sz + cy * sx * cz) * scale
            out[7] = 0f
            // Column 2 = Ry·Rx·(0, 0, 1)
            out[8] = (sy * cx) * scale
            out[9] = (-sx) * scale
            out[10] = (cy * cx) * scale
            out[11] = 0f
            out[12] = x
            out[13] = y
            out[14] = z
            out[15] = 1f
        }
    }
}

private class StageLifecycleOwner : LifecycleOwner {
    val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
    override val lifecycle: Lifecycle get() = registry
}

/** RESUMED only while [active] and the host is resumed — the Home hero's rule (#3949). */
@Composable
private fun rememberStageLifecycle(active: Boolean, clock: StageClock): Lifecycle {
    val parent = LocalLifecycleOwner.current.lifecycle
    val owner = remember { StageLifecycleOwner() }
    DisposableEffect(parent, owner, active) {
        fun sync() {
            val resumed = active && parent.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!resumed) clock.pause()
            owner.registry.currentState =
                if (resumed) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
        }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        parent.addObserver(observer)
        sync()
        onDispose { parent.removeObserver(observer) }
    }
    DisposableEffect(owner) {
        onDispose { owner.registry.currentState = Lifecycle.State.DESTROYED }
    }
    return owner.lifecycle
}
