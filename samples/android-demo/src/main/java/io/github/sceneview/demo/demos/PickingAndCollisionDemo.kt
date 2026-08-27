package io.github.sceneview.demo.demos

import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.filament.LightManager
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.common.SceneAction
import io.github.sceneview.demo.common.SceneActionBar
import io.github.sceneview.demo.common.rememberModelDemoEnvironment
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.rememberPausableHeroYaw
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.ViewNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberView
import io.github.sceneview.rememberViewNodeManager
import io.github.sceneview.sample.rememberMaterialInstance

/**
 * "Picking & Collision" — **one** scene showing both halves of SceneView's picking story
 * (#3329):
 *
 * - **Ray hit-test** — tapping a primitive ray-casts through the library's `CollisionSystem`
 *   and swaps its material.
 * - **Live Compose in 3D** — a `ViewNode` card floating over the same shapes receives the
 *   forwarded touch stream, so its `Button` really clicks.
 *
 * ## Why it is a single scene now
 *
 * It used to be two segmented-button sub-modes, each with its own `SceneView`, camera and
 * gesture pipeline — two tabs in the settings sheet for what is one subject: "what did the
 * user's finger land on?". Putting both in one scene makes the comparison the point, and the
 * card reports the hit-test state (`n / 5 shapes lit`) so the two halves visibly share a
 * single picking pass. Old deep links keep working through
 * [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES]; `view-node` no longer
 * pre-selects a tab because there is none.
 *
 * ## Why both faces of the card are clickable
 *
 * The card slowly turns, so the face under the finger is the back one for half of every
 * revolution. A non-interactive back meant the tap reached no Compose target at all and only
 * bumped the scene-level counter — the "the tap is not on the Compose component" of #3329.
 * Both faces are now real `Card(onClick = …)` targets, and the library maps a back-face pick
 * onto the pixel the user is actually looking at (see `ViewNode.onTouchEvent`).
 */
