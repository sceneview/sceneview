package io.github.sceneview.demo.demos

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contrast
import io.github.sceneview.demo.theme.SceneViewTokens
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.environment.Environment
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.node.ContactShadowContext
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.sample.LifecycleAwareLaunchedEffect
import io.github.sceneview.sample.ui.LabeledSlider
import java.util.Locale

/**
 * **Contact Shadow Preview** — a *non-AR* SceneView that shows what the procedural contact
 * shadow ([ContactShadowContext], #2740 sub-task C) *buys*, not just what it looks like.
 *
 * ### Why this screen is a side-by-side comparison (redesign rationale)
 *
 * The first version of this demo showed one static room with the shadows ON by default and
 * an on/off switch tucked in the settings sheet. Maintainer feedback: *the feature worked,
 * but the screen never communicated what it was demonstrating.* Root causes:
 *
 * 1. **A successful contact shadow is invisible as an effect.** With shadows on, the scene
 *    just looks "normal" — the value only exists relative to the shadowless state, which the
 *    default screen never showed.
 * 2. **A toggle is a *sequential* comparison.** Flipping one switch swaps the entire scene
 *    in place, and human change-detection across an interruption is poor (change blindness)
 *    — especially when the eye is on the switch, not the scene.
 * 3. **Nothing moved and nothing was named.** A static grey room gives the eye no reason to
 *    look at the floor, and no label says the *shadow* is the subject.
 *
 * The redesign makes the contrast **simultaneous and kinetic** instead of sequential and
 * static:
 *
 * - **Two boxes, side by side — same size and material, deliberately DIFFERENT motion.** The
 *   left one bounces and lands, grounded by a [ContactShadowContext.Floor] pool; the right one
 *   hovers high and never touches down, with no shadow. The comparison is spatial, so it needs
 *   no memory and no interaction — one glance settles it.
 * - **The left box STRIKES the floor** ([DemoMath.bounceHeight], a rectified sine) every 2.6 s,
 *   and its pool *responds to height*: it slides out from under the box along the key light as
 *   the box lifts, spreading and fading as it goes, then snaps back dark, tight and centred on
 *   landing (see [DemoMath.groundingShadowOffset] / [DemoMath.groundingSpread] /
 *   [DemoMath.groundingIntensityFactor]). The shadow's **path** is the strongest contact cue the
 *   visual system has — the classic "ball-in-a-box" illusion. **The right box does the opposite:**
 *   it hovers high and bobs slowly ([DemoMath.floatHoverY], a plain sine well above the floor),
 *   never landing, with no shadow. The floating is carried by the box's *own motion* — a box that
 *   visibly stays aloft needs no shadow to read as airborne — so the missing shadow reads as
 *   "it's in the air", not "the shadow is broken" (#2740). The earlier revision hopped BOTH boxes
 *   identically, which failed exactly here: a shadowless box doing the same motion as its grounded
 *   twin conveys "floating" only by the *absence* of a shadow, and an absence does not read.
 * - **Overlay chips name the two states** ("Contact shadow" / "No shadow"), so the one-line
 *   takeaway is on screen without opening anything.
 * - **The camera starts low** (about 22° above the floor, pulled in), so the pool subtends
 *   real screen area instead of degenerating into a sliver — at the original high-and-far
 *   framing the shadow was too small to ever be the subject.
 *
 * ### The wall TV gets its own beat — it is the decisive argument
 *
 * Behind the comparison, a TV is mounted on the back wall, grounded by a
 * [ContactShadowContext.Wall] pool. This is the case a *real* shadow map cannot serve:
 * indoor light comes from the ceiling, nearly parallel to the wall, so a flat-mounted panel
 * casts essentially nothing onto it.
 *
 * That argument used to be made in a whisper. The TV was scenery, and its preset picker was a
 * settings-sheet row — which failed it twice over: the sheet's scrim dims the scene, so you
 * could never watch the wall pool change *while* changing it, and a control sitting among the
 * global ones read as global while it only ever drove this one pool. [WallShadowBeat] fixes
 * both by anchoring the picker on screen, in the TV's half of the frame, with a one-line
 * verdict per preset — so the A/B is live, and the control's scope is self-evident instead of
 * being patched over by its label.
 *
 * ### Why this exists as a non-AR preview
 *
 * The contact shadow is a pure shader effect — an elliptical gradient drawn from the quad's
 * UVs — so nothing about it depends on ARCore. Like [PlaneGridPreviewDemo] (#2224),
 * reproducing the exact geometry + material in a plain
 * `SceneView` makes it visually reviewable on any emulator, with **no ARCore session and no
 * physical AR device** (#2754).
 *
 * The key light deliberately does NOT cast shadows: a real cast shadow on the floor would
 * sit alongside the procedural pool and muddy the comparison. The only grounding cue on
 * screen is the contact shadow — which is the point.
 *
 * QA mode ([DemoSettings.qaMode]) freezes the clock at t = 0: the grounded box sits at ground
 * contact (pool at full strength) and the floating box at its hover rest height, so screenshot
 * suites get a deterministic frame that already shows the full grounded-vs-floating contrast.
 */
