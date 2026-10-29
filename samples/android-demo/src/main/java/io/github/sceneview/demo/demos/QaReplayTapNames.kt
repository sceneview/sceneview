package io.github.sceneview.demo.demos

import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.common.qaStateOverridesAllowed
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.ScanBox
import io.github.sceneview.demo.demos.internal.semantic.RecognizedItem
import io.github.sceneview.demo.demos.internal.semantic.SceneLabel

/**
 * QA only (`--es qa_state replay-tap-names`): the emulator has no detector to recognise the
 * ray-cast room `plan-synthetic` keeps, so its replay is handed what recognition finds in it — its
 * sofa and its table, boxed as a detector boxes them, not its cabinet. A tap on the sofa then
 * names its box, a tap on the cabinet measures it unnamed (#4485).
 */
internal object QaReplayTapNames {
    const val STATE = "replay-tap-names"

    /** Whether this run hands the replay [items] instead of recognising the scan. */
    fun active(): Boolean = DemoSettings.qaDemoState == STATE && qaStateOverridesAllowed()

    /** The sofa and the table of the ray-cast room, placed from [cloud]'s corner: its floor's lowest x and z. */
    fun items(cloud: DenseCloud): List<RecognizedItem> {
        val p = cloud.positions
        if (p.isEmpty()) return emptyList()
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        for (i in 0 until p.size / 3) {
            minX = minOf(minX, p[3 * i])
            minY = minOf(minY, p[3 * i + 1])
            minZ = minOf(minZ, p[3 * i + 2])
        }
        fun item(label: SceneLabel, sure: Float, x: Float, z: Float, l: Float, d: Float, h: Float) =
            RecognizedItem(label, ScanBox(minX + x, minZ + z, minY, 0f, l, d, h), sure, views = 4, surfels = intArrayOf(0))
        return listOf(
            item(SceneLabel.Sofa, 0.71f, 1.0f, 0.425f, 1.4f, 0.85f, 0.9f),
            item(SceneLabel.Table, 0.63f, 2.8f, 1.85f, 1.0f, 0.7f, 0.76f),
        )
    }
}
