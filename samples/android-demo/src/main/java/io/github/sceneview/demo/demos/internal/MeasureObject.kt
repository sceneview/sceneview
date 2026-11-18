package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal enum class ObjectDimension(val symbol: String) { Length("L"), Width("W"), Height("H") }
internal enum class ObjectMeasureStage { Prepare, Capture, Review }
internal enum class ObjectMeasureProvenance { Measured, Manual }

internal data class ObjectMeasureValue(val millimeters: Double, val provenance: ObjectMeasureProvenance) {
    init { require(millimeters.isFinite() && millimeters >= 0.0) }
}

internal data class ObjectMeasureDimension<P>(
    val endpoints: List<P> = emptyList(),
    val value: ObjectMeasureValue? = null,
)

/** Independent two-endpoint dimensions: no plane constraints, inferred corners or box fit. */
internal data class ObjectMeasure<P>(
    val stage: ObjectMeasureStage = ObjectMeasureStage.Prepare,
    val selected: ObjectDimension = ObjectDimension.Length,
    val dimensions: Map<ObjectDimension, ObjectMeasureDimension<P>> = emptyMap(),
) {
    fun dimension(axis: ObjectDimension = selected) = dimensions[axis] ?: ObjectMeasureDimension()
    val endpoints: List<P> get() = dimensions.values.flatMap { it.endpoints }
    val complete: Boolean get() = ObjectDimension.entries.all { dimension(it).value != null }
    fun start() = copy(stage = ObjectMeasureStage.Capture)
    fun select(axis: ObjectDimension) = copy(selected = axis, stage = ObjectMeasureStage.Capture)

    /** A missing or invalid endpoint never advances the workflow. Values lock on the second tap. */
    fun capture(endpoint: P?, position: (P) -> Position?): ObjectMeasure<P> {
        if (stage != ObjectMeasureStage.Capture || endpoint == null) return this
        val current = dimension()
        if (current.value != null || current.endpoints.size >= 2) return this
        val end = position(endpoint)?.takeIf { it.finite() } ?: return this
        val first = current.endpoints.firstOrNull()
        val value = if (first != null) {
            val start = position(first)?.takeIf { it.finite() } ?: return this
            val mm = measureDistanceMeters(start, end).toDouble() * 1000.0
            if (!mm.isFinite() || mm <= 0.0) return this
            ObjectMeasureValue(mm, ObjectMeasureProvenance.Measured)
        } else null
        return replace(selected, current.copy(endpoints = current.endpoints + endpoint, value = value))
    }

    fun undo(axis: ObjectDimension = selected): ObjectMeasure<P> {
        val current = dimension(axis)
        return replace(axis, current.copy(endpoints = current.endpoints.dropLast(1), value = null)).select(axis)
    }

    fun repeat(axis: ObjectDimension) = replace(axis, ObjectMeasureDimension()).select(axis)

    fun correct(axis: ObjectDimension, millimeters: Double): ObjectMeasure<P> = replace(
        axis, dimension(axis).copy(value = ObjectMeasureValue(millimeters, ObjectMeasureProvenance.Manual)),
    )

    fun next(): ObjectMeasure<P> {
        if (dimension().value == null) return this
        val missing = ObjectDimension.entries.firstOrNull { dimension(it).value == null }
        return if (missing == null) copy(stage = ObjectMeasureStage.Review) else select(missing)
    }

    private fun replace(axis: ObjectDimension, dimension: ObjectMeasureDimension<P>) =
        copy(dimensions = dimensions + (axis to dimension))
}

private fun Position.finite() = x.isFinite() && y.isFinite() && z.isFinite()

/** Accept a decimal comma as well as a decimal point, but never NaN, infinity or negative sizes. */
internal fun parseObjectMillimeters(text: String): Double? =
    text.trim().replace(',', '.').takeIf { it.matches(Regex("[0-9]+(?:\\.[0-9]+)?")) }
        ?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }

/** Depth only: a support plane or feature fallback cannot masquerade as an object endpoint. */
internal fun <T> objectMeasureDepthHit(depthPoint: T?, depthImage: () -> T?): T? = depthPoint ?: depthImage()

/** Freshness and consumption are separate from rendering, which may keep its previous marker. */
internal class ObjectMeasureCandidate<T> {
    private var candidate: T? = null
    private var observedAtMillis = 0L
    fun update(value: T?, nowMillis: Long) {
        candidate = value
        observedAtMillis = nowMillis
    }
    fun consume(nowMillis: Long): T? {
        val fresh = candidate.takeIf { nowMillis - observedAtMillis in 0..250 }
        candidate = null
        return fresh
    }
}

/** V1 exports dimensions only; the object frame is a convention, not a recovered pose. */
internal fun objectMeasureJson(values: Map<ObjectDimension, ObjectMeasureValue>, device: String): String =
    buildJsonObject {
        put("schema", "sceneview.measure/1")
        put("units", "mm")
        putJsonObject("frame") {
            put("origin", "bottom-face centre")
            put("axes", JsonArray(listOf("length", "width", "height").map(::JsonPrimitive)))
            put("handedness", "right")
            put("status", "convention-only")
        }
        putJsonObject("dimensions") {
            ObjectDimension.entries.forEach { axis ->
                putJsonObject(axis.symbol) {
                    val value = values[axis]
                    put("value", value?.let { JsonPrimitive(it.millimeters) } ?: JsonNull)
                    put("u95", JsonNull)
                    put("provenance", value?.let {
                        JsonPrimitive(if (it.provenance == ObjectMeasureProvenance.Manual) "manual" else "measured")
                    } ?: JsonNull)
                }
            }
        }
        put("features", JsonArray(emptyList()))
        putJsonObject("quality") {
            put("method", "guided-two-endpoint-depth")
            put("device", device)
            put("uncertainty_status", "uncalibrated")
            put("scale_verified", false)
            put("surface_coverage", "not-assessed")
            put("unseen_surfaces", JsonArray(listOf(JsonPrimitive("underside"))))
        }
        put("mesh", JsonNull)
    }.toString()