@Composable
fun ContactShadowPreviewDemo(onBack: () -> Unit) {
    var shadowsEnabled by remember { mutableStateOf(true) }
    var motionEnabled by remember { mutableStateOf(true) }
    // Multiplier on each pool's per-context opacity (1.0 = the context's own value). The
    // v1 slider was a shared absolute value initialised from the Wall preset, which silently
    // weakened the floor pool (0.38 < 0.55) before the user touched anything.
    var intensityFactor by remember { mutableFloatStateOf(1f) }
    var wallContext by remember { mutableStateOf(ContactShadowContext.Wall) }

    // Whether a shadow is actually DRAWN — the toggle being on is not enough, because the
    // intensity slider reaches 0 and makes the pool fully transparent. THE single source for
    // every label that reports the shadow state (peek header + [GroundingLegend]): the first
    // fix of this contradiction updated the legend alone and left the peek header still
    // reading the raw toggle, so at intensity 0 the banner announced "Grounded vs floating"
    // when no shadow was drawn at all. One value means a future label cannot diverge
    // again (#2740).
    //
    // `derivedStateOf`, not a plain expression: reading `intensityFactor` directly in the
    // demo body would drag the scaffold, top bar and settings sheet into every tick of a
    // slider drag. The derived boolean only invalidates when it actually flips — same
    // recomposition-scope discipline as the hop clock read inside the scene lambda below.
    val shadowVisible by remember {
        derivedStateOf { shadowsEnabled && intensityFactor > 0f }
    }

    // Accumulated hop-loop time. Written only from the frame loop / reset callbacks —
    // never during composition.
    var bounceElapsedNanos by remember { mutableLongStateOf(0L) }

    // Drive the hop clock off the Choreographer. Lifecycle-aware so the loop stops burning
    // frames when the app is backgrounded (#936); delta accumulation means the phase resumes
    // where it left off. QA mode and the Motion toggle freeze the clock at t = 0 — ground
    // contact, the deterministic full-strength pose.
    LifecycleAwareLaunchedEffect(motionEnabled, DemoSettings.qaMode) {
        if (!motionEnabled || DemoSettings.qaMode) {
            bounceElapsedNanos = 0L
            return@LifecycleAwareLaunchedEffect
        }
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastNanos != 0L) {
                    bounceElapsedNanos += nanos - lastNanos
                }
                lastNanos = nanos
            }
        }
    }

    val engine = rememberEngine()
    val labelCamera = io.github.sceneview.rememberCameraNode(engine)
    val groundedLabel = stringResource(R.string.contact_shadow_label_grounded)
    val noShadowLabel = stringResource(R.string.contact_shadow_label_floating)
    val labelsFontSize = with(androidx.compose.ui.platform.LocalDensity.current) {
        SceneViewTokens.Type.card.fontSize.toPx()
    }

    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // A neutral room: matte off-white wall, slightly darker floor, so the shadow gradient is
    // the only thing carrying the grounding cue.
    val wallMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFFE8E6E1), metallic = 0f, roughness = 0.9f)
    }
    val floorMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFFCFCBC4), metallic = 0f, roughness = 0.85f)
    }
    val tvBody = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFF20242A), metallic = 0f, roughness = 0.8f)
    }
    val tvScreen = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFF06080C), metallic = 0f, roughness = 0.15f)
    }
    // ONE material instance shared by both boxes — identical look is what isolates the
    // shadow as the only variable in the comparison.
    val boxMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFFB4693C), metallic = 0f, roughness = 0.7f)
    }

    // The room's materials are LIT (PBR), so they need an IBL or they render flat and dark — a
    // coloured skybox alone supplies no irradiance. Studio HDR does the ambient lighting; the
    // light-grey skybox below is the fallback while the HDR is still decoding.
    val litEnvironment = rememberHDREnvironment(
        environmentLoader,
        "environments/studio_2k.hdr",
        createSkybox = true,
    )
    val fallbackSkybox = remember(engine) {
        Skybox.Builder().color(0.72f, 0.73f, 0.75f, 1.0f).build(engine)
    }
    val fallbackEnvironment = remember(fallbackSkybox) { Environment(skybox = fallbackSkybox) }
    val environment = litEnvironment ?: fallbackEnvironment

    val firstFrame = rememberFirstFrameState()

    val resetAll = {
        shadowsEnabled = true
        motionEnabled = true
        intensityFactor = 1f
        wallContext = ContactShadowContext.Wall
        bounceElapsedNanos = 0L
    }

    DemoScaffold(
        title = stringResource(R.string.demo_contact_shadow_preview_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        peekHeader = stringResource(
            if (shadowVisible) R.string.contact_shadow_peek_on
            else R.string.contact_shadow_peek_off
        ),
        // One reset, offered once: this demo has no settings-only state to restore apart from
        // what `resetAll` already covers, so wiring `onResetSettings` to the same lambda would
        // put the very same action twice in the merged sheet (#3328).
        onReset = resetAll,
        dock = listOf(io.github.sceneview.demo.DockItem(
            icon = Icons.Filled.Contrast,
            label = "Shadows",
            selected = shadowsEnabled,
            onClick = { shadowsEnabled = !shadowsEnabled },
        )),
        // Reserve the selector beneath the stage; labels stay attached to their objects.
        bottomOverlayReservesScene = true,
        bottomOverlay = {
            WallShadowBeat(wallContext = wallContext, onWallContextChange = { wallContext = it })
        },
        controls = {
            ContactShadowControls(
                shadowsEnabled = shadowsEnabled,
                onShadowsEnabledChange = { shadowsEnabled = it },
                motionEnabled = motionEnabled,
                onMotionEnabledChange = { motionEnabled = it },
                intensityFactor = intensityFactor,
                onIntensityFactorChange = { intensityFactor = it },
            )
        }
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            onFrame = firstFrame.onFrame,
            engine = engine,
            cameraNode = labelCamera,
            materialLoader = materialLoader,
            environment = environment,
            // Keep the hand-built room where it was authored — auto-centring would reframe the
            // scene and break the deterministic camera below.
            autoCenterContent = false,
            cameraManipulator = rememberCameraManipulator(
                // Low and pulled in: ~22° above the floor at the boxes, framing the comparison
                // pair in the lower half and the wall TV in the upper half. Seen high and far
                // (the v1 framing), a floor pool degenerates into a sliver and can never read.
                orbitHomePosition = Position(x = 0.0f, y = 1.35f, z = 3.3f),
                targetPosition = Position(x = 0.0f, y = 0.75f, z = -0.5f),
            ),
        ) {
            // Read the hop clock HERE, inside the content lambda, not in the demo body: this
            // lambda is its own recomposition scope, so the per-frame state change re-executes
            // only the scene nodes — never the scaffold, top bar, or settings sheet (the
            // GeometryDemo spin pattern).
            val hopHeight = DemoMath.bounceHeight(bounceElapsedNanos)

            // Directional key light for shape and specular — deliberately NOT a shadow caster.
            // The ONLY grounding cue on screen must be the contact shadow, so a real cast
            // shadow would muddy the with/without comparison.
            LightNode(
                type = LightManager.Type.DIRECTIONAL,
                direction = KEY_LIGHT_DIRECTION,
                apply = {
                    intensity(60_000f)
                    castShadows(false)
                },
            )

            // ── The room ──────────────────────────────────────────────────────────────────
            // Floor: an XZ quad (normal +Y).
            PlaneNode(
                size = Size(x = 6f, y = 0f, z = 6f),
                normal = Direction(y = 1f),
                materialInstance = floorMaterial,
            )
            // Back wall: an XY quad (normal +Z) — note the DIFFERENT size shape. `Plane` does
            // not rotate its geometry to match `normal`, so a vertical quad is built in XY.
            PlaneNode(
                size = Size(x = 6f, y = 3f, z = 0f),
                normal = Direction(z = 1f),
                position = Position(x = 0f, y = 1.5f, z = -2f),
                materialInstance = wallMaterial,
            )

            // ── The hero comparison: a grounded bouncer vs a floating twin ────────────────
            // LEFT — grounded, and it BOUNCES to strike the floor. The pool tracks the hop:
            // full-strength and tight at contact, dimmer and wider at the top (ambient-occlusion
            // physics). That coupling is what makes this box read as LANDING ON the floor.
            if (shadowsEnabled) {
                // The pool follows the light's ground projection as the box lifts (ball-in-a-box):
                // centred and tight at contact, drifted out from under the box at the top of the
                // hop. This slide — not the dim/spread alone — is what sells "landing on" vs
                // "floating"; the shadowless twin gives the eye nothing equivalent to track.
                val (slideX, slideZ) = DemoMath.groundingShadowOffset(
                    hopHeight,
                    KEY_LIGHT_DIRECTION.x, KEY_LIGHT_DIRECTION.y, KEY_LIGHT_DIRECTION.z,
                )
                ContactShadow(
                    size = Size(x = SHADOW_QUAD_METERS, y = 0f, z = SHADOW_QUAD_METERS),
                    context = ContactShadowContext.Floor,
                    normal = Direction(y = 1f),
                    intensity = ContactShadowContext.Floor.intensity * intensityFactor *
                        DemoMath.groundingIntensityFactor(hopHeight),
                    position = Position(x = -BOX_HALF_SPACING + slideX, y = 0f, z = BOXES_Z + slideZ),
                    scale = Scale(DemoMath.groundingSpread(hopHeight)),
                )
            }
            CubeNode(
                size = Size(BOX_EDGE_METERS, BOX_EDGE_METERS, BOX_EDGE_METERS),
                position = Position(
                    x = -BOX_HALF_SPACING,
                    y = BOX_EDGE_METERS / 2f + hopHeight,
                    z = BOXES_Z,
                ),
                materialInstance = boxMaterial,
            )
            // RIGHT — the floating twin. It does NOT bounce to the floor: it hovers high and
            // bobs slowly (DemoMath.floatHoverY), clearly aloft, with no contact shadow. The
            // floating is carried by the box's own MOTION — hovering high, never landing — so the
            // absent shadow reads as "it's in the air", not "the shadow is missing" (#2740). This
            // is the positive, kinetic cue an identically-hopping shadowless box could never give.
            CubeNode(
                size = Size(BOX_EDGE_METERS, BOX_EDGE_METERS, BOX_EDGE_METERS),
                position = Position(
                    x = BOX_HALF_SPACING,
                    y = DemoMath.floatHoverY(bounceElapsedNanos),
                    z = BOXES_Z,
                ),
                materialInstance = boxMaterial,
            )

            listOf(
                Position(-BOX_HALF_SPACING, BOX_EDGE_METERS + hopHeight + 0.12f, BOXES_Z) to groundedLabel,
                Position(
                    BOX_HALF_SPACING,
                    DemoMath.floatHoverY(bounceElapsedNanos) + BOX_EDGE_METERS / 2f + 0.12f,
                    BOXES_Z,
                ) to noShadowLabel,
            ).forEach { (position, label) ->
                TextNode(
                    text = if (!shadowVisible && label == groundedLabel) noShadowLabel else label,
                    fontSize = labelsFontSize,
                    textColor = SceneViewTokens.ArOverlay.onScrim.toArgb(),
                    backgroundColor = SceneViewTokens.ArOverlay.scrimDark.toArgb(),
                    widthMeters = 0.62f,
                    heightMeters = 0.16f,
                    position = position,
                    cameraPositionProvider = { labelCamera.worldPosition },
                )
            }

            // ── Wall-mounted TV — the case a real shadow map cannot serve ─────────────────
            if (shadowsEnabled) {
                ContactShadow(
                    size = Size(x = 2.4f, y = 1.6f, z = 0f),
                    context = wallContext,
                    normal = Direction(z = 1f),
                    intensity = wallContext.intensity * intensityFactor,
                    position = Position(x = 0f, y = 1.3f, z = -1.99f),
                )
            }
            Node(position = Position(x = 0f, y = 1.3f, z = -1.98f)) {
                CubeNode(
                    size = Size(1.26f, 0.74f, 0.04f),
                    position = Position(z = 0.02f),
                    materialInstance = tvBody,
                )
                CubeNode(
                    size = Size(1.20f, 0.68f, 0.01f),
                    position = Position(z = 0.045f),
                    materialInstance = tvScreen,
                )
            }
        }
    }
}

