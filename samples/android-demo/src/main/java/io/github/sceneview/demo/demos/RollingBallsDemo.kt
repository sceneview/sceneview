package io.github.sceneview.demo.demos

import android.view.MotionEvent
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.rounded.ScreenRotationAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import com.google.android.filament.Colors
import com.google.android.filament.LightManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.View
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.rotation as rotationMatrix
import dev.romainguy.kotlin.math.transpose
import io.github.sceneview.Aabb
import io.github.sceneview.CameraFit
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.SceneScope
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.LocalDemoSceneCover
import io.github.sceneview.demo.MIN_RESERVED_SCENE_HEIGHT
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoStatusCard
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.StageSkyFog
import io.github.sceneview.demo.common.rememberStageSkybox
import io.github.sceneview.demo.common.themedStageSky
import io.github.sceneview.demo.demos.internal.TrayBallDrag
import io.github.sceneview.demo.demos.internal.TrayMotion
import io.github.sceneview.demo.demos.internal.TrayMotion.Tilt
import io.github.sceneview.demo.demos.internal.TrayStage
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.GlassActionPill
import io.github.sceneview.demo.ui.overMediaEdge
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.fitCameraToBounds
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.math.Transform
import io.github.sceneview.math.toQuaternion
import io.github.sceneview.node.CubeNode as CubeNodeImpl
import io.github.sceneview.node.FloorProvider
import io.github.sceneview.node.Node as NodeImpl
import io.github.sceneview.node.PhysicsBody
import io.github.sceneview.node.SphereNode as SphereNodeImpl
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberRenderInvalidator
import io.github.sceneview.rememberView
import io.github.sceneview.utils.screenToRay
import io.github.sceneview.verticalFovDegreesForFocalLength
import io.github.sceneview.sample.ui.LabeledSlider
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

// ─── Rolling balls ──────────────────────────────────────────────────────────
// A wooden tilt board of balls to drop, tip and knock over (#3820). PhysicsBody supplies gravity
// and the floor bounce; this sample adds mass-weighted sphere contacts, per-material bounce and
// rolling, and the board's rails, because PhysicsNode has no body-to-body collision API. The
// bodies are spheres and so are their colliders: what you see is exactly what collides.
//
// Rules this screen holds to:
//  - One SceneView for the life of the screen. Reset and every drop change the *bodies*, never the
//    scene, so there is no teardown frame to show black and no camera jump.
//  - Dragging the board tips it, and the balls roll (the first thing anyone tries). The drag
//    writes a target tilt synchronously; each rendered frame eases the board towards it and hands
//    the physics the gravity of the tilt that frame *shows* — see [TrayMotion].
//  - What changes the scene lives on the scene. The material to drop, Drop, Tilt and Reset sit in
//    the bottom band, where their effect is visible as it happens; a tap on the board drops a ball
//    right there. The settings sheet — which covers the board — keeps what is read or fine-tuned.
//  - The camera frames the board in the viewport it actually gets, and the orbit cannot go under it.

/** What a ball is made of — three contrasting behaviours, told apart at a glance by their finish. */
private enum class BallKind(
    @StringRes val labelRes: Int,
    val radius: Float,
    /** Bounce kept on the floor and the rails; a contact takes the lower of the pair's two. */
    val restitution: Float,
    /** Rolling resistance, as a share of the ball's weight: how soon a rolling ball comes to rest. */
    val rollingResistance: Float,
    /** Relative mass for ball-to-ball impulses: steel scatters rubber, not the reverse. */
    val mass: Float,
    /** Dot on the material chip. */
    val swatch: Color,
) {
    Rubber(R.string.demo_rolling_balls_ball_rubber, 0.075f, 0.82f, 0.03f, 1f, TrayStage.RUBBER_COLORS.first()),
    Steel(R.string.demo_rolling_balls_ball_steel, 0.06f, 0.35f, 0.008f, 4f, TrayStage.STEEL_COLOR),
    Glass(R.string.demo_rolling_balls_ball_glass, 0.065f, 0.5f, 0.012f, 1.6f, TrayStage.GLASS_COLOR),
}

/**
 * One ball on the board. [id] is unique for the screen's life, so a Compose key is never reused;
 * [tint] picks a rubber ball's colour from [TrayStage.RUBBER_COLORS].
 */
private data class TrayBall(
    val id: Int,
    val kind: BallKind,
    val start: Position,
    val velocity: Position = Position(0f),
    val tint: Int = 0,
)

/**
 * The tilt the board *shows*, and the node that shows it. Plain fields, not Compose state: they
 * are written once per rendered frame by `onFrame` and read by the touch handler, and neither
 * needs a recomposition.
 */
private class BoardMotion {
    var rendered: Tilt = Tilt.LEVEL
    var timeConstant: Float = TrayMotion.FOLLOW_SECONDS
    var pivot: NodeImpl? = null

    /** Where the camera looks: the drag is turned by the camera's azimuth around it. */
    var aim: Position = Position(0f)
    private var previousFrame = 0L

    /** Seconds since the previous rendered frame, capped so a paused screen does not lurch. */
    fun frameSeconds(nanos: Long): Float {
        val elapsed = if (previousFrame == 0L) 0L else (nanos - previousFrame).coerceIn(0L, 100_000_000L)
        previousFrame = nanos
        return elapsed / 1e9f
    }
}

/**
 * Rolling Balls — a wooden tilt board of balls to drop, tip and knock over (#3820, #4083).
 *
 * Formerly the Physics tab of `animation-physics`; its own demo since #4083, so the
 * old `physics` deep link lands here (see
 * [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES]).
 *
 * [PhysicsBody] supplies gravity and the floor bounce; this sample adds mass-weighted
 * sphere contacts, per-material bounce and rolling, and the board's rails, because
 * PhysicsNode has no body-to-body collision API.
 */
