package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Portrait
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.android.filament.LightManager
import io.github.sceneview.SceneScope
import io.github.sceneview.SceneView
import io.github.sceneview.components.PRIORITY_DEFAULT
import io.github.sceneview.components.PRIORITY_LAST
import io.github.sceneview.demo.DemoPreviewPlaceholder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.rememberModelDemoEnvironment
import io.github.sceneview.demo.demos.internal.Callout
import io.github.sceneview.demo.demos.internal.CalloutLayout
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ViewNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberViewNodeManager
import io.github.sceneview.sample.LifecycleAwareLaunchedEffect
import io.github.sceneview.sample.ui.LabeledSlider
import java.util.Locale
import kotlin.math.abs

/**
 * **2D in 3D** — Jetpack Compose UI living inside the 3D scene, in world space.
 *
 * A `ViewNode` renders a Compose hierarchy into an OpenGL texture and puts that texture on a
 * quad in the scene. The result is not a picture of a card: it is the card, laid out by Compose,
 * themed by `MaterialTheme`, and — since #2845 — **interactive**, because the scene's picking ray
 * is converted back into a view pixel and the whole `DOWN → MOVE → UP` stream is dispatched into
 * the embedded hierarchy.
 *
 * The scene is a product annotation: the Khronos *Damaged Helmet* on a slow turntable, with three
 * call-out cards pinned around it and one live control card floating in front. Every question a
 * developer actually has about 2D-in-3D is a control in the sheet.
 *
 * ## The four decisions this demo exists to show
 *
 * | Question | Control | What changes |
 * |---|---|---|
 * | Should the card face the viewer? | **Billboard** (dock) | Off, the cards ride the turntable and go edge-on, then show their backs. On, they pivot to stay square. |
 * | Should the model be able to hide it? | **Always on top** | Off, the Vents card is half-swallowed by the helmet. On, `setDepthCulling(false)` + `PRIORITY_LAST` float it over everything. |
 * | How big is a card in metres? | **Card size** | A `ViewNode` renders at `pxPerUnits` = 250 px/m, so a 264 dp card is metres wide before you scale it. |
 * | How far off the subject? | **Card distance** | Orbit radius of the three call-outs. |
 *
 * ## World-anchored labels, viewer-anchored controls
 *
 * The three call-outs are children of the turntable: they belong to the *object*, so they turn
 * with it. The **Live Compose** card is not: a control the user may need has no business orbiting
 * out of reach, so it is pinned in world space, billboarded every frame, and always drawn on top.
 * Tap its button and the turntable stops — that is a real `Button.onClick`, through a Filament
 * picking ray, in a `ComposeView` that is not on screen.
 *
 * ## Three things that bite every `ViewNode`
 *
 * 1. **Pass `viewNodeWindowManager` to the `SceneView`.** Without it the off-screen window is
 *    never attached, `onLayout` never runs, the `SurfaceTexture` stays 0×0, and the quad renders
 *    as a black rectangle (#801).
 * 2. **`viewContent` inherits none of your `CompositionLocal`s.** It composes in its own window,
 *    so `MaterialTheme` falls back to the M3 light defaults — a card that stayed pale in dark
 *    mode while every other surface switched. Re-apply the theme inside, as [CalloutCard] does.
 * 3. **One `WindowManager` sizes itself to its largest child.** All four cards here share one
 *    manager, so all four are pinned to the same [CARD_WIDTH] × [CARD_HEIGHT] box. Let one grow
 *    and every other quad silently resizes with it.
 *
 * And one that is not in the docs: a white Material card on an `unlit = true` `ViewNode` is a
 * full-brightness block in an HDR pipeline, so SceneView's default bloom bleeds a halo around
 * every label. These are shaded instead — see [AnnotationCard].
 *
 * Rebuilt from scratch for [#3424](https://github.com/sceneview/sceneview/issues/3424). The demo
 * it replaces was four unrelated scenes behind a segmented button — `TextNode` labels, a gallery
 * of procedurally-drawn `ImageNode`s, an MP4 on a `VideoNode` with a bespoke cinematic camera,
 * and a `BillboardNode` — and used no `ViewNode` at all, so the app's headline "2D in 3D" entry
 * never showed Compose in 3D. Its Billboard tab was also inert: both `BillboardNode` and
 * `TextNode` billboard only when handed a `cameraPositionProvider`, and neither got one, so the
 * sign the caption promised would "stay readable" never turned. The retired `text`, `image`,
 * `video` and `billboard` deep links still resolve here through
 * [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES].
 */