/**
 * The wall TV's own beat: the preset picker for its pool, anchored on screen next to the thing
 * it controls, with a one-line verdict for the preset in force.
 *
 * **Why this is not a settings-sheet row (#2740 follow-up).** The picker used to live in the
 * sheet, which failed the TV twice. The sheet's scrim dims the scene, so the wall pool could
 * never be watched *while* being changed — a sequential, half-blind comparison, the very flaw
 * the box pair was redesigned to escape. And sitting among the global controls it read as
 * global, while it only ever drove [wallContext] — the TV's pool, never the two boxes a viewer
 * takes to be the subject. The previous mitigation was to rename it "TV wall preset": a label
 * patch over a scope mismatch. Anchoring the control in the TV's half of the frame makes the
 * scope self-evident and the A/B live, and it takes the settings sheet from four controls down
 * to three.
 *
 * **Why every preset gets a verdict, not just the wrong ones.** A caption that appeared only
 * for a mis-set preset would communicate by *absence* — and an absence reads as nothing at
 * all. Each preset states what it costs on a wall, so switching to `Floor` teaches ("too dark,
 * too round — a sticker") rather than merely looking different.
 *
 * Like [GroundingLegend], this is anchored to the *frame*, not to the TV's projected position:
 * the camera is an orbit manipulator, so any drag moves the TV under a fixed overlay. It sits
 * in the upper half because the home framing puts the TV there.
 */
