package io.github.sceneview.ar.collaborative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit tests for [LoopbackCollaborativeTransport] — the in-process reference
 * transport. Verifies delivery, peer roster, isolation, and lifecycle.
 */
class LoopbackCollaborativeTransportTest {

    @Test
    fun `message reaches every other peer but not the sender`() {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val a = hub.join("a")
        val b = hub.join("b")
        val c = hub.join("c")

        val receivedByA = CopyOnWriteArrayList<String>()
        val receivedByB = CopyOnWriteArrayList<String>()
        val receivedByC = CopyOnWriteArrayList<String>()
        a.incoming { _, m -> receivedByA.add(m.toString(Charsets.UTF_8)) }
        b.incoming { _, m -> receivedByB.add(m.toString(Charsets.UTF_8)) }
        c.incoming { _, m -> receivedByC.add(m.toString(Charsets.UTF_8)) }

        a.send("hello-from-a".toByteArray())

        assertTrue("sender must not receive its own message", receivedByA.isEmpty())
        assertEquals(listOf("hello-from-a"), receivedByB)
        assertEquals(listOf("hello-from-a"), receivedByC)
    }

    @Test
    fun `peers flow excludes the local peer and tracks the roster`() {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val a = hub.join("a")
        assertEquals(emptySet<String>(), a.peers.value)

        val b = hub.join("b")
        assertEquals(setOf("b"), a.peers.value)
        assertEquals(setOf("a"), b.peers.value)

        b.close()
        assertEquals(emptySet<String>(), a.peers.value)
    }

    @Test
    fun `delivered carries the originating peer id`() {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val a = hub.join("a")
        val b = hub.join("b")
        var fromPeer: String? = null
        b.incoming { peer, _ -> fromPeer = peer }
        a.send("x".toByteArray())
        assertEquals("a", fromPeer)
    }

    @Test
    fun `closed transport neither sends nor receives`() {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val a = hub.join("a")
        val b = hub.join("b")
        val received = CopyOnWriteArrayList<String>()
        b.incoming { _, m -> received.add(m.toString(Charsets.UTF_8)) }

        b.close()
        a.send("after-close".toByteArray())
        assertTrue(received.isEmpty())
    }

    @Test
    fun `incoming handle unregisters the handler`() {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val a = hub.join("a")
        val b = hub.join("b")
        val received = CopyOnWriteArrayList<String>()
        val handle = b.incoming { _, m -> received.add(m.toString(Charsets.UTF_8)) }

        a.send("first".toByteArray())
        handle.close()
        a.send("second".toByteArray())

        assertEquals(listOf("first"), received)
    }

    @Test
    fun `joining a duplicate peer id fails`() {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        hub.join("dup")
        try {
            hub.join("dup")
            throw AssertionError("expected IllegalArgumentException for duplicate peer id")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("dup"))
        }
    }

    @Test
    fun `joining a blank peer id fails`() {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        try {
            hub.join("  ")
            throw AssertionError("expected IllegalArgumentException for blank peer id")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("blank"))
        }
    }

    @Test
    fun `peerCount reflects joins and leaves`() {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        assertEquals(0, hub.peerCount)
        val a = hub.join("a")
        hub.join("b")
        assertEquals(2, hub.peerCount)
        a.close()
        assertEquals(1, hub.peerCount)
    }

    @Test
    fun `a mutated payload does not leak to other peers`() {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val a = hub.join("a")
        val b = hub.join("b")
        var receivedFirst: Byte = 0
        b.incoming { _, m -> receivedFirst = m[0] }

        val payload = byteArrayOf(7, 8, 9)
        a.send(payload)
        payload[0] = 99 // mutate after send

        assertEquals("receiver must hold an isolated copy", 7.toByte(), receivedFirst)
    }

    @Test
    fun `localPeerId is the joined id`() {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        assertEquals("zed", hub.join("zed").localPeerId)
    }
}