@Composable
fun RollingBallsDemo(onBack: () -> Unit) {
    val simulation = remember { DemoCollisionReplay() }
    val balls = remember { mutableStateListOf<TrayBall>().apply { addAll(openingBalls(firstId = 0)) } }
    var nextId by remember { mutableIntStateOf(balls.size) }
    var dropCount by remember { mutableIntStateOf(0) }
    var selectedKind by remember { mutableStateOf(BallKind.Rubber) }
    var liveBodyCount by remember { mutableIntStateOf(0) }
    var collisions by remember { mutableIntStateOf(0) }

    // Whether the simulation still has visible work, measured from the bodies (#3718). Every
    // change of population or slope re-arms it, so the screen renders continuously exactly while
    // something moves and parks on demand once the board is still.
    var simulationMoving by remember { mutableStateOf(true) }
    val wake: () -> Unit = {
        simulation.restartSettle()
        simulationMoving = true
    }

    // ── Board tilt: gesture → target → rendered → gravity ──────────────────
    // The drag, the sliders, Level and Reset all write [tiltTarget], synchronously. The board
    // follows it frame by frame in `onFrame`, which rotates the pivot and hands the simulation
    // the gravity of that same rendered tilt — see [TrayMotion] for why each step is there.
    var tiltTarget by remember { mutableStateOf(Tilt.LEVEL) }
    // True while the rendered board has not caught up with the target: keeps frames coming.
    var tiltMoving by remember { mutableStateOf(false) }
    val motion = remember { BoardMotion() }
    val showTilt: (Tilt) -> Unit = { tilt ->
        motion.pivot?.rotation = Rotation(x = tilt.pitch, z = tilt.roll)
        simulation.gravity = trayLocalGravity(tilt.pitch, tilt.roll)
        simulation.restartSettle()
    }
    val setTilt: (Tilt, Float) -> Unit = { tilt, timeConstant ->
        val clamped = TrayMotion.clamp(tilt)
        if (clamped != tiltTarget) {
            tiltTarget = clamped
            motion.timeConstant = timeConstant
            tiltMoving = true
        }
    }

    // `tiltEnabled` swaps what a one-finger drag over the board does: ON (the default) it tips the
    // board, OFF it orbits the camera. A drag that starts on a ball always picks the ball up
    // (#4180) — see `trayTouch` below. The angles survive the toggle.
    var tiltEnabled by remember { mutableStateOf(true) }
    // The gesture hint shows until the first drag of the current mode, and again when Tilt flips.
    var hintDismissed by remember { mutableStateOf(false) }

    // Reset: the opening shot again on a level board, with fresh ids so every body is rebuilt from
    // its start pose. Snapped, not eased, so the cue's first roll never runs downhill. The scene
    // itself is untouched — nothing is torn down, so nothing can show black.
    val reset: () -> Unit = {
        tiltTarget = Tilt.LEVEL
        motion.rendered = Tilt.LEVEL
        tiltMoving = false
        showTilt(Tilt.LEVEL)
        balls.clear()
        simulation.resetCounters()
        val opening = openingBalls(firstId = nextId)
        nextId += opening.size
        balls.addAll(opening)
        dropCount = 0
        wake()
    }
    // Drop: [count] balls of [kind], at [at] (board frame) or on a golden-angle spiral over the
    // board, so consecutive drops never stack on one another. Past the cap the oldest ball makes room.
    val drop: (BallKind, Int, Position?) -> Unit = { kind, count, at ->
        repeat(count) { k ->
            if (balls.size >= PHYSICS_MAX_BODIES) balls.removeAt(0)
            val spot = at ?: dropPosition(dropCount)
            val start = Position(spot.x, spot.y + (k / 5) * PHYSICS_DROP_LAYER, spot.z)
            balls.add(TrayBall(nextId, kind, start, tint = dropCount))
            nextId++
            dropCount++
        }
        wake()
    }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    // Studio stage (#3820): the studio HDR lights the board and is what the chrome and the glass
    // mirror; the themed stage sky sits behind it, never a black void and no room photograph
    // competing with the balls (#4089). The sky follows light and dark like the rest of the app.
    val studioLight = rememberHDREnvironment(
        environmentLoader,
        "environments/studio_2k.hdr",
        createSkybox = false,
    ) ?: rememberEnvironment(environmentLoader)
    // A theme flip recolours the skybox in place; a settled board is on-demand, so it has to ask
    // for the frame that shows it.
    val renderInvalidator = rememberRenderInvalidator()
    val sky = themedStageSky()
    val stageSkybox = rememberStageSkybox(engine, sky, renderInvalidator::requestRender)
    // The stage-sky fog dissolves the far floor into the `surface-container` horizon. It starts
    // well past the board.
    val view = rememberView(engine)
    StageSkyFog(view, sky, renderInvalidator::requestRender)
    // Soft shadows: the board hangs above the stage floor, and a hard-edged shadow there reads as
    // a cut-out. DPCF widens the penumbra with the distance to the caster, like a real softbox.
    LaunchedEffect(view) {
        view.setShadowType(View.ShadowType.DPCF)
        renderInvalidator.requestRender()
    }
    val physicsEnvironment = remember(studioLight, stageSkybox) {
        studioLight.copy(skybox = stageSkybox)
    }
    val cameraNode = rememberCameraNode(engine)
    val firstFrame = rememberFirstFrameState(engine)
    val counts = stringResource(R.string.demo_rolling_balls_counts, liveBodyCount, collisions)
    val board = rememberBoardMaterials(materialLoader, sky.floor)
    LaunchedEffect(board, sky.floor) {
        board.floor.setSrgbColor(sky.floor)
        renderInvalidator.requestRender()
    }

    // ── One finger on the board (#4180) ──────────────────────────────────────
    // Routed through the SceneView's raw touch callback rather than a Compose layer over it, so
    // one gesture can pick its owner on touch-down: a ball under the finger is picked up and
    // follows it across the board, released with the finger's speed; anywhere else the drag tips
    // the board (Tilt on) or falls through to the camera orbit (Tilt off). A touch that neither
    // grabs a ball nor travels past the touch slop is a tap, and drops a ball where it landed.
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val grip = remember { TrayGrip() }
    val dropAt: (Float, Float) -> Unit = dropAt@{ x, y ->
        val tilt = motion.rendered
        val ray = view.screenToRay(x, y) ?: return@dropAt
        val hit = TrayBallDrag.projectOnPlane(
            TrayBallDrag.toTrayFrame(tilt.pitch, tilt.roll, ray.origin),
            TrayBallDrag.toTrayFrame(tilt.pitch, tilt.roll, ray.direction),
            PHYSICS_FLOOR,
        ) ?: return@dropAt
        val bound = PHYSICS_TRAY_SIZE / 2f - PHYSICS_RAIL_THICKNESS / 2f - selectedKind.radius
        if (abs(hit.x) > bound || abs(hit.z) > bound) return@dropAt
        drop(selectedKind, 1, Position(hit.x, PHYSICS_DROP_HEIGHT, hit.z))
        hintDismissed = true
    }
    val trayTouch: (MotionEvent) -> Boolean = trayTouch@{ event ->
        val tilt = motion.rendered
        // Tap tracking runs for every gesture, whoever owns it: the orbit keeps its drags, and a
        // tap in orbit mode still drops a ball.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> grip.beginTap(event.x, event.y, event.eventTime)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> grip.tapAlive = false
            MotionEvent.ACTION_MOVE -> if (grip.tapAlive) {
                if (hypot(event.x - grip.downX, event.y - grip.downY) > touchSlop) grip.tapAlive = false
            }
            MotionEvent.ACTION_UP -> if (
                grip.tapAlive && grip.owner != TrayGrip.Owner.Ball &&
                event.eventTime - grip.downTime <= PHYSICS_TAP_MILLIS
            ) {
                grip.tapAlive = false
                dropAt(event.x, event.y)
            }
        }
        val step = TrayBallDrag.pointerStep(
            event.actionMasked,
            event.getPointerId(event.actionIndex),
            grip.pointerId,
        )
        when (step) {
            TrayBallDrag.PointerStep.Begin -> {
                // A new gesture never inherits a grab: a ball still held from a gesture whose end
                // never arrived is set down first.
                if (simulation.heldId != null) simulation.release(Position(0f, 0f, 0f))
                grip.owner = TrayGrip.Owner.None
                grip.pointerId = null
                val ray = view.screenToRay(event.x, event.y) ?: return@trayTouch false
                val origin = TrayBallDrag.toTrayFrame(tilt.pitch, tilt.roll, ray.origin)
                val direction = TrayBallDrag.toTrayFrame(tilt.pitch, tilt.roll, ray.direction)
                val picked = TrayBallDrag.pickBall(
                    origin,
                    direction,
                    simulation.bodies.map { (id, body) ->
                        TrayBallDrag.Candidate(id, body.node.position, body.radius)
                    },
                )
                val body = picked?.let { simulation.bodies[it] }
                if (picked != null && body != null) {
                    val planeY = PHYSICS_FLOOR + body.radius + TrayBallDrag.HOLD_LIFT
                    val hit = TrayBallDrag.projectOnPlane(origin, direction, planeY)
                    val center = body.node.position
                    grip.owner = TrayGrip.Owner.Ball
                    grip.pointerId = event.getPointerId(0)
                    grip.planeY = planeY
                    grip.offsetX = if (hit != null) center.x - hit.x else 0f
                    grip.offsetZ = if (hit != null) center.z - hit.z else 0f
                    grip.velocity.clear()
                    grip.velocity.add(event.eventTime, center.x, center.z)
                    simulation.hold(picked, Position(center.x, planeY, center.z))
                    hintDismissed = true
                    wake()
                    true
                } else if (tiltEnabled) {
                    grip.owner = TrayGrip.Owner.Tilt
                    grip.pointerId = event.getPointerId(0)
                    grip.lastX = event.x
                    grip.lastY = event.y
                    true
                } else {
                    false
                }
            }
            // The owning finger's own coordinates, never index 0: with a second finger down,
            // index 0 may be the other one.
            TrayBallDrag.PointerStep.Follow -> when (grip.owner) {
                TrayGrip.Owner.Ball -> {
                    val index = grip.pointerId?.let { event.findPointerIndex(it) } ?: -1
                    val ray = if (index >= 0) view.screenToRay(event.getX(index), event.getY(index)) else null
                    val hit = ray?.let {
                        TrayBallDrag.projectOnPlane(
                            TrayBallDrag.toTrayFrame(tilt.pitch, tilt.roll, it.origin),
                            TrayBallDrag.toTrayFrame(tilt.pitch, tilt.roll, it.direction),
                            grip.planeY,
                        )
                    }
                    if (hit != null) {
                        val x = hit.x + grip.offsetX
                        val z = hit.z + grip.offsetZ
                        grip.velocity.add(event.eventTime, x, z)
                        val (vx, vz) = grip.velocity.velocity(event.eventTime)
                        simulation.moveHeld(Position(x, grip.planeY, z), Position(vx, 0f, vz))
                        simulation.restartSettle()
                        simulationMoving = true
                    }
                    true
                }
                TrayGrip.Owner.Tilt -> {
                    val index = grip.pointerId?.let { event.findPointerIndex(it) } ?: -1
                    if (index >= 0) {
                        val x = event.getX(index)
                        val y = event.getY(index)
                        // Within the touch slop the gesture may still be a tap: the board holds
                        // still, and the drag starts from where the slop was crossed.
                        if (!grip.tapAlive) {
                            val eye = cameraNode.worldPosition
                            val aim = motion.aim
                            setTilt(
                                TrayMotion.dragTarget(
                                    tiltTarget,
                                    dxPx = x - grip.lastX,
                                    dyPx = y - grip.lastY,
                                    cameraYawRadians = TrayMotion.cameraYaw(eye.x, eye.z, aim.x, aim.z),
                                ),
                                TrayMotion.FOLLOW_SECONDS,
                            )
                            hintDismissed = true
                        }
                        grip.lastX = x
                        grip.lastY = y
                    }
                    true
                }
                TrayGrip.Owner.None -> false
            }
            // The owning finger lifted — even with another finger still down — or the system
            // cancelled the gesture: the grab ends there.
            TrayBallDrag.PointerStep.End, TrayBallDrag.PointerStep.Cancel -> {
                val owner = grip.owner
                grip.owner = TrayGrip.Owner.None
                grip.pointerId = null
                if (owner == TrayGrip.Owner.Ball) {
                    // A lift throws with the finger's speed; a cancel sets the ball down in place.
                    val (vx, vz) = if (step == TrayBallDrag.PointerStep.End) {
                        grip.velocity.velocity(event.eventTime)
                    } else {
                        0f to 0f
                    }
                    simulation.release(Position(vx, 0f, vz))
                    wake()
                }
                owner != TrayGrip.Owner.None
            }
            // A second finger while a ball or the tilt owns the gesture is ignored and the gesture
            // stays with its owner; with nothing owned, the orbit gets it.
            TrayBallDrag.PointerStep.Ignore -> grip.owner != TrayGrip.Owner.None
        }
    }

    // A phone held sideways leaves the board a band barely taller than the controls. There the
    // two rows of controls sit on one line, and the live count — which the sheet repeats — gives
    // its line back to the board.
    val compactHeight = LocalConfiguration.current.screenHeightDp < TRAY_COMPACT_HEIGHT_DP

    DemoScaffold(
        title = stringResource(R.string.demo_rolling_balls_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        peekHeader = if (compactHeight) null else counts,
        // The scene is full-frame under the chrome and these controls: the stage runs edge to
        // edge, and the board is framed in the band they leave free through `contentPadding` —
        // see the `scene` slot. Nothing here resizes the viewport.
        //
        // The gesture hint is not in this band: a pill that comes and goes here changed the band
        // the board is framed in, and the board jumped (#4073). It floats over the scene instead.
        bottomOverlay = {
            val kinds: @Composable () -> Unit = {
                BallKind.entries.forEach { kind ->
                    TrayGlassChip(
                        label = stringResource(kind.labelRes),
                        selected = kind == selectedKind,
                        swatch = kind.swatch,
                        toggle = false,
                        onClick = {
                            // Picking a material drops one straight away: the choice is seen,
                            // not just recorded.
                            selectedKind = kind
                            drop(kind, 1, null)
                        },
                    )
                }
            }
            val actions: @Composable () -> Unit = {
                // Fixed over-media palette (#3726): this row is theme-independent chrome, and
                // a default `Button` resolved to the light/dark `colorScheme.primary`.
                Button(
                    onClick = { drop(selectedKind, 1, null) },
                    modifier = Modifier.heightIn(min = SceneViewTokens.Layout.touchTarget),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SceneViewTokens.ArOverlay.accentProgress,
                        contentColor = SceneViewTokens.ArOverlay.onAccentProgress,
                    ),
                ) {
                    Icon(
                        Icons.Filled.ArrowDownward,
                        contentDescription = null,
                        modifier = Modifier.size(SceneViewTokens.Layout.dockIconSize),
                    )
                    Spacer(Modifier.width(SceneViewTokens.Space.xs))
                    Text(stringResource(R.string.demo_rolling_balls_drop))
                }
                TrayGlassChip(
                    label = stringResource(R.string.demo_rolling_balls_tilt_drag),
                    selected = tiltEnabled,
                    icon = Icons.Rounded.ScreenRotationAlt,
                    toggle = true,
                    onClick = {
                        tiltEnabled = !tiltEnabled
                        hintDismissed = false
                    },
                )
                GlassActionPill(
                    icon = Icons.Outlined.RestartAlt,
                    label = stringResource(R.string.demo_rolling_balls_reset),
                    onClick = reset,
                )
            }
            if (compactHeight) {
                Row(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    kinds()
                    actions()
                }
            } else {
                Row(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
                ) { kinds() }
                Row(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) { actions() }
            }
        },
        controls = {
            Text(counts, style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            ) {
                OutlinedButton(onClick = { drop(selectedKind, 10, null) }) {
                    Text(stringResource(R.string.demo_rolling_balls_drop_ten))
                }
                OutlinedButton(
                    enabled = !tiltTarget.isLevel,
                    onClick = { setTilt(Tilt.LEVEL, TrayMotion.LEVEL_SECONDS) },
                ) { Text(stringResource(R.string.demo_rolling_balls_tilt_level)) }
            }
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.xs))
            Text(
                stringResource(R.string.demo_rolling_balls_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.md))
            Text(
                text = stringResource(R.string.demo_rolling_balls_tilt_label),
                style = MaterialTheme.typography.labelLarge,
            )
            // The sliders mirror the drag rather than replacing it: they are what a screen reader
            // can operate, and they give the exact angle the drag can only approximate.
            LabeledSlider(
                label = stringResource(R.string.demo_rolling_balls_tilt_pitch),
                value = tiltTarget.pitch,
                onValueChange = { setTilt(tiltTarget.copy(pitch = it), TrayMotion.FOLLOW_SECONDS) },
                valueRange = -TrayMotion.MAX_TILT_DEGREES..TrayMotion.MAX_TILT_DEGREES,
                decimals = 0,
                unit = "°",
            )
            LabeledSlider(
                label = stringResource(R.string.demo_rolling_balls_tilt_roll),
                value = tiltTarget.roll,
                onValueChange = { setTilt(tiltTarget.copy(roll = it), TrayMotion.FOLLOW_SECONDS) },
                valueRange = -TrayMotion.MAX_TILT_DEGREES..TrayMotion.MAX_TILT_DEGREES,
                decimals = 0,
                unit = "°",
            )
            Text(
                text = stringResource(R.string.demo_rolling_balls_tilt_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // The scene runs edge to edge under the glass: the title row, the controls band and
            // the settings sheet all float over the live stage. What they cover is handed to the
            // SDK as `contentPadding`, so the camera treats the band they leave free as its
            // viewport — the board is drawn, and picked, there, and follows the sheet wherever
            // the finger takes it. The surface is not resized and the user's orbit is not touched.
            val safe = WindowInsets.safeDrawing.asPaddingValues()
            val layoutDirection = LocalLayoutDirection.current
            val cover = trayContentPadding(
                chrome = LocalDemoSceneCover.current,
                statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                sceneHeight = maxHeight,
                compactHeight = compactHeight,
                left = safe.calculateLeftPadding(layoutDirection),
                right = safe.calculateRightPadding(layoutDirection),
            )
            // Framed in the band the chrome leaves free, not the whole screen. The SDK never lets
            // that band go below a tenth of the view; the same floor here keeps the two in step.
            val visibleHeight = (maxHeight - cover.calculateTopPadding() - cover.calculateBottomPadding())
                .coerceAtLeast(maxHeight * TRAY_MIN_VISIBLE_FRACTION)
            // The hint sits at the foot of that band. Under a sheet dragged all the way up, or in
            // landscape, the band is barely taller than the hint and the board is all it has
            // room for.
            val hintFits = visibleHeight >= MIN_RESERVED_SCENE_HEIGHT + TRAY_HINT_CLEARANCE
            val visibleWidth = maxWidth -
                cover.calculateLeftPadding(layoutDirection) -
                cover.calculateRightPadding(layoutDirection)
            val aspect = if (visibleWidth.value > 0f && visibleHeight.value > 0f) {
                visibleWidth.value / visibleHeight.value
            } else {
                0.5f
            }
            val framing = remember(aspect) { trayFraming(aspect) }
            // One manipulator for the life of the screen: it carries the user's orbit, and a
            // framing that changes under it — the sheet, a window resize — re-fits the board
            // around that orbit instead of snapping back to the opening shot.
            val manipulator = remember { TrayCameraManipulator(home = framing) }
            // Written once the composition is applied, never while it runs: a composition that
            // is thrown away must not have moved the camera.
            SideEffect {
                manipulator.framing = framing
                motion.aim = framing.target
                renderInvalidator.requestRender()
            }
            SceneView(
                modifier = Modifier.fillMaxSize(),
                contentPadding = cover,
                engine = engine,
                view = view,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                environment = physicsEnvironment,
                cameraNode = cameraNode,
                // The rig is authored around the origin; re-centring it on its bounds would move
                // the board every time a ball flies up.
                autoCenterContent = false,
                onFrame = { nanos ->
                    firstFrame.onFrame(nanos)
                    // Board first, then physics, in the same frame: the balls always roll down the
                    // slope that is on screen, never the one of a frame ago.
                    val dt = motion.frameSeconds(nanos)
                    val target = tiltTarget
                    if (motion.rendered != target) {
                        motion.rendered = TrayMotion.follow(motion.rendered, target, dt, motion.timeConstant)
                        showTilt(motion.rendered)
                    }
                    tiltMoving = motion.rendered != target
                    // Step only once every ball on the list has registered its body, so a reset's
                    // opening shot always starts from the same complete population.
                    simulation.onFrame(nanos, playing = simulation.bodies.size == balls.size)
                    liveBodyCount = simulation.bodies.size
                    collisions = simulation.collisions
                    simulationMoving = simulation.isMoving
                },
                // The simulation is stepped from `onFrame`, which fires only *after* a frame
                // reached the surface — so it cannot be what keeps the loop awake (#3718). While
                // the board tips or something rolls the screen declares every vsync; a still board
                // hands it back to on-demand, where a drag, a drop or an orbit still repaints.
                frameRatePolicy = if (simulationMoving || tiltMoving) {
                    FrameRatePolicy.Continuous()
                } else {
                    FrameRatePolicy.OnDemand()
                },
                cameraManipulator = manipulator,
                renderInvalidator = renderInvalidator,
                onTouchEvent = { event, _ -> trayTouch(event) },
            ) {
                // Key light: high and to the front-left, so every ball throws a short shadow
                // towards the back-right of the board — the cue that tells a ball resting on the
                // wood from one in the air — and the board a soft one on the stage floor.
                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    direction = io.github.sceneview.math.Direction(-0.35f, -1f, -0.45f),
                    apply = {
                        intensity(PHYSICS_KEY_LIGHT_LUX)
                        castShadows(true)
                        shadowOptions(
                            LightManager.ShadowOptions().apply {
                                mapSize = PHYSICS_SHADOW_MAP_SIZE
                                stable = true
                            },
                        )
                    },
                )
                // The stage floor, well below the board so a 35° tilt never touches it: it takes
                // the board's shadow, and the fog melts its far edge into the horizon.
                CubeNode(
                    size = Size(PHYSICS_STAGE_FLOOR_SIZE, PHYSICS_STAGE_FLOOR_THICKNESS, PHYSICS_STAGE_FLOOR_SIZE),
                    position = Position(0f, PHYSICS_STAGE_FLOOR_Y - PHYSICS_STAGE_FLOOR_THICKNESS / 2f, 0f),
                    materialInstance = board.floor,
                )

                // Tilt pivot (#3621) — the board and every ball hang off this node, so the board
                // rotates as one rigid rig while the simulation stays in its own flat frame. Its
                // rotation is driven from `onFrame`, never from composition. The light and the
                // floor stay at the scene root: the room does not tip with the board.
                Node(apply = { motion.pivot = this }) {
                    TrayBoard(board)

                    for (ball in balls) {
                        key(ball.id) {
                            var nodeRef by remember { mutableStateOf<SphereNodeImpl?>(null) }
                            SphereNode(
                                radius = ball.kind.radius,
                                materialInstance = when (ball.kind) {
                                    BallKind.Rubber -> board.rubber[ball.tint.mod(board.rubber.size)]
                                    BallKind.Steel -> board.steel
                                    BallKind.Glass -> board.glass
                                },
                                position = ball.start,
                                apply = {
                                    isShadowCaster = true
                                    nodeRef = this
                                },
                            )
                            nodeRef?.let { node ->
                                DisposableEffect(node, simulation) {
                                    simulation.add(
                                        id = ball.id,
                                        kind = ball.kind,
                                        body = PhysicsBody(
                                            node = node,
                                            restitution = ball.kind.restitution,
                                            floorY = PHYSICS_FLOOR,
                                            radius = ball.kind.radius,
                                            initialVelocity = ball.velocity,
                                            // A floor provider keeps resting balls awake, so a
                                            // later impact or a new slope still moves them.
                                            floorProvider = FloorProvider { _, _, _, _ -> PHYSICS_FLOOR },
                                            gravity = simulation.gravity,
                                        ),
                                    )
                                    onDispose { simulation.remove(ball.id) }
                                }
                            }
                        }
                    }
                }
            }

            // Gesture hint, over the scene rather than in the bottom band (#4073): it sits just
            // above whatever covers the bottom of the scene — the controls, or the sheet — so it
            // never changes the size the board is framed in. It names what a drag does in the
            // current mode and leaves once the user has done it; flipping Tilt brings it back for
            // the new mode.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = cover.calculateBottomPadding() + SceneViewTokens.Space.md),
            ) {
                DemoStatusCard(
                    text = when {
                        hintDismissed || !hintFits -> null
                        tiltEnabled -> stringResource(R.string.demo_rolling_balls_tilt_hint)
                        else -> stringResource(R.string.demo_rolling_balls_orbit_hint)
                    },
                    tone = DemoStatusTone.Guidance,
                )
            }
        }
    }
}

