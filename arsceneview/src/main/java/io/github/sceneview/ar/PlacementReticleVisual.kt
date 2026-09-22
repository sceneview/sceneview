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
 * Manual-placement API. Automatic placement uses [AutoPlacementScene] without a reticle.
 * Existing behavior and defaults are preserved.
 *
 * The on-surface visual for a placement reticle, and its three observable states.
 *
 * Consumer AR apps — Google Scene Viewer, IKEA Place, Houzz, Pokémon GO — do not draw a solid
 * filled dot at the placement point. They draw a **thin ring** (often with a small centre dot)
 * that reads as a target and, crucially, changes appearance the moment a surface is acquired:
 * dim / hollow while the app is still *searching* for a surface, bright / filled once it is
 * *ready* for a tap. This gives the user an unambiguous "you can place now" signal without any
 * text.
 *
 * [PlacementReticleStyle] selects the geometry; [ReticlePhase] is the searching / hit / locked
 * state the reticle currently reflects (driven by the centre-screen hit test in [PlacementScene]).
 *
 * @see PlacementScene
 */
enum class PlacementReticleStyle {
    /**
     * A thin ring lying flush on the surface, with a small filled centre dot from
     * [ReticlePhase.READY] on. The modern consumer-AR default (Scene Viewer / IKEA / Houzz).
     */
    RING,

    /**
     * A single flat filled disc — the original [PlacementScene] reticle. Kept for callers that
     * relied on the pre-ring visual, and for minimal HUDs where a ring is too busy.
     */
    DISC,
}

/**
 * Whether the reticle is still hunting for a surface, sitting on an estimated one, or locked on a
 * tracked plane — the input the [PlacementReticleStyle] visuals key their appearance off.
 *
 * The three states escalate, and **only the last one is allowed a hue** (#3570): the cursor must
 * never compete with the model about to be placed, so "searching" vs "aiming" is said with
 * opacity and a dot, and colour is spent once, on the single state that means *this is a real
 * surface, tap now*.
 *
 * | State | Ring | Centre dot | Reads as |
 * |---|---|---|---|
 * | [SEARCHING] | white, [RETICLE_SEARCHING_ALPHA] | none | "I have nothing yet" |
 * | [READY] | white, [RETICLE_READY_ALPHA] | small, white | "something is there, it is a guess" |
 * | [LOCKED] | white, [RETICLE_LOCKED_ALPHA] | larger, [RETICLE_READY_ACCENT] | "tap now" |
 *
 * The ring's silhouette — radius, tube thickness, contact halo — is identical in all three, so the
 * cursor never jumps; only opacity and the centre dot change.
 */
enum class ReticlePhase {
    /** The centre-screen ray currently hits no acceptable surface. The reticle dims / hollows. */
    SEARCHING,

    /**
     * A surface is acquired under the reticle and a tap will place there, but it is an
     * **estimate** — an ARCore [com.google.ar.core.Point], a depth point or an instant-placement
     * point rather than a tracked plane. Mid opacity, small achromatic centre dot, still no hue.
     *
     * With the plane-only hit test [PlacementScene] configures by default, the reticle steps
     * straight from [SEARCHING] to [LOCKED]; this state is what a caller that opts into point /
     * instant-placement hits gets — e.g. a
     * [PlacementReticle][io.github.sceneview.ar.ARSceneScope.PlacementReticle] with
     * `snapToPlane = false`, which accepts feature points — instead of promising a lock it does
     * not have.
     */
    READY,

