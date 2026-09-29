package io.github.sceneview.demo.demos

import android.view.MotionEvent
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.google.android.filament.LightManager
import com.google.android.filament.MaterialInstance
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.rotation as rotationMatrix
import dev.romainguy.kotlin.math.transpose
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.SceneScope
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.common.DemoStatusCard
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.StageSkyFog
import io.github.sceneview.demo.common.rememberStageSkybox
import io.github.sceneview.demo.common.themedStageSky
import io.github.sceneview.demo.demos.internal.TrayBallDrag
import io.github.sceneview.demo.demos.internal.TrayFraming
import io.github.sceneview.demo.demos.internal.TrayStage
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.GlassActionPill
import io.github.sceneview.demo.ui.overMediaEdge
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.math.Transform
import io.github.sceneview.math.toQuaternion
import io.github.sceneview.node.CubeNode as CubeNodeImpl
import io.github.sceneview.node.FloorProvider
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
import io.github.sceneview.sample.rememberMaterialInstance
import io.github.sceneview.sample.ui.LabeledSlider
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.launch

// ─── Rolling balls ──────────────────────────────────────────────────────────
// A tray of balls to drop, tip and knock over (#3820). PhysicsBody supplies gravity and the floor
// bounce; this sample adds mass-weighted sphere contacts, per-material bounce and rolling friction,
// and the tray's rails, because PhysicsNode has no body-to-body collision API. The bodies are
// spheres and so are their colliders: what you see is exactly what collides.
//
// Three rules this screen holds to:
//  - One SceneView for the life of the screen. Reset and every drop change the *bodies*, never the
//    scene, so there is no teardown frame to show black and no camera jump.
//  - What changes the scene lives on the scene. The material to drop, Drop, Tilt and Reset sit in
//    the bottom band, where their effect is visible as it happens; the settings sheet — which
//    covers the tray — keeps what is read or fine-tuned (counts, exact angles, Drop 10, Level).
//  - The camera frames the tray and the drop column in the viewport it actually gets, and the
//    orbit cannot go under the tray.

/** What a ball is made of — three contrasting behaviours, told apart by colour and finish. */
private enum class BallKind(
    @StringRes val labelRes: Int,
    val radius: Float,
    /** Bounce kept on the floor and the rails; a contact takes the lower of the pair's two. */
    val restitution: Float,
    /** Rolling speed kept per 120 Hz step while touching the floor. */
    val rollFriction: Float,
    /** Relative mass for ball-to-ball impulses: steel scatters rubber, not the reverse. */
    val mass: Float,
    val color: Color,
    val metallic: Float,
    val roughness: Float,
) {
    Rubber(R.string.demo_rolling_balls_ball_rubber, 0.075f, 0.82f, 0.99f, 1f, SceneViewColors.Primary, 0f, 0.38f),
    Steel(R.string.demo_rolling_balls_ball_steel, 0.06f, 0.35f, 0.997f, 4f, TrayStage.STEEL_COLOR, 1f, 0.12f),
    Foam(R.string.demo_rolling_balls_ball_foam, 0.085f, 0.2f, 0.96f, 0.3f, SceneViewColors.TintSoft, 0f, 0.95f),
}

/** One ball on the tray. [id] is unique for the screen's life, so a Compose key is never reused. */
private data class TrayBall(
    val id: Int,
    val kind: BallKind,
    val start: Position,
    val velocity: Position = Position(0f),
)

