package io.github.sceneview.demo.demos.internal

import kotlin.math.cos
import kotlin.math.sin

/**
 * A space's measure as drawn on its model: the [inside]'s edges, the edges of each thing in the
 * way ([obstacles]), and the box that fits, its edges ([boxEdges]) and its faces ([boxFaces]).
 * In the scan's world, metres.
 */
class SpaceFitMeshes(val inside: DebugMesh, val obstacles: DebugMesh, val boxEdges: DebugMesh, val boxFaces: DebugMesh)

/** Builds a [SpaceFit]'s [SpaceFitMeshes]. Plain geometry: off the main thread. */
object SpaceFitDrawing {
    /** [fit]'s meshes, their edges [radius] in half-width. */
    fun meshes(fit: SpaceFit, radius: Float): SpaceFitMeshes {
        val inside = DebugMesh().also { edges(it, fit, SpaceBox(0f, 0f, 0f, fit.width, fit.height, fit.depth), radius) }
        val obstacles = DebugMesh().also { mesh -> fit.obstacles.forEach { edges(mesh, fit, it, radius * THIN) } }
        val boxEdges = DebugMesh()
        val boxFaces = DebugMesh()
        fit.fits?.let {
            edges(boxEdges, fit, it, radius)
            faces(boxFaces, fit, it)
        }
        return SpaceFitMeshes(inside, obstacles, boxEdges, boxFaces)
    }

    /** [box]'s eight corners in the world: bit 0 set at its far x, bit 1 at its far y, bit 2 at its far z. */
    fun corners(fit: SpaceFit, box: SpaceBox): List<Vec3> = List(CORNERS) { i ->
        fit.world(
            box.x + if (i and 1 != 0) box.width else 0f,
            box.y + if (i and 2 != 0) box.height else 0f,
            box.z + if (i and 4 != 0) box.depth else 0f,
        )
    }

    /**
     * The frame [fit]'s result is shown in ([ScanResultFrame]): its inside and [FRAME_MARGIN_M] of
     * what stands round it, seen from its opening. What was measured is what is looked at, not
     * the whole of a scan that holds the wall behind it too.
     */
    fun frame(fit: SpaceFit): ScanResultFrame {
        // A ScanResultFrame turns the other way about Y than SpaceFit.world: the same axes, the yaw's sign changed.
        val yaw = Math.toRadians(fit.yawDegrees.toDouble())
        val c = cos(yaw).toFloat()
        val s = sin(yaw).toFloat()
        val a = fit.originX * c - fit.originZ * s
        val b = fit.originX * s + fit.originZ * c
        return ScanResultFrame(
            yawDegrees = -fit.yawDegrees,
            local = floatArrayOf(
                a - FRAME_MARGIN_M, fit.originY, b - FRAME_MARGIN_M,
                a + fit.width + FRAME_MARGIN_M, fit.originY + fit.height + FRAME_MARGIN_M, b + fit.depth + FRAME_MARGIN_M,
            ),
            // The eye towards +b, which is the space's +Z: its opening.
            frontDegrees = 0f,
            turn = 1f,
        )
    }

    private fun edges(mesh: DebugMesh, fit: SpaceFit, box: SpaceBox, radius: Float) {
        val at = corners(fit, box)
        for (i in 0 until CORNERS) {
            for (bit in AXES) {
                if (i and bit != 0) continue
                // Run past both corners by its own half-width: three edges close a corner.
                val along = (at[i or bit] - at[i]).normalized() * radius
                ArDebugGeometry.addSegment(mesh, at[i] - along, at[i or bit] + along, radius)
            }
        }
    }

    private fun faces(mesh: DebugMesh, fit: SpaceFit, box: SpaceBox) {
        val at = corners(fit, box).map { mesh.vertex(it) }
        for (face in FACES) mesh.quad(at[face[0]], at[face[1]], at[face[2]], at[face[3]])
    }

    private const val CORNERS = 8
    private val AXES = intArrayOf(1, 2, 4)

    /** Each face's corners, in turn round it: back and front, bottom and top, left and right. */
    private val FACES = arrayOf(
        intArrayOf(0, 1, 3, 2), intArrayOf(4, 6, 7, 5),
        intArrayOf(0, 4, 5, 1), intArrayOf(2, 3, 7, 6),
        intArrayOf(0, 2, 6, 4), intArrayOf(1, 5, 7, 3),
    )

    /** How much of what stands round a space its result shows with it. */
    const val FRAME_MARGIN_M = 0.1f

    /** A thing in the way is drawn thinner than the inside and the box: it explains them. */
    private const val THIN = 0.6f
}