@Composable
fun PickingAndCollisionDemo(onBack: () -> Unit) {
    var highlightedIndices by remember { mutableStateOf(setOf<Int>()) }
    var tapCount by remember { mutableIntStateOf(0) }
    var isCardVisible by remember { mutableStateOf(true) }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val view = rememberView(engine)
    val collisionSystem = rememberCollisionSystem(view)
    val windowManager = rememberViewNodeManager()

    // Lit PBR instances, not the flat unlit fills this demo used to draw: unlit primitives on a
    // black background read as paper cut-outs with no volume at all, which is the "rendu assez
    // moyen" of #3329. Metallic-ish + smooth so the studio IBL gives every shape a highlight and
    // a shaded side, matching the Geometry and Materials demos.
    val defaultMaterials = listOf(
        rememberMaterialInstance(materialLoader, SceneViewColors.Ramp4[0], 0.15f, 0.35f),
        rememberMaterialInstance(materialLoader, SceneViewColors.Ramp4[1], 0.15f, 0.35f),
        rememberMaterialInstance(materialLoader, SceneViewColors.Ramp4[2], 0.15f, 0.35f),
        rememberMaterialInstance(materialLoader, SceneViewColors.Ramp4[3], 0.15f, 0.35f),
    )
    val highlightedMaterial = rememberMaterialInstance(
        materialLoader,
        SceneViewColors.TintSoft,
        metallic = 0.9f,
        roughness = 0.12f,
        reflectance = 0.9f,
    )

    val shapes = remember { PickingLayout.SHAPES }

    val (heroYaw, onHeroGesture) = rememberPausableHeroYaw(
        trigger = true,
        durationMillis = 18_000,
        staticYaw = 18f,
        idleResumeMillis = 3_000L,
    )

    val gestureListener = rememberOnGestureListener(
        // Only a tap that actually PICKS something counts — an empty-space tap arrives with
        // `node == null` and changes nothing, which is the whole point of a picking demo.
        //
        // The embedded Compose tree is the FIRST to see a tap on the card (#2845): both faces are
        // clickable, so they consume it and increment the counter themselves. A gesture the view
        // consumes never reaches this listener, so the `ViewNode` branch here is only the fallback
        // for taps Compose lets through (forwarding turned off, an unmeasured card, …).
        onSingleTapUp = { _: MotionEvent, node: Node? ->
            when {
                node is ViewNode -> tapCount++
                node != null -> {
                    val index = node.name?.removePrefix(PickingLayout.NAME_PREFIX)?.toIntOrNull()
                    if (index != null) {
                        highlightedIndices = if (index in highlightedIndices) {
                            highlightedIndices - index
                        } else {
                            highlightedIndices + index
                        }
                    }
                }
            }
            onHeroGesture()
        },
        onDoubleTap = { _, _ -> onHeroGesture() },
        onScroll = { _, _, _, _ -> onHeroGesture() },
    )

    val firstFrame = rememberFirstFrameState()
    var renderedFrames by remember { mutableIntStateOf(0) }
    // The card's first frames are the empty texture the ComposeView has not drawn into yet;
    // holding the "first frame" signal back keeps the loading veil up until the scene is real.
    val onWarmedUpFrame: (Long) -> Unit = { frameTimeNanos ->
        if (renderedFrames < VIEW_NODE_WARMUP_FRAMES) renderedFrames++ else firstFrame.onFrame(frameTimeNanos)
    }

    DemoScaffold(
        title = stringResource(R.string.demo_picking_collision_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        // One settings panel — the two sub-mode tabs are gone (#3329).
        controls = {
            Text(
                "Tap a shape to light it up: the tap is a ray cast through the library's " +
                    "CollisionSystem. Tap the floating card — either face — and the touch is " +
                    "forwarded into the real Compose tree rendered on it.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = isCardVisible,
                        onValueChange = { isCardVisible = it },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Compose card", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.width(12.dp))
                Switch(checked = isCardVisible, onCheckedChange = null)
            }
        },
        // The demo's primary action goes in the scaffold's bottom slot, which lays it
        // out against the Settings FAB instead of blindly beside it (#2779).
        bottomOverlay = {
            SceneActionBar(
                SceneAction("Reset Colors", onClick = { highlightedIndices = emptySet() }),
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = onWarmedUpFrame,
                engine = engine,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                // Studio IBL (no skybox) — the demo keeps its dark stage while the shapes get
                // something to reflect. Unlit-on-black had neither.
                environment = rememberModelDemoEnvironment(environmentLoader),
                view = view,
                collisionSystem = collisionSystem,
                viewNodeWindowManager = windowManager,
                // The scene is authored around the origin, so it must not be re-centred under us:
                // the framing below is arithmetic (see PickingLayout), not a number tuned by eye.
                autoCenterContent = false,
                cameraManipulator = rememberCameraManipulator(
                    orbitHomePosition = PickingLayout.CAMERA_EYE,
                    targetPosition = PickingLayout.CAMERA_TARGET,
                ),
                onGestureListener = gestureListener
            ) {
                // Warm key light from the upper left, complementing the v4.1.0 SceneView
                // defaults (10_000-lux main + 3_000-lux fill + IBL). Same 5_000-lux budget as
                // the Geometry and Physics demos — enough to carve a shaded side onto every
                // primitive without blowing the highlight material out to white.
                LightNode(
                    engine = engine,
                    type = LightManager.Type.DIRECTIONAL,
                    apply = {
                        color(1.0f, 0.95f, 0.9f)
                        intensity(5_000f)
                        direction(0.35f, -1f, -0.4f)
                        castShadows(false)
                    },
                )

                for (shape in shapes) {
                    val material = if (shape.index in highlightedIndices) {
                        highlightedMaterial
                    } else {
                        defaultMaterials[shape.index % defaultMaterials.size]
                    }
                    if (shape.isSphere) {
                        SphereNode(
                            radius = PickingLayout.SPHERE_RADIUS,
                            materialInstance = material,
                            position = shape.position,
                            apply = {
                                name = "${PickingLayout.NAME_PREFIX}${shape.index}"
                                isHittable = true
                            }
                        )
                    } else {
                        CubeNode(
                            size = Size(
                                PickingLayout.CUBE_EDGE,
                                PickingLayout.CUBE_EDGE,
                                PickingLayout.CUBE_EDGE,
                            ),
                            materialInstance = material,
                            // A slight yaw so a cube reads as a cube and not as a flat square —
                            // two faces visible instead of one.
                            rotation = Rotation(x = 12f, y = 24f),
                            position = shape.position,
                            apply = {
                                name = "${PickingLayout.NAME_PREFIX}${shape.index}"
                                isHittable = true
                            }
                        )
                    }
                }

                // The Compose card, turning slowly above the shapes. Both faces are live: see the
                // class KDoc for why that is the fix and not a flourish.
                Node(
                    position = PickingLayout.CARD_POSITION,
                    rotation = Rotation(y = heroYaw),
                ) {
                    ViewNode(
                        windowManager = windowManager,
                        unlit = true,
                        position = Position(x = 0f, y = 0f, z = 0.005f),
                        scale = Float3(PickingLayout.CARD_SCALE),
                        isVisible = isCardVisible
                    ) {
                        PickedCard(
                            title = "Live Compose in 3D",
                            highlighted = highlightedIndices.size,
                            total = shapes.size,
                            tapCount = tapCount,
                            containerRole = CardRole.Front,
                            onTap = { tapCount++ },
                        )
                    }
                    ViewNode(
                        windowManager = windowManager,
                        unlit = true,
                        position = Position(x = 0f, y = 0f, z = -0.005f),
                        rotation = Rotation(y = 180f),
                        scale = Float3(PickingLayout.CARD_SCALE),
                        isVisible = isCardVisible
                    ) {
                        PickedCard(
                            title = "…and its back",
                            highlighted = highlightedIndices.size,
                            total = shapes.size,
                            tapCount = tapCount,
                            containerRole = CardRole.Back,
                            onTap = { tapCount++ },
                        )
                    }
                }
            }
        }
    }
}

/** One pickable primitive: which slot it occupies, what it is, and where it sits. */
private data class ShapeSpec(
    val index: Int,
    val isSphere: Boolean,
    val position: Position,
)

/**
 * Scene framing, kept as arithmetic rather than eyeballed constants (#3329).
 *
 * The old layout spread five shapes over `x = ±0.6` and put the eye 3 m away. In a phone-portrait
 * frame the horizontal half-angle is the *vertical* FOV narrowed by the aspect ratio, so 3 m only
 * covered about ±0.6 m of world — the outer cube and sphere were sliced in half by the viewport
 * edges in every capture. Pulling the row in to `x = ±0.5` and the eye out to 4.2 m leaves a
 * comfortable margin on both sides at the same apparent size.
 */
private object PickingLayout {
    /** Node-name prefix the tap handler parses back into a slot index. */
    const val NAME_PREFIX = "shape_"

    const val CUBE_EDGE = 0.22f
    const val SPHERE_RADIUS = 0.13f

    /** World scale of the Compose card — its content is ~1.65 units wide at 250 px/unit. */
    const val CARD_SCALE = 0.35f

    private const val COLUMN_X = 0.25f
    private const val CUBE_Y = -0.30f
    private const val SPHERE_Y = -0.05f

    val CARD_POSITION = Position(x = 0f, y = 0.52f, z = 0.1f)

    /** Far enough out that the ±0.5 row plus its radii clears the portrait viewport edges. */
    val CAMERA_EYE = Position(x = 0f, y = 0.15f, z = 4.2f)
    val CAMERA_TARGET = Position(x = 0f, y = 0.1f, z = 0f)

    /** Cubes and spheres alternate along a two-row zig-zag, so neither hides the other. */
    val SHAPES: List<ShapeSpec> = (0 until 5).map { index ->
        val isSphere = index % 2 == 1
        ShapeSpec(
            index = index,
            isSphere = isSphere,
            position = Position(
                x = (index - 2) * COLUMN_X,
                y = if (isSphere) SPHERE_Y else CUBE_Y,
                z = 0f,
            ),
        )
    }
}

/**
 * The card rendered inside the 3D quad. Clickable as a whole *and* through its button: since #2845
 * a tap anywhere on the quad is a real Compose click, so the whole card counts — exactly as it
 * would on screen.
 *
 * @param containerRole picks the container colour *inside* the re-applied theme, so it follows
 * light/dark like every other surface. It cannot be a resolved [Color] handed in by the caller:
 * the caller composes in the activity's tree and this composable in the ViewNode's, and the two
 * resolve `MaterialTheme` differently — see below.
 */
@Composable
private fun PickedCard(
    title: String,
    highlighted: Int,
    total: Int,
    tapCount: Int,
    containerRole: CardRole,
    onTap: () -> Unit,
) {
    // A ViewNode composes in its own off-screen ComposeView, which inherits none of this demo's
    // CompositionLocals — without re-applying the theme here `MaterialTheme` resolves to the M3
    // *light* defaults, so the card stayed pale lavender in dark mode while every other surface
    // switched. Same reasoning as PointAndAskDemo's anchored answer card.
    SceneViewDemoTheme {
        PickedCardContent(title, highlighted, total, tapCount, containerRole, onTap)
    }
}

/** Which themed container colour a [PickedCard] wears — resolved inside the card's own theme. */
private enum class CardRole { Front, Back }

@Composable
private fun PickedCardContent(
    title: String,
    highlighted: Int,
    total: Int,
    tapCount: Int,
    containerRole: CardRole,
    onTap: () -> Unit,
) {
    val containerColor = when (containerRole) {
        CardRole.Front -> MaterialTheme.colorScheme.primaryContainer
        CardRole.Back -> MaterialTheme.colorScheme.secondaryContainer
    }
    Card(
        onClick = onTap,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.padding(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            // The card reports the ray hit-test state, so the two halves of the demo visibly
            // share one picking pass instead of living in two tabs.
            Text(
                text = "$highlighted / $total shapes lit",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(text = "Tapped $tapCount times", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onTap) {
                Text("Tap me")
            }
        }
    }
}

private const val VIEW_NODE_WARMUP_FRAMES = 18
