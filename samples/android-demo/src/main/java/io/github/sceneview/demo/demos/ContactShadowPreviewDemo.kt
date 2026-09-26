package io.github.sceneview.demo.demos

import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contrast
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.GlassPill
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
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.sample.LifecycleAwareLaunchedEffect
import io.github.sceneview.sample.ui.LabeledSlider
import java.util.Locale

/**
 * **Contact Shadow Preview** — a *non-AR* SceneView that sells what the procedural contact
 * shadow ([ContactShadowContext], #2740 sub-task C) buys: the cue that puts a 3D object *on*
 * a real surface instead of floating in front of it.
 *
 * ### What this screen is, after #3498
 *
 * Maintainer feedback on the previous revision (#3498, from the device): *rework the UI and
 * UX, make it land*. The argument the old screen made was sound — it paired a grounded box
 * against a floating twin and coupled the pool to the hop — but the *picture* never carried
 * it. Two flat orange cubes and a black slab of a TV sat in a washed-out grey box, three
 * overlapping overlays fought for the lower third, and the only real-world object on screen
 * was a television nobody had asked about. A feature that exists to make renders look
 * believable cannot be demonstrated by a scene that looks like a placeholder.
 *
 * Three changes, in order of how much they matter:
 *
 * 1. **Real subjects, one at a time.** The cubes are gone. Each preset now stages the case it
 *    was actually designed for, with a real glTF subject and the whole frame to itself —
 *    [ContactShadowContext.Floor] puts a chair on the floor, [ContactShadowContext.TableTop]
 *    puts a toy car on a table, [ContactShadowContext.Wall] hangs the TV. A PBR model under
 *    studio light is what makes a shadow look like a shadow; a flat-shaded cube never did.
 * 2. **One picker, and it means what it says.** The old chip row sat on screen but drove only
 *    the TV's pool while two boxes occupied the frame — a control whose scope contradicted
 *    its position. The same three chips now restage the entire scene, so the chip, the
 *    subject, the surface and the preset are always the same statement.
 * 3. **Hold to compare.** The before/after is a *held* gesture on a single pill rather than a
 *    second object or a latched switch. The old side-by-side rationale was right that a
 *    latched toggle in a settings sheet fails — the eye is on the switch, the scene swaps
 *    behind an interruption, and change blindness eats the difference. A press-and-hold does
 *    not have that flaw: the viewer causes the transition, watches it happen, holds it as
 *    long as they like and flaps back and forth at will. That is the standard before/after of
 *    every photo editor, and it buys back the half of the frame the floating twin was using.
 *
 * The subject also **lifts and settles** on a slow loop ([DemoMath.bounceHeight] at
 * [LIFT_PERIOD_NANOS]), and the pool answers: it slides out from under the subject along the
 * key light, spreads and fades as it rises, then snaps back dark and tight on contact (see
 * [DemoMath.groundingShadowOffset] / [DemoMath.groundingSpread] /
 * [DemoMath.groundingIntensityFactor]). The shadow's **path** is the strongest contact cue the
 * visual system has — the classic "ball-in-a-box" illusion — and it is the one thing that
 * reads at a glance, before any label is read or any control is touched. The wall staging
 * lifts too: the TV pulls away from the wall and its pool widens and fades, which is exactly
 * the behaviour a real shadow map cannot produce there.
 *
 * ### Why the wall case still earns its chip
 *
 * Indoor light comes from the ceiling, nearly parallel to a wall, so a flat-mounted panel
 * casts essentially nothing onto it — a shadow map renders a wall-mounted TV as a sticker.
 * The [ContactShadowContext.Wall] preset is the answer, and it now gets the full frame and a
 * caption of its own rather than being scenery behind someone else's comparison.
 *
 * ### Why this exists as a non-AR preview
 *
 * The contact shadow is a pure shader effect — an elliptical gradient drawn from the quad's
 * UVs — so nothing about it depends on ARCore. Like [PlaneGridPreviewDemo] (#2224),
 * reproducing the exact geometry + material in a plain `SceneView` makes it visually
 * reviewable on any emulator, with **no ARCore session and no physical AR device** (#2754).
 *
 * The key light deliberately does NOT cast shadows: a real cast shadow would sit alongside the
 * procedural pool and muddy the comparison. The only grounding cue on screen is the contact
 * shadow — which is the point.
 *
 * Both models come from the bundled Khronos sample set and are already credited in
 * `assets/CREDITS.md`; neither is added by this screen.
 *
 * QA mode ([DemoSettings.qaMode]) freezes the clock at t = 0, where the subject rests at
 * contact and the pool is at full strength — a deterministic frame that already shows the
 * grounded state a screenshot suite is there to guard.
 */
