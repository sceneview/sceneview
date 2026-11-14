package io.github.sceneview.demo.demos.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * A measured space written for whoever designs what goes in it, a person or an assistant: one
 * JSON object, every length in metres.
 *
 * ```json
 * {
 *   "v": 1,
 *   "kind": "space",
 *   "label": "Space · Nov 14, 5:40 PM",
 *   "units": "m",
 *   "frame": "right-handed, Y up; origin at the back-left-bottom corner; …",
 *   "dimensionSource": "scan",
 *   "resolutionM": 0.02,
 *   "interior": { "kind": "box", "sizeM": [0.6, 0.55, 0.5] },
 *   "open": ["front"],
 *   "obstacles": [{ "kind": "box", "minM": [0.26, 0.24, 0.1], "sizeM": [0.08, 0.28, 0.08] }],
 *   "fits": { "kind": "box", "minM": [0.01, 0, 0.2], "sizeM": [0.58, 0.54, 0.3] }
 * }
 * ```
 *
 * `sizeM` is `[width, height, depth]` along X, Y, Z and `minM` a box's lowest corner, both in
 * [FRAME]. `interior` is the inside of the space; `open` the faces no surface closes; `obstacles`
 * what stands inside, the biggest first; `fits` the biggest box that rests on the bottom and
 * slides in from the opening clear of the walls and of every obstacle, `null` when none does.
 * `dimensionSource` is where the numbers come from and `resolutionM` the grid of the scan they
 * were read off: it is not a measured accuracy.
 */
object SpaceAnswer {
    const val VERSION = 1
    const val KIND = "space"
    const val SOURCE = "scan"
    const val FRAME = "right-handed, Y up; origin at the back-left-bottom corner; " +
        "+X to the right seen from the opening; +Z from the back towards the opening"
    const val EXTENSION = "json"
    const val MIME_TYPE = "application/json"

    private val pretty = Json { prettyPrint = true }

    /** [fit] as its JSON text, named [label]. */
    fun json(fit: SpaceFit, label: String, resolutionM: Float = SpaceFitter.DEFAULT_VOXEL_M): String =
        pretty.encodeToString(JsonObject.serializer(), tree(fit, label, resolutionM))

    /** [fit] as its JSON object, named [label]. */
    fun tree(fit: SpaceFit, label: String, resolutionM: Float = SpaceFitter.DEFAULT_VOXEL_M): JsonObject = buildJsonObject {
        put("v", VERSION)
        put("kind", KIND)
        put("label", label)
        put("units", "m")
        put("frame", FRAME)
        put("dimensionSource", SOURCE)
        put("resolutionM", metres(resolutionM))
        put("interior", box(null, floatArrayOf(fit.width, fit.height, fit.depth)))
        put("open", JsonArray(SpaceFace.entries.filter { it in fit.open }.map { JsonPrimitive(it.key) }))
        put("obstacles", JsonArray(fit.obstacles.map(::box)))
        put("fits", fit.fits?.let(::box) ?: JsonNull)
    }

    private fun box(of: SpaceBox): JsonObject =
        box(floatArrayOf(of.x, of.y, of.z), floatArrayOf(of.width, of.height, of.depth))

    private fun box(min: FloatArray?, size: FloatArray): JsonObject = buildJsonObject {
        put("kind", "box")
        if (min != null) put("minM", JsonArray(min.map(::metres)))
        put("sizeM", JsonArray(size.map(::metres)))
    }

    /** [value] to the millimetre, written as the decimal it reads as (`0.6`, never `0.60000002`). */
    private fun metres(value: Float): JsonPrimitive = JsonPrimitive((value * MM_PER_M).roundToInt() / MM_PER_M.toDouble())

    private const val MM_PER_M = 1000f
}
