package io.github.sceneview.demo.demos.internal

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.math.Position
import io.github.sceneview.verticalFovDegreesForFocalLength
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Layout and camera framing of the Multi-Model "park" formation
 * ([io.github.sceneview.demo.demos.ModelViewerDemo]'s Multi-Model section).
 *
 * This lives beside [DemoMath] rather than inside the composable file for one reason: the framing
 * is DERIVED from the layout ([PARK_BOUNDS] / [PARK_HEIGHT] are computed from [PARK_SLOTS]), and that
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
 * this value and stand lower in the frame.
 */
internal val PARK_HEIGHT: Float = PARK_SLOTS.maxOf { it.scale }

/**
 * An axis-aligned box in world metres.
 *
 * @see PARK_BOUNDS
 */
internal data class ParkBounds(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
    val minZ: Float,
    val maxZ: Float,
) {
    val center: Position get() = Position((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f)
    val extentX: Float get() = maxX - minX
    val extentY: Float get() = maxY - minY
    val extentZ: Float get() = maxZ - minZ
}

/**
 * The box the formation occupies at rest, known before any model loads (#3923).
 *
 * Each slot's model is normalised into a cube of side `scale` (`scaleToUnits`), centred on the
 * slot's x / z and standing on the ground plane at `y = -PARK_HEIGHT / 2` (`centerOrigin`). The
 * union of those cubes bounds whatever the registry puts in the slots: the streamed trees of a
 * keyed build, or the bundled lantern, lantern, shiba and soldier that stand in without a
 * Sketchfab key. So the camera is placed from it on the first frame and never has to move when the
 * models land. A model narrower than its cube (the lantern) leaves margin, never overflow.
 *
 * "Spin scene" turns the formation around the world origin. The back slot sits 0.2 m off it, so
 * during a spin the corners of its cube can brush the frame edges. That is accepted for a spin the
 * user starts; framing the whole sweep would push the camera back for the resting view too.
 */
internal val PARK_BOUNDS: ParkBounds = ParkBounds(
    minX = PARK_SLOTS.minOf { it.x - it.scale / 2f },
    maxX = PARK_SLOTS.maxOf { it.x + it.scale / 2f },
    minY = -PARK_HEIGHT / 2f,
    maxY = PARK_SLOTS.maxOf { -PARK_HEIGHT / 2f + it.scale },
    minZ = PARK_SLOTS.minOf { it.z - it.scale / 2f },
    maxZ = PARK_SLOTS.maxOf { it.z + it.scale / 2f },
)

/** SceneView's default lens. `CameraNode._focalLength` = 28 mm ⇒ ≈46.4° vertical FOV. */
internal const val PARK_FOCAL_LENGTH_MM = 28.0

/** The Park's opening camera: eye and orbit target in world metres, and their distance. */
internal data class ParkCamera(val eye: Position, val target: Position, val distance: Float)

/**
 * Where the Park's camera opens (#3923): the whole of [PARK_BOUNDS] inside the band the chrome
 * leaves visible, through the same [DemoMath.viewerFraming] fit as the single-model viewer.
 *
 * - **Fit, not cover.** Until #3923 the distance *covered* the frame with the tallest model
 *   (#2913), about 2.1 m on a phone. With a Sketchfab key that put the lens inside the trees'
 *   canopies. Without one, the two lanterns filled the frame and the shiba and the soldier were out
 *   of view. Fitting the box keeps every slot in view in portrait and landscape; the fitted
 *   distance is then backed off until all eight corners of the box project inside the band.
 * - **The target is the box centre**, so the camera orbits around the middle of the formation.
 *   The fit's margins absorb the small difference between the top and bottom chrome bands, so the
 *   target is not shifted to centre the band the way the single-model viewer's is.
 * - **The eye is pitched [DemoMath.VIEWER_PITCH_DEGREES] above**, like the single-model viewer, so
 *   the front row reads in front of the back model rather than against it.
 * - [distanceOverride] (the `camera_distance` extra) replaces the fitted distance, in the same
 *   direction. A null, non-finite or non-positive override is ignored.
 *
 * Sizes are in any one unit (the caller passes dp); only their ratios matter.
 */
internal fun parkCamera(
    viewportWidth: Float,
    viewportHeight: Float,
    topInset: Float,
    bottomInset: Float,
    distanceOverride: Float? = null,
): ParkCamera {
    val framing = DemoMath.viewerFraming(
        extentX = PARK_BOUNDS.extentX,
        extentY = PARK_BOUNDS.extentY,
        extentZ = PARK_BOUNDS.extentZ,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        topInset = topInset,
        bottomInset = bottomInset,
        verticalFovDegrees = verticalFovDegreesForFocalLength(PARK_FOCAL_LENGTH_MM),
    )
    val halfTan = tan(Math.toRadians(verticalFovDegreesForFocalLength(PARK_FOCAL_LENGTH_MM)) / 2.0).toFloat()
    val aspect = if (viewportWidth > 0f && viewportHeight > 0f) viewportWidth / viewportHeight else 1f
    // The fit's depth allowance is tuned for a single model's silhouette; the front corners of the
    // hero's cube stand closer than that. Back off until every corner is in the band, so the
    // guarantee holds for any asset that fits its slot.
    val fitted = generateSequence(framing.distance) { it * CORNER_FIT_STEP }
        .take(CORNER_FIT_MAX_STEPS)
        .firstOrNull { cornersInBand(it, halfTan, aspect, viewportHeight, topInset, bottomInset) }
        ?: framing.distance
    val distance = distanceOverride?.takeIf { it.isFinite() && it > 0f } ?: fitted
    return ParkCamera(eye = parkEye(distance), target = PARK_BOUNDS.center, distance = distance)
}

/** Each corner-fit step backs the camera off by 1 %. */
private const val CORNER_FIT_STEP = 1.01f

/** Enough 1 % steps to double the distance — far more than any viewport needs. */
private const val CORNER_FIT_MAX_STEPS = 70

/** The eye [distance] from [PARK_BOUNDS]' centre, pitched [DemoMath.VIEWER_PITCH_DEGREES] above it. */
private fun parkEye(distance: Float): Position {
    val pitch = Math.toRadians(DemoMath.VIEWER_PITCH_DEGREES.toDouble())
    val target = PARK_BOUNDS.center
    return Position(
        target.x,
        target.y + distance * sin(pitch).toFloat(),
        target.z + distance * cos(pitch).toFloat(),
    )
}

/**
 * Whether all eight corners of [PARK_BOUNDS], seen from [parkEye] at [distance], project inside
 * the frame horizontally and inside the band between the top and bottom insets vertically.
 */
private fun cornersInBand(
    distance: Float,
    halfTan: Float,
    aspect: Float,
    viewportHeight: Float,
    topInset: Float,
    bottomInset: Float,
): Boolean {
    val eye = parkEye(distance)
    val target = PARK_BOUNDS.center
    val forward = normalize(target - eye)
    val right = normalize(cross(forward, Float3(0f, 1f, 0f)))
    val up = cross(right, forward)
    val height = if (viewportHeight > 0f) viewportHeight else 1f
    val bandTop = 1f - 2f * topInset.coerceAtLeast(0f) / height
    val bandBottom = -1f + 2f * bottomInset.coerceAtLeast(0f) / height
    for (x in floatArrayOf(PARK_BOUNDS.minX, PARK_BOUNDS.maxX)) {
        for (y in floatArrayOf(PARK_BOUNDS.minY, PARK_BOUNDS.maxY)) {
            for (z in floatArrayOf(PARK_BOUNDS.minZ, PARK_BOUNDS.maxZ)) {
                val v = Float3(x, y, z) - eye
                val depth = dot(v, forward)
                if (depth <= DemoMath.DEFAULT_NEAR_PLANE) return false
                val ndcX = dot(v, right) / (depth * halfTan * aspect)
                val ndcY = dot(v, up) / (depth * halfTan)
                if (abs(ndcX) > 1f || ndcY > bandTop || ndcY < bandBottom) return false
            }
        }
    }
    return true
}