@Composable
fun ContactShadowPreviewDemo(onBack: () -> Unit) {
    var shadowsEnabled by remember { mutableStateOf(true) }
    var motionEnabled by remember { mutableStateOf(true) }
    // Multiplier on each pool's per-context opacity (1.0 = the context's own value). The
    // v1 slider was a shared absolute value initialised from the Wall preset, which silently
    // weakened the floor pool (0.38 < 0.55) before the user touched anything.
    var intensityFactor by remember { mutableFloatStateOf(1f) }
    var stage by remember { mutableStateOf(ContactShadowContext.Floor) }

    // True only while the "hold to compare" pill is held down. Kept separate from
    // [shadowsEnabled] so releasing always restores whatever the user had set, rather than
    // forcing the toggle back on behind their back.
    var comparing by remember { mutableStateOf(false) }

    // Whether a shadow is actually DRAWN — the toggle being on is not enough, because the
    // intensity slider reaches 0 and makes the pool fully transparent, and the compare
    // gesture suppresses it outright. THE single source for every label that reports the
    // shadow state: the first fix of this contradiction updated one label and left another
    // reading the raw toggle, so at intensity 0 the banner announced a grounded scene when
    // no shadow was drawn at all. One value means a future label cannot diverge again (#2740).
    //
    // `derivedStateOf`, not a plain expression: reading `intensityFactor` directly in the
    // demo body would drag the scaffold, top bar and settings sheet into every tick of a
    // slider drag. The derived boolean only invalidates when it actually flips — same
    // recomposition-scope discipline as the lift clock read inside the scene lambda below.
    val shadowVisible by remember {
        derivedStateOf { shadowsEnabled && !comparing && intensityFactor > 0f }
    }

    // Accumulated lift-loop time. Written only from the frame loop / reset callbacks —
    // never during composition.
    var liftElapsedNanos by remember { mutableLongStateOf(0L) }

    // Drive the lift clock off the Choreographer. Lifecycle-aware so the loop stops burning
    // frames when the app is backgrounded (#936); delta accumulation means the phase resumes
    // where it left off. QA mode and the Motion toggle freeze the clock at t = 0 — contact,
    // the deterministic full-strength pose.
    LifecycleAwareLaunchedEffect(motionEnabled, DemoSettings.qaMode) {
        if (!motionEnabled || DemoSettings.qaMode) {
            liftElapsedNanos = 0L
            return@LifecycleAwareLaunchedEffect
        }
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastNanos != 0L) {
                    liftElapsedNanos += nanos - lastNanos
                }
                lastNanos = nanos
            }
        }
    }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // Both subjects are loaded up front rather than keyed on [stage]: together they are under
    // 7 MB, and loading on chip tap would put a visible stall inside the one interaction the
    // screen is built around. `rememberModelInstance` keeps the Filament JNI calls on the main
    // thread, which is mandatory for the model and material loaders.
    val chairInstance = rememberModelInstance(modelLoader, CHAIR_MODEL)
    val carInstance = rememberModelInstance(modelLoader, CAR_MODEL)

    // A warm room: the floor is the lighter, warmer surface a contact pool reads on, the wall
    // sits a shade deeper so the subject has something to separate against. The previous room
    // ran the other way — a bright wall behind a darker floor — which flattened every subject
    // into its background.
    val wallMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(ROOM_WALL_COLOR, metallic = 0f, roughness = 0.95f)
    }
    val floorMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(ROOM_FLOOR_COLOR, metallic = 0f, roughness = 0.8f)
    }
    val tableMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(TABLE_COLOR, metallic = 0f, roughness = 0.55f)
    }
    val tvBody = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFF20242A), metallic = 0f, roughness = 0.8f)
    }
    val tvScreen = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFF06080C), metallic = 0f, roughness = 0.15f)
    }

    // The room's materials are LIT (PBR), so they need an IBL or they render flat and dark — a
    // coloured skybox alone supplies no irradiance. The warm studio HDR does the ambient
    // lighting; the skybox below is the fallback while the HDR is still decoding.
    val litEnvironment = rememberHDREnvironment(
        environmentLoader,
        "environments/studio_warm_2k.hdr",
        createSkybox = true,
    )
    val fallbackSkybox = remember(engine) {
        Skybox.Builder().color(0.80f, 0.78f, 0.75f, 1.0f).build(engine)
    }
    val fallbackEnvironment = remember(fallbackSkybox) { Environment(skybox = fallbackSkybox) }
    val environment = litEnvironment ?: fallbackEnvironment

    val firstFrame = rememberFirstFrameState()

    val resetAll = {
        shadowsEnabled = true
        motionEnabled = true
        intensityFactor = 1f
        stage = ContactShadowContext.Floor
        liftElapsedNanos = 0L
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
        dock = listOf(
            DockItem(
                icon = Icons.Filled.Contrast,
                label = stringResource(R.string.contact_shadow_dock_shadows),
                selected = shadowsEnabled,
                onClick = { shadowsEnabled = !shadowsEnabled },
            )
        ),
        // Reserve the picker's band beneath the stage so the subject is never under it.
        bottomOverlayReservesScene = true,
        bottomOverlay = {
            ContactShadowStageBar(
                stage = stage,
                onStageChange = { stage = it },
                comparing = comparing,
                onComparingChange = { comparing = it },
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
                // Low and pulled in, framing a subject standing at the room centre with the
                // wall behind it. Seen high and far, a floor pool degenerates into a sliver and
                // can never be the subject of the screen.
                orbitHomePosition = Position(x = 0.0f, y = 1.10f, z = 2.85f),
                targetPosition = Position(x = 0.0f, y = 0.72f, z = -0.55f),
            ),
        ) {
            // Read the lift clock HERE, inside the content lambda, not in the demo body: this
            // lambda is its own recomposition scope, so the per-frame state change re-executes
            // only the scene nodes — never the scaffold, top bar, or settings sheet (the
            // GeometryDemo spin pattern).
            val lift = DemoMath.bounceHeight(
                liftElapsedNanos,
                periodNanos = LIFT_PERIOD_NANOS,
                maxHeight = LIFT_MAX_HEIGHT_METERS,
            )
            // The pool's response to that lift, shared by all three stagings: dimmer and wider
            // the further the subject is from its surface, full strength and tight at contact.
            val poolIntensity = DemoMath.groundingIntensityFactor(
                lift,
                maxHeight = LIFT_MAX_HEIGHT_METERS,
            )
            val poolSpread = DemoMath.groundingSpread(lift, maxHeight = LIFT_MAX_HEIGHT_METERS)

            // Directional key light for shape and specular — deliberately NOT a shadow caster.
            // The ONLY grounding cue on screen must be the contact shadow, so a real cast
            // shadow would muddy the with/without comparison.
            LightNode(
                type = LightManager.Type.DIRECTIONAL,
                direction = KEY_LIGHT_DIRECTION,
                apply = {
                    intensity(70_000f)
                    castShadows(false)
                },
            )

            // ── The room ──────────────────────────────────────────────────────────────────
            // Floor: an XZ quad (normal +Y).
            PlaneNode(
                size = Size(x = 7f, y = 0f, z = 7f),
                normal = Direction(y = 1f),
                materialInstance = floorMaterial,
            )
            // Back wall: an XY quad (normal +Z) — note the DIFFERENT size shape. `Plane` does
            // not rotate its geometry to match `normal`, so a vertical quad is built in XY.
            PlaneNode(
                size = Size(x = 7f, y = 3.4f, z = 0f),
                normal = Direction(z = 1f),
                position = Position(x = 0f, y = 1.7f, z = -2f),
                materialInstance = wallMaterial,
            )

            when (stage) {
                // ── Floor: a chair standing on the floor ──────────────────────────────────
                ContactShadowContext.Floor -> {
                    if (shadowVisible) {
                        // The pool follows the light's ground projection as the subject lifts
                        // (ball-in-a-box): centred and tight at contact, drifted out from under
                        // the chair at the top. This slide — not the dim/spread alone — is what
                        // sells "standing on" rather than "hanging in front of".
                        val (slideX, slideZ) = DemoMath.groundingShadowOffset(
                            lift,
                            KEY_LIGHT_DIRECTION.x, KEY_LIGHT_DIRECTION.y, KEY_LIGHT_DIRECTION.z,
                        )
                        ContactShadow(
                            size = Size(x = 1.5f, y = 0f, z = 1.5f),
                            context = ContactShadowContext.Floor,
                            normal = Direction(y = 1f),
                            intensity = ContactShadowContext.Floor.intensity *
                                intensityFactor * poolIntensity,
                            position = Position(
                                x = SUBJECT_X + slideX,
                                y = 0f,
                                z = SUBJECT_Z + slideZ,
                            ),
                            scale = Scale(poolSpread),
                        )
                    }
                    chairInstance?.let { instance ->
                        ModelNode(
                            modelInstance = instance,
                            scaleToUnits = CHAIR_UNITS,
                            // Seat the model's bounding box on its own origin, so `position.y`
                            // is the gap between the subject and the floor rather than an
                            // offset to whatever the exporter chose as the pivot.
                            centerOrigin = Position(y = -1f),
                            position = Position(x = SUBJECT_X, y = lift, z = SUBJECT_Z),
                        )
                    }
                }

                // ── Table top: a toy car resting on a table ───────────────────────────────
                ContactShadowContext.TableTop -> {
                    CubeNode(
                        size = Size(TABLE_WIDTH, TABLE_HEIGHT, TABLE_DEPTH),
                        position = Position(
                            x = SUBJECT_X,
                            y = TABLE_HEIGHT / 2f,
                            z = SUBJECT_Z,
                        ),
                        materialInstance = tableMaterial,
                    )
                    if (shadowVisible) {
                        val (slideX, slideZ) = DemoMath.groundingShadowOffset(
                            lift,
                            KEY_LIGHT_DIRECTION.x, KEY_LIGHT_DIRECTION.y, KEY_LIGHT_DIRECTION.z,
                        )
                        ContactShadow(
                            size = Size(x = 0.62f, y = 0f, z = 0.62f),
                            context = ContactShadowContext.TableTop,
                            normal = Direction(y = 1f),
                            intensity = ContactShadowContext.TableTop.intensity *
                                intensityFactor * poolIntensity,
                            // A hair above the table top: co-planar with it, the two quads
                            // z-fight and the pool flickers in and out as the camera orbits.
                            position = Position(
                                x = SUBJECT_X + slideX,
                                y = TABLE_HEIGHT + SURFACE_EPSILON,
                                z = SUBJECT_Z + slideZ,
                            ),
                            scale = Scale(poolSpread),
                        )
                    }
                    carInstance?.let { instance ->
                        ModelNode(
                            modelInstance = instance,
                            scaleToUnits = CAR_UNITS,
                            centerOrigin = Position(y = -1f),
                            position = Position(
                                x = SUBJECT_X,
                                y = TABLE_HEIGHT + lift,
                                z = SUBJECT_Z,
                            ),
                        )
                    }
                }

                // ── Wall: a TV mounted flat against the wall ──────────────────────────────
                // The case a real shadow map cannot serve: ceiling light runs nearly parallel
                // to the wall, so a flat panel casts essentially nothing onto it. Here the
                // lift pulls the TV away from the wall, and the pool widens and fades exactly
                // as a real contact shadow would.
                ContactShadowContext.Wall -> {
                    if (shadowVisible) {
                        ContactShadow(
                            size = Size(x = 2.2f, y = 1.5f, z = 0f),
                            context = ContactShadowContext.Wall,
                            normal = Direction(z = 1f),
                            intensity = ContactShadowContext.Wall.intensity *
                                intensityFactor * poolIntensity,
                            position = Position(x = SUBJECT_X, y = TV_CENTER_Y, z = -2f + SURFACE_EPSILON),
                            scale = Scale(poolSpread),
                        )
                    }
                    Node(
                        position = Position(
                            x = SUBJECT_X,
                            y = TV_CENTER_Y,
                            z = -2f + TV_MOUNT_GAP + lift,
                        )
                    ) {
                        CubeNode(
                            size = Size(1.34f, 0.80f, 0.05f),
                            position = Position(z = 0.025f),
                            materialInstance = tvBody,
                        )
                        CubeNode(
                            size = Size(1.28f, 0.74f, 0.01f),
                            position = Position(z = 0.055f),
                            materialInstance = tvScreen,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The one control bar this screen puts on the scene: the three-preset picker, the caption for
 * the preset in force, and the hold-to-compare pill.
 *
 * **Why the picker is here and not in the settings sheet.** The sheet's scrim dims the scene,
 * so a pool could never be watched *while* it was being changed — a sequential, half-blind
 * comparison. Anchored on the scene, the A/B is live.
 *
 * **Why every preset gets a caption, not just the notable ones.** A caption that appeared only
 * for some presets would communicate by *absence*, and an absence reads as nothing at all.
 * Each line says, in plain words, what the staged scene is showing — no preset names, no
 * "elliptical gradient", no jargon a reader has to already know to parse (#3498).
 *
 * Anchored to the *frame*, not to the subject's projected position: the camera is an orbit
 * manipulator, so any drag moves the subject under a fixed overlay.
 */
@Composable
internal fun ContactShadowStageBar(
    stage: ContactShadowContext,
    onStageChange: (ContactShadowContext) -> Unit,
    comparing: Boolean,
    onComparingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(stage.captionRes()),
            style = SceneViewTokens.Type.body,
            color = SceneViewTokens.Glass.onGlass,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = SceneViewTokens.Space.lg),
        )
        Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
            ContactShadowContext.entries.forEach { context ->
                FilterChip(
                    selected = context == stage,
                    onClick = { onStageChange(context) },
                    label = { Text(stringResource(context.chipLabelRes())) },
                )
            }
        }
        Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
        // Press-and-hold, not a latched toggle: the viewer causes the transition and watches
        // it happen, which is what a latched switch in a sheet could never give (#3498).
        // `detectTapGestures(onPress)` + `tryAwaitRelease` covers the cancel case too — a
        // finger dragged off the pill releases the comparison instead of stranding it on.
        GlassPill(
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onComparingChange(true)
                        tryAwaitRelease()
                        onComparingChange(false)
                    }
                )
            }
        ) {
            Text(
                text = stringResource(
                    if (comparing) R.string.contact_shadow_compare_active
                    else R.string.contact_shadow_compare_hint
                ),
                style = SceneViewTokens.Type.caption,
            )
        }
    }
}

