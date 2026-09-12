package io.github.sceneview.demo.demos

import android.view.MotionEvent
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.google.android.filament.LightManager
import com.google.android.filament.MaterialInstance
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.SceneView
import io.github.sceneview.SceneScope
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.common.SceneAction
import io.github.sceneview.demo.common.SceneActionBar
import io.github.sceneview.demo.common.rememberModelDemoEnvironment
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.rememberPausableHeroYaw
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.GlassPill
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.node.ContactShadowContext
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
import io.github.sceneview.haptic.rememberHapticFeedback
import io.github.sceneview.sample.rememberMaterialInstance
import io.github.sceneview.sample.rememberUnlitMaterialInstance

/**
 * "Picking & Collision" — **one** scene showing both halves of SceneView's picking story
 * (#3329), rebuilt as a signature scene for #3501.
 *
 * - **Ray hit-test** — tapping a primitive ray-casts through the library's `CollisionSystem`
 *   and the shape answers: it lifts off the floor, polishes to a mirror finish, turns on its
 *   own axis, lights a ring on the ground under it, ticks the haptic motor, and its **name**
 *   appears in the pill above the action bar.
 * - **Live Compose in 3D** — a `ViewNode` card floating over the same shapes receives the
 *   forwarded touch stream, so its `Button` really clicks.
 *
 * ## What #3501 changed, and why
 *
 * The scene was five shapes — three cubes and two spheres — in one flat row on a black void,
 * and the only thing a tap did was swap a colour. Read back: "on dirait une scène de test".
 * Three things were missing and all three are about *where the eye lands*.
 *
 * **A stage.** There is a floor now (a large `PlaneNode` in `surface-dim`, the DESIGN.md
 * grounding token) and a per-shape [SceneScope.ContactShadow] pool on it. A shape that sits
 * *on* something reads as an object; the same shape on black reads as a sprite. The camera
 * moved up and pitched down onto it, so the composition is a three-quarter view of a table of
 * objects rather than a front elevation of a row.
 *
 * **Variety.** Six primitives, one of each kind the library ships — cube, cone, cylinder,
 * sphere, torus, capsule — in two staggered rows. Six kinds also give the picked-shape label
 * something worth saying: "Torus" is information, "Cube #3" is not.
 *
 * **A reaction with a body.** Selection is no longer only a colour: [SceneScope.PickableShape]
 * springs the shape up by [PickingLayout.LIFT] metres, scales it, swaps to a polished instance
 * of *its own* colour (so the identity survives the highlight), fades a ring in on the floor,
 * and spins it slowly. The haptic tick fires from the gesture callback, not the UI, so it is
 * the *pick* that is confirmed, not a button press.
 *
 * ## Why the default frame is still static
 *
 * Nothing is selected on launch, so no shape animates and the render golden
 * (`pickingcollision_default`) stays a deterministic still. The only thing moving in the
 * default state is the Compose card's slow yaw, which `rememberPausableHeroYaw` already
 * freezes under `DemoSettings.qaMode`.
 *
 * ## Why both faces of the card forward touches
 *
 * The card slowly turns, so the face under the finger is the back one for half of every
 * revolution. A non-forwarding back meant the tap reached no Compose target at all and only
 * bumped the scene-level counter — the "the tap is not on the Compose component" of #3329.
 * Both faces keep [io.github.sceneview.node.ViewNode.isTouchForwardingEnabled] on (the
 * default), and the library maps a back-face pick onto the pixel the user is actually
 * looking at (see `ViewNode.onTouchEvent`).
 *
 * ## Why only the button counts as a tap (#3422)
 *
 * The card itself is a plain, non-clickable `Card` — only its `Button` has an `onClick`. A
 * tap that lands elsewhere on the card (its title, its counters, the padding around them) is
 * therefore not consumed by anything in the embedded Compose tree, falls through
 * `ViewNode.onTouchEvent` untouched, and reaches [rememberOnGestureListener]'s
 * `onSingleTapUp` as a plain node hit. That fallback used to bump [tapCount] itself, which
 * made the *whole card* count as "the button" — this demo instead lets it no-op (a `ViewNode`
 * carries no `name`, so the shape-highlight branch below does not match it either), so
 * `tapCount` only ever increases from the real `Button.onClick`.
 *
 * ## Why the floor, the rings and the shadows are not hittable
 *
 * `Node.isHittable` defaults to `true`, so every decorative node added here would otherwise
 * be a valid ray-cast target. They carry no `name`, so a hit on one would be a silent no-op
 * rather than a wrong answer — but a selection ring sits *in front of* the shape it belongs
 * to from a low camera angle, and swallowing that pick would make the demo look broken at
 * exactly the moment it is being shown off. They opt out instead.
 */