@Composable
fun TwoDInThreeDDemo(onBack: () -> Unit) {
    // Inspection mode (Android Studio @Preview pane, Roborazzi snapshot tests): bypass the
    // Filament-backed body BEFORE any rememberEngine() call — LayoutLib ships no .so files.
    if (LocalInspectionMode.current) {
        DemoPreviewPlaceholder(title = "2D in 3D", onBack = onBack)
        return
    }

    var billboard by remember { mutableStateOf(true) }
    var alwaysOnTop by remember { mutableStateOf(false) }
    var cardScale by remember { mutableFloatStateOf(CalloutLayout.DEFAULT_CARD_SCALE) }
    var spread by remember { mutableFloatStateOf(CalloutLayout.DEFAULT_SPREAD) }
    var spinning by remember { mutableStateOf(true) }
    var turntableYaw by remember { mutableFloatStateOf(CalloutLayout.QA_SPIN_DEGREES) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // Owned explicitly, because the billboard needs to read the live eye position every frame —
    // the user can orbit, so the camera's home is only where it starts.
    val cameraNode = rememberCameraNode(engine)
    var cameraPosition by remember {
        mutableStateOf(CalloutLayout.cameraHomePosition(DemoSettings.cameraDistance ?: CalloutLayout.CAMERA_DISTANCE))
    }

    // One manager for all four cards — see the class KDoc's third gotcha for the size contract
    // that buys. It must also be handed to the SceneView below, or the quads render black (#801).
    val viewNodeManager = rememberViewNodeManager()

    val helmet = rememberModelInstance(modelLoader, HELMET_ASSET)
    val firstFrame = rememberFirstFrameState()

    // Turntable off the Choreographer. `spinning` is a key, so the loop simply stops when the
    // in-scene button is tapped rather than spinning a paused counter.
    LifecycleAwareLaunchedEffect(spinning, DemoSettings.qaMode) {
        if (!spinning || DemoSettings.qaMode) return@LifecycleAwareLaunchedEffect
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastNanos != 0L) {
                    turntableYaw = CalloutLayout.nextTurntableYaw(turntableYaw, nanos - lastNanos)
                }
                lastNanos = nanos
            }
        }
    }

    DemoScaffold(
        title = stringResource(R.string.demo_two_d_in_three_d_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        // The two facts a reader needs at a glance and cannot get from the picture: how wide the
        // quads actually are in metres, and whether the depth buffer still applies to them.
        peekHeader = statusLabel(cardScale, alwaysOnTop),
        onResetSettings = {
            billboard = true
            alwaysOnTop = false
            cardScale = CalloutLayout.DEFAULT_CARD_SCALE
            spread = CalloutLayout.DEFAULT_SPREAD
            spinning = true
        },
        dock = listOf(
            DockItem(
                icon = Icons.Filled.Portrait,
                label = BILLBOARD_LABEL,
                onClick = { billboard = !billboard },
                selected = billboard,
            )
        ),
        controls = {
            TwoDInThreeDControls(
                alwaysOnTop = alwaysOnTop,
                onAlwaysOnTopChange = { alwaysOnTop = it },
                cardScale = cardScale,
                onCardScaleChange = { cardScale = it },
                spread = spread,
                onSpreadChange = { spread = it },
            )
        },
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            environmentLoader = environmentLoader,
            cameraNode = cameraNode,
            // Required. See gotcha 1 in the class KDoc.
            viewNodeWindowManager = viewNodeManager,
            // The shared model-demo IBL (#2110), no skybox. SceneView's *default* environment
            // pairs a neutral IBL with a solid black skybox, and the helmet's shell is metallic:
            // with nothing bright to reflect it renders solid black, which is exactly the bug
            // #2110 was filed for. Leaving the skybox off keeps the subject floating on the
            // demo's own background, so one scene reads correctly in both light and dark.
            environment = rememberModelDemoEnvironment(environmentLoader),
            cameraManipulator = rememberCameraManipulator(
                // The orbit distance is the LENGTH of `orbitHomePosition` (see GeometryLayout and
                // #2930), and `camera_distance` (#2652) is honoured so a capture run can reframe
                // the scene from adb.
                orbitHomePosition = CalloutLayout.cameraHomePosition(
                    DemoSettings.cameraDistance ?: CalloutLayout.CAMERA_DISTANCE
                ),
                targetPosition = CalloutLayout.targetPosition(),
            ),
            onFrame = { nanos ->
                firstFrame.onFrame(nanos)
                // Gate the state write on real movement. The camera is static until the user
                // orbits, and writing an equal Position every frame would recompose the whole
                // demo sixty times a second for nothing.
                val eye = cameraNode.worldPosition
                if (movedPerceptibly(cameraPosition, eye)) {
                    cameraPosition = eye
                }
            },
        ) {
            // Warm key from the upper front-left, on top of SceneView's own 10 000-lux main and
            // 3 000-lux fill. It rakes across the helmet's dents so the normal map the Shell card
            // talks about is actually visible.
            LightNode(
                type = LightManager.Type.DIRECTIONAL,
                apply = {
                    color(1f, 0.96f, 0.9f)
                    intensity(4_500f)
                    direction(0.4f, -0.7f, -0.55f)
                    castShadows(false)
                },
            )

            // ── The turntable: the model and everything anchored to it ────────────────────────
            Node(rotation = Rotation(y = turntableYaw)) {
                helmet?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = CalloutLayout.MODEL_SIZE_METERS,
                    )
                }
                CalloutLayout.CALLOUTS.forEach { callout ->
                    key(callout.id) {
                        AnnotationCard(
                            callout = callout,
                            windowManager = viewNodeManager,
                            spread = spread,
                            cardScale = cardScale,
                            turntableYaw = turntableYaw,
                            cameraPosition = cameraPosition,
                            billboard = billboard,
                            alwaysOnTop = alwaysOnTop,
                        )
                    }
                }
            }

            // ── The control card: world-anchored, always facing, always on top ────────────────
            ControlCard(
                windowManager = viewNodeManager,
                cardScale = cardScale,
                cameraPosition = cameraPosition,
                spinning = spinning,
                onToggleSpin = { spinning = !spinning },
            )
        }
    }
}

