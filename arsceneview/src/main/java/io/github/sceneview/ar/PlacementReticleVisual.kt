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
 * Default reticle tint — the DESIGN.md primary cyan. The [ReticlePhase] modulates its alpha, so
 * this is the fully-opaque hue; the searching state renders it faded.
 *
 * Public because it is the documented default of the public [PlacementScene]/[PlacementReticleVisual]
 * `reticleColor` params — a caller (or an AI copying the signature) must be able to name it.
 */
val RETICLE_TINT: Color = Color(0xFF_44_E7_FF)

/** Ring outer radius (major radius + tube), metres — ~8 cm across on the surface. */
internal const val RING_MAJOR_RADIUS = 0.06f

/**
 * Ring tube thickness (minor radius), metres — a slim ~5 mm band (~8% of the major radius, the
 * sleek proportion consumer AR reticles use; a thicker tube reads as a heavy "toy" target).
 */
internal const val RING_MINOR_RADIUS = 0.005f

/** Centre-dot radius shown only in the READY phase, metres. */
internal const val RING_DOT_RADIUS = 0.013f

/** Ring / dot height off the surface, metres — a hair above the plane to avoid z-fighting. */
internal const val RETICLE_LIFT = 0.004f

/** Ring tessellation — segments around the main circle. */
internal const val RING_MAJOR_SEGMENTS = 64

/** Ring tessellation — segments around the tube. */
internal const val RING_MINOR_SEGMENTS = 12

/** Alpha applied to the ring while [ReticlePhase.SEARCHING] — visible but clearly "not yet". */
internal const val RETICLE_SEARCHING_ALPHA = 0.35f

/** Alpha applied to the ring while [ReticlePhase.READY] — bright, tap-me. */
internal const val RETICLE_READY_ALPHA = 0.95f

/**
 * Maps a centre-screen hit presence to the reticle phase: a non-null hit means a surface is
 * acquired ([ReticlePhase.READY]), a null hit means the ray finds nothing ([ReticlePhase.SEARCHING]).
 * Extracted so the mapping is unit-testable without Compose / ARCore.
 */
internal fun reticlePhaseFor(hasHit: Boolean): ReticlePhase =
    if (hasHit) ReticlePhase.READY else ReticlePhase.SEARCHING

/** The ring/dot opacity for [phase] — bright when READY, dimmed while SEARCHING. */
internal fun reticleAlphaFor(phase: ReticlePhase): Float =
    if (phase == ReticlePhase.READY) RETICLE_READY_ALPHA else RETICLE_SEARCHING_ALPHA

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
 */
@Composable
fun io.github.sceneview.NodeScope.PlacementReticleVisual(
    materialLoader: MaterialLoader,
    phase: ReticlePhase,
    tint: Color = RETICLE_TINT,
    style: PlacementReticleStyle = PlacementReticleStyle.RING,
) {
    val alpha = reticleAlphaFor(phase)

    // One material for the ring/disc body — recoloured in place per phase (no re-alloc).
    val bodyMaterial: MaterialInstance = remember(materialLoader, tint) {
        materialLoader.createUnlitColorInstance(tint.copy(alpha = alpha))
    }
    DisposableEffect(materialLoader, bodyMaterial) {
        onDispose { materialLoader.destroyMaterialInstance(bodyMaterial) }
    }
    // Mutate the live colour so the ring dims/brightens without churning the instance.
    SideEffect { bodyMaterial.setColor(tint.copy(alpha = alpha)) }

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
            TorusNode(
                majorRadius = RING_MAJOR_RADIUS,
                minorRadius = RING_MINOR_RADIUS,
                majorSegments = RING_MAJOR_SEGMENTS,
                minorSegments = RING_MINOR_SEGMENTS,
                position = Position(y = RETICLE_LIFT),
                materialInstance = bodyMaterial,
            )
            // Centre dot only once a surface is acquired — reinforces the "ready" signal.
            if (phase == ReticlePhase.READY) {
                CylinderNode(
                    radius = RING_DOT_RADIUS,
                    height = RETICLE_HEIGHT,
                    sideCount = RETICLE_SIDES,
                    position = Position(y = RETICLE_LIFT),
                    materialInstance = bodyMaterial,
                )
            }
        }
    }
}
