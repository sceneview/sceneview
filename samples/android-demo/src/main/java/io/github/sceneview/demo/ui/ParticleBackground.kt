package io.github.sceneview.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Skybox
import com.google.android.filament.utils.KTX1Loader
import io.github.sceneview.SceneScope
import io.github.sceneview.SceneView
import io.github.sceneview.createCameraNode
import io.github.sceneview.createEnvironment
import io.github.sceneview.createView
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.math.colorOf
import io.github.sceneview.math.toLinearSpace
import io.github.sceneview.node.Node
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberView
import io.github.sceneview.sample.rememberMaterialInstance
import io.github.sceneview.sample.rememberUnlitMaterialInstance
import io.github.sceneview.utils.readBuffer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animated 3D backdrop for the Explore and Samples tabs — itself a [SceneView] scene, so
 * the demo app dogfoods the SDK on its own home screen (#1488).
 *
 * ## Why it was redesigned (#3236)
 *
 * The first version was a cloud of 36 tiny *unlit* spheres orbited by a distant camera.
 * Unlit means every sphere is a flat disc of one colour; tiny and distant means no
 * perspective size contrast; a cloud with no floor means no reference for "near" or
 * "far". The owner's Play Store feedback on 4.31.0 was blunt: "we should really see it's
 * 3D". Every depth cue the eye uses was missing, so the fix adds them, one by one:
 *
 * - **A ground plane with a perspective grid.** Hairline grid lines converge toward the
 *   horizon — linear perspective is the single strongest depth cue on a flat screen.
 * - **Lit PBR volumes, not discs.** Spheres, tori, cubes and capsules in the brand ramp
 *   wear a lit material with the SDK's neutral IBL, so each one carries a highlight, a
 *   shaded side and a terminator — a shape reads as a volume only when it is shaded.
 * - **Cast shadows.** The main light is angled (not straight down) and every object
 *   drops a shadow onto the floor, which is what anchors it at a depth instead of letting
 *   it float as a sticker.
 * - **Depth-staggered layout with size contrast.** Objects spread from a metre in front of
 *   the camera to ten metres out, so the same radius renders large up close and small far
 *   away.
 * - **Atmospheric fade.** Filament fog tinted to the theme surface fades far objects and
 *   the grid toward the backdrop, so distance reads even in a still frame.
 * - **A lateral camera dolly.** The camera slides sideways and back over
 *   [DOLLY_PERIOD_SECONDS] while keeping its gaze on the field, so near objects sweep
 *   across the frame faster than far ones — motion parallax.
 *
 * ## Kept from the first version
 * - A scrim ([scrimBrush]) so headers drawn on the backdrop keep their contrast in both
 *   themes (#1443) — re-tuned, because the old 0.97 band hid three quarters of the scene.
 * - The clear colour is the theme `surface`, converted to linear (#3237-a).
 * - Lifecycle: the caller places this only while its tab is selected, so the whole
 *   [SceneView] and its frame loop leave composition on a tab switch.
 *
 * ## Frame budget
 * 20 lit primitives + 1 floor + ~30 hairline cubes, one directional shadow map, fog.
 * The view is built by [createView] and then trimmed: SSAO and bloom are switched off —
 * neither is visible behind a scrim, and SSAO is the most expensive default pass — and
 * the shadow map is kept at Filament's default resolution. The geometry is seeded once
 * per composition entry; per-frame work is a camera transform and a sine bob per object.
 */
@Composable
fun ParticleBackground(modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()

    // Clear colour, fog colour and scrim target — one value, read from the theme
    // (#3237-a: the three used to differ and light mode rendered a dirty grey field).
    val backdrop = MaterialTheme.colorScheme.surface
    val floorTint = MaterialTheme.colorScheme.surfaceDim
    val gridTint = MaterialTheme.colorScheme.outline

    // `@Preview` panes and Roborazzi run on the JVM, where `rememberEngine()` throws
    // `UnsatisfiedLinkError: no filament-jni`. Short-circuit to a static backdrop —
    // same pattern as [io.github.sceneview.demo.DemoPreviewPlaceholder].
    if (LocalInspectionMode.current) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(SolidColor(backdrop))
                .background(scrimBrush(backdrop, dark)),
        )
        return
    }

    val context = LocalContext.current
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)

    // Backdrop budget: keep shadows (the grounding cue), drop SSAO and bloom — both
    // invisible behind the scrim, and SSAO is the costliest default pass.
    val view = rememberView(engine) {
        createView(engine).apply {
            ambientOcclusionOptions = ambientOcclusionOptions.apply { enabled = false }
            bloomOptions = bloomOptions.apply { enabled = false }
        }
    }

    // Filament's skybox and fog colours are LINEAR, the theme token is sRGB.
    val linearBackdrop = remember(backdrop) { colorOf(backdrop).toLinearSpace() }

    // Atmospheric fade toward the clear colour: far objects and the grid dissolve into
    // the backdrop, so distance reads in a still frame. Pushed as a SideEffect (main
    // thread — Filament JNI) and re-pushed whenever the theme flips.
    SideEffect {
        view.fogOptions = view.fogOptions.apply {
            enabled = true
            fogColorFromIbl = false
            color[0] = linearBackdrop.r
            color[1] = linearBackdrop.g
            color[2] = linearBackdrop.b
            distance = FOG_START_DISTANCE
            density = FOG_DENSITY
            height = FOG_HEIGHT
            heightFalloff = FOG_HEIGHT_FALLOFF
            cutOffDistance = FOG_CUTOFF_DISTANCE
            maximumOpacity = 1f
        }
    }

    // The SDK's neutral studio IBL gives every lit surface a highlight and a shaded
    // side; the skybox stays a flat clear colour so the field sits on the theme surface.
    val environment = rememberEnvironment(engine, key = backdrop) {
        createEnvironment(
            engine = engine,
            indirectLight = KTX1Loader.createIndirectLight(
                engine,
                context.assets.readBuffer("environments/neutral/neutral_ibl.ktx"),
            ).indirectLight?.also { it.intensity = IBL_INTENSITY },
            skybox = Skybox.Builder()
                .color(linearBackdrop.toFloatArray())
                .build(engine),
        )
    }

    // Angled key light: straight-down (the SDK default) puts the shadow directly under
    // the object where the object hides it. A 3/4 angle throws it onto the floor where
    // it can be seen, and shades one side of each volume.
    val mainLight = rememberMainLightNode(engine) {
        lightDirection = Direction(x = -0.45f, y = -1f, z = -0.55f)
    }

    // Seed the layout once per composition entry. `Random.Default` is unseeded so each
    // cold start produces a different — but always structured — arrangement.
    val objects = remember {
        val rng = Random(Random.nextInt())
        List(OBJECT_COUNT) { index -> FloatingObject.random(rng, index) }
    }

    // Low, wide perspective: the camera sits at eye height just above the floor so the
    // grid converges steeply and the near objects tower over the far ones.
    val cameraNode = remember(engine) {
        createCameraNode(engine).apply {
            focalLength = CAMERA_FOCAL_LENGTH_MM
            position = Position(x = 0f, y = CAMERA_HEIGHT, z = CAMERA_DISTANCE)
            lookAt(CAMERA_TARGET)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            materialLoader = materialLoader,
            view = view,
            cameraNode = cameraNode,
            mainLightNode = mainLight,
            // Passive backdrop — every touch falls through to the content on top.
            cameraManipulator = null,
            // Opaque SurfaceView cleared to the theme surface (a translucent one would
            // show what is under the *window*, not the Compose background above it).
            isOpaque = true,
            environment = environment,
            autoCenterContent = false,
            onFrame = { frameTimeNanos ->
                val t = frameTimeNanos / 1_000_000_000.0
                // Lateral dolly + a slow yaw around the field: near objects cross the
                // frame faster than far ones, which is the parallax the eye reads as depth.
                val sway = sin(t / DOLLY_PERIOD_SECONDS * 2.0 * Math.PI)
                val yaw = sway * DOLLY_YAW_RADIANS
                cameraNode.position = Position(
                    x = (sin(yaw) * CAMERA_DISTANCE + sway * DOLLY_AMPLITUDE).toFloat(),
                    y = CAMERA_HEIGHT +
                        (sin(t / DOLLY_PERIOD_SECONDS * Math.PI) * CAMERA_BOB).toFloat(),
                    z = (cos(yaw) * CAMERA_DISTANCE).toFloat(),
                )
                cameraNode.lookAt(CAMERA_TARGET)
            },
        ) {
            val floorMaterial = rememberMaterialInstance(
                materialLoader,
                floorTint,
                metallic = 0f,
                roughness = 0.9f,
                reflectance = 0.3f,
            )
            val gridMaterial = rememberUnlitMaterialInstance(materialLoader, gridTint)
            val objectMaterials = SceneViewColors.Ramp4.map { color ->
                rememberMaterialInstance(
                    materialLoader,
                    objectColor(color, dark),
                    metallic = OBJECT_METALLIC,
                    roughness = OBJECT_ROUGHNESS,
                    reflectance = 0.5f,
                )
            }

            // Ground plane — receives every shadow, fades into fog at the horizon.
            PlaneNode(
                size = Size(x = FLOOR_SIZE, y = FLOOR_SIZE),
                materialInstance = floorMaterial,
                apply = { isShadowCaster = false },
            )

            // Perspective grid: hairline bars along Z converge at the horizon, bars
            // along X foreshorten toward it. Unlit so they never pick up a highlight.
            for (i in -GRID_HALF_LINES..GRID_HALF_LINES) {
                CubeNode(
                    size = Size(x = GRID_LINE_WIDTH, y = GRID_LINE_WIDTH, z = FLOOR_SIZE),
                    materialInstance = gridMaterial,
                    position = Position(x = i * GRID_SPACING, y = GRID_LINE_WIDTH, z = 0f),
                    apply = { isShadowCaster = false; isShadowReceiver = false },
                )
                CubeNode(
                    size = Size(x = FLOOR_SIZE, y = GRID_LINE_WIDTH, z = GRID_LINE_WIDTH),
                    materialInstance = gridMaterial,
                    position = Position(x = 0f, y = GRID_LINE_WIDTH, z = i * GRID_SPACING),
                    apply = { isShadowCaster = false; isShadowReceiver = false },
                )
            }

            objects.forEach { spec ->
                FloatingObjectNode(spec = spec, materialInstance = objectMaterials[spec.colorIndex])
            }
        }

        // Scrim — holds the controls band near-solid, opens up over the scrolling content.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scrimBrush(backdrop, dark)),
        )
    }
}