/**
 * Chip label for each preset — the *surface*, not the enum constant.
 *
 * Exhaustive `when` on purpose: a new [ContactShadowContext] must fail this compile rather
 * than ship a chip labelled with a raw enum name.
 */
@StringRes
private fun ContactShadowContext.chipLabelRes(): Int = when (this) {
    ContactShadowContext.Floor -> R.string.contact_shadow_stage_floor
    ContactShadowContext.Wall -> R.string.contact_shadow_stage_wall
    ContactShadowContext.TableTop -> R.string.contact_shadow_stage_table
}

/**
 * The one-line caption shown for each staged preset — see [ContactShadowStageBar] for why all
 * three are covered.
 *
 * **The Wall caption is worded to the measurement, not to the ambition (#2957).** It used to
 * promise "a faint, wide halo below the panel". Device QA sampled the wall against the
 * detected panel bbox and the pool is real but *narrow*: 18.6/255 darker in the first 70 px
 * under a 314 px-tall panel, 0.4 at 70–130 px, 0.0 beyond. "Wide" therefore described
 * something a viewer looking for it cannot find, and a caption whose cue is not on screen
 * teaches nothing (#2740). The wording names the cue that *is* visible — the band of shade
 * against the panel's lower edge — and what it buys: the TV reads as flat against the wall.
 */