@Composable
fun PickingAndCollisionDemo(onBack: () -> Unit) {
    var highlightedIndices by remember { mutableStateOf(setOf<Int>()) }
    var lastPicked by remember { mutableStateOf<ShapeKind?>(null) }
    var tapCount by remember { mutableIntStateOf(0) }
    var isCardVisible by remember { mutableStateOf(true) }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val view = rememberView(engine)
    val collisionSystem = rememberCollisionSystem(view)
    val windowManager = rememberViewNodeManager()
    val haptic = rememberHapticFeedback()

    val shapes = remember { PickingLayout.SHAPES }

    // Two PBR instances per shape, both in the shape's OWN brand colour: a matte one at rest
    // and a near-mirror one when picked. Highlighting by swapping every shape to one shared
    // accent colour — what this demo used to do — throws away the only thing that told the
    // shapes apart, so a screen with three picked shapes read as three identical blobs.
    val restMaterials = shapes.map { shape ->
        rememberMaterialInstance(materialLoader, shape.color, metallic = 0.15f, roughness = 0.35f)
    }
    val pickedMaterials = shapes.map { shape ->
        rememberMaterialInstance(
            materialLoader,
            shape.color,
            metallic = 0.95f,
            roughness = 0.08f,
            reflectance = 1f,
        )
    }
    // The floor. Slightly metallic and fairly smooth so the studio IBL lays a soft gradient
    // across it instead of a flat grey slab — that gradient is what separates "a stage" from
    // "a rectangle".
    val floorMaterial = rememberMaterialInstance(
        materialLoader,
        SceneViewColors.SurfaceDim,
        metallic = 0.2f,
        roughness = 0.45f,
    )
    // Unlit, so the selection ring keeps the same brightness wherever the shape stands and
    // never picks up a shaded side — it is a UI mark drawn in 3D, not an object.
    val ringMaterial = rememberUnlitMaterialInstance(materialLoader, SceneViewColors.TintLight)

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
        // The embedded Compose tree is the FIRST to see a tap on the card (#2845): the card's
        // `Button` is the only clickable target on it, so a tap on the button consumes the
        // gesture and increments `tapCount` itself (via its own `onClick`, not from here). A
        // tap anywhere else on the card is NOT consumed by Compose (#3422 — "tap me" must only
        // fire from the button) and falls through to this listener as a plain `ViewNode` hit;
        // it is deliberately ignored below (a `ViewNode` has no `name`, so it does not match
        // the shape-highlight branch either) rather than bumping the counter.
        onSingleTapUp = { _: MotionEvent, node: Node? ->
            if (node != null) {
                val index = node.name?.removePrefix(PickingLayout.NAME_PREFIX)?.toIntOrNull()
                if (index != null) {
                    if (index in highlightedIndices) {
                        highlightedIndices = highlightedIndices - index
                        // A distinct, lighter tick for "let go": the two directions of the
                        // same gesture should not feel identical in the hand.
                        haptic.selection()
                    } else {
                        highlightedIndices = highlightedIndices + index
                        haptic.light()
                    }
                    // The label names the shape the ray landed on either way — including on a
                    // release, where "Torus" is still the answer to "what did I just touch?".
                    lastPicked = shapes[index].kind
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
                stringResource(R.string.demo_picking_collision_controls_help),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.md))
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
                Text(
                    stringResource(R.string.demo_picking_collision_compose_card),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(modifier = Modifier.width(SceneViewTokens.Space.sm))
                Switch(checked = isCardVisible, onCheckedChange = null)
            }
        },
        // The demo's primary action goes in the scaffold's bottom slot, which lays it
        // out against the Settings FAB instead of blindly beside it (#2779).
        bottomOverlay = {
            // The answer to "what did I touch?", in the one place the eye already goes after
            // a tap. Glass chrome, not a themed surface: the stage under it is black in both
            // light and dark (see the render golden), so white-on-media is the token set that
            // stays legible in either — the same call `DemoScaffold` makes for its own pills.
            AnimatedVisibility(
                visible = lastPicked != null,
                enter = fadeIn(SceneViewTokens.Motion.fade()) +
                    slideInVertically(SceneViewTokens.Motion.spring()) { it / 2 },
                exit = fadeOut(SceneViewTokens.Motion.fade()),
            ) {
                GlassPill {
                    Text(
                        text = lastPicked?.label.orEmpty(),
                        style = SceneViewTokens.Type.card,
                        color = SceneViewTokens.Glass.onGlass,
                    )
                    Spacer(modifier = Modifier.width(SceneViewTokens.Space.sm))
                    Text(
                        text = stringResource(
                            R.string.demo_picking_collision_lit_count,
                            highlightedIndices.size,
                            shapes.size,
                        ),
                        style = SceneViewTokens.Type.caption,
                        color = SceneViewTokens.Glass.onGlassMuted,
                    )
                }
            }
            SceneActionBar(
                SceneAction(
                    stringResource(R.string.demo_picking_collision_clear),
                    onClick = {
                        highlightedIndices = emptySet()
                        lastPicked = null
                    },
                    enabled = highlightedIndices.isNotEmpty(),
                ),
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
                // Warm key from the upper front-left, complementing the v4.1.0 SceneView
                // defaults (10_000-lux main + 3_000-lux fill + IBL). Same 5_000 / 3_500-lux
                // key-and-rim budget CustomGeometryDemo settled on — enough to carve a shaded
                // side onto every primitive without blowing the polished material to white.
                //
                // NOTE the call shape. These two used to read `LightNode(engine = engine, …)`,
                // which does NOT resolve to the `SceneScope` composable below — that one takes
                // no `engine`. It resolved to the `io.github.sceneview.node.LightNode` *class
                // constructor*, so every recomposition of this lambda built a bare light node
                // that was never attached to the scene graph and never destroyed: the "warm key
                // light" this demo documented has never lit anything, and leaked an entity per
                // recomposition. The scene was carried entirely by SceneView's defaults + IBL.
                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    apply = {
                        color(1.0f, 0.95f, 0.9f)
                        intensity(5_000f)
                        direction(0.35f, -1f, -0.4f)
                        castShadows(false)
                    },
                )
                // Cool rim from behind-right, in the brand accent. It draws a bright edge down
                // the far side of every shape, which is what separates a silhouette from the
                // black stage behind it — the cheapest "this was lit by someone" cue there is
                // without a shadow map.
                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    apply = {
                        color(0.62f, 0.76f, 1.0f)
                        intensity(3_500f)
                        direction(-0.55f, -0.2f, 0.8f)
                        castShadows(false)
                    },
                )

                // The stage. Large enough that its far edge leaves the frame instead of ending
                // in a visible seam against the black background.
                PlaneNode(
                    size = Size(x = PickingLayout.FLOOR_EDGE, y = 0f, z = PickingLayout.FLOOR_EDGE),
                    normal = Direction(y = 1f),
                    materialInstance = floorMaterial,
                    position = Position(x = 0f, y = 0f, z = 0f),
                    apply = { isHittable = false },
                )

                for ((slot, shape) in shapes.withIndex()) {
                    PickableShape(
                        spec = shape,
                        selected = shape.index in highlightedIndices,
                        restMaterial = restMaterials[slot],
                        pickedMaterial = pickedMaterials[slot],
                        ringMaterial = ringMaterial,
                    )
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

/**
 * One pickable primitive plus everything that reacts when the ray lands on it: the contact
 * shadow that grounds it, and the ring that lights up under it once picked.
 *
 * ## It is its own composable on purpose
 *
 * The selection animation and the idle spin are per-shape state. Read at the demo level they
 * would invalidate the whole scene content lambda — every node, every frame, for as long as
 * anything is selected. Read here, a spinning torus re-executes a scope containing three
 * nodes. Same reasoning as the `ContactShadowPreviewDemo` hop clock.
 *
 * ## Nothing animates until something is picked
 *
 * [spin] only runs inside `if (selected)`, and [pick] rests at exactly `0f`. That is what
 * keeps the default frame — the one `pickingcollision_default` baselines — a still image.
 */
@Composable
private fun SceneScope.PickableShape(
    spec: ShapeSpec,
    selected: Boolean,
    restMaterial: MaterialInstance,
    pickedMaterial: MaterialInstance,
    ringMaterial: MaterialInstance,
) {
    // The spring is the DESIGN.md interaction token, not a number chosen here: "Spring
    // animations — interactive elements use spring-based easing for physical, bouncy feedback".
    val pick by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = SceneViewTokens.Motion.spring(),
        label = "pick-${spec.index}",
    )
    val spin = remember { Animatable(0f) }
    LaunchedEffect(selected) {
        if (selected) {
            // `infiniteRepeatable` never completes, so this call suspends until the effect is
            // re-keyed (deselection) or the composable leaves — Compose cancels it either way.
            spin.animateTo(
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(SPIN_PERIOD_MILLIS, easing = LinearEasing),
                ),
            )
        } else {
            spin.snapTo(0f)
        }
    }

    val lift = PickingLayout.LIFT * pick
    val position = Position(x = spec.x, y = spec.baseY + lift, z = spec.z)
    val scale = Scale(1f + PickingLayout.PICKED_SCALE_GAIN * pick)
    val material = if (pick > 0.5f) pickedMaterial else restMaterial
    val rotation = Rotation(
        x = spec.tiltX,
        y = spec.yaw + spin.value,
    )
    val name = "${PickingLayout.NAME_PREFIX}${spec.index}"

    // Grounding pool. It stays on the floor while the shape lifts and it widens as it does,
    // which is the ball-in-a-box cue that reads as "this thing left the ground".
    ContactShadow(
        size = Size(x = spec.shadowEdge, y = 0f, z = spec.shadowEdge),
        context = ContactShadowContext.Floor,
        normal = Direction(y = 1f),
        intensity = ContactShadowContext.Floor.intensity * (1f - PickingLayout.LIFT_SHADOW_FADE * pick),
        position = Position(x = spec.x, y = 0f, z = spec.z),
        scale = Scale(1f + PickingLayout.LIFT_SHADOW_SPREAD * pick),
        apply = { isHittable = false },
    )

    // The selection ring, flat on the floor. `Torus` is generated in the XZ plane already, so
    // a ring needs no rotation — and at rest it is scaled to nothing rather than removed from
    // the composition, so selecting a shape never costs a node construction on the main thread.
    TorusNode(
        majorRadius = spec.ringRadius,
        minorRadius = PickingLayout.RING_THICKNESS,
        majorSegments = PickingLayout.RING_SEGMENTS,
        minorSegments = PickingLayout.RING_TUBE_SEGMENTS,
        materialInstance = ringMaterial,
        position = Position(x = spec.x, y = PickingLayout.RING_Y, z = spec.z),
        scale = Scale(RING_HIDDEN_SCALE + (1f - RING_HIDDEN_SCALE) * pick),
        apply = { isHittable = false },
    )

    when (spec.kind) {
        ShapeKind.Cube -> CubeNode(
            size = Size(spec.extent, spec.extent, spec.extent),
            materialInstance = material,
            position = position,
            rotation = rotation,
            scale = scale,
            apply = { this.name = name },
        )

        ShapeKind.Sphere -> SphereNode(
            radius = spec.extent,
            materialInstance = material,
            position = position,
            rotation = rotation,
            scale = scale,
            apply = { this.name = name },
        )

        ShapeKind.Torus -> TorusNode(
            majorRadius = spec.extent,
            minorRadius = spec.secondaryExtent,
            materialInstance = material,
            position = position,
            rotation = rotation,
            scale = scale,
            apply = { this.name = name },
        )

        ShapeKind.Cone -> ConeNode(
            radius = spec.extent,
            height = spec.secondaryExtent,
            materialInstance = material,
            position = position,
            rotation = rotation,
            scale = scale,
            apply = { this.name = name },
        )

        ShapeKind.Cylinder -> CylinderNode(
            radius = spec.extent,
            height = spec.secondaryExtent,
            materialInstance = material,
            position = position,
            rotation = rotation,
            scale = scale,
            apply = { this.name = name },
        )

        ShapeKind.Capsule -> CapsuleNode(
            radius = spec.extent,
            height = spec.secondaryExtent,
            materialInstance = material,
            position = position,
            rotation = rotation,
            scale = scale,
            apply = { this.name = name },
        )
    }
}