/**
 * Every material of the board, created once for the life of the screen. The wood is one
 * procedural shader (`tray_wood`) in four instances, one per grain direction: a field cut along
 * X, frame pieces along X and along Z, and the rounded edges, whose cylinders are modelled along Y.
 * The balls and the floor use the demo's own `studio_pbr` / `studio_glass`: SceneView's stock
 * glTF-based material renders procedural spheres black (see `MaterialsDemo`).
 */
private class BoardMaterials(
    val field: MaterialInstance,
    val frameAlongX: MaterialInstance,
    val frameAlongZ: MaterialInstance,
    val frameAlongY: MaterialInstance,
    val floor: MaterialInstance,
    val steel: MaterialInstance,
    val glass: MaterialInstance,
    val rubber: List<MaterialInstance>,
) {
    val all: List<MaterialInstance>
        get() = listOf(this.field, frameAlongX, frameAlongZ, frameAlongY, floor, steel, glass) + rubber
}

@Composable
private fun rememberBoardMaterials(materialLoader: MaterialLoader, floorColor: Color): BoardMaterials {
    val materials = remember(materialLoader) {
        val wood = materialLoader.createMaterial("materials/tray_wood.filamat")
        val pbr = materialLoader.createMaterial("materials/studio_pbr.filamat")
        val glass = materialLoader.createMaterial("materials/studio_glass.filamat")
        fun woodPiece(early: Color, late: Color, rings: Float, axis: Position, offset: Position) =
            materialLoader.createInstance(wood).apply {
                setSrgbColor("earlyColor", early)
                setSrgbColor("lateColor", late)
                setParameter("grainAxis", axis.x, axis.y, axis.z)
                setParameter("grainOffset", offset.x, offset.y, offset.z)
                setParameter("ringDensity", rings)
                setParameter("roughness", TrayStage.WOOD_ROUGHNESS)
                setParameter("clearCoat", TrayStage.LACQUER)
                setParameter("clearCoatRoughness", TrayStage.LACQUER_ROUGHNESS)
            }
        fun walnut(axis: Position, offset: Position) = woodPiece(
            TrayStage.WALNUT_EARLY, TrayStage.WALNUT_LATE, TrayStage.WALNUT_RING_DENSITY, axis, offset,
        )
        fun studio(
            color: Color,
            metallic: Float,
            roughness: Float,
            coat: Float = 0f,
            coatRoughness: Float = 0f,
        ) = materialLoader.createInstance(pbr).apply {
            setSrgbColor(color)
            setParameter("metallic", metallic)
            setParameter("roughness", roughness)
            setParameter("reflectance", PHYSICS_DIELECTRIC_REFLECTANCE)
            // One shader serves several looks: every optional layer is written, an unwritten
            // uniform is not a guaranteed zero.
            setParameter("clearCoat", coat)
            setParameter("clearCoatRoughness", coatRoughness)
            setParameter("sheenColor", 0f, 0f, 0f)
            setParameter("sheenRoughness", 0f)
            setParameter("emissive", 0f, 0f, 0f)
        }
        BoardMaterials(
            field = woodPiece(
                TrayStage.MAPLE_EARLY, TrayStage.MAPLE_LATE, TrayStage.MAPLE_RING_DENSITY,
                axis = Position(1f, 0f, 0f), offset = Position(0f, 0.4f, 0.9f),
            ),
            frameAlongX = walnut(Position(1f, 0f, 0f), Position(0f, 0.3f, 0.25f)),
            frameAlongZ = walnut(Position(0f, 0f, 1f), Position(0.3f, 0.3f, 0f)),
            frameAlongY = walnut(Position(0f, 1f, 0f), Position(0.2f, 0f, 0.35f)),
            // Recoloured in place when the theme flips — see the LaunchedEffect on `sky.floor`.
            floor = studio(floorColor, metallic = 0f, roughness = PHYSICS_STAGE_FLOOR_ROUGHNESS),
            steel = studio(TrayStage.STEEL_COLOR, metallic = 1f, roughness = TrayStage.STEEL_ROUGHNESS),
            glass = materialLoader.createInstance(glass).apply {
                setSrgbColor(TrayStage.GLASS_COLOR)
                setParameter("roughness", TrayStage.GLASS_ROUGHNESS)
                setParameter("reflectance", PHYSICS_GLASS_REFLECTANCE)
                setParameter("transmission", 1f)
                setParameter("ior", TrayStage.GLASS_IOR)
            },
            rubber = TrayStage.RUBBER_COLORS.map { color ->
                studio(
                    color, metallic = 0f, roughness = TrayStage.RUBBER_ROUGHNESS,
                    coat = TrayStage.RUBBER_COAT, coatRoughness = TrayStage.RUBBER_COAT_ROUGHNESS,
                )
            },
        )
    }
    DisposableEffect(materials) {
        onDispose { materials.all.forEach(materialLoader::destroyMaterialInstance) }
    }
    return materials
}

