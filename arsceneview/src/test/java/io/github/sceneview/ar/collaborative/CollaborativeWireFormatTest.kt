package io.github.sceneview.ar.collaborative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-JSON + round-trip tests for [CollaborativeWireFormat].
 *
 * Pure JVM — no ARCore, no Android. Mirrors the `RerunWireFormatTest`
 * approach: assert exact serialized output and that every encoder/decoder
 * pair round-trips.
 */
class CollaborativeWireFormatTest {

    @Test
    fun `hello emits canonical JSON line`() {
        val line = CollaborativeWireFormat.hello("peer-1", "Alice")
        assertEquals(
            "{\"type\":\"hello\",\"peer\":\"peer-1\",\"name\":\"Alice\"}\n",
            line,
        )
    }

    @Test
    fun `bye emits canonical JSON line`() {
        val line = CollaborativeWireFormat.bye("peer-1")
        assertEquals("{\"type\":\"bye\",\"peer\":\"peer-1\"}\n", line)
    }

    @Test
    fun `anchor emits canonical JSON line`() {
        val line = CollaborativeWireFormat.anchor("host", "ca-xyz", "session")
        assertEquals(
            "{\"type\":\"anchor\",\"peer\":\"host\",\"id\":\"ca-xyz\",\"name\":\"session\"}\n",
            line,
        )
    }

    @Test
    fun `pose emits canonical JSON line`() {
        val line = CollaborativeWireFormat.pose(
            peerId = "p2",
            epochMs = 1234L,
            translation = floatArrayOf(0.1f, 1.7f, -0.2f),
            quaternion = floatArrayOf(0f, 0f, 0f, 1f),
        )
        assertEquals(
            "{\"type\":\"pose\",\"peer\":\"p2\",\"t\":1234," +
                "\"translation\":[0.1,1.7,-0.2],\"quaternion\":[0.0,0.0,0.0,1.0]}\n",
            line,
        )
    }

    @Test
    fun `node emits canonical JSON line`() {
        val line = CollaborativeWireFormat.node(
            peerId = "p3",
            nodeKey = "cube-1",
            modelKey = "cube",
            translation = floatArrayOf(1f, 2f, 3f),
            quaternion = floatArrayOf(0f, 0f, 0f, 1f),
            scale = floatArrayOf(1f, 1f, 1f),
            logicalClock = 7L,
        )
        assertEquals(
            "{\"type\":\"node\",\"peer\":\"p3\",\"node\":\"cube-1\",\"model\":\"cube\"," +
                "\"translation\":[1.0,2.0,3.0],\"quaternion\":[0.0,0.0,0.0,1.0]," +
                "\"scale\":[1.0,1.0,1.0],\"clock\":7}\n",
            line,
        )
    }

    @Test
    fun `remove emits canonical JSON line`() {
        assertEquals(
            "{\"type\":\"remove\",\"peer\":\"p3\",\"node\":\"cube-1\",\"clock\":8}\n",
            CollaborativeWireFormat.remove("p3", "cube-1", 8L),
        )
    }

    @Test
    fun `node without a counter emits the line of a peer that predates it`() {
        val t = floatArrayOf(1f, 2f, 3f)
        val q = floatArrayOf(0f, 0f, 0f, 1f)
        val scale = floatArrayOf(1f, 1f, 1f)
        val legacy = PreClockWireFormat.node("p3", "cube-1", "cube", t, q, scale)
        assertEquals(legacy, CollaborativeWireFormat.node("p3", "cube-1", "cube", t, q, scale))
        assertEquals(legacy, CollaborativeWireFormat.node("p3", "cube-1", "cube", t, q, scale, 0L))
        assertFalse(legacy.contains("clock"))
    }