/** The primitive an object is built from. Mixed shapes read as a scene, not a pattern. */
private enum class Shape { Sphere, Torus, Cube, Capsule }

/**
 * One floating volume: a lit primitive hovering just above the floor, bobbing on its own
 * sine phase and — for the non-spherical shapes — turning slowly so its shading changes.
 * The motion is applied from the node's own frame loop so it never fights recomposition.
 */
@Composable
private fun SceneScope.FloatingObjectNode(
    spec: FloatingObject,
    materialInstance: MaterialInstance,
) {
    val drive: Node.() -> Unit = {
        onFrame = { frameTimeNanos ->
            val t = frameTimeNanos / 1_000_000_000.0
            val phase = spec.bobPhase + t / BOB_PERIOD_SECONDS * 2.0 * Math.PI
            position = Position(
                x = spec.basePosition.x,
                y = spec.basePosition.y + (sin(phase) * BOB_AMPLITUDE).toFloat(),
                z = spec.basePosition.z,
            )
            if (spec.shape != Shape.Sphere) {
                val spin = (t / SPIN_PERIOD_SECONDS * 360.0 + spec.spinOffset).toFloat()
                rotation = Rotation(x = spec.tilt, y = spin, z = 0f)
            }
        }
    }
    when (spec.shape) {
        Shape.Sphere -> SphereNode(
            radius = spec.size,
            stacks = LIT_STACKS,
            slices = LIT_SLICES,
            materialInstance = materialInstance,
            position = spec.basePosition,
            apply = { drive() },
        )
        Shape.Torus -> TorusNode(
            majorRadius = spec.size,
            minorRadius = spec.size * 0.38f,
            majorSegments = LIT_SLICES,
            minorSegments = LIT_STACKS,
            materialInstance = materialInstance,
            position = spec.basePosition,
            apply = { drive() },
        )
        Shape.Cube -> CubeNode(
            size = Size(spec.size * 1.6f),
            materialInstance = materialInstance,
            position = spec.basePosition,
            apply = { drive() },
        )
        Shape.Capsule -> CapsuleNode(
            radius = spec.size * 0.6f,
            height = spec.size * 2.4f,
            capStacks = LIT_STACKS / 2,
            sideSlices = LIT_SLICES,
            materialInstance = materialInstance,
            position = spec.basePosition,
            apply = { drive() },
        )
    }
}

