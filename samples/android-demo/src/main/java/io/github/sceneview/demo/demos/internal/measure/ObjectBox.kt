package io.github.sceneview.demo.demos.internal.measure

import kotlin.math.sqrt

/** Metres in a right-handed, Y-up coordinate system. No renderer or AR dependencies. */
data class BoxPoint(val x: Double, val y: Double, val z: Double) {
    init { require(listOf(x, y, z).all { it.isFinite() }) }
    operator fun plus(p: BoxPoint) = BoxPoint(x + p.x, y + p.y, z + p.z)
    operator fun minus(p: BoxPoint) = BoxPoint(x - p.x, y - p.y, z - p.z)
    operator fun times(s: Double) = BoxPoint(x * s, y * s, z * s)
    fun dot(p: BoxPoint) = x * p.x + y * p.y + z * p.z
    fun length() = sqrt(dot(this))
}

enum class ObjectBoxError { TOO_CLOSE, TOO_LARGE, BELOW_SUPPORT, INVALID_SUPPORT }
class ObjectBoxException(val reason: ObjectBoxError) : IllegalArgumentException(reason.name)

data class BoxSupport(val origin: BoxPoint, val normal: BoxPoint) {
    val unitNormal: BoxPoint = normal.length().let {
        if (it < 1e-6) throw ObjectBoxException(ObjectBoxError.INVALID_SUPPORT)
        normal * (1.0 / it)
    }
    fun height(point: BoxPoint) = (point - origin).dot(unitNormal)
    fun project(point: BoxPoint) = point - unitNormal * height(point)
}

/** A rectangular footprint; the third point defines its signed perpendicular width. */
data class ObjectBox(val base: List<BoxPoint>, val lengthM: Double, val widthM: Double, val heightM: Double) {
    fun vertices(normal: BoxPoint): List<BoxPoint> = base + base.map { it + normal * heightM }

    companion object {
        // Smaller dimensions would not be meaningful with the uncalibrated centimetre estimate.
        const val MIN_SIZE_M = 0.015
        const val MAX_SIZE_M = 10.0
        private fun checkSize(value: Double) {
            if (value < MIN_SIZE_M) throw ObjectBoxException(ObjectBoxError.TOO_CLOSE)
            if (value > MAX_SIZE_M) throw ObjectBoxException(ObjectBoxError.TOO_LARGE)
        }
        fun along(support: BoxSupport, first: BoxPoint, second: BoxPoint): Double =
            (support.project(second) - support.project(first)).length().also {
                checkSize(it)
            }

        fun fromPoints(support: BoxSupport, first: BoxPoint, second: BoxPoint, across: BoxPoint, top: BoxPoint? = null): ObjectBox {
            val a = support.project(first)
            val b = support.project(second)
            val length = along(support, first, second)
            val direction = (b - a) * (1.0 / length)
            val offset = support.project(across) - a
            val perpendicular = offset - direction * offset.dot(direction)
            val width = perpendicular.length()
            checkSize(width)
            val height = top?.let(support::height) ?: 0.0
            if (height < 0) throw ObjectBoxException(ObjectBoxError.BELOW_SUPPORT)
            if (top != null) checkSize(height)
            return ObjectBox(listOf(a, b, b + perpendicular, a + perpendicular), length, width, height)
        }
    }
}
