package io.github.sceneview.demo.demos.internal

import kotlin.math.sqrt

/**
 * The three large, retained stores of a live scan share at most 35% of [maxHeapBytes]
 * (`Runtime.maxMemory()`), leaving the rest for ARCore, photos, transient copies and finishing.
 * This is a retained-storage budget, not a bound on the process's transient heap usage.
 *
 * Reserve the journal's 4 MiB and 2 MiB for TSDF indexing first. Scale the remaining TSDF and
 * dense-map allowances together when they do not fit. Dense storage is planned at 192 bytes
 * per surfel, allowing for growing primitive arrays, hash slots, live-cloud scratch and snapshots;
 * its existing spatial coarsening then makes room sooner, rather than discarding new rooms.
 * The TSDF voxel grows by the square root of its budget reduction: a surface with a fixed
 * truncation width in voxels needs roughly one block per unit of surface area, so it can cover
 * the same area at lower detail. At 512 MiB and above the existing 48 MiB TSDF, preset voxel
 * and dense-point limits are unchanged. The journal never grows beyond 4 MiB, even on a large heap.
 */
internal class ScanMemoryBudget(val maxHeapBytes: Long) {
    val totalBytes: Long = maxHeapBytes * SHARE_PERCENT / 100
    val journalBytes: Long = minOf(ArDebugJournal.MAX_BYTES, totalBytes / JOURNAL_DIVISOR)
    private val available = (totalBytes - journalBytes - TSDF_INDEX_BYTES).coerceAtLeast(0)
    private val standardDensePoints = DenseFusion.budgetFor(maxHeapBytes)
    private val standardBytes = RerunTsdf.MAX_BYTES + standardDensePoints * DENSE_BYTES_PER_POINT
    private val scale = minOf(1.0, available.toDouble() / standardBytes)

    val tsdfBytes: Long = (RerunTsdf.MAX_BYTES * scale).toLong()
    val densePoints: Int = (standardDensePoints * scale).toInt().coerceAtLeast(1)
    val denseBytes: Long get() = densePoints * DENSE_BYTES_PER_POINT
    val retainedBytes: Long get() = tsdfBytes + TSDF_INDEX_BYTES + denseBytes + journalBytes

    fun modelVoxelM(preset: ScanPreset): Float =
        preset.modelVoxelM * sqrt(RerunTsdf.MAX_BYTES.toDouble() / tsdfBytes.coerceAtLeast(1)).toFloat()

    companion object {
        const val SHARE_PERCENT = 35
        const val DENSE_BYTES_PER_POINT = 192L
        const val TSDF_INDEX_BYTES = 2L * 1024 * 1024
        private const val JOURNAL_DIVISOR = 8
    }
}