/** Immutable per-object layout, seeded once per launch. */
private data class FloatingObject(
    val shape: Shape,
    val basePosition: Position,
    val size: Float,
    val colorIndex: Int,
    val bobPhase: Double,
    val spinOffset: Double,
    val tilt: Float,
) {
    companion object {
        /**
         * Objects are dealt into depth lanes — [index] walks from the nearest lane to
         * the farthest — so every launch has something close enough to look big and
         * something far enough to look small, instead of leaving that to chance.
         */
        fun random(rng: Random, index: Int): FloatingObject {
            val lane = index.toFloat() / (OBJECT_COUNT - 1)
            val z = FIELD_NEAR_Z + (FIELD_FAR_Z - FIELD_NEAR_Z) * lane +
                rng.nextDouble(-LANE_JITTER.toDouble(), LANE_JITTER.toDouble()).toFloat()
            // Alternate sides so the line of sight down the grid stays open.
            val side = if (index % 2 == 0) 1f else -1f
            // The frustum is narrow up close and wide far away: widen the lateral band
            // with depth so near objects stay inside the frame (not cut by its edge) and
            // far ones spread across the horizon instead of bunching at the centre.
            val xMax = FIELD_NEAR_MAX_X + (FIELD_MAX_X - FIELD_NEAR_MAX_X) * lane
            val x = side * rng.nextDouble(FIELD_MIN_X.toDouble(), xMax.toDouble()).toFloat()
            // Perspective already makes the near lane huge: scale the physical size down
            // up close so a near object never swallows a section header, and up far away
            // so the far lane still reads instead of vanishing into the fog.
            val size = rng.nextDouble(OBJECT_MIN_SIZE.toDouble(), OBJECT_MAX_SIZE.toDouble()).toFloat() *
                (NEAR_SIZE_FACTOR + (1f - NEAR_SIZE_FACTOR) * lane)
            val hover = rng.nextDouble(HOVER_MIN.toDouble(), HOVER_MAX.toDouble()).toFloat()
            return FloatingObject(
                shape = Shape.entries[rng.nextInt(Shape.entries.size)],
                basePosition = Position(x = x, y = size + hover, z = z),
                size = size,
                colorIndex = rng.nextInt(SceneViewColors.Ramp4.size),
                bobPhase = rng.nextDouble(0.0, 2.0 * Math.PI),
                spinOffset = rng.nextDouble(0.0, 360.0),
                tilt = rng.nextDouble(-35.0, 35.0).toFloat(),
            )
        }
    }
}

