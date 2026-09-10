package io.github.sceneview.ar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.google.android.filament.MaterialInstance
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.material.setColor
import io.github.sceneview.math.Position

/**
 * The on-surface visual for a placement reticle, and its two observable states.
 *
 * Consumer AR apps — Google Scene Viewer, IKEA Place, Houzz, Pokémon GO — do not draw a solid
 * filled dot at the placement point. They draw a **thin ring** (often with a small centre dot)
 * that reads as a target and, crucially, changes appearance the moment a surface is acquired:
 * dim / hollow while the app is still *searching* for a surface, bright / filled once it is
 * *ready* for a tap. This gives the user an unambiguous "you can place now" signal without any
 * text.
 *
 * [PlacementReticleStyle] selects the geometry; [ReticlePhase] is the searching-vs-ready state
 * the reticle currently reflects (driven by the centre-screen hit test in [PlacementScene]).
 *
 * @see PlacementScene
 */
enum class PlacementReticleStyle {
    /**
     * A thin ring lying flush on the surface, with a small filled centre dot when [ReticlePhase]
     * is [ReticlePhase.READY]. The modern consumer-AR default (Scene Viewer / IKEA / Houzz).
     */
    RING,

    /**
     * A single flat filled disc — the original [PlacementScene] reticle. Kept for callers that
     * relied on the pre-ring visual, and for minimal HUDs where a ring is too busy.
     */
    DISC,
}

/**
 * Whether the reticle is still hunting for a surface or has locked onto one — the input the
 * [PlacementReticleStyle] visuals key their appearance off.
 */
enum class ReticlePhase {
    /** The centre-screen ray currently hits no acceptable surface. The reticle dims / hollows. */
    SEARCHING,

    /** A surface is acquired under the reticle; a tap will place there. The reticle brightens. */
    READY,
}

/**
 * Default reticle tint — **achromatic white**, the DESIGN.md `on-ar-scrim` foreground.
 *
 * It used to be `0xFF44E7FF` and its KDoc called that "the DESIGN.md primary cyan". DESIGN.md has
 * no cyan: `primary` is `#005bc1` / `#a4c1ff`, and `#44E7FF` appears in no token table. On a real
 * floor that saturated ring was the loudest object in the frame and tinted the room (#3570).
 *
 * Every reticle worth copying is neutral — RealityKit's `FocusEntity` bracket, Scene Viewer's and
 * Polycam's soft white ellipse, IKEA Place's plain white ring. They say *searching* vs *ready*
 * with opacity and shape, never with hue, so the cursor never competes with the model about to
 * be placed. Like every element drawn over a camera frame (DESIGN.md "AR Coaching Overlay":
 * *the ground is a camera frame*), it is theme-independent — identical in light and dark.
 *
 * The one colour on screen is [RETICLE_READY_ACCENT], and only on the small centre dot.
 *
 * Public because it is the documented default of the public [PlacementScene]/[PlacementReticleVisual]
 * `reticleColor` params — a caller (or an AI copying the signature) must be able to name it.
 */
val RETICLE_TINT: Color = Color(0xFF_FF_FF_FF)

/**
 * The centre dot's hue in [ReticlePhase.READY] — DESIGN.md `primary`, dark-scheme value
 * `#a4c1ff`. Accents over the camera feed are the dark-scheme values in **both** themes
 * (DESIGN.md, "AR Coaching Overlay"), because they are read on a camera frame, not on a
 * surface role.
 *
 * Colour is confined to this ~2 cm dot: enough to make "you can place now" unmistakable at a
 * glance, small enough that the cursor still reads as neutral.
 */
val RETICLE_READY_ACCENT: Color = Color(0xFF_A4_C1_FF)

/**
 * The contact halo under the ring — DESIGN.md `ar-scrim` black. A white ring on a white tile
 * floor is the case the old cyan never had to solve; a barely-there dark tube a hair below the
 * ring gives it a ground to sit on, exactly as Scene Viewer's reticle carries a soft shadow
 * ellipse. Its alpha is [reticleHaloAlphaFor], derived from the ring's so the two fade together.
 */
val RETICLE_HALO: Color = Color(0xFF_00_00_00)

/** Ring outer radius (major radius + tube), metres — ~11 cm across on the surface. */
internal const val RING_MAJOR_RADIUS = 0.055f

