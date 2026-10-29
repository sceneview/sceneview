package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.room.RoomFigure
import io.github.sceneview.demo.demos.internal.room.RoomStructure
import io.github.sceneview.demo.demos.internal.room.RoomWall
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A room the structure engine could have read: walls between [corners] (x, z pairs in the scan's
 * frame, in order around the room), every wall seen whole, [height] tall, standing on [floorY],
 * squared at [yaw]. Its figures are exact.
 */
internal fun structureOf(vararg corners: Float, yaw: Float = 0f, floorY: Float = 0f, height: Float = 2.5f): RoomStructure {
    val n = corners.size / 2
    val cx = (0 until n).sumOf { corners[it * 2].toDouble() }.toFloat() / n
    val cz = (0 until n).sumOf { corners[it * 2 + 1].toDouble() }.toFloat() / n
    val walls = List(n) { i ->
        val j = (i + 1) % n
        val ax = corners[i * 2]
        val az = corners[i * 2 + 1]
        val bx = corners[j * 2]
        val bz = corners[j * 2 + 1]
        val length = hypot(bx - ax, bz - az)
        // The normal of the two that points into the room.
        var nx = -(bz - az) / length
        var nz = (bx - ax) / length
        if (nx * (cx - (ax + bx) / 2) + nz * (cz - (az + bz) / 2) < 0) {
            nx = -nx
            nz = -nz
        }
        RoomWall(
            ax = ax, az = az, bx = bx, bz = bz, nx = nx, nz = nz,
            length = RoomFigure(length, 0.01f),
            seenShare = 1f, seen = true, deduced = false, unseen = emptyList(),
            height = height, openings = emptyList(),
        )
    }
    var twice = 0f
    for (i in 0 until n) {
        val j = (i + 1) % n
        twice += corners[i * 2] * corners[j * 2 + 1] - corners[j * 2] * corners[i * 2 + 1]
    }
    val c = cos(yaw)
    val s = sin(yaw)
    val u = (0 until n).map { c * corners[it * 2] + s * corners[it * 2 + 1] }
    val v = (0 until n).map { -s * corners[it * 2] + c * corners[it * 2 + 1] }
    val extentU = u.max() - u.min()
    val extentV = v.max() - v.min()
    val area = abs(twice) / 2
    return RoomStructure(
        walls = walls,
        floorY = floorY,
        floorThickness = 0.01f,
        yaw = yaw,
        ceilingHeight = RoomFigure(height, 0.01f),
        area = RoomFigure(area, 0.01f),
        perimeter = RoomFigure(walls.sumOf { it.length.value.toDouble() }.toFloat(), 0.01f),
        volume = RoomFigure(area * height, 0.01f),
        length = RoomFigure(maxOf(extentU, extentV), 0.01f),
        width = RoomFigure(minOf(extentU, extentV), 0.01f),
        confidence = 1f,
        gaps = emptyList(),
    )
}

/** [structureOf]'s room laid on the page, as the plan and the result read it. */
internal fun structuredSurvey(vararg corners: Float, yaw: Float = 0f, floorY: Float = 0f, height: Float = 2.5f): RoomSurvey =
    RoomSurveyor.of(structureOf(*corners, yaw = yaw, floorY = floorY, height = height))

/** The corners of a [width] × [depth] room squared at [yaw], its own frame shifted by ([u0], [v0]): x, z pairs. */
internal fun turnedRoom(width: Float, depth: Float, yaw: Float, u0: Float = 0f, v0: Float = 0f): FloatArray {
    val c = cos(yaw)
    val s = sin(yaw)
    val own = floatArrayOf(u0, v0, u0 + width, v0, u0 + width, v0 + depth, u0, v0 + depth)
    return FloatArray(8) { k ->
        val a = own[k / 2 * 2]
        val b = own[k / 2 * 2 + 1]
        if (k % 2 == 0) a * c - b * s else a * s + b * c
    }
}