/**
 * The six primitive kinds the scene puts on the table — one of each the library ships with a
 * `SceneScope` builder.
 *
 * [label] is what the picked-shape pill says. It is the plain English name of the thing, which
 * is the whole point: a demo that answers "what did I touch?" with "shape_4" has not answered.
 */
internal enum class ShapeKind(val label: String) {
    Cube("Cube"),
    Sphere("Sphere"),
    Torus("Torus"),
    Cone("Cone"),
    Cylinder("Cylinder"),
    Capsule("Capsule"),
}

/**
 * One pickable primitive: which slot it occupies, what it is, where it sits, and how big its
 * grounding marks are.
 *
 * [extent] and [secondaryExtent] are read differently per [kind] — radius/edge and
 * height/minor-radius respectively — because the six builders do not share a size vocabulary.
 * Keeping the pair generic is what lets [SceneScope.PickableShape] stay one `when` instead of
 * six near-identical composables.
 */
internal data class ShapeSpec(
    val index: Int,
    val kind: ShapeKind,
    val color: Color,
    val x: Float,
    val z: Float,
    /** Resting centre height — half the shape's own vertical extent, so it sits ON the floor. */
    val baseY: Float,
    val extent: Float,
    val secondaryExtent: Float = 0f,
    val yaw: Float = 0f,
    val tiltX: Float = 0f,
    val shadowEdge: Float,
    val ringRadius: Float,
)

