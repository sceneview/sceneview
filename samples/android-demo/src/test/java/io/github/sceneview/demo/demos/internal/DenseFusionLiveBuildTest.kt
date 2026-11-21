package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.ScanLiveFreshnessTest
import org.junit.Assert.*
import org.junit.Test

class DenseFusionLiveBuildTest {
    @Test
    fun `workspace shrinks after a large near field becomes far without changing output`() {
        val fusion = DenseFusion(maxPoints = 200_000, maxLevel = 0)
        fusion.add(ScanLiveFreshnessTest.patch(200_000))
        val snapshot = DenseFusion.LiveSnapshot(fusion)
        snapshot.copyFrom(fusion)
        snapshot.liveCloud(0.5f, 1f, 0.5f, 50_000)
        val nearBytes = snapshot.workspaceBytes
        val far = snapshot.liveCloud(100f, 100f, 100f, 50_000)
        assertTrue("${snapshot.workspaceBytes} vs $nearBytes", snapshot.workspaceBytes < nearBytes / 2)
        val fresh = DenseFusion.LiveSnapshot(fusion)
        fresh.copyFrom(fusion)
        val expected = fresh.liveCloud(100f, 100f, 100f, 50_000)
        assertArrayEquals(expected.positions, far.positions, 0f)
        assertArrayEquals(expected.colors, far.colors)
        assertArrayEquals(expected.normals, far.normals, 0f)
        assertEquals(fresh.workspaceBytes, snapshot.workspaceBytes)
    }

    @Test
    fun `copied buffers preserve every value through later adds and compaction and reuse their capacity`() {
        val fusion = DenseFusion(maxPoints = 12_000, maxLevel = 7)
        fusion.add(ScanLiveFreshnessTest.patch(12_000))
        val expected = fusion.liveCloud(0.5f, 1.4f, 0.5f, 5_000, minViews = 1)
        val snapshot = DenseFusion.LiveSnapshot(fusion)
        snapshot.copyFrom(fusion)
        val capacity = snapshot.capacity
        repeat(100) { i -> fusion.add(ScanLiveFreshnessTest.patch(8_000, (i + 1) * 3f)) }
        assertTrue("${fusion.compactions} compactions", fusion.compactions > 0)
        val actual = snapshot.liveCloud(0.5f, 1.4f, 0.5f, 5_000)
        assertArrayEquals(expected.positions, actual.positions, 0f)
        assertArrayEquals(expected.normals, actual.normals, 0f)
        assertArrayEquals(expected.colors, actual.colors)
        assertArrayEquals(expected.confidences, actual.confidences)
        assertArrayEquals(expected.levels, actual.levels)
        snapshot.copyFrom(fusion)
        assertTrue(snapshot.capacity >= capacity)
        val grown = snapshot.capacity
        snapshot.copyFrom(fusion)
        assertEquals(grown, snapshot.capacity)
    }
}
