package io.github.sceneview.demo.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.foundation.background
import com.google.android.filament.MaterialInstance
import io.github.sceneview.SceneScope
import io.github.sceneview.SceneView
import io.github.sceneview.createCameraNode
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.sample.rememberUnlitMaterialInstance
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animated 3D particle background for the Samples tab — a tasteful nod to the
 * old Windows Media Player music visualizers (#1488). It is itself a
 * [SceneView] scene, so the demo app dogfoods the SDK on its own home screen.
 *
 * Design intent (kept deliberately restrained):
 * - **A drifting particle field** — [PARTICLE_COUNT] small unlit spheres,
 *   seeded per launch ([Random] without a fixed seed) so each cold start
 *   looks slightly different. Each particle slowly bobs on its own sine
 *   phase and the whole field auto-orbits via a camera sweep.
 * - **Subtle + performant** — particle count is in the low tens, motion is
 *   low-frequency, the spheres are low-poly ([PARTICLE_STACKS]/[PARTICLE_SLICES]),
 *   and the colours are pulled from the on-brand [SceneViewColors] ramp at low
 *   brightness. A vertical scrim ([scrimBrush]) is drawn on top so the demo
 *   cards keep their contrast (dark-mode readability — see #1443).
 * - **Lifecycle-aware** — the caller only places this composable while the
 *   Samples tab is selected, so the whole [SceneView] (and its frame loop)
 *   leaves composition — and pauses — when another tab is shown.
 *
 * ## Tuning for iteration
 * This is a first visual experiment. To iterate:
 * - **Particle count** — change [PARTICLE_COUNT] (keep it in the tens; this
 *   runs always-on behind a scrollable list).
 * - **Colours** — swap the [SceneViewColors] ramp used in [particleColor], or
 *   adjust the `alpha` there for more / less brightness.
 * - **Motion** — [ORBIT_PERIOD_SECONDS] sets the camera sweep speed,
 *   [DRIFT_AMPLITUDE] / [DRIFT_PERIOD_SECONDS] set the per-particle bob.
 * - **Scrim** — [scrimBrush] controls how much the background bleeds through
 *   to the cards.
 *
 * The composable is intentionally self-contained (one file, demo app only — it
 * never touches the `sceneview` library module) so it is easy to revert.
 */
@Composable
fun ParticleBackground(modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()

    // `@Preview` panes and Roborazzi run on the JVM, where `rememberEngine()`
    // below throws `UnsatisfiedLinkError: no filament-jni`. Short-circuit to a
    // static backdrop first — same pattern as [GeometryDemo] and
    // [SplatPreviewDemo], see [io.github.sceneview.demo.DemoPreviewPlaceholder].
    // This also makes the composable deterministic: the particle field is
    // seeded from an *unseeded* [Random], so a pixel-exact golden could never
    // match the live scene anyway.
    if (LocalInspectionMode.current) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(inspectionBackdrop(dark))
                .background(scrimBrush(dark)),
        )
        return
    }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)

    // Seed the layout once per composition entry (≈ once per launch / per tab
    // switch into Samples). `Random.Default` is unseeded so each cold start
    // produces a different — but always tasteful — constellation.
    val particles = remember {
        val rng = Random(Random.nextInt())
        List(PARTICLE_COUNT) { ParticleSpec.random(rng) }
    }

    // Camera node driven by the frame loop below — a slow continuous orbit
    // around the field gives the whole scene its drifting "alive" feel
    // without any per-particle physics.
    val cameraNode = remember(engine) {
        createCameraNode(engine).apply {
            position = Position(x = 0f, y = 0f, z = CAMERA_RADIUS)
            lookAt(Position(0f, 0f, 0f))
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            materialLoader = materialLoader,
            cameraNode = cameraNode,
            // No gesture manipulator — this is a passive backdrop, all touches
            // must fall through to the demo grid on top.
            cameraManipulator = null,
            // Opaque dark render target: a flat low-brightness backdrop so the
            // particles read as glints on a calm field, and the demo cards keep
            // their contrast.
            isOpaque = true,
            // Static content is centred by hand; skip the auto-centre pass so
            // the seeded layout is not re-translated on the first frame.
            autoCenterContent = false,
            onFrame = { frameTimeNanos ->
                val t = frameTimeNanos / 1_000_000_000.0
                // Slow auto-orbit — one full revolution every ORBIT_PERIOD.
                val orbit = (t / ORBIT_PERIOD_SECONDS) * 2.0 * Math.PI
                cameraNode.position = Position(
                    x = (sin(orbit) * CAMERA_RADIUS).toFloat(),
                    y = (sin(t / ORBIT_PERIOD_SECONDS * Math.PI) * CAMERA_BOB).toFloat(),
                    z = (cos(orbit) * CAMERA_RADIUS).toFloat(),
                )
                cameraNode.lookAt(Position(0f, 0f, 0f))
            },
        ) {
            // A single shared material per ramp colour keeps the MaterialInstance
            // count tiny regardless of PARTICLE_COUNT.
            val materials = SceneViewColors.Ramp4.map { color ->
                rememberUnlitMaterialInstance(materialLoader, particleColor(color, dark))
            }

            particles.forEach { spec ->
                ParticleNode(spec = spec, materialInstance = materials[spec.colorIndex])
            }
        }

        // Scrim — a soft vertical gradient that fades the backdrop toward the
        // theme surface so the demo cards stay readable on top. Tweak the alpha
        // values here to let more / less of the scene bleed through.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scrimBrush(dark)),
        )
    }
}