@Composable
internal fun WallShadowBeat(
    wallContext: ContactShadowContext,
    onWallContextChange: (ContactShadowContext) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(SceneViewTokens.Radius.xs))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .padding(horizontal = SceneViewTokens.Space.sm, vertical = SceneViewTokens.Space.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
            ContactShadowContext.values().forEach { context ->
                FilterChip(
                    selected = context == wallContext,
                    onClick = { onWallContextChange(context) },
                    label = { Text(if (context == ContactShadowContext.TableTop) "Table" else context.name) },
                )
            }
        }
        Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
        Text(
            text = stringResource(wallContext.wallVerdictRes()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The one-line verdict shown under the picker for each preset, phrased as what that preset
 * costs *on a wall* — see [WallShadowBeat] for why all three are covered.
 *
 * Exhaustive `when` on purpose: a new [ContactShadowContext] must fail this compile rather
 * than ship a chip with no verdict under it.
 *
 * **The Wall verdict is worded to the measurement, not to the ambition (#2957).** It used to
 * promise "a faint, wide halo below the panel". Device QA sampled the wall against the
 * detected panel bbox and the pool is real but *narrow*: 18.6/255 darker in the first 70 px
 * under a 314 px-tall panel, 0.4 at 70–130 px, 0.0 beyond — and ~12 levels of the same
 * darkening sits above the panel too. "Wide" therefore described something a viewer looking
 * for it cannot find, and a caption whose cue is not on screen teaches nothing (#2740). The
 * wording now names the cue that *is* visible — the thin band of shade against the panel's
 * lower edge — and states what it buys: the panel reads as pressed against the wall.
 */
@StringRes
private fun ContactShadowContext.wallVerdictRes(): Int = when (this) {
    ContactShadowContext.Floor -> R.string.contact_shadow_wall_verdict_floor
    ContactShadowContext.Wall -> R.string.contact_shadow_wall_verdict_wall
    ContactShadowContext.TableTop -> R.string.contact_shadow_wall_verdict_tabletop
}

@Composable
internal fun ContactShadowControls(
    shadowsEnabled: Boolean,
    onShadowsEnabledChange: (Boolean) -> Unit,
    motionEnabled: Boolean,
    onMotionEnabledChange: (Boolean) -> Unit,
    intensityFactor: Float,
    onIntensityFactorChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = shadowsEnabled,
                onValueChange = onShadowsEnabledChange,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.contact_shadow_toggle),
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(checked = shadowsEnabled, onCheckedChange = null)
    }
    Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = motionEnabled,
                onValueChange = onMotionEnabledChange,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.contact_shadow_motion_toggle),
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(checked = motionEnabled, onCheckedChange = null)
    }
    Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
    LabeledSlider(
        label = stringResource(R.string.contact_shadow_intensity_label),
        value = intensityFactor,
        onValueChange = onIntensityFactorChange,
        valueRange = SHADOW_INTENSITY_RANGE,
        valueText = formatShadowIntensityFactor(intensityFactor),
    )
}