/**
 * Ring tube thickness (minor radius), metres — a ~3.5 mm hairline band (~6% of the major radius).
 * Slimmer than the pre-#3570 5 mm tube: the reference reticles are all hairlines, and a thicker
 * band reads as a heavy "toy" target.
 */
internal const val RING_MINOR_RADIUS = 0.0035f

/** Centre-dot radius shown only in the READY phase, metres. */
internal const val RING_DOT_RADIUS = 0.010f

/**
 * Contact-halo tube thickness, metres — the halo is an **annulus** tracing the ring, not a filled
 * disc. A filled disc at [RETICLE_HALO_ALPHA_RATIO] reads as a grey blob on a pale floor (measured
 * on the demo's pale-ground swatch) and swallows the hairline it was meant to lift; a slightly
 * fatter dark tube directly under the white one behaves like the soft drop shadow every reference
 * reticle carries, and only ever darkens the few millimetres the ring itself occupies.
 */
internal const val RETICLE_HALO_MINOR_RADIUS = RING_MINOR_RADIUS * 1.8f

/** Ring / dot height off the surface, metres — a hair above the plane to avoid z-fighting. */
internal const val RETICLE_LIFT = 0.004f

/** Halo height off the surface, metres — under the ring, still clear of the plane. */
internal const val RETICLE_HALO_LIFT = 0.002f

/** Ring tessellation — segments around the main circle. */
internal const val RING_MAJOR_SEGMENTS = 64

/** Ring tessellation — segments around the tube. */
internal const val RING_MINOR_SEGMENTS = 12

/**
 * Alpha applied to the ring while [ReticlePhase.SEARCHING] — visible but clearly "not yet".
 *
 * Public so a caller driving its own animated [PlacementReticleVisual] `alpha` can target the
 * same two values the built-in phase step uses (#3326).
 */
const val RETICLE_SEARCHING_ALPHA = 0.35f

/** Alpha applied to the ring while [ReticlePhase.READY] — bright, tap-me. */
const val RETICLE_READY_ALPHA = 0.95f

/**
 * How much of the ring's opacity the contact halo carries. Kept well under half: the halo is a
 * grounding cue, and a halo that reads as a disc is a second cursor.
 */
const val RETICLE_HALO_ALPHA_RATIO = 0.28f

/**
 * Maps a centre-screen hit presence to the reticle phase: a non-null hit means a surface is
 * acquired ([ReticlePhase.READY]), a null hit means the ray finds nothing ([ReticlePhase.SEARCHING]).
 * Extracted so the mapping is unit-testable without Compose / ARCore.
 */
fun reticlePhaseFor(hasHit: Boolean): ReticlePhase =
    if (hasHit) ReticlePhase.READY else ReticlePhase.SEARCHING

/** The ring/dot opacity for [phase] — bright when READY, dimmed while SEARCHING. */
fun reticleAlphaFor(phase: ReticlePhase): Float =
    if (phase == ReticlePhase.READY) RETICLE_READY_ALPHA else RETICLE_SEARCHING_ALPHA

/**
 * The contact halo's opacity for a given ring opacity — a fixed fraction
 * ([RETICLE_HALO_ALPHA_RATIO]) so halo and ring fade together and the halo is never the
 * dominant mark. Pure so the ratio is pinned without Compose or Filament (#3570).
 */
fun reticleHaloAlphaFor(ringAlpha: Float): Float =
    (ringAlpha * RETICLE_HALO_ALPHA_RATIO).coerceIn(0f, 1f)

/**
 * Renders the reticle geometry for [style], recolouring live as [phase] changes.
 *
 * Declared inside an AR content block on a node whose pose already orients +Y along the surface
 * normal (e.g. a [PlacementReticleNode] child), so the ring lies flat on the detected surface.
 *
 * Materials are created once per [MaterialLoader] and destroyed with the composable (#2458
 * class), and their colour is mutated in place on phase change rather than recreating the
 * instance — so the searching→ready transition is allocation-free.
 *
 * Public so callers building a **custom** AR placement flow (or a non-AR design-time preview of
 * one) can render the same reticle their users see in [PlacementScene]. Declare it inside a node
 * whose pose orients +Y along the target surface normal.
 *
 * @param materialLoader loader that builds the unlit reticle materials.
 * @param phase          the current searching / ready state.
 * @param tint           reticle hue; its alpha is overridden per [phase].
 * @param style          ring or disc geometry.
 * @param alpha          reticle opacity. Defaults to the step value [phase] implies. Pass an
 *                       **animated** value (e.g. Compose `animateFloatAsState`) to cross-fade
 *                       the searching→ready transition instead of stepping it: a reticle that
 *                       snaps between two opacities at 60 Hz on a jittery hit test reads as
 *                       flicker, which is the opposite of the "you can place now" signal the
 *                       phase change is supposed to give (#3326). Additive — every existing
 *                       call site keeps the original stepped behaviour.
 */
