package io.github.sceneview.demo.demos

import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.common.qaStateOverridesAllowed
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.ScanBox
import io.github.sceneview.demo.demos.internal.semantic.RecognizedItem
import io.github.sceneview.demo.demos.internal.semantic.SceneLabel

/**
 * QA only (`--es qa_state recognized-crowd`): the emulator's camera sees no furniture, so a saved
 * scan's replay is handed six recognised parts standing close together — two beds side by side, a
 * chair at their foot, a sofa, a toilet and a table in one corner — as a Pixel 4a found them in a
 * flat, labels piled into one stack. The 3D Scan landing and its saved scans are as usual.
 */
internal object QaRecognizedLabels {
    const val STATE = "recognized-crowd"

    /** Whether this run hands the replay [items] instead of recognising the scan. */
    fun active(): Boolean = DemoSettings.qaDemoState == STATE && qaStateOverridesAllowed()

    /** The six parts, laid round the middle of [cloud]'s floor. */
    fun items(cloud: DenseCloud): List<RecognizedItem> {
        val p = cloud.positions
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (i in 0 until p.size / 3) {
            minX = minOf(minX, p[3 * i])
            maxX = maxOf(maxX, p[3 * i])
            minY = minOf(minY, p[3 * i + 1])
            minZ = minOf(minZ, p[3 * i + 2])
            maxZ = maxOf(maxZ, p[3 * i + 2])
        }
        if (minX > maxX) return emptyList()
        val cx = (minX + maxX) / 2
        val cz = (minZ + maxZ) / 2
        fun item(label: SceneLabel, sure: Float, dx: Float, dz: Float, l: Float, d: Float, h: Float) =
            RecognizedItem(label, ScanBox(cx + dx, cz + dz, minY, 0f, l, d, h), sure, views = 3, surfels = intArrayOf(0))
        return listOf(
            item(SceneLabel.Bed, 0.92f, -0.5f, 0f, 1.9f, 1.4f, 0.5f),
            item(SceneLabel.Bed, 0.81f, 0.5f, 0.1f, 1.9f, 0.9f, 0.5f),
            item(SceneLabel.Chair, 0.74f, 0f, 0.9f, 0.5f, 0.5f, 0.9f),
            item(SceneLabel.Sofa, 0.66f, -0.3f, -1.2f, 2.0f, 0.9f, 0.8f),
            item(SceneLabel.Toilet, 0.58f, 0.2f, -1.0f, 0.4f, 0.6f, 0.8f),
            item(SceneLabel.Table, 0.52f, 0.1f, -1.4f, 1.2f, 0.8f, 0.75f),
        )
    }
}