/**
 * Track bounds of the intensity slider, as a multiplier on each pool's per-context opacity.
 *
 * The top of the track is deliberately above `1f`: the control exists to over- and under-drive
 * the context preset, so it has to reach past the preset's own value. `1f` — the default — is
 * therefore two thirds along the track, not at its end.
 */
internal val SHADOW_INTENSITY_RANGE = 0f..1.5f

/**
 * Renders [factor] as the multiplier it is (`1.00×`), not as a percentage.
 *
 * The readout used to be `"${(factor * 100).toInt()}%"`, which put a flat `100%` on screen while
 * the thumb sat visibly short of the track end — the range runs to `1.5`, so `100%` is two thirds
 * along (#3372). A bare percentage promises that its maximum is `100`, and this quantity's is not:
 * the slider boosts *past* the context preset. Naming the value `×` instead keeps the headroom the
 * control was built for and makes a thumb short of the end self-explanatory rather than broken —
 * the end of the track now reads `1.50×`, which is exactly what it is.
 *
 * [Locale.US] rather than the device locale, for the same reason `LabeledSlider` formats that way:
 * these readouts sit beside API values a reader is meant to copy into code, and a decimal comma
 * would not round-trip through `toFloat()`.
 */
internal fun formatShadowIntensityFactor(factor: Float): String =
    String.format(Locale.US, "%.2f", factor) + MULTIPLIER_SIGN