@StringRes
private fun ContactShadowContext.captionRes(): Int = when (this) {
    ContactShadowContext.Floor -> R.string.contact_shadow_caption_floor
    ContactShadowContext.Wall -> R.string.contact_shadow_caption_wall
    ContactShadowContext.TableTop -> R.string.contact_shadow_caption_table
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

/** The floor subject: a fabric armchair, already bundled and credited. */
private const val CHAIR_MODEL = "models/khronos_sheen_chair.glb"

/** The table-top subject: a toy car, already bundled and credited. */
private const val CAR_MODEL = "models/khronos_toy_car.glb"

/** Chair height, metres — a real armchair, so the room reads at human scale. */
private const val CHAIR_UNITS = 0.92f

/** Toy-car length, metres: small enough that the table reads as a table. */
private const val CAR_UNITS = 0.30f

/** X of every staged subject — centred, so one camera frames all three stagings. */
private const val SUBJECT_X = 0f

/** Z of the floor and table-top subjects: pulled toward the camera, in front of the wall. */
private const val SUBJECT_Z = 0.1f

/** Table slab dimensions, metres. */
private const val TABLE_WIDTH = 1.1f
private const val TABLE_HEIGHT = 0.44f
private const val TABLE_DEPTH = 0.66f
private val TABLE_COLOR = Color(0xFF8C6A4A)

/** Height of the TV's centre above the floor, metres. */
private const val TV_CENTER_Y = 1.15f

/** Resting gap between the wall plane and the TV's back face, metres. */
private const val TV_MOUNT_GAP = 0.012f

/**
 * Clearance between a contact-shadow quad and the surface it sits on, metres. Co-planar quads
 * z-fight: the pool flickers in and out as the camera orbits.
 */
private const val SURFACE_EPSILON = 0.004f

/**
 * Period of the lift-and-settle loop, nanoseconds — slower than the hop this replaced
 * ([DemoMath.CONTACT_BOUNCE_PERIOD_NANOS], 2.6 s). A chair or a mounted TV that bounced like a
 * ball would read as a physics toy; at 4.4 s the subject rises, hangs and settles, which is the
 * motion of something being *placed* — the moment the contact shadow exists to sell.
 */
private const val LIFT_PERIOD_NANOS = 4_400_000_000L

/** Peak height of the lift, metres. */
private const val LIFT_MAX_HEIGHT_METERS = 0.30f

/** Warm room surfaces — floor lighter than wall, so a subject separates against the back. */
private val ROOM_FLOOR_COLOR = Color(0xFFE3DCD1)
private val ROOM_WALL_COLOR = Color(0xFFC9C0B4)

/**
 * Travel direction of the directional key light — also the axis the floor and table pools
 * project along ([DemoMath.groundingShadowOffset]). The light and the shadow's slide are driven
 * from this single value so they can never drift out of agreement.
 */
private val KEY_LIGHT_DIRECTION = Direction(-0.35f, -1f, -0.4f)
