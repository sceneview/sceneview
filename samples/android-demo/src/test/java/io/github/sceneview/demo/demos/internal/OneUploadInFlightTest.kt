package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #4750: a debug layer pins one upload at most, whatever the renderer does. */
class OneUploadInFlightTest {

    @Test
    fun `an idle layer uploads at once`() {
        val flight = OneUploadInFlight()
        assertTrue(flight.request())
        flight.begin(STREAMS)
        assertTrue(flight.isInFlight)
    }

    @Test
    fun `a renderer that never consumes leaves one upload pinned, not one a frame`() {
        // The Pixel 4a crash: 12 686 index buffers pinned after a minute of a frozen camera.
        val flight = OneUploadInFlight()
        var uploads = 0
        repeat(10_000) {
            if (flight.request()) {
                flight.begin(STREAMS)
                uploads++
            }
        }
        assertEquals(1, uploads)
        assertTrue(flight.isDirty)
    }

    @Test
    fun `the last stream released sends the latest state, once`() {
        val flight = OneUploadInFlight()
        assertTrue(flight.request())
        val id = flight.begin(STREAMS)
        repeat(5) { assertFalse(flight.request()) }
        assertFalse("streams still held", flight.release(id))
        assertTrue("one owed upload for five asked", flight.release(id))
        assertFalse(flight.isInFlight)
        val next = flight.begin(STREAMS)
        assertFalse(flight.release(next))
        assertFalse("nothing asked meanwhile", flight.release(next))
        assertTrue(flight.request())
    }

    @Test
    fun `releases of an aborted upload do not reopen the next one`() {
        val flight = OneUploadInFlight()
        val failed = flight.begin(STREAMS)
        flight.abort()
        assertTrue(flight.request())
        val current = flight.begin(STREAMS)
        assertFalse(flight.request())
        // The half of the failed upload Filament had taken reports back late.
        assertFalse(flight.release(failed))
        assertFalse(flight.release(failed))
        assertTrue(flight.isInFlight)
        assertFalse(flight.release(current))
        assertTrue(flight.release(current))
    }

    @Test
    fun `a cleared layer owes nothing`() {
        val flight = OneUploadInFlight()
        val id = flight.begin(1)
        assertFalse(flight.request())
        flight.clear()
        assertFalse(flight.release(id))
        assertFalse(flight.isDirty)
    }

    @Test
    fun `a deferred mesh is copied, so the caller may reuse its own`() {
        val source = DebugMesh()
        val a = source.vertex(0f, 0f, 0f, 0.1f, 0.2f)
        val b = source.vertex(1f, 0f, 0f, 0.3f, 0.4f)
        val c = source.vertex(1f, 1f, 0f, 0.5f, 0.6f)
        source.triangle(a, b, c)
        val pending = DebugMesh(0)
        pending.set(source)
        source.clear()
        source.vertex(9f, 9f, 9f)
        assertEquals(3, pending.vertexCount)
        assertEquals(3, pending.indexCount)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f), pending.positions.copyOf(9), 0f)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f), pending.uvs.copyOf(6), 0f)
        assertArrayEquals(intArrayOf(0, 1, 2), pending.indices.copyOf(3))
    }

    private companion object {
        /** Positions and indices: a plain layer's two buffers. */
        const val STREAMS = 2
    }
}