/**
 * Brand ramp colour as a lit base colour. Lit materials get their brightness from the
 * light, so the colour stays at full saturation; dark mode lifts it a touch because the
 * scrim above it is darker.
 */
private fun objectColor(base: Color, dark: Boolean): Color {
    val lift = if (dark) 1f else 0.9f
    return Color(
        red = base.red * lift,
        green = base.green * lift,
        blue = base.blue * lift,
        alpha = 1f,
    )
}

/**
 * Vertical scrim drawn on top of the [SceneView]. Fades toward [surface] — the same
 * colour the render target is cleared to.
 *
 * ## Why the top band is no longer held at 0.97
 *
 * The previous ramp held the top 74 % of the box at α 0.97 / 0.98 because device QA had
 * found particle discs drifting *through the Explore search field*. That field has since
 * moved inside the opaque hero card (#3242), and the first measurement of this redesign
 * showed the consequence of keeping the band anyway: with 97 % of the backdrop covered
 * over three quarters of the screen, the scene the owner asked to "really see is 3D"
 * (#3236) was a faint sliver under the bottom cards.
 *
 * What the band still has to protect is **text drawn directly on the backdrop** — the
 * "Samples" / "3D Basics" / "Trending in 3D" headers. Those sit on a calm floor with a
 * hairline grid, not on drifting discs, so they need contrast, not opacity: the top
 * stop keeps [SCRIM_HEADER_ALPHA_DARK] / [SCRIM_HEADER_ALPHA_LIGHT] through the header
 * band, then the ramp opens to [SCRIM_OPEN_ALPHA_DARK] / [SCRIM_OPEN_ALPHA_LIGHT] over
 * the card area, where every card carries its own opaque container, and closes again
 * toward the bottom so the scene does not end in a hard edge at the navigation bar.
 */
private fun scrimBrush(surface: Color, dark: Boolean): Brush = Brush.verticalGradient(
    // Near-solid at the very top: this box starts under the status bar / top bar, which
    // are painted plain `surface`, and a backdrop that is visible right at that edge
    // draws a hard seam across the screen. Fade in over the first stop instead.
    0.0f to surface.copy(alpha = SCRIM_EDGE_ALPHA),
    SCRIM_EDGE_FADE to
        surface.copy(alpha = if (dark) SCRIM_HEADER_ALPHA_DARK else SCRIM_HEADER_ALPHA_LIGHT),
    SCRIM_HEADER_BAND to
        surface.copy(alpha = if (dark) SCRIM_HEADER_ALPHA_DARK else SCRIM_HEADER_ALPHA_LIGHT),
    0.55f to surface.copy(alpha = if (dark) SCRIM_OPEN_ALPHA_DARK else SCRIM_OPEN_ALPHA_LIGHT),
    1.0f to surface.copy(alpha = if (dark) 0.55f else 0.70f),
)

