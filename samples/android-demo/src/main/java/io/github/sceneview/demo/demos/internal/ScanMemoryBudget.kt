package io.github.sceneview.demo.demos.internal

import kotlin.math.sqrt

/**
 * Safety net for devices still below 384 MiB after the demo requests a large heap.
 * Small heaps share 35% between TSDF, dense points, journal and live observation indices.
 * At 384 MiB and above, the pre-budget TSDF cap and preset voxels stay unchanged, and the
 * dense cap reaches [DenseFusion.MAX_BUDGET]. Full model detail is kept; replay history is still thinned.
 * Journal and observation allowances grow with the heap (4 MiB each at 256 MiB, 8 MiB at
 * 512 MiB); 4.69.7 had no journal byte bound.
 * Replay-critical journal metadata is never discarded, even if it alone exceeds its allowance.
 */
internal class ScanMemoryBudget(val maxHeapBytes: Long) {
    val journalBytes: Long = scaledTimelineBytes(ArDebugJournal.MAX_BYTES)
    val observationBytes: Long = scaledTimelineBytes(ArDebugTrace.MAX_OBSERVATION_BYTES)
    private val standardDensePoints = DenseFusion.budgetFor(maxHeapBytes)
    private val standardBytes = RerunTsdf.MAX_BYTES + standardDensePoints * DENSE_BYTES_PER_POINT
    private val reserve = journalBytes + observationBytes + TSDF_INDEX_BYTES
    val totalBytes: Long = if (maxHeapBytes < FULL_QUALITY_HEAP_BYTES) {
        maxHeapBytes * SHARE_PERCENT / 100
    } else {
        maxOf(maxHeapBytes * SHARE_PERCENT / 100, standardBytes + reserve)
    }
    private val available = (totalBytes - reserve).coerceAtLeast(0)
    private val scale = minOf(1.0, available.toDouble() / standardBytes)

    val tsdfBytes: Long = (RerunTsdf.MAX_BYTES * scale).toLong()
    val densePoints: Int = (standardDensePoints * scale).toInt().coerceAtLeast(1)
    val denseBytes: Long get() = densePoints * DENSE_BYTES_PER_POINT
    val retainedBytes: Long get() = tsdfBytes + denseBytes + reserve

    fun modelVoxelM(preset: ScanPreset): Float = if (preset == ScanPreset.OBJECT) {
        preset.modelVoxelM
    } else {
        preset.modelVoxelM * sqrt(RerunTsdf.MAX_BYTES.toDouble() / tsdfBytes.coerceAtLeast(1)).toFloat()
    }

    private fun scaledTimelineBytes(standard: Long): Long =
        (standard * maxHeapBytes / TIMELINE_REFERENCE_HEAP_BYTES)
            .coerceAtLeast(ArDebugTrace.MAX_MAP_POINTS * 4L)

    companion object {
        const val SHARE_PERCENT = 35
        const val DENSE_BYTES_PER_POINT = 192L
        const val TSDF_INDEX_BYTES = 2L * 1024 * 1024
        const val FULL_QUALITY_HEAP_BYTES = 384L * 1024 * 1024
        private const val TIMELINE_REFERENCE_HEAP_BYTES = 256L * 1024 * 1024
    }
}
