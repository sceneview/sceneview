package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.ScanDenseMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RerunScanBudgetTest {
    @Test
    fun `256 MiB heap shares at most 35 percent over a seven minute synthetic scan`() {
        val budget = ScanMemoryBudget(256 * MIB)
        assertTrue(budget.retainedBytes <= budget.totalBytes)
        assertTrue(budget.modelVoxelM(ScanPreset.ROOM) > ScanPreset.ROOM.modelVoxelM)
        assertTrue(budget.modelVoxelM(ScanPreset.OBJECT) > ScanPreset.OBJECT.modelVoxelM)
        val tsdf = RerunTsdf(voxelM = budget.modelVoxelM(ScanPreset.ROOM), maxBytes = budget.tsdfBytes)
        var now = 0L
        val dense = ScanDenseMap(maxPoints = budget.densePoints, clock = { now })
        val journal = ArDebugJournal(maxBytes = budget.journalBytes)
        var peakStorage = 0L
        repeat(4_201) { tick ->
            // A fresh patch of floor plus a previous observation: enough to fill/coarsen the
            // dense map repeatedly, not a loop over the same small room that never hits a cap.
            val samples = patch(tick)
            val nanos = tick * 100_000_000L
            if (tick % 100 == 0) {
                now = nanos
                dense.project(DepthFrame(2, 2, ShortArray(4), null, IntArray(4), 1f, 1f, 0f, 0f,
                    DebugPose(tick * 0.64f, 1f, 0f)), nanos)
            }
            dense.add(samples)
            repeat(4) { tsdf.update((tick * 4 + it) * RerunTsdf.BLOCK, 0, 0, 0f, 1f) }
            journal.append(ArDebugEvent.CameraPose(nanos, DebugPose(tick * 0.1f, 1f, 0f)))
            journal.append(ArDebugEvent.Points(nanos, samples.positions, null, samples.colors))
            val stored = tsdf.storageBytes + dense.storageBytes + journal.bytes
            peakStorage = maxOf(peakStorage, stored)
            assertTrue("stored $stored at $tick", stored <= budget.totalBytes)
            assertTrue(tsdf.bytes <= budget.tsdfBytes)
            assertTrue(dense.fusion.count <= budget.densePoints)
            assertTrue(journal.bytes <= budget.journalBytes)
        }
        assertTrue("dense map coarsened, rather than stopping at full detail", dense.fusion.compactions > 1)
        assertTrue("TSDF reached its cap", tsdf.budgetReached)
        assertTrue("journal reached its cap", journal.capped)
        assertEquals(420_000_000_000L, journal.last().nanos)
        assertEquals(journal.sumOf { ArDebugJournal.bytesOf(it) }, journal.bytes)
        println("256 MiB scan: planned=${budget.retainedBytes}, peakStorage=$peakStorage, " +
            "tsdf=${budget.tsdfBytes}, densePoints=${budget.densePoints}, compactions=${dense.fusion.compactions}")
    }

    @Test
    fun `512 MiB and larger heaps keep current dense cap TSDF cap and preset voxels`() {
        for (mib in listOf(512, 768, 1024)) {
            val heap = mib * MIB
            val budget = ScanMemoryBudget(heap)
            assertEquals(RerunTsdf.MAX_BYTES, budget.tsdfBytes)
            assertEquals(DenseFusion.budgetFor(heap), budget.densePoints)
            assertEquals(ArDebugJournal.MAX_BYTES, budget.journalBytes)
            for (preset in listOf(ScanPreset.ROOM, ScanPreset.OBJECT)) {
                assertEquals(preset.modelVoxelM, budget.modelVoxelM(preset), 0f)
            }
            assertTrue(budget.retainedBytes <= budget.totalBytes)
        }
    }

    @Test
    fun `a full map cannot accumulate empty chunks when a long scan visits new regions`() {
        val dense = DenseFusion(maxPoints = 32, maxLevel = 0)
        repeat(64) { dense.add(patch(it)) }
        val atCap = dense.storageBytes
        repeat(1_000) { dense.add(patch(it + 64)) }
        assertEquals(atCap, dense.storageBytes)
        assertTrue(dense.count <= 32)
    }

    private fun patch(tick: Int): DenseSamples {
        val side = 32
        val count = side * side * 2
        val xyz = FloatArray(count * 3)
        for (i in 0 until count) {
            val patch = (tick - i / (side * side)).coerceAtLeast(0)
            xyz[i * 3] = patch * 0.64f + (i % side) * 0.02f + 0.005f
            xyz[i * 3 + 2] = (i / side % side) * 0.02f + 0.005f
        }
        return DenseSamples(count, xyz, FloatArray(count * 3) { if (it % 3 == 1) 1f else 0f },
            IntArray(count) { 0xff557799.toInt() }, ByteArray(count) { -1 })
    }

    private companion object {
        const val MIB = 1024L * 1024
    }
}