/**
 * Fraction of this box (not of the screen — the box starts under the status bar and
 * stops above the bottom bar) held at the header alpha. Covers the page title and the
 * first section header on both screens.
 */
private const val SCRIM_HEADER_BAND = 0.28f

/** Alpha at the box's top edge and the fraction over which it eases to the header alpha. */
private const val SCRIM_EDGE_ALPHA = 0.98f
private const val SCRIM_EDGE_FADE = 0.10f

/** Scrim alpha over the header band — text on the backdrop keeps its contrast. */
private const val SCRIM_HEADER_ALPHA_DARK = 0.76f
private const val SCRIM_HEADER_ALPHA_LIGHT = 0.80f

/**
 * Scrim alpha where it opens over the card area. Lit volumes carry their own contrast
 * (highlight vs. shaded side), and every card on top has an opaque container.
 */
private const val SCRIM_OPEN_ALPHA_DARK = 0.24f
private const val SCRIM_OPEN_ALPHA_LIGHT = 0.36f

// ── Tuning constants — see the KDoc on [ParticleBackground] ─────────────────

/** Number of floating volumes. Keep in the low tens — this runs always-on. */
private const val OBJECT_COUNT = 20

/** Lit primitive tessellation — enough for a smooth terminator, cheap at this count. */
private const val LIT_STACKS = 16
private const val LIT_SLICES = 24

private const val OBJECT_MIN_SIZE = 0.2f
private const val OBJECT_MAX_SIZE = 0.55f
/** Size multiplier on the nearest lane (ramps to 1 on the farthest). */
private const val NEAR_SIZE_FACTOR = 0.55f
private const val OBJECT_METALLIC = 0.15f
private const val OBJECT_ROUGHNESS = 0.35f

/** Hover height (m) above the floor, so the shadow detaches from the object. */
private const val HOVER_MIN = 0.05f
private const val HOVER_MAX = 0.45f

/** Depth lanes (m, camera looks toward -Z) and lateral spread. */
private const val FIELD_NEAR_Z = 0.8f
private const val FIELD_FAR_Z = -10.0f
private const val LANE_JITTER = 0.35f
private const val FIELD_MIN_X = 0.45f
/** Lateral band is narrow on the nearest lane and opens up to [FIELD_MAX_X] on the farthest. */
private const val FIELD_NEAR_MAX_X = 1.3f
private const val FIELD_MAX_X = 4.0f

/** Floor and grid. */
private const val FLOOR_SIZE = 40f
private const val GRID_SPACING = 1.0f
private const val GRID_HALF_LINES = 14
private const val GRID_LINE_WIDTH = 0.012f

/** Camera: eye height, distance from the field origin, lens, gaze. */
private const val CAMERA_HEIGHT = 1.15f
private const val CAMERA_DISTANCE = 4.6f
private const val CAMERA_FOCAL_LENGTH_MM = 22.0
private const val CAMERA_BOB = 0.12f
private val CAMERA_TARGET = Position(x = 0f, y = 0.35f, z = -2.5f)

/** Lateral dolly: amplitude (m), yaw swing (rad) and period (s). Longer = calmer. */
private const val DOLLY_AMPLITUDE = 1.6f
private const val DOLLY_YAW_RADIANS = 0.26
private const val DOLLY_PERIOD_SECONDS = 34.0

/** Per-object bob amplitude (m) / period (s) and spin period (s). */
private const val BOB_AMPLITUDE = 0.08f
private const val BOB_PERIOD_SECONDS = 11.0
private const val SPIN_PERIOD_SECONDS = 40.0

/** Neutral IBL intensity — below the SDK default so the backdrop stays behind the UI. */
private const val IBL_INTENSITY = 6_000f

/** Fog: starts past the nearest objects, fully hides the far edge of the grid. */
private const val FOG_START_DISTANCE = 2.5f
private const val FOG_DENSITY = 0.07f
private const val FOG_HEIGHT = -1f
private const val FOG_HEIGHT_FALLOFF = 0.02f
private const val FOG_CUTOFF_DISTANCE = 30f