/**
 * Scene framing and the table plan, kept as arithmetic rather than eyeballed constants (#3329).
 *
 * Two staggered rows of three on a floor at `y = 0`, seen from an eye lifted to
 * [CAMERA_EYE] and pitched down onto [CAMERA_TARGET]. The row half-width is
 * [COLUMN_X] + the widest shape radius; the eye distance is set so that the **front** row —
 * the nearer one, therefore the one that projects widest — still clears the portrait viewport
 * edges, which is the failure #3329 had to fix when the eye sat at 3 m.
 *
 * Every shape's `baseY` is half its own vertical extent, so "sits on the floor" is a
 * consequence of the numbers rather than a value tuned against a screenshot.
 */
private object PickingLayout {
    /** Node-name prefix the tap handler parses back into a slot index. */
    const val NAME_PREFIX = "shape_"

    /** Edge of the stage quad, in metres. Its far edge leaves the frame. */
    const val FLOOR_EDGE = 14f

    /** How far a picked shape springs off the floor. */
    const val LIFT = 0.13f

    /** Extra size a picked shape takes on, as a fraction. */
    const val PICKED_SCALE_GAIN = 0.12f

    /** How much of the contact pool's opacity the lift takes away, and how much it spreads. */
    const val LIFT_SHADOW_FADE = 0.45f
    const val LIFT_SHADOW_SPREAD = 0.35f

