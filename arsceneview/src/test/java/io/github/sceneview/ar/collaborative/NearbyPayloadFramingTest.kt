package io.github.sceneview.ar.collaborative

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NearbyPayloadFraming] — the SDK-free framing half of
 * [NearbyCollaborativeTransport]. Pure JVM: no Play Services, no Android.
 *
 * Promised by the #2221 review, tracked by #2569.
 */
class NearbyPayloadFramingTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)
    private fun text(b: ByteArray) = b.toString(Charsets.UTF_8)

    // ── frame ─────────────────────────────────────────────────────────────

    @Test
    fun `frame appends exactly one trailing delimiter`() {
        val framed = NearbyPayloadFraming.frame(bytes("{\"type\":\"bye\",\"peer\":\"p\"}"))
        assertEquals("{\"type\":\"bye\",\"peer\":\"p\"}\n", text(framed))
    }

    @Test
    fun `frame normalizes an existing trailing newline instead of doubling it`() {
        val framed = NearbyPayloadFraming.frame(bytes("hello\n"))
        assertEquals("hello\n", text(framed))
    }

    @Test
    fun `frame collapses multiple trailing newlines to one`() {
        val framed = NearbyPayloadFraming.frame(bytes("hello\n\n\n"))
        assertEquals("hello\n", text(framed))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `frame rejects an empty message`() {
        NearbyPayloadFraming.frame(ByteArray(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `frame rejects a newline-only message`() {
        NearbyPayloadFraming.frame(bytes("\n\n"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `frame rejects a blank message`() {
        NearbyPayloadFraming.frame(bytes("   "))
    }

    // ── unframe ───────────────────────────────────────────────────────────

    @Test
    fun `unframe yields the single record of a well-formed payload`() {
        val records = NearbyPayloadFraming.unframe(bytes("hello\n"))
        assertEquals(1, records.size)
        assertEquals("hello", text(records[0]))
    }

    @Test
    fun `unframe splits a batched payload into ordered records`() {
        val records = NearbyPayloadFraming.unframe(bytes("one\ntwo\nthree\n"))
        assertEquals(listOf("one", "two", "three"), records.map(::text))
    }

    @Test
    fun `unframe drops blank segments from double newlines`() {
        val records = NearbyPayloadFraming.unframe(bytes("one\n\n \ntwo\n"))
        assertEquals(listOf("one", "two"), records.map(::text))
    }

    @Test
    fun `unframe of an empty payload is an empty list`() {
        assertTrue(NearbyPayloadFraming.unframe(ByteArray(0)).isEmpty())
    }

    @Test
    fun `unframe of a newline-only payload is an empty list`() {
        assertTrue(NearbyPayloadFraming.unframe(bytes("\n\n")).isEmpty())
    }

    // ── round trip ────────────────────────────────────────────────────────

    @Test
    fun `frame then unframe round-trips a wire format line losslessly`() {
        val line = CollaborativeWireFormat.hello("peer-1", "Alicé \"quoted\"")
        val framed = NearbyPayloadFraming.frame(bytes(line))
        val records = NearbyPayloadFraming.unframe(framed)
        assertEquals(1, records.size)
        assertArrayEquals(bytes(line.trimEnd('\n')), records[0])
        // And the record still parses back to the original message.
        val parsed = CollaborativeWireFormat.parse(text(records[0]))
        assertEquals("Alicé \"quoted\"", (parsed as CollaborativeMessage.Hello).displayName)
    }
}