/**
 * One drifting particle: an unlit low-poly [io.github.sceneview.SceneScope.SphereNode]
 * that bobs on its own sine phase. The bob is applied every frame through the
 * node's `onFrame` callback so it never fights Compose recomposition.
 */
@Composable
private fun SceneScope.ParticleNode(
    spec: ParticleSpec,
    materialInstance: MaterialInstance,
) {
    SphereNode(
        radius = spec.radius,
        stacks = PARTICLE_STACKS,
        slices = PARTICLE_SLICES,
        materialInstance = materialInstance,
        position = spec.basePosition,
        scale = Scale(1f),
        apply = {
            // Drive the gentle vertical bob from the node's own frame loop so it
            // does not clobber, and is not clobbered by, recomposition.
            onFrame = { frameTimeNanos ->
                val t = frameTimeNanos / 1_000_000_000.0
                val phase = spec.driftPhase + t / DRIFT_PERIOD_SECONDS * 2.0 * Math.PI
                position = Position(
                    x = spec.basePosition.x +
                        (cos(phase) * DRIFT_AMPLITUDE * spec.driftScale).toFloat(),
                    y = spec.basePosition.y +
                        (sin(phase) * DRIFT_AMPLITUDE).toFloat(),
                    z = spec.basePosition.z +
                        (sin(phase * 0.5) * DRIFT_AMPLITUDE * spec.driftScale).toFloat(),
                )
            }
        },
    )
}

/** Immutable per-particle layout, seeded once per launch. */
private data class ParticleSpec(
    val basePosition: Position,
    val radius: Float,
    val colorIndex: Int,
    val driftPhase: Double,
    val driftScale: Float,
) {
    companion object {
        fun random(rng: Random): ParticleSpec {
            // Scatter inside a soft sphere of FIELD_RADIUS so the constellation
            // reads as a 3D cloud rather than a flat ring.
            val theta = rng.nextDouble(0.0, 2.0 * Math.PI)
            val phi = rng.nextDouble(0.0, Math.PI)
            val r = FIELD_RADIUS * rng.nextDouble(0.35, 1.0).toFloat()
            return ParticleSpec(
                basePosition = Position(
                    x = (r * sin(phi) * cos(theta)).toFloat(),
                    y = (r * cos(phi)).toFloat(),
                    z = (r * sin(phi) * sin(theta)).toFloat(),
                ),
                radius = rng.nextDouble(
                    PARTICLE_MIN_RADIUS.toDouble(),
                    PARTICLE_MAX_RADIUS.toDouble(),
                ).toFloat(),
                colorIndex = rng.nextInt(SceneViewColors.Ramp4.size),
                driftPhase = rng.nextDouble(0.0, 2.0 * Math.PI),
                driftScale = rng.nextDouble(0.6, 1.4).toFloat(),
            )
        }
    }
}

/**
 * Tones a brand ramp colour down to a low-brightness "glint" suitable for a
 * background — bright particles would compete with the demo cards. Dark mode
 * keeps a touch more luminance because the surface behind it is darker.
 */
private fun particleColor(base: Color, dark: Boolean): Color {
    val lift = if (dark) 0.85f else 0.55f
    return Color(
        red = base.red * lift,
        green = base.green * lift,
        blue = base.blue * lift,
        alpha = 1f,
    )
}

/**
 * Flat stand-in for the opaque [SceneView] render target, used only when
 * [LocalInspectionMode] is on. It is the colour the live scene's scrim already
 * fades toward, so a preview reads as "calm dark field" rather than a hole.
 */
private fun inspectionBackdrop(dark: Boolean): Brush =
    SolidColor(if (dark) SceneViewColors.SurfaceDim else Color.White)

/**
 * Vertical scrim drawn on top of the [SceneView]. Fades toward the theme
 * surface so the demo grid keeps its contrast — the particle field is meant to
 * be felt, not read.
 */
private fun scrimBrush(dark: Boolean): Brush {
    val surface = if (dark) SceneViewColors.SurfaceDim else Color.White
    return Brush.verticalGradient(
        0.0f to surface.copy(alpha = if (dark) 0.30f else 0.55f),
        0.45f to surface.copy(alpha = if (dark) 0.55f else 0.78f),
        1.0f to surface.copy(alpha = if (dark) 0.80f else 0.92f),
    )
}

// ── Tuning constants — see the KDoc on [ParticleBackground] ─────────────────

/** Number of particles. Keep in the low tens — this runs always-on. */
private const val PARTICLE_COUNT = 36

/** Low-poly sphere subdivisions — cheap enough for [PARTICLE_COUNT] spheres. */
private const val PARTICLE_STACKS = 8
private const val PARTICLE_SLICES = 8

private const val PARTICLE_MIN_RADIUS = 0.02f
private const val PARTICLE_MAX_RADIUS = 0.06f

/** Radius (m) of the soft sphere the particle field is scattered inside. */
private const val FIELD_RADIUS = 1.6f

/** Camera orbit radius (m) and vertical bob amplitude (m). */
private const val CAMERA_RADIUS = 4.2f
private const val CAMERA_BOB = 0.5f

/** Seconds for one full camera revolution — higher = slower, calmer. */
private const val ORBIT_PERIOD_SECONDS = 48.0

/** Per-particle drift bob amplitude (m) and period (s). */
private const val DRIFT_AMPLITUDE = 0.12f
private const val DRIFT_PERIOD_SECONDS = 9.0
