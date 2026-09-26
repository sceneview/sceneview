package io.github.sceneview.demo.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.github.sceneview.RenderQuality
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.demo.common.rememberModelDemoEnvironment
import io.github.sceneview.demo.theme.LocalMotionEnabled
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode as ModelNodeImpl
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberRenderInvalidator
import io.github.sceneview.rememberView

/** The subject of the live home hero — the app's own Model Viewer subject, already bundled. */
const val HOME_HERO_MODEL: String = "models/khronos_damaged_helmet.glb"

/** How long the hero waits for its model before it gives up and stays a still. */
private const val HERO_LOAD_TIMEOUT_MILLIS = 8_000L

/** Idle turntable speed. A full revolution in 24 s — present, never distracting. */
private const val HERO_IDLE_DEGREES_PER_SECOND = 15f

/** Elevation of the subject — the 3/4 view `DESIGN.md` frames every preview from. */
private const val HERO_PITCH_DEGREES = -12f

/** Distance the camera sits at, for a subject scaled to [HERO_SUBJECT_UNITS]. */
private const val HERO_CAMERA_DISTANCE = 2.6f

/** Size the subject is normalised to, whatever the glTF's intrinsic scale. */
private const val HERO_SUBJECT_UNITS = 1.55f

/**
 * Yaw of the hero subject: a slow turntable, and nothing else.
 *
 * It used to take a horizontal drag and a fling too. That made the hero answer the
 * same gesture two ways: the card sits in a pager whose page dots say "swipe for
 * the next card", and a swipe on the card turned the model instead (#3829). The
 * swipe now belongs to the pager alone, as it does on every featured carousel the
 * layout borrows from (Play Store, App Store "Today"); orbiting is what the Model
 * Viewer the card opens is for.
 *
 * Not Compose state, deliberately. It is written once per rendered frame from
 * `SceneView`'s `onFrame` and read only by the Filament node it drives — publishing
 * it as state would recompose the whole home grid sixty times a second to move one
 * transform the composition never reads.
 */
@Stable
internal class HeroTurntable {
    var yawDegrees: Float = INITIAL_YAW_DEGREES
        private set

    /**
     * Advances the turntable by [deltaSeconds] and returns the yaw to draw.
     *
     * [idle] is the system's "remove animations" answer. The unprompted turntable is
     * exactly the kind of perpetual motion that setting exists to stop, so with
     * [idle] false the subject holds its three-quarter pose.
     */
    fun advance(deltaSeconds: Float, idle: Boolean): Float {
        if (idle) yawDegrees += HERO_IDLE_DEGREES_PER_SECOND * deltaSeconds
        return yawDegrees
    }

    private companion object {
        /** Opening pose — three-quarter, the angle every bundled preview is framed from. */
        const val INITIAL_YAW_DEGREES = -28f
    }
}