    @Test
    fun `remove rejects a missing counter at encode time`() {
        var threw = false
        try {
            CollaborativeWireFormat.remove("p3", "cube-1", 0L)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `every line terminates with newline`() {
        assertTrue(CollaborativeWireFormat.hello("a", "A").endsWith("\n"))
        assertTrue(CollaborativeWireFormat.bye("a").endsWith("\n"))
        assertTrue(CollaborativeWireFormat.anchor("a", "id", "n").endsWith("\n"))
    }

    @Test
    fun `hello round-trips`() {
        val msg = CollaborativeWireFormat.parse(CollaborativeWireFormat.hello("p", "Bob"))
        assertTrue(msg is CollaborativeMessage.Hello)
        msg as CollaborativeMessage.Hello
        assertEquals("p", msg.peerId)
        assertEquals("Bob", msg.displayName)
    }

    @Test
    fun `bye round-trips`() {
        val msg = CollaborativeWireFormat.parse(CollaborativeWireFormat.bye("p"))
        assertTrue(msg is CollaborativeMessage.Bye)
        assertEquals("p", msg!!.peerId)
    }

    @Test
    fun `anchor round-trips`() {
        val msg = CollaborativeWireFormat.parse(
            CollaborativeWireFormat.anchor("host", "cloud-id", "room"),
        )
        assertTrue(msg is CollaborativeMessage.SharedAnchor)
        msg as CollaborativeMessage.SharedAnchor
        assertEquals("host", msg.peerId)
        assertEquals("cloud-id", msg.cloudAnchorId)
        assertEquals("room", msg.name)
    }

    @Test
    fun `pose round-trips`() {
        val line = CollaborativeWireFormat.pose(
            peerId = "p",
            epochMs = 99L,
            translation = floatArrayOf(1f, 2f, 3f),
            quaternion = floatArrayOf(0f, 0f, 0.7071f, 0.7071f),
        )
        val msg = CollaborativeWireFormat.parse(line)
        assertTrue(msg is CollaborativeMessage.ParticipantPose)
        msg as CollaborativeMessage.ParticipantPose
        assertEquals("p", msg.peerId)
        assertEquals(99L, msg.epochMs)
        assertEquals(3, msg.translation.size)
        assertEquals(2f, msg.translation[1], 1e-6f)
        assertEquals(0.7071f, msg.quaternion[3], 1e-6f)
    }

    @Test
    fun `node round-trips`() {
        val line = CollaborativeWireFormat.node(
            peerId = "p",
            nodeKey = "n-7",
            modelKey = "robot",
            translation = floatArrayOf(-1f, 0f, 5f),
            quaternion = floatArrayOf(0f, 0f, 0f, 1f),
            scale = floatArrayOf(2f, 2f, 2f),
            logicalClock = 12L,
        )
        val msg = CollaborativeWireFormat.parse(line)
        assertTrue(msg is CollaborativeMessage.NodeState)
        msg as CollaborativeMessage.NodeState
        assertEquals("n-7", msg.nodeKey)
        assertEquals("robot", msg.modelKey)
        assertEquals(2f, msg.scale[0], 1e-6f)
        assertEquals(5f, msg.translation[2], 1e-6f)
        assertEquals(12L, msg.logicalClock)
    }

    @Test
    fun `remove round-trips`() {
        val msg = CollaborativeWireFormat.parse(
            CollaborativeWireFormat.remove("p", "n-7", 13L),
        ) as CollaborativeMessage.NodeRemoval
        assertEquals("p", msg.peerId)
        assertEquals("n-7", msg.nodeKey)
        assertEquals(13L, msg.logicalClock)
    }

    @Test
    fun `a wall-clock sized counter round-trips`() {
        val counter = 1_791_504_000_123L
        val node = CollaborativeWireFormat.parse(
            CollaborativeWireFormat.node(
                "p", "n", "cube",
                floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(1f, 1f, 1f),
                counter,
            ),
        ) as CollaborativeMessage.NodeState
        assertEquals(counter, node.logicalClock)
        val removal = CollaborativeWireFormat.parse(
            CollaborativeWireFormat.remove("p", "n", Long.MAX_VALUE),
        ) as CollaborativeMessage.NodeRemoval
        assertEquals(Long.MAX_VALUE, removal.logicalClock)
    }

    // ── Peers that predate `clock` and `remove` (#4384) ───────────────────
    //
    // PreClockWireFormat is the wire format as released up to 4.53.0. Both
    // directions go through it: what such a peer sends, what it makes of the
    // lines it now receives.

    @Test
    fun `a node line from an older peer parses with no counter`() {
        val msg = CollaborativeWireFormat.parse(
            PreClockWireFormat.node(
                "old", "n", "cube",
                floatArrayOf(4f, 5f, 6f), floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(1f, 1f, 1f),
            ),
        ) as CollaborativeMessage.NodeState
        assertEquals(0L, msg.logicalClock)
        assertEquals("old", msg.peerId)
        assertEquals(5f, msg.translation[1], 0f)
    }

    @Test
    fun `an older peer reads a node line that carries a counter`() {
        val msg = PreClockWireFormat.parse(
            CollaborativeWireFormat.node(
                "new", "n-1", "robot",
                floatArrayOf(4f, 5f, 6f), floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(2f, 2f, 2f),
                1_791_504_000_123L,
            ),
        ) as CollaborativeMessage.NodeState
        assertEquals("new", msg.peerId)
        assertEquals("n-1", msg.nodeKey)
        assertEquals("robot", msg.modelKey)
        assertEquals(6f, msg.translation[2], 0f)
        assertEquals(1f, msg.quaternion[3], 0f)
        assertEquals(2f, msg.scale[0], 0f)
    }

    @Test
    fun `an older peer ignores a remove line`() {
        assertNull(PreClockWireFormat.parse(CollaborativeWireFormat.remove("new", "n-1", 9L)))
    }

    @Test
    fun `every other line is unchanged for an older peer`() {
        val t = floatArrayOf(1f, 2f, 3f)
        val q = floatArrayOf(0f, 0f, 0f, 1f)
        assertEquals(PreClockWireFormat.hello("p", "P"), CollaborativeWireFormat.hello("p", "P"))
        assertEquals(PreClockWireFormat.bye("p"), CollaborativeWireFormat.bye("p"))
        assertEquals(
            PreClockWireFormat.anchor("p", "id", "room"),
            CollaborativeWireFormat.anchor("p", "id", "room"),
        )
        assertEquals(
            PreClockWireFormat.pose("p", 12L, t, q),
            CollaborativeWireFormat.pose("p", 12L, t, q),
        )
    }

    // ── Lines that cannot be ordered are dropped ──────────────────────────

    private fun nodeLineWithClock(clock: String): String =
        "{\"type\":\"node\",\"peer\":\"p\",\"node\":\"n\",\"model\":\"cube\"," +
            "\"translation\":[0,0,0],\"quaternion\":[0,0,0,1],\"scale\":[1,1,1]," +
            "\"clock\":$clock}"

    @Test
    fun `a node line with an unreadable counter is dropped, not read as no counter`() {
        assertNotNull(CollaborativeWireFormat.parse(nodeLineWithClock("7")))
        assertNull(CollaborativeWireFormat.parse(nodeLineWithClock("\"7\"")))
        assertNull(CollaborativeWireFormat.parse(nodeLineWithClock("null")))
        assertNull(CollaborativeWireFormat.parse(nodeLineWithClock("-3")))
        // One digit past Long.MAX_VALUE.
        assertNull(CollaborativeWireFormat.parse(nodeLineWithClock("92233720368547758070")))
    }

    @Test
    fun `a remove line without a usable counter is dropped`() {
        val head = "{\"type\":\"remove\",\"peer\":\"p\",\"node\":\"n\""
        assertNotNull(CollaborativeWireFormat.parse("$head,\"clock\":1}"))
        assertNull(CollaborativeWireFormat.parse("$head}"))
        assertNull(CollaborativeWireFormat.parse("$head,\"clock\":0}"))
        assertNull(CollaborativeWireFormat.parse("$head,\"clock\":-1}"))
        assertNull(CollaborativeWireFormat.parse("{\"type\":\"remove\",\"peer\":\"p\",\"clock\":4}"))
    }

    @Test
    fun `parse returns null for blank line`() {
        assertNull(CollaborativeWireFormat.parse(""))
        assertNull(CollaborativeWireFormat.parse("   \n"))
    }

    @Test
    fun `parse returns null for unknown type`() {
        assertNull(
            CollaborativeWireFormat.parse(
                "{\"type\":\"future-message\",\"peer\":\"p\"}",
            ),
        )
    }

    @Test
    fun `parse returns null for missing peer`() {
        assertNull(CollaborativeWireFormat.parse("{\"type\":\"hello\",\"name\":\"X\"}"))
    }

    @Test
    fun `parse returns null for malformed pose vector`() {
        // translation has only 2 components — not a valid pose.
        assertNull(
            CollaborativeWireFormat.parse(
                "{\"type\":\"pose\",\"peer\":\"p\",\"t\":1," +
                    "\"translation\":[1.0,2.0],\"quaternion\":[0.0,0.0,0.0,1.0]}",
            ),
        )
    }

    @Test
    fun `hello defaults display name to peer id when name absent`() {
        val msg = CollaborativeWireFormat.parse("{\"type\":\"hello\",\"peer\":\"px\"}")
        assertNotNull(msg)
        assertEquals("px", (msg as CollaborativeMessage.Hello).displayName)
    }

    @Test
    fun `escapes quote in display name`() {
        val line = CollaborativeWireFormat.hello("p", "Al\"ice")
        assertTrue(line.contains("Al\\\"ice"))
        val msg = CollaborativeWireFormat.parse(line) as CollaborativeMessage.Hello
        assertEquals("Al\"ice", msg.displayName)
    }

    @Test
    fun `non-finite floats serialize as zero to keep line parseable`() {
        val line = CollaborativeWireFormat.pose(
            peerId = "p",
            epochMs = 0L,
            translation = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, 1f),
            quaternion = floatArrayOf(0f, 0f, 0f, 1f),
        )
        val msg = CollaborativeWireFormat.parse(line) as CollaborativeMessage.ParticipantPose
        assertEquals(0f, msg.translation[0], 0f)
        assertEquals(0f, msg.translation[1], 0f)
        assertEquals(1f, msg.translation[2], 0f)
    }

    @Test
    fun `parse rejects a vector with more than 4 components`() {
        // 5 components can never be valid (quaternion is the longest at 4) —
        // the parser must bail before per-component parsing (#2569).
        assertNull(
            CollaborativeWireFormat.parse(
                "{\"type\":\"pose\",\"peer\":\"p\",\"t\":1," +
                    "\"translation\":[1.0,2.0,3.0,4.0,5.0],\"quaternion\":[0.0,0.0,0.0,1.0]}",
            ),
        )
    }

    @Test
    fun `parse rejects an oversized vector body without parsing it`() {
        // A maliciously long [...] body is rejected on length alone, before
        // any substring/split allocation proportional to its size (#2569).
        val huge = (1..10_000).joinToString(",") { "1.0" }
        assertNull(
            CollaborativeWireFormat.parse(
                "{\"type\":\"pose\",\"peer\":\"p\",\"t\":1," +
                    "\"translation\":[$huge],\"quaternion\":[0.0,0.0,0.0,1.0]}",
            ),
        )
    }

    @Test
    fun `pose rejects wrong-size translation at encode time`() {
        try {
            CollaborativeWireFormat.pose(
                peerId = "p",
                epochMs = 0L,
                translation = floatArrayOf(1f, 2f),
                quaternion = floatArrayOf(0f, 0f, 0f, 1f),
            )
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("translation"))
        }
    }
}
