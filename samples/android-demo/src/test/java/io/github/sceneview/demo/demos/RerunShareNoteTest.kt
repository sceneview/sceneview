package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.RerunGlbMesh
import io.github.sceneview.demo.demos.internal.RerunMesh
import io.github.sceneview.demo.demos.internal.RerunMeshGlb
import io.github.sceneview.demo.demos.internal.RerunMeshSimplifier
import io.github.sceneview.demo.demos.internal.RerunScanModel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The model the export sheet writes: lighter and on its floor, and what it says when it is not. */
// Robolectric for android.util.Log, which the export's measurement line writes.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RerunShareNoteTest {

    @Test
    fun `the light copy is made only when its working arrays fit in half the free heap`() {
        val workspace = RerunMeshSimplifier.workspaceBytes(vertices = 200_000, triangles = 400_000)
        assertTrue(lightShareFits(200_000, 400_000, free = workspace * 2))
        assertFalse(lightShareFits(200_000, 400_000, free = workspace * 2 - 2))
        assertFalse(lightShareFits(200_000, 400_000, free = 0))
    }

    @Test
    fun `running out of memory is named, anything else is a share that failed`() {
        assertEquals(RerunShareNote.OutOfMemory, RerunShareNote.of(OutOfMemoryError()))
        assertEquals(RerunShareNote.Failed, RerunShareNote.of(IllegalArgumentException("no triangles")))
        assertEquals(RerunShareNote.Failed, RerunShareNote.of(java.io.IOException("disk full")))
    }

    @Test
    fun `each note has its own line`() {
        val lines = RerunShareNote.entries.map { it.message }
        assertEquals(lines.size, lines.distinct().size)
    }

    @Test
    fun `a light copy is heavy only well above the triangle target`() {
        val target = RerunMeshSimplifier.DEFAULT_TARGET_TRIANGLES
        assertFalse(stillHeavy(target))
        assertFalse(stillHeavy(target * 3 / 2))
        assertTrue(stillHeavy(target * 3 / 2 + 1))
        assertTrue(stillHeavy(target * 4))
        assertFalse(stillHeavy(0))
        assertFalse(stillHeavy(1_500, target = 1_000))
        assertTrue(stillHeavy(1_501, target = 1_000))
    }

    @Test
    fun `the exported model is lighter than the one on screen and rests on its floor, centred`() {
        val model = meshModel(floor(side = 260))
        val shared = sharedRerunModel(model, free = Long.MAX_VALUE)
        assertNull(shared.note)
        val back = checkNotNull(RerunGlbMesh.read(shared.glb))
        assertTrue(back.triangleCount <= RerunMeshSimplifier.DEFAULT_TARGET_TRIANGLES)
        assertTrue(shared.glb.size < model.glb.size)
        val b = back.bounds()
        assertEquals(0f, b[1], 1e-5f)
        assertEquals(0f, (b[0] + b[3]) / 2, 1e-4f)
        assertEquals(0f, (b[2] + b[5]) / 2, 1e-4f)
    }

    @Test
    fun `without the memory to lighten it, the whole mesh is exported on its floor and says so`() {
        val mesh = floor(side = 40)
        val shared = sharedRerunModel(meshModel(mesh), free = 0)
        assertEquals(RerunShareNote.SentWhole, shared.note)
        val back = checkNotNull(RerunGlbMesh.read(shared.glb))
        assertEquals(mesh.triangleCount, back.triangleCount)
        assertEquals(0f, back.bounds()[1], 1e-5f)
    }

    @Test
    fun `a model of points is exported as it is`() {
        val glb = RerunMeshGlb.write(floor(side = 4))
        val points = RerunScanModel(RerunScanModel.Kind.Points, glb, floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f), 18, 16)
        val shared = sharedRerunModel(points, free = Long.MAX_VALUE)
        assertArrayEquals(glb, shared.glb)
        assertNull(shared.note)
    }

    private fun meshModel(mesh: RerunMesh) = RerunScanModel(
        kind = RerunScanModel.Kind.Mesh,
        glb = RerunMeshGlb.write(mesh),
        bounds = mesh.bounds(),
        triangles = mesh.triangleCount,
        vertices = mesh.vertexCount,
    )

    /** A floor of [side] x [side] vertices, 1 cm apart, 0.7 m up and off the origin. */
    private fun floor(side: Int): RerunMesh {
        val n = side * side
        val positions = FloatArray(n * 3) { i ->
            when (i % 3) {
                0 -> 2f + (i / 3 % side) * 0.01f
                1 -> 0.7f
                else -> -1f + (i / 3 / side) * 0.01f
            }
        }
        val indices = IntArray((side - 1) * (side - 1) * 6)
        var k = 0
        for (z in 0 until side - 1) for (x in 0 until side - 1) {
            val a = z * side + x
            val b = a + 1
            val c = a + side
            val d = c + 1
            intArrayOf(a, c, b, b, c, d).forEach { indices[k++] = it }
        }
        return RerunMesh(
            positions,
            FloatArray(n * 3) { if (it % 3 == 1) 1f else 0f },
            IntArray(n) { 0xFF8A6A4A.toInt() },
            indices,
        )
    }
}