/** Writes [color] (sRGB) to a `float4 color` parameter, linearised as the shader expects. */
private fun MaterialInstance.setSrgbColor(color: Color) =
    setParameter("color", Colors.RgbaType.SRGB, color.red, color.green, color.blue, 1f)

/** Writes [color] (sRGB) to the `float3` parameter [name], linearised as the shader expects. */
private fun MaterialInstance.setSrgbColor(name: String, color: Color) =
    setParameter(name, Colors.RgbType.SRGB, color.red, color.green, color.blue)

/**
 * The board: a maple field whose top face is the simulation floor, inside a walnut frame whose
 * inner face is where the collision rails stop a ball — built in the board's frame, so it tips
 * with the pivot it is composed in.
 *
 * The frame's top edges are rounded: each side is a straight block topped by a flat strip between
 * two cylinders, and the outer corners are a sphere over an upright cylinder, so the lacquer
 * catches one continuous highlight all the way round instead of four hard box edges.
 *
 * The frame casts, the field receives: the frame throws its shadow across the field's edge and the
 * balls throw theirs onto the wood, which is what seats them on it.
 */
@Composable
private fun SceneScope.TrayBoard(board: BoardMaterials) {
    val half = PHYSICS_TRAY_SIZE / 2f
    val r = TrayStage.RIM_EDGE_RADIUS
    // The rails stop a ball's surface half a rail-thickness inside the board edge; the frame's
    // inner face sits exactly there, so a ball comes to rest touching the wood it is seen hitting.
    val inner = half - PHYSICS_RAIL_THICKNESS / 2f
    val outer = inner + TrayStage.RIM_WIDTH
    val mid = (inner + outer) / 2f
    val fieldBottom = PHYSICS_FLOOR - TrayStage.FIELD_THICKNESS
    val bottom = fieldBottom - TrayStage.BODY_HEIGHT
    val top = PHYSICS_FLOOR + TrayStage.RIM_HEIGHT
    // The straight block runs up to where the rounding starts.
    val blockTop = top - r
    val blockHeight = blockTop - bottom
    val blockY = (blockTop + bottom) / 2f
    val caster: CubeNodeImpl.() -> Unit = { isShadowCaster = true }

    // Playing field, and the body under it.
    CubeNode(
        size = Size(2f * inner, TrayStage.FIELD_THICKNESS, 2f * inner),
        position = Position(0f, PHYSICS_FLOOR - TrayStage.FIELD_THICKNESS / 2f, 0f),
        materialInstance = board.field,
    )
    CubeNode(
        size = Size(2f * inner, TrayStage.BODY_HEIGHT, 2f * inner),
        position = Position(0f, fieldBottom - TrayStage.BODY_HEIGHT / 2f, 0f),
        materialInstance = board.frameAlongX,
        apply = caster,
    )
    for (side in listOf(-1f, 1f)) {
        // Blocks: the two along Z stop short of the outer corners, the two along X reach them,
        // and the upright corner cylinders round what is left.
        CubeNode(
            size = Size(TrayStage.RIM_WIDTH, blockHeight, 2f * (outer - r)),
            position = Position(side * mid, blockY, 0f),
            materialInstance = board.frameAlongZ,
            apply = caster,
        )
        CubeNode(
            size = Size(2f * (outer - r), blockHeight, TrayStage.RIM_WIDTH),
            position = Position(0f, blockY, side * mid),
            materialInstance = board.frameAlongX,
            apply = caster,
        )
        // Flat top strips between the two rounded edges.
        CubeNode(
            size = Size(TrayStage.RIM_WIDTH - 2f * r, r, 2f * (outer - r)),
            position = Position(side * mid, top - r / 2f, 0f),
            materialInstance = board.frameAlongZ,
            apply = caster,
        )
        CubeNode(
            size = Size(2f * (inner + r), r, TrayStage.RIM_WIDTH - 2f * r),
            position = Position(0f, top - r / 2f, side * mid),
            materialInstance = board.frameAlongX,
            apply = caster,
        )
        // Rounded top edges: the outer ones close into a loop through the corner spheres; the
        // inner ones cross at the inside corners.
        for ((edge, length) in listOf(outer - r to 2f * (outer - r), inner + r to 2f * (inner + r))) {
            CylinderNode(
                radius = r,
                height = length,
                position = Position(side * edge, top - r, 0f),
                rotation = Rotation(x = 90f),
                materialInstance = board.frameAlongY,
            )
            CylinderNode(
                radius = r,
                height = length,
                position = Position(0f, top - r, side * edge),
                rotation = Rotation(z = 90f),
                materialInstance = board.frameAlongY,
            )
        }
        for (other in listOf(-1f, 1f)) {
            val corner = outer - r
            SphereNode(
                radius = r,
                position = Position(side * corner, top - r, other * corner),
                materialInstance = board.frameAlongY,
            )
            CylinderNode(
                radius = r,
                height = blockHeight,
                position = Position(side * corner, blockY, other * corner),
                materialInstance = board.frameAlongY,
            )
        }
    }
}