/**
 * One world-anchored annotation: a Compose card on a quad, parented to the turntable.
 *
 * Lift this function as-is. The three parts worth reading are the `rotation` (billboarding solved
 * as arithmetic in [CalloutLayout], not as a per-frame `lookTowards`), the `apply` block (the two
 * halves of "always on top"), and the `SceneViewDemoTheme` wrapper inside [CalloutCard].
 */
@Composable
private fun SceneScope.AnnotationCard(
    callout: Callout,
    windowManager: ViewNode.WindowManager,
    spread: Float,
    cardScale: Float,
    turntableYaw: Float,
    cameraPosition: Position,
    billboard: Boolean,
    alwaysOnTop: Boolean,
) {
    // Read through a State: `apply` runs once, at construction, so the lambda installed then has
    // to reach today's value through something whose identity is stable.
    val onTop = rememberUpdatedState(alwaysOnTop)

    val yaw = if (billboard) {
        CalloutLayout.billboardYawDegrees(callout, spread, turntableYaw, cameraPosition)
    } else {
        CalloutLayout.fixedYawDegrees(callout)
    }

    ViewNode(
        windowManager = windowManager,
        // Lit, not `unlit = true`, and the reason is bloom rather than realism. An unlit
        // ViewNode is full-brightness by definition, so a white Material card lands above the
        // HDR pipeline's bloom threshold and every label wore a halo that ate its own contrast.
        // Shading the quad brings it back under. The studio IBL is omnidirectional, so a card
        // that turns away from the key light still reads — which is what makes this safe for a
        // label, where `unlit` would otherwise be the obvious choice.
        unlit = false,
        position = CalloutLayout.localPosition(callout, spread),
        rotation = Rotation(y = yaw),
        scale = Scale(cardScale),
        // These are labels, not controls, so touch forwarding is off: an inert Material `Card`
        // still consumes the DOWN it is hit by — a Surface swallows touches whether or not
        // anything inside is clickable — and orbiting the scene would stall every time a drag
        // happened to start on a card.
        apply = viewNodePolicy(alwaysOnTop = onTop, touchForwarding = false),
        viewContent = { CalloutCard(callout) },
    )
}

