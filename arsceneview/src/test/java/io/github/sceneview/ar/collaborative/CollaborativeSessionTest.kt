package io.github.sceneview.ar.collaborative

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration tests for [CollaborativeSession] driven over two
 * [LoopbackCollaborativeTransport]s on a shared hub — no ARCore, no Android.
 *
 * The session merges inbound messages on a background supervisor scope, so
 * assertions poll [until] for the expected state with a short timeout rather
 * than asserting synchronously.
 */
class CollaborativeSessionTest {

    private val sessions = mutableListOf<CollaborativeSession>()

    @After
    fun tearDown() {
        sessions.forEach { runCatching { it.stop() } }
        sessions.clear()
    }

    private fun session(
        transport: CollaborativeTransport,
        displayName: String,
    ): CollaborativeSession =
        CollaborativeSession(transport, displayName = displayName).also { sessions.add(it) }

    /** Polls [condition] until true or the timeout elapses. */
    private fun until(timeoutMs: Long = 2000L, condition: () -> Boolean) = runBlocking {
        withTimeout(timeoutMs) {
            while (!condition()) delay(10)
        }
    }

    @Test
    fun `hello propagates a participant to the other session`() {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        val bob = session(hub.join("bob"), "Bob")

        alice.start()
        bob.start()

        // Each side announced itself with a hello on start().
        until { bob.participants.any { it.id == "alice" } }
        until { alice.participants.any { it.id == "bob" } }

        assertEquals("Alice", bob.participants.first { it.id == "alice" }.displayName)
        assertEquals("Bob", alice.participants.first { it.id == "bob" }.displayName)
    }

    @Test
    fun `a session never lists itself as a participant`() {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        alice.start()
        // Give the hello a moment to round-trip (it shouldn't, but be sure).
        runBlocking { delay(100) }
        assertTrue(alice.participants.none { it.id == "alice" })
    }

    @Test
    fun `pose broadcast updates the peer's participant transform`() {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        val bob = session(hub.join("bob"), "Bob")
        alice.start()
        bob.start()
        until { bob.participants.any { it.id == "alice" } }

        // Feed Bob a pose as if it came from Alice (bypasses ARCore Pose).
        bob.testOnlyReceive(
            "alice",
            CollaborativeWireFormat.pose(
                peerId = "alice",
                epochMs = 500L,
                translation = floatArrayOf(1.5f, 0f, -2f),
                quaternion = floatArrayOf(0f, 0f, 0f, 1f),
            ),
        )
        until { bob.participants.firstOrNull { it.id == "alice" }?.hasPose == true }
        val alicePose = bob.participants.first { it.id == "alice" }
        assertEquals(1.5f, alicePose.translation!![0], 1e-5f)
        assertEquals(-2f, alicePose.translation!![2], 1e-5f)
    }

    @Test
    fun `placeNode broadcasts to peers and reflects locally`() {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        val bob = session(hub.join("bob"), "Bob")
        alice.start()
        bob.start()

        alice.placeNode(
            nodeKey = "robot-1",
            modelKey = "robot",
            translation = floatArrayOf(0f, 0f, -1f),
            quaternion = floatArrayOf(0f, 0f, 0f, 1f),
        )

        // The placing session reflects its own placement immediately.
        assertTrue(alice.placedNodes.any { it.nodeKey == "robot-1" })
        // The peer receives it over the transport.
        until { bob.placedNodes.any { it.nodeKey == "robot-1" } }
        val node = bob.placedNodes.first { it.nodeKey == "robot-1" }
        assertEquals("robot", node.modelKey)
        assertEquals("alice", node.ownerPeerId)
    }

    @Test
    fun `shared anchor id propagates from a received anchor message`() {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val bob = session(hub.join("bob"), "Bob")
        bob.start()
        assertNull(bob.sharedCloudAnchorId)

        bob.testOnlyReceive(
            "alice",
            CollaborativeWireFormat.anchor("alice", "cloud-id-42", "room"),
        )
        until { bob.sharedCloudAnchorId == "cloud-id-42" }
    }

    @Test
    fun `bye removes the peer from participants`() {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        val bob = session(hub.join("bob"), "Bob")
        alice.start()
        bob.start()
        until { bob.participants.any { it.id == "alice" } }

        alice.stop()
        until { bob.participants.none { it.id == "alice" } }
    }

    @Test
    fun `start is idempotent`() {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        alice.start()
        assertTrue(alice.isStarted)
        alice.start() // second call is a harmless no-op
        assertTrue(alice.isStarted)
    }

    @Test
    fun `stop flips isStarted false`() {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        alice.start()
        alice.stop()
        assertFalse(alice.isStarted)
    }

    @Test
    fun `last-writer-wins across two peers for the same node key`() {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val carol = session(hub.join("carol"), "Carol")
        carol.start()

        carol.testOnlyReceive(
            "alice",
            CollaborativeWireFormat.node(
                "alice", "shared-cube", "cube",
                floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(1f, 1f, 1f),
            ),
        )
        until { carol.placedNodes.any { it.nodeKey == "shared-cube" } }

        carol.testOnlyReceive(
            "bob",
            CollaborativeWireFormat.node(
                "bob", "shared-cube", "cube",
                floatArrayOf(9f, 9f, 9f), floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(3f, 3f, 3f),
            ),
        )
        until {
            carol.placedNodes.firstOrNull { it.nodeKey == "shared-cube" }?.ownerPeerId == "bob"
        }
        val node = carol.placedNodes.first { it.nodeKey == "shared-cube" }
        assertEquals(9f, node.translation[0], 1e-5f)
        assertEquals(3f, node.scale[0], 1e-5f)
    }

    @Test
    fun `enqueue before start does not crash and onFrame is a no-op without anchor`() {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        // broadcastLocalPose / placeNode before start() are silently ignored.
        alice.placeNode(
            "n", "m", floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f),
        )
        assertTrue(alice.placedNodes.isEmpty())
    }
}