@Composable
fun io.github.sceneview.NodeScope.PlacementReticleVisual(
    materialLoader: MaterialLoader,
    phase: ReticlePhase,
    tint: Color = RETICLE_TINT,
    style: PlacementReticleStyle = PlacementReticleStyle.RING,
    alpha: Float = reticleAlphaFor(phase),
) {
    val haloAlpha = reticleHaloAlphaFor(alpha)

    // One material for the ring/disc body — recoloured in place per phase (no re-alloc).
    val bodyMaterial: MaterialInstance = remember(materialLoader, tint) {
        materialLoader.createUnlitColorInstance(tint.copy(alpha = alpha))
    }
    // The READY centre dot is the only coloured mark on the cursor (#3570), so it needs its own
    // instance — the ring must stay achromatic.
    val accentMaterial: MaterialInstance = remember(materialLoader) {
        materialLoader.createUnlitColorInstance(RETICLE_READY_ACCENT.copy(alpha = alpha))
    }
    // Contact halo — gives the white ring a ground to sit on when the real surface is light.
    val haloMaterial: MaterialInstance = remember(materialLoader) {
        materialLoader.createUnlitColorInstance(RETICLE_HALO.copy(alpha = haloAlpha))
    }
    DisposableEffect(materialLoader, bodyMaterial, accentMaterial, haloMaterial) {
        onDispose {
            materialLoader.destroyMaterialInstance(bodyMaterial)
            materialLoader.destroyMaterialInstance(accentMaterial)
            materialLoader.destroyMaterialInstance(haloMaterial)
        }
    }
    // Mutate the live colours so the ring dims/brightens without churning the instances.
    SideEffect {
        bodyMaterial.setColor(tint.copy(alpha = alpha))
        accentMaterial.setColor(RETICLE_READY_ACCENT.copy(alpha = alpha))
        haloMaterial.setColor(RETICLE_HALO.copy(alpha = haloAlpha))
    }

    when (style) {
        PlacementReticleStyle.DISC -> {
            CylinderNode(
                radius = RING_MAJOR_RADIUS,
                height = RETICLE_HEIGHT,
                sideCount = RETICLE_SIDES,
                materialInstance = bodyMaterial,
            )
        }

        PlacementReticleStyle.RING -> {
            // Halo first, lowest — a soft dark ground so the white hairline survives on a light
            // floor. Drawn for both phases so the cursor's silhouette never changes on the
            // searching→ready step; only its opacity does.
            TorusNode(
                majorRadius = RING_MAJOR_RADIUS,
                minorRadius = RETICLE_HALO_MINOR_RADIUS,
                majorSegments = RING_MAJOR_SEGMENTS,
                minorSegments = RING_MINOR_SEGMENTS,
                position = Position(y = RETICLE_HALO_LIFT),
                materialInstance = haloMaterial,
            )
            TorusNode(
                majorRadius = RING_MAJOR_RADIUS,
                minorRadius = RING_MINOR_RADIUS,
                majorSegments = RING_MAJOR_SEGMENTS,
                minorSegments = RING_MINOR_SEGMENTS,
                position = Position(y = RETICLE_LIFT),
                materialInstance = bodyMaterial,
            )
            // Centre dot only once a surface is acquired — the one coloured mark, and the
            // unambiguous "tap now" signal.
            if (phase == ReticlePhase.READY) {
                CylinderNode(
                    radius = RING_DOT_RADIUS,
                    height = RETICLE_HEIGHT,
                    sideCount = RETICLE_SIDES,
                    position = Position(y = RETICLE_LIFT),
                    materialInstance = accentMaterial,
                )
            }
        }
    }
}