/**
 * The interactive card. Pinned in world space rather than to the turntable, so the only control
 * in the scene never orbits out of reach, and always on top so the helmet can never swallow it.
 */
@Composable
private fun SceneScope.ControlCard(
    windowManager: ViewNode.WindowManager,
    cardScale: Float,
    cameraPosition: Position,
    spinning: Boolean,
    onToggleSpin: () -> Unit,
) {
    val alwaysOnTop = remember { mutableStateOf(true) }

    ViewNode(
        windowManager = windowManager,
        // Lit, for the same bloom reason as the call-outs — see [AnnotationCard].
        unlit = false,
        position = CalloutLayout.CONTROL_CARD_POSITION,
        rotation = Rotation(
            y = CalloutLayout.billboardYawDegrees(
                cardWorldPosition = CalloutLayout.CONTROL_CARD_POSITION,
                cameraWorldPosition = cameraPosition,
            )
        ),
        scale = Scale(cardScale),
        // Touch forwarding stays on — this is the one card that must receive the stream — and the
        // depth policy is not a toggle here: a control the helmet could swallow is a control the
        // user cannot press.
        apply = viewNodePolicy(alwaysOnTop = alwaysOnTop, touchForwarding = true),
        viewContent = { LiveComposeCard(spinning = spinning, onToggleSpin = onToggleSpin) },
    )
}

/**
 * The `apply` block every card in this demo is built with: what it does with touches, and how it
 * answers the depth buffer.
 *
 * "Always on top" is two settings, and neither works alone:
 *
 * - `MaterialInstance.setDepthCulling(false)` makes the quad ignore what is already in the depth
 *   buffer, so the model cannot hide it. On its own it lets two overlapping cards resolve in
 *   submission order, which is not the order the eye expects.
 * - `setPriority(PRIORITY_LAST)` moves the renderable into the last of Filament's eight buckets,
 *   so the cards are submitted after the model. On its own it changes nothing: draw ordering does
 *   not defeat a depth test.
 *
 * Why a per-frame hook rather than a `SideEffect`: `SceneScope.ViewNode`'s `apply` lambda runs
 * once, at construction, and the composable never hands the node back — so a switch flipped later
 * has no other seam to reach the `MaterialInstance` through. Comparing against the last applied
 * value keeps this at two JNI calls per *change*, not two per frame.
 */
private fun viewNodePolicy(
    alwaysOnTop: State<Boolean>,
    touchForwarding: Boolean,
): ViewNode.() -> Unit = {
    isTouchForwardingEnabled = touchForwarding
    var applied: Boolean? = null
    onFrame = {
        val wanted = alwaysOnTop.value
        if (applied != wanted) {
            applied = wanted
            materialInstance.setDepthCulling(!wanted)
            setPriority(if (wanted) PRIORITY_LAST else PRIORITY_DEFAULT)
        }
    }
}