    const val RING_THICKNESS = 0.012f
    const val RING_SEGMENTS = 48
    const val RING_TUBE_SEGMENTS = 8

    /** Just clear of the floor quad, so the ring never z-fights with it. */
    const val RING_Y = 0.004f

    /** World scale of the Compose card — its content is ~1.65 units wide at 250 px/unit. */
    const val CARD_SCALE = 0.3f

    private const val COLUMN_X = 0.5f
    private const val BACK_Z = -0.45f
    private const val FRONT_Z = 0.3f

    val CARD_POSITION = Position(x = 0f, y = 0.92f, z = -0.35f)

    /**
     * Lifted and pitched down: a three-quarter view of a table, not a front elevation of a row.
     * Far enough out that the front row plus its radii clears the portrait viewport edges.
     */
    val CAMERA_EYE = Position(x = 0f, y = 1.05f, z = 4.4f)
    val CAMERA_TARGET = Position(x = 0f, y = 0.25f, z = -0.05f)

    /** One of each primitive, back row first, walking the brand ramp from blue to deep purple. */
    val SHAPES: List<ShapeSpec> = listOf(
        ShapeSpec(
            index = 0,
            kind = ShapeKind.Cube,
            color = SceneViewColors.Primary,
            x = -COLUMN_X, z = BACK_Z,
            baseY = 0.15f,
            extent = 0.3f,
            // A slight yaw so a cube reads as a cube and not as a flat square — two faces
            // visible instead of one.
            yaw = 24f,
            shadowEdge = 0.7f,
            ringRadius = 0.28f,
        ),
        ShapeSpec(
            index = 1,
            kind = ShapeKind.Cone,
            color = SceneViewColors.TintSoft,
            x = 0f, z = BACK_Z,
            baseY = 0.19f,
            extent = 0.17f,
            secondaryExtent = 0.38f,
            shadowEdge = 0.62f,
            ringRadius = 0.25f,
        ),
        ShapeSpec(
            index = 2,
            kind = ShapeKind.Cylinder,
            color = SceneViewColors.PrimaryHover,
            x = COLUMN_X, z = BACK_Z,
            baseY = 0.15f,
            extent = 0.15f,
            secondaryExtent = 0.3f,
            shadowEdge = 0.58f,
            ringRadius = 0.23f,
        ),
        ShapeSpec(
            index = 3,
            kind = ShapeKind.Sphere,
            color = SceneViewColors.TintLight,
            x = -COLUMN_X, z = FRONT_Z,
            baseY = 0.17f,
            extent = 0.17f,
            shadowEdge = 0.62f,
            ringRadius = 0.25f,
        ),
        ShapeSpec(
            index = 4,
            kind = ShapeKind.Torus,
            color = SceneViewColors.Accent,
            x = 0f, z = FRONT_Z,
            // Stood up and tilted: `Torus` is generated flat in XZ, and a donut lying on the
            // floor is indistinguishable from the ring under it.
            baseY = 0.21f,
            extent = 0.17f,
            secondaryExtent = 0.062f,
            yaw = -18f,
            tiltX = 74f,
            shadowEdge = 0.62f,
            ringRadius = 0.26f,
        ),
        ShapeSpec(
            index = 5,
            kind = ShapeKind.Capsule,
            color = SceneViewColors.AccentDeep,
            x = COLUMN_X, z = FRONT_Z,
            // Half of (height + 2 * radius) — a capsule's `height` is its straight section only.
            baseY = 0.195f,
            extent = 0.115f,
            secondaryExtent = 0.16f,
            shadowEdge = 0.55f,
            ringRadius = 0.22f,
        ),
    )
}