/**
 * Who owns the finger currently on the table (#4180): a ball it picked up, the tray tilt, or
 * nobody — in which case the gesture belongs to the camera orbit. Decided once, on touch-down,
 * and kept until the finger lifts.
 */
private class TrayGrip {
    enum class Owner { None, Ball, Tilt }

    var owner: Owner = Owner.None

    /** Id of the finger that owns the grab; other fingers never move or release it. */
    var pointerId: Int? = null

    /** Height, in the tray's frame, of the plane a held ball slides along. */
    var planeY: Float = 0f

    /** Ball centre minus the point under the finger at grab time: the ball never jumps. */
    var offsetX: Float = 0f
    var offsetZ: Float = 0f

    /** Last pointer position of a tilt drag, in pixels. */
    var lastX: Float = 0f
    var lastY: Float = 0f

    /**
     * Whether the gesture can still be a tap: one finger, no further than the touch slop from
     * where it went down. A tap on the board drops a ball there.
     */
    var tapAlive: Boolean = false
    var downX: Float = 0f
    var downY: Float = 0f
    var downTime: Long = 0L

    fun beginTap(x: Float, y: Float, time: Long) {
        tapAlive = true
        downX = x
        downY = y
        downTime = time
    }

    val velocity = TrayBallDrag.ThrowVelocityTracker()
}

/**
 * A selectable capsule over the scene: glass when off, solid white when on — the white-on-media
 * language of the dock, readable on the dark stage in both themes. [swatch] shows the colour of
 * the ball a material chip stands for; [icon] labels a toggle. [toggle] picks the semantics: a
 * switch for Tilt, one radio button of a group for the materials.
 */
