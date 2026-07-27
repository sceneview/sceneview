package io.github.sceneview.demo.demos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
 * - **Two identical boxes, side by side** — same size, same material, same motion. The left
 *   one is grounded by a [ContactShadowContext.Floor] pool; the right one has none. The
 *   comparison is spatial, so it needs no memory and no interaction — one glance settles it.
 * - **Both boxes hop in sync**, and the left pool *responds to height*: it slides out from
 *   under the box along the key light as the box lifts, spreading and fading as it goes (dark,
 *   tight and centred at contact; drifted, wide and faint at the top — see
 *   [DemoMath.groundingShadowOffset] / [DemoMath.groundingSpread] /
 *   [DemoMath.groundingIntensityFactor]). The shadow's **path** is the strongest contact cue
 *   the visual system has (the classic "ball-in-a-box" illusion: the same vertical trajectory
 *   reads as bouncing or floating depending only on where the shadow goes). The grounded box
 *   visibly *lands on* the floor every 2.6 s; its shadowless twin, animated identically,
 *   floats with no depth anchor at all.
 * - **Overlay chips name the two states** ("Contact shadow" / "No shadow"), so the one-line
 *   takeaway is on screen without opening anything.
 * - **The camera starts low** (about 22° above the floor, pulled in), so the pool subtends
 *   real screen area instead of degenerating into a sliver — at the original high-and-far
 *   framing the shadow was too small to ever be the subject.
 *
 * ### The wall TV stays — it is the decisive argument
 *
 * Behind the comparison, a TV is mounted on the back wall, grounded by a
 * [ContactShadowContext.Wall] pool. This is the case a *real* shadow map cannot serve:
 * indoor light comes from the ceiling, nearly parallel to the wall, so a flat-mounted panel
 * casts essentially nothing onto it. The settings sheet lets you switch that pool's preset —
 * putting `Floor` on the wall makes it too dark and too round (a sticker, not a shadow),
 * which is exactly why the per-surface contexts exist.
 *
 * ### Why this exists as a non-AR preview
 *
 * The contact shadow is a pure shader effect — an elliptical gradient drawn from the quad's
 * UVs — so nothing about it depends on ARCore. Like [PlaneGridPreviewDemo] (#2224) and
 * [PlacementReticlePreviewDemo], reproducing the exact geometry + material in a plain
 * `SceneView` makes it visually reviewable on any emulator, with **no ARCore session and no
 * physical AR device** (#2754).
 *
 * The key light deliberately does NOT cast shadows: a real cast shadow on the floor would
 * sit alongside the procedural pool and muddy the comparison. The only grounding cue on
 * screen is the contact shadow — which is the point.
 *
 * QA mode ([DemoSettings.qaMode]) freezes the hop at ground contact (a zeroed clock), the
 * pose where the pool is at full strength, so screenshot suites get a deterministic frame.
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
    // over two identically floating boxes. One value means a future label cannot diverge
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
        onReset = resetAll,
        onResetSettings = resetAll,
        bottomOverlay = {
            // The scaffold owns the bottom band: it pins the row, applies the system-bar
            // inset, and resolves the Settings-FAB reserve (#2779/#2780). The row keeps
            // sitting just ABOVE that band rather than inside it — `settingsFabReservedSpace`
            // is the band's own height, so this is the former hand-written `128.dp` with
            // its magic number replaced by the constant it was silently duplicating, and
            // it collapses to the plain gutter on any demo that ships no controls.
            GroundingLegend(
                shadowVisible = shadowVisible,
                modifier = Modifier.padding(bottom = settingsFabReservedSpace + 24.dp),
            )
        },
        controls = {
            ContactShadowControls(
                shadowsEnabled = shadowsEnabled,
                onShadowsEnabledChange = { shadowsEnabled = it },
                motionEnabled = motionEnabled,
                onMotionEnabledChange = { motionEnabled = it },
                intensityFactor = intensityFactor,
                onIntensityFactorChange = { intensityFactor = it },
                wallContext = wallContext,
                onWallContextChange = { wallContext = it },
            )
        }
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            onFrame = firstFrame.onFrame,
            engine = engine,
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

            // ── The hero comparison: two identical hopping boxes ──────────────────────────
            // LEFT — grounded. The pool tracks the hop: full-strength and tight at contact,
            // dimmer and wider at the top (ambient-occlusion physics). That coupling is what
            // makes this box read as LANDING ON the floor.
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
            // RIGHT — identical box, identical motion, NO shadow. With no pool to anchor it,
            // the eye cannot tell where (or whether) it meets the floor: it floats.
            CubeNode(
                size = Size(BOX_EDGE_METERS, BOX_EDGE_METERS, BOX_EDGE_METERS),
                position = Position(
                    x = BOX_HALF_SPACING,
                    y = BOX_EDGE_METERS / 2f + hopHeight,
                    z = BOXES_Z,
                ),
                materialInstance = boxMaterial,
            )

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
 * The two overlay chips naming the comparison columns — "Contact shadow" for the grounded
 * box, "No shadow" for its floating twin. [shadowVisible] is whether a shadow is actually
 * being drawn (toggle ON *and* intensity above zero), not merely whether the toggle is on:
 * at intensity 0 the pool is fully transparent, so the left chip reports "Shadows off" and
 * the labels never contradict the scene. The caller passes the same value it gives the peek
 * header, so the two labels cannot disagree with each other either.
 *
 * The chips name the two columns; they are NOT positioned under their respective boxes.
 * The camera is an interactive orbit manipulator, so any drag moves the boxes relative to
 * this bottom-anchored row — a positional claim would invert as soon as the user orbits
 * past 90°. Read them as a legend, in the same left-to-right order as the scene at the
 * home framing.
 *
 * Style mirrors the scaffold's `AssetSourceChip` (dot + label on an 85 %-alpha surface) so
 * the demo chrome stays one family. Rendered through `DemoScaffold(bottomOverlay = …)`,
 * which pins it to the bottom of the scene and applies the system-bar inset; the caller
 * supplies only the gutter that lifts it clear of the settings FAB / peek-chip band.
 */
@Composable
private fun GroundingLegend(shadowVisible: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendChip(
            label = stringResource(
                if (shadowVisible) R.string.contact_shadow_label_grounded
                else R.string.contact_shadow_label_off
            ),
            dotColor = if (shadowVisible) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline,
        )
        LegendChip(
            label = stringResource(R.string.contact_shadow_label_floating),
            dotColor = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun LegendChip(label: String, dotColor: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text = " $label",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Settings-sheet controls, extracted (and `internal`, like [GeometryDemoControls]) so the
 * panel layout CAN be snapshot-tested in pure JVM (no Filament, no SceneView) — the #880
 * pattern. Unlike `GeometryDemoControls`, no such test exists for this panel yet: the
 * extraction makes it possible, it does not deliver it. Adding the sibling
 * `ContactShadowControlsSnapshotTest` is a follow-up.
 */
@Composable
internal fun ContactShadowControls(
    shadowsEnabled: Boolean,
    onShadowsEnabledChange: (Boolean) -> Unit,
    motionEnabled: Boolean,
    onMotionEnabledChange: (Boolean) -> Unit,
    intensityFactor: Float,
    onIntensityFactorChange: (Float) -> Unit,
    wallContext: ContactShadowContext,
    onWallContextChange: (ContactShadowContext) -> Unit,
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
    Spacer(modifier = Modifier.height(8.dp))
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
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        stringResource(R.string.contact_shadow_intensity, (intensityFactor * 100).toInt()),
        style = MaterialTheme.typography.labelLarge
    )
    Slider(
        value = intensityFactor,
        onValueChange = onIntensityFactorChange,
        valueRange = 0f..1.5f
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        stringResource(R.string.contact_shadow_wall_preset),
        style = MaterialTheme.typography.labelLarge
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ContactShadowContext.values().forEach { context ->
            if (context == wallContext) {
                Button(onClick = { onWallContextChange(context) }) { Text(context.name) }
            } else {
                OutlinedButton(onClick = { onWallContextChange(context) }) { Text(context.name) }
            }
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        stringResource(R.string.contact_shadow_wall_preset_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

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
