package io.github.sceneview.demo.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.google.android.filament.Camera
import io.github.sceneview.RenderQuality
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.demo.common.rememberModelDemoEnvironment
import io.github.sceneview.demo.theme.LocalMotionEnabled
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode as ModelNodeImpl
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberRenderInvalidator
import io.github.sceneview.rememberView
import kotlin.math.abs
import kotlin.math.min

/** The subject of the live home hero — the app's own Model Viewer subject, already bundled. */
const val HOME_HERO_MODEL: String = "models/khronos_damaged_helmet.glb"

/** How long the hero waits for its model before it gives up and stays a still. */
private const val HERO_LOAD_TIMEOUT_MILLIS = 8_000L

/** Idle turntable speed. A full revolution in 24 s — present, never distracting. */
private const val HERO_IDLE_DEGREES_PER_SECOND = 15f

/** Seconds of stillness before the idle turntable arms itself. */
private const val HERO_IDLE_ARM_SECONDS = 1.6f

/** Past this progress the idle turntable never runs: the dock is a resting state, not a toy. */
private const val HERO_IDLE_MAX_PROGRESS = 0.5f

/** Size the subject is normalised to, whatever the glTF's intrinsic scale. This is `U`. */
internal const val HERO_SUBJECT_UNITS = 1.55f

/** Clamp for a delta across a dropped frame or a resumed app — one turntable step, not a jump. */
private const val MAX_FRAME_SECONDS = 0.1f

/** Below this, two poses are the same pose and the render loop is allowed to park. */
private const val POSE_EPSILON = 1e-3f

/**
 * The bounded idle turntable — `psi` in the pose.
 *
 * Bounded is the whole design: it arms [HERO_IDLE_ARM_SECONDS] after the last contact, turns at
 * [HERO_IDLE_DEGREES_PER_SECOND] until it reaches the next whole revolution, and then stops dead
 * and lets the render loop park. A hero that turns forever is a hero that costs a frame every
 * 16 ms for the whole time the catalogue is open, which is the one thing a decorative band may
 * not do.
 *
 * Not Compose state, deliberately: it is written once per rendered frame from `onFrame` and read
 * only by the camera it drives. Publishing it as state would recompose the home grid sixty times
 * a second to move a transform the composition never reads.
 */
@Stable
internal class HeroTurntable {
    /** Degrees added to the camera azimuth. Always a finite, settled value once [running] is false. */
    var yawDegrees: Float = 0f
        private set

    private var armedSeconds = 0f
    private var goalDegrees = 0f

    /** Whether the turntable still owes the render loop frames. */
    var running: Boolean = false
        private set

    /** The user touched something, or the pose is past the dock: stand down, keep the angle. */
    fun stop() {
        running = false
        armedSeconds = 0f
    }

    /**
     * Advances by [deltaSeconds] and returns the yaw to draw.
     *
     * @param enabled false when "remove animations" is on, when the pose is past
     *   [HERO_IDLE_MAX_PROGRESS], or when anything else is moving. The turntable then holds
     *   exactly where it is — it never snaps back, so nothing jumps when it is re-enabled.
     */
    fun advance(deltaSeconds: Float, enabled: Boolean): Float {
        if (!enabled) {
            stop()
            return yawDegrees
        }
        if (!running) {
            armedSeconds += deltaSeconds
            if (armedSeconds < HERO_IDLE_ARM_SECONDS) return yawDegrees
            // Aim at the next whole revolution — and at the one after it if the current one is
            // nearly done, so the turntable is never a two-degree twitch.
            val next = (kotlin.math.floor(yawDegrees / 360f) + 1f) * 360f
            goalDegrees = if (next - yawDegrees < 90f) next + 360f else next
            running = true
        }
        yawDegrees = min(goalDegrees, yawDegrees + HERO_IDLE_DEGREES_PER_SECOND * deltaSeconds)
        if (yawDegrees >= goalDegrees - POSE_EPSILON) {
            yawDegrees = goalDegrees
            running = false
            armedSeconds = 0f
        }
        return yawDegrees
    }
}

/**
 * The live 3D subject behind the home hero (#3620).
 *
 * This is the **stage**: a viewport of a fixed size, pinned under the header for the whole life of
 * the home screen. It never translates, never resizes, and — the part that matters — **receives no
 * input at all** (`isTouchEnabled = false`). That single parameter is what fixes the bug this whole
 * change exists for: the SDK used to install a touch listener unconditionally and report every
 * event handled, so a thumb that started its downward travel over the hero had its scroll swallowed
 * and a collision ray cast per motion event. With no listener, the gesture reaches whatever is
 * arbitrating above — here, the grid — and the vertical scroll always passes.
 *
 * Nothing on this screen animates the camera. [HomeHeroPose] *derives* it, from scroll progress and
 * from nothing else, so there is exactly one writer with no memory: the four "camera jumps" the
 * previous hero could produce (interrupt a fling, swipe mid-collapse, rotate, come back from the
 * viewer) are all unrepresentable here.
 *
 * Frames are owed, never scheduled. `onFrame` applies the pose only when it actually moved; a pose
 * that has settled writes nothing, so nothing pushes the next frame and the on-demand loop parks by
 * itself. Scroll -> full rate; at rest, docked, or off screen -> zero.
 *
 * @param pose the pose to draw, given the idle turntable's current contribution. A lambda, not a
 *   value: a `HeroPose` parameter would recompose this composable — and the grid around it — once
 *   per scrolled frame, to move a camera the composition never reads.
 * @param rendering whether the hero is allowed to draw at all (on screen, no search open, app
 *   resumed). False is a hard stop, not a hint.
 */
