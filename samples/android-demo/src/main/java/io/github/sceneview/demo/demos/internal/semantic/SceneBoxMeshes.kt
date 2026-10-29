package io.github.sceneview.demo.demos.internal.semantic

import io.github.sceneview.demo.demos.internal.ArDebugGeometry
import io.github.sceneview.demo.demos.internal.DebugMesh
import io.github.sceneview.demo.demos.internal.ScanBox

/**
 * What the replay draws for the recognised parts of a room: every box's twelve edges in one mesh,
 * one upload for the lot. Pure, like [io.github.sceneview.demo.demos.internal.ScanObjectMeshes],
 * whose tubes these are.
 */
object SceneBoxMeshes {
    /** [boxes]' edges as square tubes of [radius], written into [into] after clearing it. */
    fun edges(boxes: List<ScanBox>, radius: Float, into: DebugMesh = DebugMesh()): DebugMesh {
        into.clear()
        for (box in boxes) {
            val corners = box.corners()
            for ((from, to) in ScanBox.EDGES) {
                val a = corners[from]
                val b = corners[to]
                val along = (b - a).normalized() * radius
                ArDebugGeometry.addSegment(into, a - along, b + along, radius)
            }
        }
        return into
    }

    /**
     * Whether [item] is in what the replay draws with its first [shown] surfels out of [all]: a
     * piece of furniture once every surfel it was fused from is, an opening — a hole, no surfels
     * of its own — once the whole scan is.
     */
    fun isShown(item: RecognizedItem, shown: Int, all: Int): Boolean =
        if (item.surfels.isEmpty()) shown >= all && all > 0 else item.surfels.max() < shown
}