@Composable
private fun TrayGlassChip(
    label: String,
    selected: Boolean,
    toggle: Boolean,
    onClick: () -> Unit,
    swatch: Color? = null,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(SceneViewTokens.Radius.full)
    val content = if (selected) SceneViewTokens.Stage.background else SceneViewTokens.Glass.onGlass
    Row(
        modifier = Modifier
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .overMediaEdge(shape)
            .clip(shape)
            .background(if (selected) SceneViewTokens.Glass.onGlass else SceneViewTokens.Glass.surface)
            .then(
                if (toggle) {
                    Modifier.toggleable(value = selected, role = Role.Switch, onValueChange = { onClick() })
                } else {
                    Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                },
            )
            .padding(horizontal = SceneViewTokens.Glass.pillPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
    ) {
        when {
            selected && swatch != null -> Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(SceneViewTokens.Layout.dockIconSize),
            )
            swatch != null -> Box(
                Modifier
                    .size(SceneViewTokens.Space.md)
                    .clip(CircleShape)
                    .background(swatch),
            )
            icon != null -> Icon(
                icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(SceneViewTokens.Layout.dockIconSize),
            )
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

/**
 * What Rolling Balls hands to `contentPadding`, from what the scaffold reports as covered.
 *
 * The bottom is taken as it is: the controls and the settings sheet are where the board must not
 * be. The sides are the window's safe insets — a display cutout on a phone held sideways, a
 * navigation bar on the short edge — so the board is centred where the controls are centred. The
 * top is a row of glass chips over a live stage, and it gives way in two cases.
 *
 * - **A phone held sideways** ([compactHeight]): the title is a chip in a corner the centred
 *   board never reaches, and counting it as a band would halve a stage that is already short.
 *   Only the status bar is kept clear.
 * - **A sheet dragged all the way up**: what is left under the title row is thinner than the
 *   tenth of the view the SDK keeps visible, and the SDK would take the difference from both
 *   edges — part of it under the sheet. The top yields the whole difference instead, so the
 *   board stays above the sheet.
 */
internal fun trayContentPadding(
    chrome: PaddingValues,
    statusBar: Dp,
    sceneHeight: Dp,
    compactHeight: Boolean,
    left: Dp = 0.dp,
    right: Dp = 0.dp,
): PaddingValues {
    val bottom = chrome.calculateBottomPadding()
    val top = if (compactHeight) {
        statusBar
    } else {
        val room = sceneHeight - bottom - sceneHeight * TRAY_MIN_VISIBLE_FRACTION
        minOf(chrome.calculateTopPadding(), room.coerceAtLeast(0.dp))
    }
    return PaddingValues.Absolute(left = left, top = top, right = right, bottom = bottom)
}

/**
 * The tray's opening shot for a visible area of [aspect]: the table itself fitted at
 * [PHYSICS_CAMERA_PITCH_DEGREES] of look-down — [PHYSICS_FRAME_WIDTH_FILL] of the width, centred in
 * the band between the title row and the controls (#4180). The SDK's [fitCameraToBounds] does the
 * fit; the scene declares its own bounds, because a board, its rails and thirty balls are not one
 * model to measure.
 */
private fun trayFraming(aspect: Float): CameraFit {
    val half = PHYSICS_TABLE_SIZE / 2f
    val low = PHYSICS_FLOOR - TrayStage.FIELD_THICKNESS - TrayStage.BODY_HEIGHT
    val high = PHYSICS_FLOOR + TrayStage.RIM_HEIGHT
    val pitch = Math.toRadians(PHYSICS_CAMERA_PITCH_DEGREES.toDouble())
    val fit = fitCameraToBounds(
        bounds = Aabb(
            center = Position(0f, (low + high) / 2f, 0f),
            halfExtent = Position(half, (high - low) / 2f, half),
        ),
        direction = Position(0f, -sin(pitch).toFloat(), -cos(pitch).toFloat()),
        verticalFovDegrees = verticalFovDegreesForFocalLength(PHYSICS_FOCAL_LENGTH_MM),
        aspect = aspect.toDouble(),
        widthFill = PHYSICS_FRAME_WIDTH_FILL,
        heightFill = PHYSICS_FRAME_HEIGHT_FILL,
    )
    return checkNotNull(fit) { "the tray has a volume to frame" }
}

/**
 * The stock orbit with the eye kept above the tray: its polar angle is clamped between
 * [PHYSICS_MIN_POLAR_DEGREES] and [PHYSICS_MAX_POLAR_DEGREES] from straight up and re-aimed at
 * the tray, so no drag can carry the camera under the floor and lose the balls from view.
 *
 * The orbit is built once, around [home]. A later [framing] — the same board fitted in another
 * visible area — is reached by carrying the orbit's eye over to it: same angles about the new
 * target, radius scaled by the two fits' distances. The user's orbit and zoom survive a re-fit.
 */
private class TrayCameraManipulator(
    private val home: CameraFit,
) : CameraGestureDetector.CameraManipulator {
    /** The fit in force. Written as a composition is applied, read by the frame loop — both on main. */
    var framing: CameraFit = home

    private val orbit = CameraGestureDetector.DefaultCameraManipulator(
        eyePosition = home.eye,
        targetPosition = home.target,
    )

    override fun setViewport(width: Int, height: Int) = orbit.setViewport(width, height)

    override fun getTransform(): Transform {
        val transform = orbit.getTransform()
        val orbitEye = transform.position
        val clamped = io.github.sceneview.demo.clampOrbitEyePitch(
            orbitEye, home.target, PHYSICS_MIN_POLAR_DEGREES, PHYSICS_MAX_POLAR_DEGREES,
        )
        val live = framing
        if (live == home && clamped == orbitEye) return transform
        val scale = live.distance / home.distance
        return Transform(
            dev.romainguy.kotlin.math.lookAt(
                eye = live.target + (clamped - home.target) * scale,
                target = live.target,
                up = dev.romainguy.kotlin.math.Float3(0f, 1f, 0f),
            )
        )
    }

    override fun grabBegin(x: Int, y: Int, strafe: Boolean) = orbit.grabBegin(x, y, strafe)
    override fun grabUpdate(x: Int, y: Int) = orbit.grabUpdate(x, y)
    override fun grabEnd() = orbit.grabEnd()
    override fun scrollBegin(x: Int, y: Int, separation: Float) = orbit.scrollBegin(x, y, separation)
    override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) =
        orbit.scrollUpdate(x, y, prevSeparation, currSeparation)
    override fun scrollEnd() = orbit.scrollEnd()
    override fun update(deltaTime: Float) = orbit.update(deltaTime)
}

/**
 * Gravity expressed in the tilted tray's own frame.
 *
 * The tray pivot is rotated by `Rotation(pitch, 0, roll)`; a vector that is constant in world
 * space is therefore the inverse of that rotation applied to it inside the tray. We build the
 * rotation through the exact same `toQuaternion()` call [io.github.sceneview.node.Node.rotation]
 * uses and invert it by transposing the matrix (a rotation matrix is orthonormal), so the result
 * cannot drift from whatever Euler order the SDK settles on.
 *
 * At zero tilt this returns `(0, PhysicsBody.GRAVITY, 0)` exactly, which is what keeps the
 * untouched demo bit-identical to its previous behaviour.
 */
internal fun trayLocalGravity(pitchDegrees: Float, rollDegrees: Float): Position {
    if (pitchDegrees == 0f && rollDegrees == 0f) {
        return Position(0f, PhysicsBody.GRAVITY, 0f)
    }
    val trayRotation = rotationMatrix(Rotation(pitchDegrees, 0f, rollDegrees).toQuaternion())
    val local = transpose(trayRotation) * Float4(0f, PhysicsBody.GRAVITY, 0f, 0f)
    return Position(local.x, local.y, local.z)
}


/** Balls on the tray at once; past it a drop recycles the oldest, so Drop never goes dead. */
private const val PHYSICS_MAX_BODIES = 30
private const val PHYSICS_FLOOR = -0.5f
private const val PHYSICS_STEP_NANOS = 8_333_333L

/** Side of the square tray, rail to rail. */
private const val PHYSICS_TRAY_SIZE = 1.6f
private const val PHYSICS_RAIL_THICKNESS = 0.03f

/** Height a dropped ball starts from — inside the frame, so the fall itself is seen. */
private const val PHYSICS_DROP_HEIGHT = 0.2f

/** Extra height per five balls of one multi-ball drop, so a Drop 10 does not spawn overlapping. */
private const val PHYSICS_DROP_LAYER = 0.2f

/** Outer footprint of the table: the tray plus its rim on both sides (#4180). */
private const val PHYSICS_TABLE_SIZE = PHYSICS_TRAY_SIZE - PHYSICS_RAIL_THICKNESS + 2f * TrayStage.RIM_WIDTH

/**
 * Look-down of the opening frame: the whole felt reads, and so does a ball's bounce. Steeper than
 * the 32° it was before the finger drag (#4180): the more the felt faces the eye, the more screen
 * a centimetre of table gets, so a ball follows the finger as precisely at the back as at the front.
 */
private const val PHYSICS_CAMERA_PITCH_DEGREES = 40f

/**
 * Share of the viewport width the table spans in the opening shot (#4180). The drop column is no
 * longer part of the fit: a dropped ball starts just above the felt and is in view at once, and
 * reserving room for it left an empty band above a table that filled three quarters of the width.
 */
private const val PHYSICS_FRAME_WIDTH_FILL = 0.92f

/** Most of the viewport height the table may span — the binding axis in landscape. */
private const val PHYSICS_FRAME_HEIGHT_FILL = 0.84f

/**
 * Least share of the scene's height the board is framed in, whatever the chrome covers: the floor
 * the SDK applies to `contentPadding` (a tenth of the view), mirrored so the fit is computed for
 * the band the camera really projects into.
 */
private const val TRAY_MIN_VISIBLE_FRACTION = 0.1f

/**
 * Window height, in dp, under which the two rows of controls share one line — Material's compact
 * height class, i.e. a phone held sideways.
 */
private const val TRAY_COMPACT_HEIGHT_DP = 480

/**
 * Height the gesture hint takes at the foot of the free band, margin included. The hint shows
 * only when the band keeps the least height a scene is framed in above it.
 */
private val TRAY_HINT_CLEARANCE = 80.dp

/** SceneView's stock lens, which the demo's camera keeps. */
private const val PHYSICS_FOCAL_LENGTH_MM = 28.0

/** The orbit's polar range: never straight down, never level with or under the tray. */
private const val PHYSICS_MIN_POLAR_DEGREES = 15f
private const val PHYSICS_MAX_POLAR_DEGREES = 78f

/**
 * Minimum closing speed, in m/s, for a contact to be worth counting as an impact.
 *
 * A body pressed against a rail is re-accelerated into it every step, so without this floor a
 * tilted tray counts one "impact" per body per step — 7 resting balls turned the counter into
 * 75 000 in under a minute (#3621). It matches the threshold the floor bounce and the
 * sphere-to-sphere response already use, so all three surfaces agree on what a hit is.
 */
private const val PHYSICS_IMPACT_SPEED = 0.2f

/**
 * How long every body has to stay put before the screen stops declaring continuous rendering.
 * Matches the SDK's own settle window (`SETTLE_DURATION_NANOS`), and for the same reason: a ball at
 * the apex of its bounce is motionless for one frame without being finished.
 */
private const val PHYSICS_SETTLE_NANOS = 500_000_000L

/** Squared displacement below which a body counts as not having moved: 0.1 mm. */
private const val MOTION_EPSILON_SQ = 1e-8f

private fun distanceSquared(a: Position, b: Position): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    val dz = a.z - b.z
    return dx * dx + dy * dy + dz * dz
}

/** Longest press that still counts as a tap on the board (and drops a ball there). */
private const val PHYSICS_TAP_MILLIS = 350L

/** Key light, in lux: the studio HDR does the rest. */
private const val PHYSICS_KEY_LIGHT_LUX = 6_000f
private const val PHYSICS_SHADOW_MAP_SIZE = 2048

/** Fresnel at normal incidence of the non-metals: 4 %, Filament's default dielectric. */
private const val PHYSICS_DIELECTRIC_REFLECTANCE = 0.5f
private const val PHYSICS_GLASS_REFLECTANCE = 0.5f

/**
 * The stage floor: far enough under the pivot that a board tipped 35° both ways never reaches it,
 * wide enough that the fog has swallowed its edge before the eye can find it.
 */
private const val PHYSICS_STAGE_FLOOR_Y = -1.45f
private const val PHYSICS_STAGE_FLOOR_SIZE = 16f
private const val PHYSICS_STAGE_FLOOR_THICKNESS = 0.02f
private const val PHYSICS_STAGE_FLOOR_ROUGHNESS = 0.9f

/** One fixed simulation step, in seconds. */
private const val PHYSICS_STEP_SECONDS = PHYSICS_STEP_NANOS / 1e9f

/**
 * Turns "did anything actually move?" into the answer a [FrameRatePolicy.Continuous] declaration is
 * allowed to rest on (#3718).
 *
 * The physics screen used to declare `Continuous()` from a `replaying` flag that started `true` and
 * was cleared only by the Reset button. The stack of spheres therefore held 878 frames per 15 s on a
 * picture identical to the byte — the same lie as a loading flag nobody lowers. A declaration of
 * continuous rendering has to follow real motion, so this watches the thing that would be visible:
 * the bodies' positions, with a settle window rather than a single still frame, because a ball at
 * the top of its bounce is motionless for one frame and is not finished.
 */
internal class MotionSettleTracker(private val settleNanos: Long = PHYSICS_SETTLE_NANOS) {

    private var lastMotionNanos: Long? = null

    /** True while something moved within the last [settleNanos]. Starts `true`: nothing seen yet. */
    var isMoving: Boolean = true
        private set

    fun update(frameTimeNanos: Long, moved: Boolean) {
        val since = lastMotionNanos
        if (moved || since == null) {
            lastMotionNanos = frameTimeNanos
            isMoving = true
            return
        }
        isMoving = frameTimeNanos - since < settleNanos
    }

    /** Re-arms the window: a replay, a new body or a tilt is motion that has not happened yet. */
    fun restart() {
        lastMotionNanos = null
        isMoving = true
    }
}

/**
 * The opening shot, and what Reset restores: a chrome cue ball rolling into a pyramid of rubber
 * balls with two glass marbles in it. Steel is four times the rubber's mass, so the pile scatters
 * instead of stopping it dead.
 */
private fun openingBalls(firstId: Int): List<TrayBall> {
    val r = BallKind.Rubber.radius
    val rowHeight = r * sqrt(3f)
    val baseX = 0.05f
    val pyramid = listOf(
        Position(baseX, PHYSICS_FLOOR + r, 0f),
        Position(baseX + 2f * r, PHYSICS_FLOOR + r, 0f),
        Position(baseX + 4f * r, PHYSICS_FLOOR + r, 0f),
        Position(baseX + r, PHYSICS_FLOOR + r + rowHeight, 0f),
        Position(baseX + 3f * r, PHYSICS_FLOOR + r + rowHeight, 0f),
        Position(baseX + 2f * r, PHYSICS_FLOOR + r + 2f * rowHeight, 0f),
    )
    val cue = TrayBall(
        id = firstId,
        kind = BallKind.Steel,
        start = Position(-0.65f, PHYSICS_FLOOR + 0.18f, 0f),
        velocity = Position(1.9f, 0.4f, 0f),
    )
    val kinds = listOf(
        BallKind.Rubber, BallKind.Rubber, BallKind.Rubber,
        BallKind.Glass, BallKind.Glass,
        BallKind.Rubber,
    )
    return listOf(cue) + pyramid.mapIndexed { i, p -> TrayBall(firstId + 1 + i, kinds[i], p, tint = i) }
}

/**
 * Where the [n]th drop falls from: a golden-angle spiral over the tray, so consecutive drops land
 * apart from each other and every one of them is seen hitting the floor.
 */
private fun dropPosition(n: Int): Position {
    val angle = n * 2.3999632f
    val spread = 0.1f + 0.4f * ((n * 0.618034f) % 1f)
    return Position(spread * cos(angle), PHYSICS_DROP_HEIGHT, spread * sin(angle))
}

/** A deterministic sphere-contact demonstration, deliberately local to this sample. */
private class DemoCollisionReplay {
    val bodies = sortedMapOf<Int, PhysicsBody>()
    private val kinds = mutableMapOf<Int, BallKind>()

    private val settle = MotionSettleTracker()
    private val previousPositions = mutableMapOf<Int, Position>()

    /**
     * Whether the simulation still has visible work. Read by the composable to decide between
     * [FrameRatePolicy.Continuous] and [FrameRatePolicy.OnDemand] — see [MotionSettleTracker].
     */
    val isMoving: Boolean get() = settle.isMoving

    /** Called when the population or the slope changes: the settle window starts over. */
    fun restartSettle() = settle.restart()

    /**
     * Gravity in the tray's frame, pushed onto every body at the top of each step. Held here
     * rather than on the bodies so a ball dropped mid-tilt starts under the same slope as the
     * ones already rolling.
     */
    var gravity: Position = Position(0f, PhysicsBody.GRAVITY, 0f)
    var collisions = 0
        private set
    private var previousFrame = 0L
    private var accumulatedNanos = 0L

    fun add(id: Int, kind: BallKind, body: PhysicsBody) {
        bodies[id] = body
        kinds[id] = kind
    }

    fun remove(id: Int) {
        bodies.remove(id)
        kinds.remove(id)
        if (id == heldId) heldId = null
    }

    // ── A ball in the hand (#4180) ───────────────────────────────────────────
    // The held ball is kinematic: each step puts it where the finger is and gives it the finger's
    // velocity, and it takes part in contacts with an infinite mass, so it shoves the balls it is
    // dragged through without ever being pushed off the finger.

    /** The ball the finger is holding, if any. */
    var heldId: Int? = null
        private set
    private var heldTarget: Position = Position(0f)
    private var heldVelocity: Position = Position(0f)

    /** Picks up ball [id], carried at [target] (tray frame) until [release]. */
    fun hold(id: Int, target: Position) {
        if (id !in bodies) return
        heldId = id
        heldVelocity = Position(0f)
        heldTarget = clampToTray(id, target)
    }

    /** Moves the held ball to [target], kept inside the rails, travelling at [velocity]. */
    fun moveHeld(target: Position, velocity: Position) {
        val id = heldId ?: return
        heldTarget = clampToTray(id, target)
        heldVelocity = velocity
    }

    /** Lets go of the held ball with [velocity]: it drops the hold lift and rolls on. */
    fun release(velocity: Position) {
        val id = heldId ?: return
        heldId = null
        bodies[id]?.velocity = velocity
    }

    private fun clampToTray(id: Int, p: Position): Position {
        val radius = bodies[id]?.radius ?: return p
        val bound = PHYSICS_TRAY_SIZE / 2f - PHYSICS_RAIL_THICKNESS / 2f - radius
        return Position(p.x.coerceIn(-bound, bound), p.y, p.z.coerceIn(-bound, bound))
    }

    /** Reset starts the impact count over along with the opening shot. */
    fun resetCounters() {
        collisions = 0
        accumulatedNanos = 0L
    }

    fun onFrame(nanos: Long, playing: Boolean) {
        val elapsed = if (previousFrame == 0L) 0L else (nanos - previousFrame).coerceIn(0L, 100_000_000L)
        previousFrame = nanos
        if (!playing) {
            settle.update(nanos, moved = false)
            return
        }
        accumulatedNanos += elapsed
        while (accumulatedNanos >= PHYSICS_STEP_NANOS) {
            step()
            accumulatedNanos -= PHYSICS_STEP_NANOS
        }
        // A held ball keeps the screen live even while the finger pauses: it can move again at
        // any moment, and the next touch event must not wait for an on-demand frame.
        settle.update(nanos, moved = takeMotionSinceLastFrame() || heldId != null)
    }

    /**
     * Whether any body ended this frame somewhere the eye could tell from where it started it.
     * The threshold is a tenth of a millimetre — three orders of magnitude under a sphere radius,
     * so it cannot hide motion, and above the float noise a resting contact keeps producing.
     */
    private fun takeMotionSinceLastFrame(): Boolean {
        var moved = false
        for ((index, body) in bodies) {
            val position = body.node.position
            val previous = previousPositions.put(index, position)
            if (moved) continue
            moved = previous == null || distanceSquared(previous, position) > MOTION_EPSILON_SQ
        }
        if (previousPositions.size != bodies.size) {
            previousPositions.keys.retainAll(bodies.keys)
            moved = true
        }
        return moved
    }

    private fun kindOf(id: Int): BallKind = kinds[id] ?: BallKind.Rubber

    private fun step() {
        for ((id, body) in bodies) {
            body.gravity = gravity
            if (id == heldId) {
                body.node.position = heldTarget
                body.velocity = heldVelocity
                continue
            }
            val before = body.velocity
            body.step(PHYSICS_STEP_NANOS, 0L)
            val p = body.node.position
            val v = body.velocity
            if (before.y < -PHYSICS_IMPACT_SPEED && v.y > 0f) collisions++
            // The rails hold at any height: a ball that bounces over the rail height is kept on
            // the board instead of leaving the frame for good. A hit bounces; a ball merely
            // pressed against a rail by the slope stops dead there instead of buzzing against it.
            val bound = PHYSICS_TRAY_SIZE / 2f - PHYSICS_RAIL_THICKNESS / 2f - body.radius
            var vx = v.x
            var vy = v.y
            var vz = v.z
            if (abs(p.x) > bound && p.x * vx > 0f) {
                vx = if (abs(vx) >= PHYSICS_IMPACT_SPEED) {
                    collisions++
                    -vx * body.restitution
                } else {
                    0f
                }
            }
            if (abs(p.z) > bound && p.z * vz > 0f) {
                vz = if (abs(vz) >= PHYSICS_IMPACT_SPEED) {
                    collisions++
                    -vz * body.restitution
                } else {
                    0f
                }
            }
            // On the board: a rebound too small to see is a ball sitting on the wood, and a ball
            // on the wood rolls — 5/7 of the slope's pull, minus its rolling resistance.
            if (TrayMotion.isRolling(p.y, vy, body.radius, PHYSICS_FLOOR)) {
                if (abs(vy) < PHYSICS_IMPACT_SPEED) vy = 0f
                val (rx, rz) = TrayMotion.roll(
                    vx, vz, gravity.x, gravity.y, gravity.z,
                    kindOf(id).rollingResistance, PHYSICS_STEP_SECONDS,
                )
                vx = rx
                vz = rz
            }
            if (abs(p.x) > bound || abs(p.z) > bound) {
                body.node.position = Position(p.x.coerceIn(-bound, bound), p.y, p.z.coerceIn(-bound, bound))
            }
            body.velocity = Position(vx, vy, vz)
        }
        // Stable id order, a fixed timestep and fixed initial velocities make the same opening
        // population produce the same sequence of impacts on every reset.
        for ((aId, a) in bodies) {
            for ((bId, b) in bodies) {
                if (bId > aId && resolvePair(a, kindOf(aId), aId == heldId, b, kindOf(bId), bId == heldId)) {
                    collisions++
                }
            }
        }
    }

    /**
     * Separates [a] and [b] if their spheres overlap and exchanges the mass-weighted impulse along
     * the contact normal: the lighter ball gives way and takes the larger share of the velocity
     * change. Returns `true` when the pair met hard enough to count as an impact.
     */
    private fun resolvePair(
        a: PhysicsBody,
        aKind: BallKind,
        aHeld: Boolean,
        b: PhysicsBody,
        bKind: BallKind,
        bHeld: Boolean,
    ): Boolean {
        val pa = a.node.position
        val pb = b.node.position
        val dx = pb.x - pa.x
        val dy = pb.y - pa.y
        val dz = pb.z - pa.z
        val distanceSquared = dx * dx + dy * dy + dz * dz
        val diameter = a.radius + b.radius
        if (distanceSquared >= diameter * diameter) return false
        val distance = sqrt(distanceSquared)
        val nx = if (distance > 0.00001f) dx / distance else 1f
        val ny = if (distance > 0.00001f) dy / distance else 0f
        val nz = if (distance > 0.00001f) dz / distance else 0f
        // A held ball has an infinite mass: it is where the finger puts it.
        val inverseA = if (aHeld) 0f else 1f / aKind.mass
        val inverseB = if (bHeld) 0f else 1f / bKind.mass
        val inverseSum = inverseA + inverseB
        val overlap = diameter - distance
        val shiftA = overlap * inverseA / inverseSum
        val shiftB = overlap * inverseB / inverseSum
        a.node.position = Position(
            pa.x - nx * shiftA,
            (pa.y - ny * shiftA).coerceAtLeast(PHYSICS_FLOOR + a.radius),
            pa.z - nz * shiftA,
        )
        b.node.position = Position(
            pb.x + nx * shiftB,
            (pb.y + ny * shiftB).coerceAtLeast(PHYSICS_FLOOR + b.radius),
            pb.z + nz * shiftB,
        )
        val va = a.velocity
        val vb = b.velocity
        val approach = (vb.x - va.x) * nx + (vb.y - va.y) * ny + (vb.z - va.z) * nz
        if (approach >= 0f) return false
        // A gentle push — balls resting against each other on a slope — does not bounce: a
        // bounce there would make a pile shiver forever.
        val restitution = if (approach > -PHYSICS_IMPACT_SPEED) 0f else minOf(aKind.restitution, bKind.restitution)
        val impulse = -(1f + restitution) * approach / inverseSum
        a.velocity = Position(
            va.x - impulse * inverseA * nx, va.y - impulse * inverseA * ny, va.z - impulse * inverseA * nz,
        )
        b.velocity = Position(
            vb.x + impulse * inverseB * nx, vb.y + impulse * inverseB * ny, vb.z + impulse * inverseB * nz,
        )
        return approach < -PHYSICS_IMPACT_SPEED
    }
}