/**
 * Rolling Balls — a tray of balls to drop, tip and knock over (#3820, #4083).
 *
 * Formerly the Physics tab of `animation-physics`; its own demo since #4083, so the
 * old `physics` deep link lands here (see
 * [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES]).
 *
 * [PhysicsBody] supplies gravity and the floor bounce; this sample adds mass-weighted
 * sphere contacts, per-material bounce and rolling friction, and the tray's rails,
 * because PhysicsNode has no body-to-body collision API.
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
    // something moves and parks on demand once the tray is still.
    var simulationMoving by remember { mutableStateOf(true) }
    val wake: () -> Unit = {
        simulation.restartSettle()
        simulationMoving = true
    }

    // Reset: the opening shot again, with fresh ids so every body is rebuilt from its start pose.
    // The scene itself is untouched — nothing is torn down, so nothing can show black.
    val reset: () -> Unit = {
        balls.clear()
        simulation.resetCounters()
        val opening = openingBalls(firstId = nextId)
        nextId += opening.size
        balls.addAll(opening)
        dropCount = 0
        wake()
    }
    // Drop: [count] balls of [kind] over the tray, on a golden-angle spiral so consecutive drops
    // never stack on one another. Past the cap the oldest ball makes room.
    val drop: (BallKind, Int) -> Unit = { kind, count ->
        repeat(count) { k ->
            if (balls.size >= PHYSICS_MAX_BODIES) balls.removeAt(0)
            val spot = dropPosition(dropCount)
            balls.add(TrayBall(nextId, kind, Position(spot.x, spot.y + (k / 5) * PHYSICS_DROP_LAYER, spot.z)))
            nextId++
            dropCount++
        }
        wake()
    }

    // ── Tray tilt (#3621) ────────────────────────────────────────────────────
    // `tiltEnabled` swaps what a one-finger drag over the table does: ON it tips the tray, OFF it
    // orbits the camera. A drag that starts on a ball always picks the ball up (#4180) — see
    // `trayTouch` below. On by default (#4180): tipping the table is the first thing to try, and
    // the camera already frames the whole tray. The angles survive the toggle — tilting, turning
    // tilt off to re-frame, then back on is a normal thing to do.
    var tiltEnabled by remember { mutableStateOf(true) }
    // The gesture hint shows until the first drag of the current mode, and again when Tilt flips.
    var hintDismissed by remember { mutableStateOf(false) }
    val pitchAnim = remember { Animatable(0f) }
    val rollAnim = remember { Animatable(0f) }
    val tiltScope = rememberCoroutineScope()

    // Gravity expressed in the *tray's* frame: the whole rig hangs off a pivot node rotated by
    // (pitch, 0, roll), so the simulation keeps its flat floor and axis-aligned rails and the slope
    // shows up purely as a horizontal component of gravity.
    val trayGravity = remember(pitchAnim.value, rollAnim.value) {
        trayLocalGravity(pitchAnim.value, rollAnim.value)
    }
    LaunchedEffect(simulation, trayGravity) {
        simulation.gravity = trayGravity
        wake()
    }
    val applyTilt: (Float, Float) -> Unit = { pitch, roll ->
        tiltScope.launch {
            pitchAnim.snapTo(pitch.coerceIn(-PHYSICS_MAX_TILT_DEGREES, PHYSICS_MAX_TILT_DEGREES))
            rollAnim.snapTo(roll.coerceIn(-PHYSICS_MAX_TILT_DEGREES, PHYSICS_MAX_TILT_DEGREES))
        }
    }
    val levelTray: () -> Unit = {
        tiltScope.launch {
            launch { pitchAnim.animateTo(0f, tween(400, easing = FastOutSlowInEasing)) }
            rollAnim.animateTo(0f, tween(400, easing = FastOutSlowInEasing))
        }
    }
    // Reset from the bottom bar is the whole opening shot: a level tray as well as the opening
    // balls. Snapped, not eased, so the cue's first roll never runs downhill.
    val resetAll: () -> Unit = {
        tiltScope.launch {
            pitchAnim.snapTo(0f)
            rollAnim.snapTo(0f)
        }
        reset()
    }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    // Studio stage (#3820): the studio HDR lights the tray, a flat themed backdrop sits behind
    // it — never a black void inside the reserved band, and no room photograph competing with the
    // balls. The backdrop and the tray floor are the stage-sky tokens (#4089), so the stage follows
    // light and dark like the rest of the app (#4083).
    val studioLight = rememberHDREnvironment(
        environmentLoader,
        "environments/studio_2k.hdr",
        createSkybox = false,
    ) ?: rememberEnvironment(environmentLoader)
    // A theme flip recolours the skybox in place; a settled tray is on-demand, so it has to ask for
    // the frame that shows it.
    val renderInvalidator = rememberRenderInvalidator()
    val sky = themedStageSky()
    val stageSkybox = rememberStageSkybox(engine, sky, renderInvalidator::requestRender)
    // The stage-sky fog lifts the band above the tray's far rail to the `surface-container`
    // horizon, so dark is a lit stage, not a black void. It starts well past the tray.
    val view = rememberView(engine)
    StageSkyFog(view, sky, renderInvalidator::requestRender)
    val physicsEnvironment = remember(studioLight, stageSkybox) {
        studioLight.copy(skybox = stageSkybox)
    }
    val cameraNode = rememberCameraNode(engine)
    val firstFrame = rememberFirstFrameState(engine)
    val counts = stringResource(R.string.demo_rolling_balls_counts, liveBodyCount, collisions)

    // ── One finger on the table (#4180) ──────────────────────────────────────
    // Routed through the SceneView's raw touch callback rather than a Compose layer over it, so
    // one gesture can pick its owner on touch-down: a ball under the finger is picked up and
    // follows it across the felt, released with the finger's speed; anywhere else the drag tips
    // the tray (Tilt on) or falls through to the camera orbit (Tilt off). Returning `true` keeps
    // the gesture away from the orbit for as long as a ball or the tilt owns it.
    val grip = remember { TrayGrip() }
    val trayTouch: (MotionEvent) -> Boolean = trayTouch@{ event ->
        val pitch = pitchAnim.value
        val roll = rollAnim.value
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
                val origin = TrayBallDrag.toTrayFrame(pitch, roll, ray.origin)
                val direction = TrayBallDrag.toTrayFrame(pitch, roll, ray.direction)
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
                            TrayBallDrag.toTrayFrame(pitch, roll, it.origin),
                            TrayBallDrag.toTrayFrame(pitch, roll, it.direction),
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
                        val dx = event.getX(index) - grip.lastX
                        val dy = event.getY(index) - grip.lastY
                        grip.lastX = event.getX(index)
                        grip.lastY = event.getY(index)
                        if (dx != 0f || dy != 0f) hintDismissed = true
                        applyTilt(
                            pitch + dy * PHYSICS_TILT_DEGREES_PER_PIXEL,
                            roll - dx * PHYSICS_TILT_DEGREES_PER_PIXEL,
                        )
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

    DemoScaffold(
        title = stringResource(R.string.demo_rolling_balls_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        peekHeader = counts,
        // The scene is framed inside the band between the title row and these controls, so the
        // tray is never drawn under them.
        bottomOverlayReservesScene = true,
        // The Tilt hint is not in this band: the band reserves the scene, so a pill that comes
        // and goes here resized the viewport and the tray jumped (#4073). It floats over the
        // scene instead — see the end of the `scene` slot.
        bottomOverlay = {
            Row(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
            ) {
                BallKind.entries.forEach { kind ->
                    TrayGlassChip(
                        label = stringResource(kind.labelRes),
                        selected = kind == selectedKind,
                        swatch = kind.color,
                        toggle = false,
                        onClick = {
                            // Picking a material drops one straight away: the choice is seen,
                            // not just recorded.
                            selectedKind = kind
                            drop(kind, 1)
                        },
                    )
                }
            }
            Row(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Fixed over-media palette (#3726): this row is theme-independent chrome, and
                // a default `Button` resolved to the light/dark `colorScheme.primary`.
                Button(
                    onClick = { drop(selectedKind, 1) },
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
                    onClick = resetAll,
                )
            }
        },
        controls = {
            Text(counts, style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            ) {
                OutlinedButton(onClick = { drop(selectedKind, 10) }) {
                    Text(stringResource(R.string.demo_rolling_balls_drop_ten))
                }
                OutlinedButton(
                    enabled = pitchAnim.value != 0f || rollAnim.value != 0f,
                    onClick = levelTray,
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
                value = pitchAnim.value,
                onValueChange = { applyTilt(it, rollAnim.value) },
                valueRange = -PHYSICS_MAX_TILT_DEGREES..PHYSICS_MAX_TILT_DEGREES,
                decimals = 0,
                unit = "°",
            )
            LabeledSlider(
                label = stringResource(R.string.demo_rolling_balls_tilt_roll),
                value = rollAnim.value,
                onValueChange = { applyTilt(pitchAnim.value, it) },
                valueRange = -PHYSICS_MAX_TILT_DEGREES..PHYSICS_MAX_TILT_DEGREES,
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
            // Framed in the viewport this scene actually gets — the band between the title row
            // and the controls — not the whole screen: the tray and the column the balls drop
            // from fill it, seen from a fixed look-down.
            val aspect = if (maxWidth.value > 0f && maxHeight.value > 0f) {
                maxWidth.value / maxHeight.value
            } else {
                0.5f
            }
            val cameraManipulator = remember(aspect) { trayCameraManipulator(aspect) }
            SceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                view = view,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                environment = physicsEnvironment,
                cameraNode = cameraNode,
                // The rig is authored around the origin; re-centring it on its bounds would move
                // the tray every time a ball flies up.
                autoCenterContent = false,
                onFrame = { nanos ->
                    firstFrame.onFrame(nanos)
                    // Step only once every ball on the list has registered its body, so a reset's
                    // opening shot always starts from the same complete population.
                    simulation.onFrame(nanos, playing = simulation.bodies.size == balls.size)
                    liveBodyCount = simulation.bodies.size
                    collisions = simulation.collisions
                    simulationMoving = simulation.isMoving
                },
                // The simulation is stepped from `onFrame`, which fires only *after* a frame
                // reached the surface — so it cannot be what keeps the loop awake (#3718). While
                // something moves the screen declares every vsync; a still tray hands it back to
                // on-demand, where a tilt, a drop or an orbit still repaints.
                frameRatePolicy = if (simulationMoving) {
                    FrameRatePolicy.Continuous()
                } else {
                    FrameRatePolicy.OnDemand()
                },
                cameraManipulator = cameraManipulator,
                renderInvalidator = renderInvalidator,
                onTouchEvent = { event, _ -> trayTouch(event) },
            ) {
                // Key light: high and to the front-left, so every ball throws a short shadow
                // towards the back-right of the felt — the cue that tells a ball resting on the
                // table from one in the air, and the one that follows a ball in the hand.
                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    direction = io.github.sceneview.math.Direction(-0.35f, -1f, -0.45f),
                    apply = {
                        intensity(6_000f)
                        castShadows(true)
                    },
                )
                val feltMaterial = rememberMaterialInstance(
                    materialLoader, TrayStage.FELT_COLOR,
                    metallic = 0f, roughness = TrayStage.FELT_ROUGHNESS,
                    reflectance = TrayStage.FELT_REFLECTANCE,
                )
                val rimMaterial = rememberMaterialInstance(
                    materialLoader, TrayStage.RIM_COLOR,
                    metallic = 0f, roughness = TrayStage.RIM_ROUGHNESS,
                    reflectance = TrayStage.RIM_REFLECTANCE,
                )
                val inlayMaterial = rememberMaterialInstance(
                    materialLoader, TrayStage.INLAY_COLOR,
                    metallic = 1f, roughness = TrayStage.INLAY_ROUGHNESS,
                )
                val rubberMaterial = rememberMaterialInstance(
                    materialLoader, BallKind.Rubber.color,
                    metallic = BallKind.Rubber.metallic, roughness = BallKind.Rubber.roughness,
                )
                val steelMaterial = rememberMaterialInstance(
                    materialLoader, BallKind.Steel.color,
                    metallic = BallKind.Steel.metallic, roughness = BallKind.Steel.roughness,
                )
                val foamMaterial = rememberMaterialInstance(
                    materialLoader, BallKind.Foam.color,
                    metallic = BallKind.Foam.metallic, roughness = BallKind.Foam.roughness,
                )

                // Tilt pivot (#3621) — the table and every ball hang off this node, so the tray
                // rotates as one rigid rig while the simulation stays in its own flat frame. The
                // light stays at the scene root: the room does not tip with the tray.
                Node(rotation = Rotation(x = pitchAnim.value, z = rollAnim.value)) {
                    TrayTable(
                        feltMaterial = feltMaterial,
                        rimMaterial = rimMaterial,
                        inlayMaterial = inlayMaterial,
                    )

                    for (ball in balls) {
                        key(ball.id) {
                            var nodeRef by remember { mutableStateOf<SphereNodeImpl?>(null) }
                            SphereNode(
                                radius = ball.kind.radius,
                                materialInstance = when (ball.kind) {
                                    BallKind.Rubber -> rubberMaterial
                                    BallKind.Steel -> steelMaterial
                                    BallKind.Foam -> foamMaterial
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

            // Gesture hint, over the scene rather than in the reserved bottom band (#4073): it
            // overlays the bottom of the viewport, just above the controls, so it never changes
            // the size the tray is framed in. It names what a drag does in the current mode and
            // leaves once the user has done it; flipping Tilt brings it back for the new mode.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = SceneViewTokens.Space.md),
            ) {
                DemoStatusCard(
                    text = when {
                        hintDismissed -> null
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
 * The table (#4180): a felt bed whose top face is the simulation floor, a lacquered rim whose inner
 * face is where the collision rails stop a ball, a brass line along the top of the rim, and a
 * wooden body under it all — built in the tray's frame, so it tips with the pivot it is composed in.
 *
 * The rim casts, the felt receives: the rim throws its shadow across the felt edge and the balls
 * throw theirs onto the felt, which is what seats them on it.
 */