    /**
     * The hit is on a **tracked plane** — the strongest placement signal ARCore gives. Brightest
     * ring, and the one coloured mark on the whole cursor ([RETICLE_READY_ACCENT], DESIGN.md
     * `primary` dark value).
     */
    LOCKED,
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
 * The centre dot's hue in [ReticlePhase.LOCKED] — DESIGN.md `primary`, dark-scheme value
 * `#a4c1ff`. Accents over the camera feed are the dark-scheme values in **both** themes
 * (DESIGN.md, "AR Coaching Overlay"), because they are read on a camera frame, not on a
 * surface role.
 *
 * Colour is confined to this ~1.6 cm dot, and to the locked state only: enough to make "you can
 * place now" unmistakable at a glance, small enough that the cursor still reads as neutral. The
 * [ReticlePhase.READY] dot stays achromatic — an estimated surface has not earned the hue.
 *
 * (Name kept from the two-state era for source compatibility; it is the *locked* accent.)
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

/**
 * Centre-dot radius in [ReticlePhase.LOCKED], metres — ~1.6 cm across, down from the pre-#3570
 * 2 cm. It is the only coloured mark on screen; it has to be *spotted*, not *seen*.
 */
internal const val RING_DOT_RADIUS = 0.008f

/**
 * Centre-dot radius in [ReticlePhase.READY], metres — half the locked dot, and white. A guessed
 * surface gets a smaller, achromatic mark so the escalation to a real plane lock is visible
 * without any text.
 */
internal const val RING_HIT_DOT_RADIUS = 0.004f

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
 * same values the built-in phase step uses (#3326) — or just call [reticleAlphaFor].
 */
const val RETICLE_SEARCHING_ALPHA = 0.35f

/**
 * Alpha applied to the ring while [ReticlePhase.READY] — a surface is under the cursor, but it is
 * an estimate: between "searching" and "locked", so the escalation reads as a ramp.
 */
const val RETICLE_READY_ALPHA = 0.6f

/**
 * Alpha applied to the ring while [ReticlePhase.LOCKED] — the brightest the cursor ever gets.
 *
 * 90 %, not 100 %: a fully opaque white ring over a camera frame reads as a sticker pasted on the
 * lens rather than a mark lying on the floor, and the last 10 % is what lets the contact halo
 * show through as a shadow.
 */
const val RETICLE_LOCKED_ALPHA = 0.9f

/**
 * How much of the ring's opacity the contact halo carries. Kept well under half: the halo is a
 * grounding cue, and a halo that reads as a disc is a second cursor.
 */
const val RETICLE_HALO_ALPHA_RATIO = 0.28f

/**
 * Maps a centre-screen hit to the reticle phase: no hit is [ReticlePhase.SEARCHING], a hit on a
 * tracked plane is [ReticlePhase.LOCKED], any other hit (point, depth point, instant placement)
 * is [ReticlePhase.READY].
 *
 * Takes booleans rather than an ARCore `HitResult` on purpose: the mapping is then unit-testable
 * without Compose, ARCore or a device. Call sites pass
 * `lockedOnPlane = hit?.trackable is Plane && trackable.trackingState == TRACKING`.
 *
 * @param hasHit       the centre-screen ray resolved to an acceptable hit this frame.
 * @param lockedOnPlane that hit is on a tracked [com.google.ar.core.Plane]. Ignored when
 *                      [hasHit] is `false`.
 */
@JvmOverloads
fun reticlePhaseFor(hasHit: Boolean, lockedOnPlane: Boolean = false): ReticlePhase = when {
    !hasHit -> ReticlePhase.SEARCHING
    lockedOnPlane -> ReticlePhase.LOCKED
    else -> ReticlePhase.READY
}

/** The ring/dot opacity for [phase] — a ramp from dim while searching to 90 % once locked. */
fun reticleAlphaFor(phase: ReticlePhase): Float = when (phase) {
    ReticlePhase.SEARCHING -> RETICLE_SEARCHING_ALPHA
    ReticlePhase.READY -> RETICLE_READY_ALPHA
    ReticlePhase.LOCKED -> RETICLE_LOCKED_ALPHA
}

/**
 * The centre-dot radius for [phase] in metres — `0` while [ReticlePhase.SEARCHING] (no dot at
 * all), a small achromatic dot on an estimated surface, a larger accented one once locked.
 */
fun reticleDotRadiusFor(phase: ReticlePhase): Float = when (phase) {
    ReticlePhase.SEARCHING -> 0f
    ReticlePhase.READY -> RING_HIT_DOT_RADIUS
    ReticlePhase.LOCKED -> RING_DOT_RADIUS
}

/**
 * The centre-dot colour for [phase]: [RETICLE_READY_ACCENT] in [ReticlePhase.LOCKED], the ring's
 * own achromatic [tint] otherwise. The single place the "colour only when locked" rule of #3570
 * is decided, so a test can pin it without a renderer.
 */
fun reticleDotColorFor(phase: ReticlePhase, tint: Color = RETICLE_TINT): Color =
    if (phase == ReticlePhase.LOCKED) RETICLE_READY_ACCENT else tint

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
 * @param phase          the current searching / hit / locked state — see [ReticlePhase] for
 *                       what each one draws.
 * @param tint           reticle hue; its alpha is overridden per [phase].
 * @param style          ring or disc geometry.
 * @param alpha          reticle opacity. Defaults to the step value [phase] implies
 *                       ([reticleAlphaFor]). Pass an
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
    val dotColor = reticleDotColorFor(phase, tint)
    val dotRadius = reticleDotRadiusFor(phase)

    // One material for the ring/disc body — recoloured in place per phase (no re-alloc).
    val bodyMaterial: MaterialInstance = remember(materialLoader, tint) {
        materialLoader.createUnlitColorInstance(tint.copy(alpha = alpha))
    }
    // The LOCKED centre dot is the only coloured mark on the cursor (#3570), so the dot needs its
    // own instance — the ring must stay achromatic whatever the phase does.
    val accentMaterial: MaterialInstance = remember(materialLoader) {
        materialLoader.createUnlitColorInstance(dotColor.copy(alpha = alpha))
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
        accentMaterial.setColor(dotColor.copy(alpha = alpha))
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
            // floor. Drawn in all three phases so the ring's silhouette never changes as the
            // state escalates; only its opacity and its centre dot do.
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
            // Centre dot only once a surface is acquired: small and white on an estimated hit,
            // larger and accented once locked on a tracked plane — the unambiguous "tap now"
            // signal, and the one coloured mark of the whole cursor (#3570).
            if (dotRadius > 0f) {
                CylinderNode(
                    radius = dotRadius,
                    height = RETICLE_HEIGHT,
                    sideCount = RETICLE_SIDES,
                    position = Position(y = RETICLE_LIFT),
                    materialInstance = accentMaterial,
                )
            }
        }
    }
}