/**
 * The card rendered inside the 3D quad. Only its `Button` is clickable (#3422) — the card
 * container itself has no `onClick`, so a tap has to actually land on "Tap me" to count, the
 * same as it would on screen. Since #2845 that button click is a real Compose click forwarded
 * through the quad; a tap elsewhere on the card is inert (see the demo-level KDoc above).
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
internal enum class CardRole { Front, Back }

@Composable
@VisibleForTesting
internal fun PickedCardContent(
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
    // Plain, non-clickable Card (#3422): the whole card used to be a `Card(onClick = onTap)`,
    // so any tap on it — not just the "Tap me" button — counted. Only the Button below has an
    // onClick now; the rest of the card (title, counters, padding) is inert.
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.padding(SceneViewTokens.Space.sm)
    ) {
        Column(modifier = Modifier.padding(SceneViewTokens.Space.md)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
            // The card reports the ray hit-test state, so the two halves of the demo visibly
            // share one picking pass instead of living in two tabs.
            Text(
                text = "$highlighted / $total shapes lit",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(text = "Tapped $tapCount times", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
            Button(onClick = onTap) {
                Text("Tap me")
            }
        }
    }
}

/** One revolution of a picked shape. Slow enough to read as "on display", not as "spinning". */
private const val SPIN_PERIOD_MILLIS = 12_000

/**
 * What the selection ring is scaled to at rest. Not `0f`: a zero scale is a degenerate
 * transform, and this keeps the node in the composition so a pick costs a uniform write
 * rather than a Filament renderable construction on the main thread.
 */
private const val RING_HIDDEN_SCALE = 0.001f

private const val VIEW_NODE_WARMUP_FRAMES = 18