/**
 * The live 3D subject behind the home hero (#3620).
 *
 * The catalogue's first card is the one place in the app where showing what the SDK
 * *does* costs nothing the user has to ask for: the model is already in the APK, the
 * IBL is the one every model demo shares, and the band is on screen for as long as it
 * takes to read the first row of titles. So it renders, rather than showing a picture
 * of itself.
 *
 * Four constraints shape everything here, and each is enforced in one place:
 *
 *  - **One Filament engine on this screen, released when the screen goes.**
 *    [rememberEngine] owns that: its `DisposableEffect` destroys the engine and the
 *    EGL context when this composable leaves — which is what the "Engine destroyed"
 *    line in logcat is. Nothing else on the home screen creates one, and this
 *    composable is only ever composed once, from the first featured page.
 *  - **The scroll is never paid for.** [SceneView] renders on demand, so a still hero
 *    costs no GPU frames and no CPU wake-up. What keeps it awake here is the turntable
 *    writing a rotation every frame; while the grid is being dragged, or once the band
 *    has scrolled away, [rendering] goes `false`, the turntable stops advancing and the
 *    loop settles and parks by itself. The load needs no special handling — the library
 *    keeps drawing while `modelLoader.isLoading` is `true`, because Filament finalises
 *    texture uploads inside the frame loop and a model that landed during a park would
 *    otherwise render untextured. Not `progress < 1f`: Filament reports `0`, not `1`, for
 *    a loader that was never asked for an async load, so that form would read as "still
 *    loading" for the lifetime of every procedural scene.
 *  - **Quality is sized to the band, not to the phone.** [RenderQuality.Performance]
 *    on a 320 dp strip that is decoration, not the subject of the screen; the
 *    Cinematic preset belongs to the Model Viewer this page opens.
 *  - **It is allowed to fail.** [rememberModelInstance] returns `null` while the
 *    model loads *and* if it never loads; [visible] stays `false` until there is
 *    something to see, so the caller keeps its bundled still underneath and the
 *    worst case is the screen that shipped before this one.
 *
 * @param collapseFraction 0 when the band is at rest, 1 when it has scrolled out.
 *   A lambda, read inside the `graphicsLayer` block at *draw* time: a `Float` parameter
 *   would recompose this composable — and the pager and card around it — on every
 *   frame of every scroll, to move a transform nothing in the composition reads.
 *   Drives the drawn stage only, never layout, so the hero collapses without the
 *   grid's own scroll maths ever depending on a height this composable chose.
 * @param rendering whether the subject should be turning right now (on screen, not being
 *                  flung). It drives the turntable, and the turntable is what holds the
 *                  render-on-demand loop awake.
 * @param onVisibilityChange raised with `true` on the first frame there is a model to draw.
 */