@Composable
private fun SceneScope.TrayTable(
    feltMaterial: MaterialInstance,
    rimMaterial: MaterialInstance,
    inlayMaterial: MaterialInstance,
) {
    val half = PHYSICS_TRAY_SIZE / 2f
    // The rails stop a ball's surface half a rail-thickness inside the tray edge; the rim's inner
    // face sits exactly there, so a ball comes to rest touching the wood it is seen hitting.
    val inner = half - PHYSICS_RAIL_THICKNESS / 2f
    val outer = inner + TrayStage.RIM_WIDTH
    val rimCenter = (inner + outer) / 2f
    val feltBottom = PHYSICS_FLOOR - TrayStage.FELT_THICKNESS
    val rimBottom = feltBottom
    val rimTop = PHYSICS_FLOOR + TrayStage.RIM_HEIGHT
    val rimHeight = rimTop - rimBottom
    val rimY = (rimTop + rimBottom) / 2f
    val caster: CubeNodeImpl.() -> Unit = { isShadowCaster = true }

    // Felt bed.
    CubeNode(
        size = Size(2f * inner, TrayStage.FELT_THICKNESS, 2f * inner),
        position = Position(0f, PHYSICS_FLOOR - TrayStage.FELT_THICKNESS / 2f, 0f),
        materialInstance = feltMaterial,
    )
    // Wooden body under the felt and the rim.
    CubeNode(
        size = Size(2f * outer, TrayStage.BODY_HEIGHT, 2f * outer),
        position = Position(0f, feltBottom - TrayStage.BODY_HEIGHT / 2f, 0f),
        materialInstance = rimMaterial,
    )
    for (side in listOf(-1f, 1f)) {
        // The two rims along Z run the full outer length and close the corners; the two along X
        // fit between them.
        CubeNode(
            size = Size(TrayStage.RIM_WIDTH, rimHeight, 2f * outer),
            position = Position(side * rimCenter, rimY, 0f),
            materialInstance = rimMaterial,
            apply = caster,
        )
        CubeNode(
            size = Size(2f * inner, rimHeight, TrayStage.RIM_WIDTH),
            position = Position(0f, rimY, side * rimCenter),
            materialInstance = rimMaterial,
            apply = caster,
        )
        // Brass inlay: one closed line along the middle of the rim's top.
        val inlayY = rimTop + TrayStage.INLAY_HEIGHT / 2f
        CubeNode(
            size = Size(TrayStage.INLAY_WIDTH, TrayStage.INLAY_HEIGHT, 2f * rimCenter + TrayStage.INLAY_WIDTH),
            position = Position(side * rimCenter, inlayY, 0f),
            materialInstance = inlayMaterial,
        )
        CubeNode(
            size = Size(2f * rimCenter - TrayStage.INLAY_WIDTH, TrayStage.INLAY_HEIGHT, TrayStage.INLAY_WIDTH),
            position = Position(0f, inlayY, side * rimCenter),
            materialInstance = inlayMaterial,
        )
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
 * The tray's camera for a viewport of [aspect]: the table itself fitted at
 * [PHYSICS_CAMERA_PITCH_DEGREES] of look-down — [PHYSICS_FRAME_WIDTH_FILL] of the width, centred in
 * the band between the title row and the controls — with a stock orbit the user can drag (#4180).
 */
private fun trayCameraManipulator(aspect: Float): CameraGestureDetector.CameraManipulator {
    val half = PHYSICS_TABLE_SIZE / 2f
    val shot = TrayFraming.fit(
        min = Position(-half, PHYSICS_FLOOR - TrayStage.FELT_THICKNESS - TrayStage.BODY_HEIGHT, -half),
        max = Position(half, PHYSICS_FLOOR + TrayStage.RIM_HEIGHT + TrayStage.INLAY_HEIGHT, half),
        aspect = aspect,
        pitchDegrees = PHYSICS_CAMERA_PITCH_DEGREES,
        verticalFovDegrees = io.github.sceneview.verticalFovDegreesForFocalLength(PHYSICS_FOCAL_LENGTH_MM)
            .toFloat(),
        widthFill = PHYSICS_FRAME_WIDTH_FILL,
        heightFill = PHYSICS_FRAME_HEIGHT_FILL,
    )
    return TrayCameraManipulator(eye = shot.eye, target = shot.target)
}

/**
 * The stock orbit with the eye kept above the tray: its polar angle is clamped between
 * [PHYSICS_MIN_POLAR_DEGREES] and [PHYSICS_MAX_POLAR_DEGREES] from straight up and re-aimed at
 * the tray, so no drag can carry the camera under the floor and lose the balls from view.
 */
private class TrayCameraManipulator(
    eye: Position,
    private val target: Position,
) : CameraGestureDetector.CameraManipulator {
    private val orbit = CameraGestureDetector.DefaultCameraManipulator(
        eyePosition = eye,
        targetPosition = target,
    )

    override fun setViewport(width: Int, height: Int) = orbit.setViewport(width, height)

    override fun getTransform(): Transform {
        val transform = orbit.getTransform()
        val eye = transform.position
        val clamped = io.github.sceneview.demo.clampOrbitEyePitch(
            eye, target, PHYSICS_MIN_POLAR_DEGREES, PHYSICS_MAX_POLAR_DEGREES,
        )
        if (clamped == eye) return transform
        return Transform(
            dev.romainguy.kotlin.math.lookAt(
                eye = clamped,
                target = target,
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

/** Tilt is clamped well short of the angle at which the rails stop being able to hold a ball. */
private const val PHYSICS_MAX_TILT_DEGREES = 20f

/**
 * Drag sensitivity. At ~2.6x density a comfortable half-screen swipe (≈500 px) sweeps the full
 * ±20° range, so the extremes are reachable without the control feeling twitchy near flat.
 */
private const val PHYSICS_TILT_DEGREES_PER_PIXEL = 0.06f

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
 * The opening shot, and what Reset restores: a steel cue ball rolling into a pyramid of six rubber
 * balls. Steel is four times the rubber's mass, so the pile scatters instead of stopping it dead.
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
    return listOf(cue) + pyramid.mapIndexed { i, p -> TrayBall(firstId + 1 + i, BallKind.Rubber, p) }
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
            // the tray instead of leaving the frame for good. Only approaching impacts count, not
            // resting contacts or positional corrections.
            val bound = PHYSICS_TRAY_SIZE / 2f - PHYSICS_RAIL_THICKNESS / 2f - body.radius
            var vx = v.x
            var vz = v.z
            if (kotlin.math.abs(p.x) > bound && p.x * vx > 0f) {
                if (kotlin.math.abs(vx) >= PHYSICS_IMPACT_SPEED) collisions++
                vx = -vx * body.restitution
            }
            if (kotlin.math.abs(p.z) > bound && p.z * vz > 0f) {
                if (kotlin.math.abs(vz) >= PHYSICS_IMPACT_SPEED) collisions++
                vz = -vz * body.restitution
            }
            if (p.y <= PHYSICS_FLOOR + body.radius && kotlin.math.abs(v.y) < 0.2f) {
                val friction = kindOf(id).rollFriction
                vx *= friction
                vz *= friction
            }
            if (kotlin.math.abs(p.x) > bound || kotlin.math.abs(p.z) > bound) {
                body.node.position = Position(p.x.coerceIn(-bound, bound), p.y, p.z.coerceIn(-bound, bound))
            }
            body.velocity = Position(vx, v.y, vz)
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
        val restitution = minOf(aKind.restitution, bKind.restitution)
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
