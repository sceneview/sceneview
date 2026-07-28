package io.github.sceneview.demo.demos.internal

import io.github.sceneview.verticalFovDegreesForFocalLength
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Layout and camera framing of the Multi-Model "park" formation
 * ([io.github.sceneview.demo.demos.ModelViewerDemo]'s Multi-Model section).
 *
 * This lives beside [DemoMath] rather than inside the composable file for one reason: the framing
 * is DERIVED from the layout ([PARK_SPAN] / [PARK_HEIGHT] are computed from [PARK_SLOTS]), and that
 * derivation is only a real guarantee if a JVM unit test can assert it. While these declarations
 * were `private` to the composable file, the tests had to restate `2.2` / `1.8` as literals — so
 * editing the layout left every test green while the assertions described a formation that no
 * longer existed (#2913).
 */

/**
 * One model's fixed place in the formation, in metres.
 *
 * [x] / [z] are offsets from the formation centre — which is the world origin, since the section
 * renders with `autoCenterContent = false` — and [scale] is the `scaleToUnits` cube the model is
 * normalised into.
 *
 * [uid] is the `SampleAssets` entry that occupies the slot. It rides on the slot rather than in a
 * parallel list so the layout, the loader and the chip label are all indexed by one thing — a slot
 * can never end up labelled with another slot's model (#2933). The framing below reads only
 * [x] / [z] / [scale]: which model stands in a slot has no bearing on where the camera goes.
 */
internal data class ParkSlot(val uid: String, val x: Float, val z: Float, val scale: Float)

/**
 * The four `park` slots, back row first. Order matches the visibility chips.
 *
 * The uids are looked up in `SampleAssets` by identity, so re-ordering the registry moves nothing
 * here. What the registry DOES decide is which model — and therefore which chip label — each slot
 * gets; the slots themselves only say where a model stands and how big it is drawn.
 */
internal val PARK_SLOTS = listOf(
    ParkSlot(uid = "d841c3bcc5324daebee50f45619e05fc", x = 0.0f, z = -0.2f, scale = 1.80f),
    ParkSlot(uid = "6d1aeea748f147789004bc03e1930d32", x = 0.0f, z = 0.2f, scale = 0.65f),
    ParkSlot(uid = "4f6ab5594a8a415aba3f958682b9ced5", x = -0.55f, z = 0.2f, scale = 0.40f),
    ParkSlot(uid = "fd582b0d4a8c4af1a1b5c4f21a481c93", x = 0.55f, z = 0.2f, scale = 0.15f),
)

/**
 * Height of the formation. Every slot is bottom-aligned onto a shared ground plane, so the union is
 * exactly as tall as the tallest model.
 *
 * **Precondition this bound assumes:** `scaleToUnits` normalises a model's LARGEST bounding-box
 * axis into a cube of side `scale`, so `scale` is the model's height only when it is taller than it
 * is wide or deep. That holds for the hero slot's assets (a streamed oak, or the bundled lantern
 * that stands in without a Sketchfab key). A hero that is wider than tall would render shorter than
 * this value and sit low in the frame with backdrop above it.
 */
internal val PARK_HEIGHT: Float = PARK_SLOTS.maxOf { it.scale }

/**
 * Width the formation sweeps out as it spins.
 *
 * Each slot orbits the centre at radius `hypot(x, z)` and additionally spins on its own Y axis, so
 * a model that fills its `scale` cube reaches `scale · √2 / 2` from its own centre at 45° — not
 * `scale / 2`. The farthest any part of the formation ever reaches from the centre is therefore
 * `radius + scale · √2 / 2`, and twice that is the width the camera has to cover for the frame to
 * stay full of models at every phase of the spin.
 *
 * In portrait this term never binds (the vertical one wins by a wide margin) — it decides the
 * framing only on a landscape / foldable viewport.
 */
internal val PARK_SPAN: Float = 2f * PARK_SLOTS.maxOf { hypot(it.x, it.z) + it.scale * sqrt(2f) / 2f }

/** SceneView's default lens. `CameraNode._focalLength` = 28 mm ⇒ ≈46.4° vertical FOV. */
internal const val PARK_FOCAL_LENGTH_MM = 28.0

/**
 * Fraction of the frame height the formation spans. `1f` = the hero model runs edge to edge — the
 * subject reads at Play Store thumbnail size instead of sitting on a shelf in the middle.
 */
internal const val PARK_FRAME_FILL = 1.0f

/** Camera height, level with the mid-height of the tallest model (the ground plane is below). */
internal const val PARK_EYE_HEIGHT = 0.0f

/**
 * Aspect used when the viewport has not been measured yet (a zero / infinite constraint on the very
 * first composition). A portrait phone — the most common case, and the shape the demo was
 * originally framed for.
 */
internal const val PARK_FALLBACK_ASPECT = 0.47f

/**
 * Camera distance for the park formation at a given viewport [aspect] (`width / height`), in
 * metres.
 *
 * **Cover, not fit.** The formation is wider than it is tall ([PARK_SPAN] against [PARK_HEIGHT] =
 * 1.8 m), so fitting all of it inside a portrait frame would park the camera metres back and shrink
 * the models to a strip across the middle. [DemoMath.coverDistance] instead fills the frame on both
 * axes and lets the neighbouring models be cropped by the edges.
 *
 * **Why the aspect matters at all (#2913).** Filament derives the vertical FOV from the focal
 * length against a fixed 24 mm sensor height, so the vertical framing is aspect-invariant and a
 * wider viewport simply reveals more world to the left and right at the same distance. On a phone
 * (~0.47 w/h) and a tablet (~0.64) the vertical term wins and this returns the same distance — both
 * frame the formation full height, and the tablet's extra width lands on the neighbouring models.
 * On a landscape / foldable viewport the horizontal term takes over and pulls the camera in so the
 * formation still spans the width rather than trailing off into the backdrop.
 */
internal fun parkCameraDistance(aspect: Float): Float = DemoMath.coverDistance(
    contentWidth = PARK_SPAN,
    contentHeight = PARK_HEIGHT,
    verticalFovDegrees = verticalFovDegreesForFocalLength(PARK_FOCAL_LENGTH_MM),
    aspect = aspect,
    fill = PARK_FRAME_FILL,
)