/** `×` U+00D7, set tight against the number — a multiplier sign is not a unit. */
private const val MULTIPLIER_SIGN = '×'

// ── Scene layout constants ────────────────────────────────────────────────────────────────

/** Edge length of the two comparison boxes, metres. */
private const val BOX_EDGE_METERS = 0.38f

/** Half the centre-to-centre spacing of the comparison pair, metres. */
private const val BOX_HALF_SPACING = 0.38f

/** Z position of the comparison pair — pulled toward the camera, in front of the room. */
private const val BOXES_Z = 0.35f

/**
 * Travel direction of the directional key light — also the axis the grounded pool projects
 * along ([DemoMath.groundingShadowOffset]). The light and the shadow's slide are driven from
 * this single value so they can never drift out of agreement.
 */
private val KEY_LIGHT_DIRECTION = Direction(-0.35f, -1f, -0.4f)

/**
 * Side of the grounded box's square shadow quad, metres. Generously larger than the box
 * ([BOX_EDGE_METERS]) — the Floor gradient fades out well before the quad edge — while
 * keeping the pool clear of the shadowless twin at [BOX_HALF_SPACING], including at the
 * peak of the hop, where it has slid furthest out from under its own box. Only the
 * grounded box has a quad; the comparison depends on the twin's floor staying bare.
 */
private const val SHADOW_QUAD_METERS = 0.8f