@Composable
internal fun HomeHeroScene(
    collapseFraction: () -> Float,
    rendering: Boolean,
    onVisibilityChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val engine = rememberEngine()
    val view = rememberView(engine)
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val environment = rememberModelDemoEnvironment(environmentLoader)
    val cameraNode = rememberCameraNode(engine) {
        position = Position(z = HERO_CAMERA_DISTANCE)
    }

    val modelInstance = rememberModelInstance(modelLoader, HOME_HERO_MODEL)

    // The hero declares itself visible one composition after the model exists, and
    // never declares itself invisible again — the caller crossfades its still out, and
    // a still that came back would read as a glitch, not as a fallback.
    var gaveUp by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(HERO_LOAD_TIMEOUT_MILLIS)
        gaveUp = true
    }
    DisposableEffect(modelInstance != null) {
        if (modelInstance != null) onVisibilityChange(true)
        onDispose { }
    }

    val turntable = remember { HeroTurntable() }
    val nodeHolder = remember { arrayOfNulls<ModelNodeImpl>(1) }
    // A plain holder, deliberately not Compose state: `onFrame` writes it on every
    // rendered frame, and a `mutableStateOf` written there would recompose this
    // composable 60 times a second to carry a timestamp nothing in the composition
    // reads — the exact jank this hero is not allowed to add to the scroll.
    val lastFrameNanos = remember { longArrayOf(0L) }

    // Filament has to keep drawing until the instance is there whatever the scroll is
    // doing, or the model lands untextured; after that, the caller decides.
    val loaded = modelInstance != null || gaveUp
    // Render-on-demand: "I want frames" is not something the caller states any more, it is
    // something the scene observes. Not advancing the turntable IS the pause.
    val advancing = !loaded || rendering

    // "Remove animations" is on: the subject is still there, it just stops turning on
    // its own. Read once per composition, not per frame.
    val idleTurntable = LocalMotionEnabled.current

    // …but "not advancing IS the pause" only works in one direction (#3718). The turntable
    // keeps itself awake while it turns — the `rotation` write below is a push source — and
    // `onFrame` fires only *after* a frame reached the surface, so once the band has parked
    // there is no callback left to notice that `advancing` went back to `true`. Scrolling the
    // hero back into view would leave a frozen subject on a screen that looks alive. One
    // frame is all this needs: its `onFrame` writes a rotation, and that pushes the next.
    val renderInvalidator = rememberRenderInvalidator()
    LaunchedEffect(advancing, idleTurntable) {
        if (advancing) renderInvalidator.requestRender()
    }

    // The stage shrinks and fades as the band leaves, drawn only: no re-measure, so
    // the grid's scroll offset can never depend on a height this collapse produced.
    val stageAlpha by animateFloatAsState(
        targetValue = if (modelInstance != null) 1f else 0f,
        animationSpec = tween(SceneViewTokens.Duration.mediumMillis),
        label = "hero-stage",
    )

    Box(
        modifier = modifier
            // The scene is decoration over a card that already names itself; a screen
            // reader must hear "Model Viewer", not a second, unlabelled 3D view.
            .clearAndSetSemantics { },
    ) {
        SceneView(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val collapse = collapseFraction().coerceIn(0f, 1f)
                    alpha = stageAlpha * (1f - collapse)
                    val scale = 1f - COLLAPSE_SCALE * collapse
                    scaleX = scale
                    scaleY = scale
                    translationY = -size.height * COLLAPSE_RISE * collapse
                },
            // A TextureView, not the default SurfaceView. A SurfaceView is punched
            // through the window below the whole Compose hierarchy: it cannot be
            // alpha-blended, cannot be clipped to the card's `radius-xl` corners and
            // cannot be crossfaded with the still it replaces — all three of which this
            // band needs. The cost is one extra copy per frame, on a paused-by-default
            // 320 dp strip.
            surfaceType = SurfaceType.TextureSurface,
            // Transparent clear, so the subject floats on the card's own `hero-field`
            // and the bundled still can fade out from under it.
            isOpaque = false,
            engine = engine,
            view = view,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            environmentLoader = environmentLoader,
            environment = environment,
            cameraNode = cameraNode,
            // No manipulator and no gesture listener: the scene answers no gesture at
            // all. A horizontal drag belongs to the featured pager, a vertical one to
            // the grid, a tap to the card that opens the Model Viewer (#3829).
            cameraManipulator = null,
            onGestureListener = null,
            renderQuality = RenderQuality.Performance,
            renderInvalidator = renderInvalidator,
            onFrame = { frameTimeNanos ->
                val previous = lastFrameNanos[0]
                lastFrameNanos[0] = frameTimeNanos
                if (previous == 0L) return@SceneView
                if (!advancing) return@SceneView
                val deltaSeconds = ((frameTimeNanos - previous) / 1_000_000_000.0).toFloat()
                    .coerceIn(0f, MAX_FRAME_SECONDS)
                val yaw = turntable.advance(deltaSeconds, idleTurntable)
                nodeHolder[0]?.rotation = Rotation(x = HERO_PITCH_DEGREES, y = yaw)
            },
        ) {
            modelInstance?.let { instance ->
                ModelNode(
                    modelInstance = instance,
                    scaleToUnits = HERO_SUBJECT_UNITS,
                    rotation = Rotation(x = HERO_PITCH_DEGREES, y = turntable.yawDegrees),
                    apply = { nodeHolder[0] = this },
                )
            }
        }

        // A touch shield over the viewport. The `SceneView` is an Android `View`, and
        // the interop layer hands it every touch that lands on it: if it claims the
        // stream, the pager and the card above it see consumed events and neither
        // pages nor opens the demo. This sibling sits on top so the hit test stops
        // here, and it observes without consuming — every event still travels up to
        // the pager (horizontal swipe), the grid (vertical scroll) and the card (tap).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent()
                    }
                },
        )
    }
}

/** How much of its size the stage gives up by the time the band has fully scrolled out. */
private const val COLLAPSE_SCALE = 0.35f

/** How far the stage rises into the collapse, as a fraction of its own height. */
private const val COLLAPSE_RISE = 0.18f

/** Clamp for a delta across a dropped frame or a resumed app — one turntable step, not a jump. */
private const val MAX_FRAME_SECONDS = 0.1f