/**
 * A world-anchored annotation, as it looks inside its quad.
 *
 * @see TwoDInThreeDDemo — gotcha 2. `SceneViewDemoTheme` is re-applied here because this composes
 * in the `ViewNode`'s own off-screen window and inherits none of the caller's `CompositionLocal`s;
 * without it the card resolves M3's light defaults and stays pale in dark mode.
 */
@Composable
private fun CalloutCard(callout: Callout) {
    SceneViewDemoTheme {
        CardShell {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The pin. A call-out with no visible attachment point reads as a floating
                // tooltip; a dot in the accent role reads as "this names a part".
                Surface(
                    modifier = Modifier.size(SceneViewTokens.Space.sm),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    content = {},
                )
                Spacer(modifier = Modifier.width(SceneViewTokens.Space.sm))
                Text(text = callout.title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
            Text(
                text = callout.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The live control, as it looks inside its quad. Everything here is ordinary Compose: the
 * `Button` below is hit by a Filament picking ray, not by a touch on a view that is on screen.
 */
@Composable
private fun LiveComposeCard(spinning: Boolean, onToggleSpin: () -> Unit) {
    SceneViewDemoTheme {
        CardShell(containerColor = MaterialTheme.colorScheme.primaryContainer) {
            Text(text = "Live Compose", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.xs))
            Text(
                text = "This card is a texture on a quad. The button is real.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
            Button(onClick = onToggleSpin) {
                Icon(
                    imageVector = if (spinning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(modifier = Modifier.width(SceneViewTokens.Space.sm))
                Text(text = if (spinning) SPIN_PAUSE_LABEL else SPIN_RESUME_LABEL)
            }
        }
    }
}

/**
 * The box every in-scene card lives in.
 *
 * Fixed [CARD_WIDTH] × [CARD_HEIGHT] on purpose, and not for looks: all four cards share one
 * `ViewNode.WindowManager`, which measures itself to its largest child and re-sizes every other
 * quad to match. A card left to wrap its content would move its neighbours whenever its text
 * changed. The explicit size is also what makes `Modifier` sizing meaningful at all here — the
 * off-screen window is `WRAP_CONTENT`, so `fillMaxWidth()` would resolve to the whole display.
 */
@Composable
private fun CardShell(
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = Modifier.size(width = CARD_WIDTH, height = CARD_HEIGHT)) {
        Card(
            modifier = Modifier.fillMaxSize(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = containerColor),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(SceneViewTokens.Space.md),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
    }
}

/**
 * Controls panel for [TwoDInThreeDDemo] — stateless, so a Roborazzi snapshot test can capture it
 * in pure JVM with no Filament Engine. Pattern from issue #880.
 *
 * Billboard is deliberately absent: it lives in the dock, one tap away, because it is the toggle
 * whose effect a reader most needs to see while the turntable is turning.
 */
@Composable
internal fun TwoDInThreeDControls(
    alwaysOnTop: Boolean,
    onAlwaysOnTopChange: (Boolean) -> Unit,
    cardScale: Float,
    onCardScaleChange: (Float) -> Unit,
    spread: Float,
    onSpreadChange: (Float) -> Unit,
) {
    Text(
        text = stringResource(R.string.demo_two_d_in_three_d_explainer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Without this the caption's last line sits on the first control and the two read as one
    // paragraph. `space-md` is DESIGN.md's block separator.
    Spacer(modifier = Modifier.height(SceneViewTokens.Space.md))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = alwaysOnTop, onValueChange = onAlwaysOnTopChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = ALWAYS_ON_TOP_LABEL, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = alwaysOnTop, onCheckedChange = null)
    }
    Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))

    LabeledSlider(
        label = "Card size",
        value = cardScale,
        onValueChange = onCardScaleChange,
        valueRange = CalloutLayout.MIN_CARD_SCALE..CalloutLayout.MAX_CARD_SCALE,
        // Metres, not the raw scale factor: "0.12" says nothing, "38 cm wide" is the number a
        // reader is trying to work out when they ask how big a ViewNode is.
        valueText = cardWidthLabel(cardScale),
    )

    LabeledSlider(
        label = "Card distance",
        value = spread,
        onValueChange = onSpreadChange,
        valueRange = CalloutLayout.MIN_SPREAD..CalloutLayout.MAX_SPREAD,
        decimals = 2,
        unit = "m",
    )
}

/** The scaffold's status pill: the two facts the picture cannot carry. */
private fun statusLabel(cardScale: Float, alwaysOnTop: Boolean): String = String.format(
    Locale.US,
    "%s · %s",
    cardWidthLabel(cardScale),
    if (alwaysOnTop) "always on top" else "depth-tested",
)

/**
 * A card's world width, in centimetres, for the given scale.
 *
 * `ViewNode` renders at `pxPerUnits` = 250 px/m, and [CARD_WIDTH] is a dp value, so the exact
 * metre width depends on the display density. This uses the app's baseline density so the readout
 * is a stable, honest order of magnitude rather than a per-device number the reader cannot check.
 */
internal fun cardWidthLabel(cardScale: Float): String = String.format(
    Locale.US,
    "%d cm wide",
    (CARD_WIDTH.value * BASELINE_DENSITY / VIEW_NODE_PX_PER_UNIT * cardScale * 100f).toInt(),
)

/** True when two eye positions differ by more than a millimetre on any axis. */
private fun movedPerceptibly(previous: Position, current: Position): Boolean =
    abs(previous.x - current.x) > CAMERA_EPSILON ||
        abs(previous.y - current.y) > CAMERA_EPSILON ||
        abs(previous.z - current.z) > CAMERA_EPSILON

/** Content description of the dock's billboard toggle — also its UI-test handle. */
internal const val BILLBOARD_LABEL = "Billboard"

/** Label of the depth toggle in the settings sheet — also its UI-test handle. */
internal const val ALWAYS_ON_TOP_LABEL = "Always on top"

/** Label on the in-scene button while the turntable is turning — also its UI-test handle. */
internal const val SPIN_PAUSE_LABEL = "Pause spin"

/** Label on the in-scene button once it has been tapped — the proof the tap landed. */
internal const val SPIN_RESUME_LABEL = "Resume spin"

/** Bundled subject. 2 048² PBR set with an emissive map — the three call-outs describe it. */
private const val HELMET_ASSET = "models/khronos_damaged_helmet.glb"

/** @see CardShell */
private val CARD_WIDTH = 264.dp

/** @see CardShell */
private val CARD_HEIGHT = 156.dp

/** `ViewNode.pxPerUnits` default — the px-to-metre rate every card's world size divides by. */
private const val VIEW_NODE_PX_PER_UNIT = 250f

/** xhdpi. The density [cardWidthLabel]'s centimetre readout is quoted at. */
private const val BASELINE_DENSITY = 2f

/** A millimetre. Below this the camera has not really moved. */
private const val CAMERA_EPSILON = 0.001f

// ── Android Studio @Preview support ────────────────────────────────────────────

@Preview(name = "Demo (light)", showBackground = true)
@Composable
private fun TwoDInThreeDDemoPreview_Light() {
    SceneViewDemoTheme(darkTheme = false) {
        TwoDInThreeDDemo(onBack = {})
    }
}

@Preview(
    name = "Demo (dark)",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun TwoDInThreeDDemoPreview_Dark() {
    SceneViewDemoTheme(darkTheme = true) {
        TwoDInThreeDDemo(onBack = {})
    }
}
