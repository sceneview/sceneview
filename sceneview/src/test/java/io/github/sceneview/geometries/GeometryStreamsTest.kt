package io.github.sceneview.geometries

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * What a [Geometry] upload carries while it waits behind the one in flight (#4365).
 *
 * Before the gate, `Geometry.update` copied its lists into Filament buffers inside the call. A
 * deferred upload reads them later — from a Filament release callback — so it must read what the
 * call was given, not whatever the caller's list holds by then.
 */
class GeometryStreamsTest {

    private fun vertex(x: Float) = Geometry.Vertex(position = Position(x = x))

    /** A consumer that holds what it is given until [consume] is called, as in the gate's tests. */
    private class Backend {
        val uploaded = mutableListOf<GeometryStreams>()
        private val held = ArrayDeque<() -> Unit>()

        val gate = LatestWinsUploadGate<GeometryStreams>(
            merge = { pending, next -> pending.mergedWith(next) },
            canUpload = { true },
            upload = { value, onReleased ->
                uploaded += value
                held += onReleased
            },
        )

        fun consume() = held.removeFirst().invoke()
    }

    // ── The snapshot ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a pending upload keeps its vertices when the caller reuses the list`() {
        val backend = Backend()
        val scratch = mutableListOf(vertex(1f), vertex(2f))
        backend.gate.submit(GeometryStreams.snapshotOf(vertices = listOf(vertex(0f))))

        // Waits behind the upload above.
        backend.gate.submit(GeometryStreams.snapshotOf(vertices = scratch))
        // The caller refills its scratch list for the next frame without submitting it.
        scratch.clear()
        scratch += vertex(9f)
        backend.consume()

        assertEquals(listOf(vertex(1f), vertex(2f)), backend.uploaded.last().vertices)
    }

    @Test
    fun `a pending upload keeps its indices, one level down too`() {
        val backend = Backend()
        val primitive = mutableListOf(0, 1, 2)
        val primitives = mutableListOf<List<Int>>(primitive)
        backend.gate.submit(GeometryStreams.snapshotOf(primitivesIndices = listOf(listOf(0))))

        backend.gate.submit(GeometryStreams.snapshotOf(primitivesIndices = primitives))
        primitive[0] = 7
        primitives += listOf(3, 4, 5)
        backend.consume()

        assertEquals(listOf(listOf(0, 1, 2)), backend.uploaded.last().primitivesIndices)
    }

    @Test
    fun `a stream that did not change stays absent`() {
        val streams = GeometryStreams.snapshotOf(vertices = listOf(vertex(0f)))

        assertNull(streams.primitivesIndices)
        assertNull(GeometryStreams.snapshotOf(primitivesIndices = listOf(listOf(0))).vertices)
    }

    // ── The merge ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `merging keeps the newest of each stream`() {
        val older = GeometryStreams.snapshotOf(
            vertices = listOf(vertex(1f)), primitivesIndices = listOf(listOf(0, 1, 2))
        )
        val newer = GeometryStreams.snapshotOf(
            vertices = listOf(vertex(2f)), primitivesIndices = listOf(listOf(2, 1, 0))
        )

        val merged = older.mergedWith(newer)

        assertSame(newer.vertices, merged.vertices)
        assertSame(newer.primitivesIndices, merged.primitivesIndices)
    }

    @Test
    fun `merging keeps a pending stream the newer upload does not touch`() {
        val indices = GeometryStreams.snapshotOf(primitivesIndices = listOf(listOf(0, 1, 2)))
        val vertices = GeometryStreams.snapshotOf(vertices = listOf(vertex(1f)))

        // setPrimitivesIndices then setVertices while an upload is in flight: both must go out.
        val merged = indices.mergedWith(vertices)

        assertSame(vertices.vertices, merged.vertices)
        assertSame(indices.primitivesIndices, merged.primitivesIndices)
        // And in the other order.
        val reversed = vertices.mergedWith(indices)
        assertSame(vertices.vertices, reversed.vertices)
        assertSame(indices.primitivesIndices, reversed.primitivesIndices)
    }

    @Test
    fun `three updates waiting collapse into the last vertices and the only indices`() {
        val backend = Backend()
        backend.gate.submit(GeometryStreams.snapshotOf(vertices = listOf(vertex(0f))))

        backend.gate.submit(GeometryStreams.snapshotOf(vertices = listOf(vertex(1f))))
        backend.gate.submit(GeometryStreams.snapshotOf(primitivesIndices = listOf(listOf(0))))
        backend.gate.submit(GeometryStreams.snapshotOf(vertices = listOf(vertex(3f))))
        backend.consume()

        assertEquals(2, backend.uploaded.size)
        assertEquals(listOf(vertex(3f)), backend.uploaded.last().vertices)
        assertEquals(listOf(listOf(0)), backend.uploaded.last().primitivesIndices)
    }
}