@Composable
internal fun HomeHeroScene(
    pose: (idleYaw: Float) -> HeroPose,
    rendering: Boolean,
    modifier: Modifier = Modifier,
) {
    val engine = rememberEngine()
    val view = rememberView(engine)
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val environment = rememberModelDemoEnvironment(environmentLoader)
    val cameraNode = rememberCameraNode(engine) {
        position = Position(z = 2.6f)
    }

    val modelInstance = rememberModelInstance(modelLoader, HOME_HERO_MODEL)

    // A model that never arrives must not hold the render loop open for the life of the screen:
    // past the timeout the hero stops waiting and lets the pose decide, empty stage or not.
    var gaveUp by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(HERO_LOAD_TIMEOUT_MILLIS)
        gaveUp = true
    }

    val turntable = remember { HeroTurntable() }
    val nodeHolder = remember { arrayOfNulls<ModelNodeImpl>(1) }
    // Plain holders, deliberately not Compose state: `onFrame` writes them on every rendered frame
    // and nothing in the composition reads them.
    val lastFrameNanos = remember { longArrayOf(0L) }
    val lastApplied = remember { floatArrayOf(Float.NaN, Float.NaN, Float.NaN) }

    // Filament has to keep drawing until the instance is there whatever the scroll is doing, or the
    // model lands untextured; after that, the pose decides.
    val loaded = modelInstance != null || gaveUp

    // "Remove animations" is on: the stage, the dock and the gesture fix are all unchanged — only
    // the movement goes. The dolly becomes two poses (see `HomeScreen`), and the turntable stops.
    val motionEnabled = LocalMotionEnabled.current

    // On-demand rendering (#3718): a frame has to be *asked for*. The pose write below is a push
    // source that asks for the next one, but a loop that has already parked has no callback left to
    // notice that `rendering` came back. One explicit request restarts the chain.
    val renderInvalidator = rememberRenderInvalidator()
    LaunchedEffect(rendering, motionEnabled, loaded) {
        if (rendering || !loaded) renderInvalidator.requestRender()
    }

    val stageAlpha by animateFloatAsState(
        targetValue = if (modelInstance != null) 1f else 0f,
        animationSpec = tween(SceneViewTokens.Duration.mediumMillis),
        label = "hero-stage",
    )

    Box(
        modifier = modifier
            // The scene is decoration over a page that already names itself; a screen reader must
            // hear "Model Viewer", not a second, unlabelled 3D view.
            .clearAndSetSemantics { },
    ) {
        SceneView(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = stageAlpha },
            // A TextureView, not the default SurfaceView: a SurfaceView is punched through the
            // window below the whole Compose hierarchy, so it could be neither crossfaded with the
            // bundled still nor clipped to the stage's live height. The cost is one extra copy per
            // frame on a band that draws nothing at rest.
            surfaceType = SurfaceType.TextureSurface,
            isOpaque = false,
            engine = engine,
            view = view,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            environmentLoader = environmentLoader,
            environment = environment,
            cameraNode = cameraNode,
            // No manipulator, no gesture listener — and, the part that actually works,
            // `isTouchEnabled = false`. The first two have never been enough: the SDK installed its
            // listener regardless and returned `true` for every event.
            cameraManipulator = null,
            onGestureListener = null,
            isTouchEnabled = false,
            renderQuality = RenderQuality.Performance,
            renderInvalidator = renderInvalidator,
            onFrame = { frameTimeNanos ->
                val previous = lastFrameNanos[0]
                lastFrameNanos[0] = frameTimeNanos
                if (!rendering && loaded) return@SceneView
                val deltaSeconds = if (previous == 0L) {
                    0f
                } else {
                    ((frameTimeNanos - previous) / 1_000_000_000.0).toFloat()
                        .coerceIn(0f, MAX_FRAME_SECONDS)
                }

                val settled = pose(turntable.yawDegrees)
                val idleAllowed = motionEnabled && rendering &&
                    settled.progress < HERO_IDLE_MAX_PROGRESS
                val drawn = pose(turntable.advance(deltaSeconds, idleAllowed))

                // The whole render policy, in four lines: apply the pose only when it moved. A
                // settled pose writes nothing, so nothing pushes the next frame and the loop parks.
                val moved = abs(drawn.eye.x - lastApplied[0]) > POSE_EPSILON ||
                    abs(drawn.eye.y - lastApplied[1]) > POSE_EPSILON ||
                    abs(drawn.eye.z - lastApplied[2]) > POSE_EPSILON
                if (!moved && !turntable.running) return@SceneView
                lastApplied[0] = drawn.eye.x
                lastApplied[1] = drawn.eye.y
                lastApplied[2] = drawn.eye.z

                cameraNode.setProjection(
                    fovInDegrees = HomeHeroPose.VERTICAL_FOV_DEGREES,
                    direction = Camera.Fov.VERTICAL,
                )
                cameraNode.position = Position(drawn.eye.x, drawn.eye.y, drawn.eye.z)
                cameraNode.lookAt(
                    targetWorldPosition = Position(drawn.target.x, drawn.target.y, drawn.target.z),
                    smooth = false,
                )
                renderInvalidator.requestRender()
            },
        ) {
            modelInstance?.let { instance ->
                ModelNode(
                    modelInstance = instance,
                    scaleToUnits = HERO_SUBJECT_UNITS,
                    apply = { nodeHolder[0] = this },
                )
            }
        }
    }
}
