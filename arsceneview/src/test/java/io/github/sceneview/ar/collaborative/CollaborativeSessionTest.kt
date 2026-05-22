package io.github.sceneview.ar.collaborative

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
 * ### Determinism (#2091)
 *
 * The session merges inbound messages on a background supervisor scope. These
 * tests inject a single [StandardTestDispatcher] into every session so that
 * scope, the writer loop, and the merge coroutines all run on **virtual time**
 * driven by the test's [TestScope]. After every action that triggers I/O the
 * test calls [advanceUntilIdle], which deterministically runs the whole
 * propagation chain (enqueue → writer loop → transport delivery → merge →
 * publish) to completion. No `Dispatchers.Default`, no wall-clock `withTimeout`
 * — so there is no scheduler race to flake on a loaded CI runner.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CollaborativeSessionTest {

    private val sessions = mutableListOf<CollaborativeSession>()

    @After
    fun tearDown() {
        sessions.forEach { runCatching { it.stop() } }
        sessions.clear()
    }

    /**
     * Builds a [CollaborativeSession] whose message-I/O scope runs on the
     * supplied test [dispatcher], so its coroutines are advanced by the
     * enclosing [runTest]'s virtual clock rather than a real thread pool.
     */
    private fun TestScope.session(
        transport: CollaborativeTransport,
        displayName: String,
    ): CollaborativeSession =
        CollaborativeSession(
            transport = transport,
            displayName = displayName,
            poseRateHz = CollaborativeSession.DEFAULT_POSE_RATE_HZ,
            tag = "CollaborativeSessionTest",
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ).also { sessions.add(it) }

    @Test
    fun `hello propagates a participant to the other session`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        val bob = session(hub.join("bob"), "Bob")

        alice.start()
        bob.start()

        // Drain the full propagation chain on virtual time: each start()
        // enqueued a hello; the writer loop, transport delivery and merge
        // coroutines all run to completion here — deterministically.
        advanceUntilIdle()

        // Each side announced itself with a hello on start().
        assertTrue(bob.participants.any { it.id == "alice" })
        assertTrue(alice.participants.any { it.id == "bob" })

        assertEquals("Alice", bob.participants.first { it.id == "alice" }.displayName)
        assertEquals("Bob", alice.participants.first { it.id == "bob" }.displayName)
    }

    @Test
    fun `a session never lists itself as a participant`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        alice.start()
        // Run any I/O the hello might trigger — it must not round-trip to self.
        advanceUntilIdle()
        assertTrue(alice.participants.none { it.id == "alice" })
    }

    @Test
    fun `pose broadcast updates the peer's participant transform`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        val bob = session(hub.join("bob"), "Bob")
        alice.start()
        bob.start()
        advanceUntilIdle()
        assertTrue(bob.participants.any { it.id == "alice" })

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
        advanceUntilIdle()
        assertTrue(bob.participants.firstOrNull { it.id == "alice" }?.hasPose == true)
        val alicePose = bob.participants.first { it.id == "alice" }
        assertEquals(1.5f, alicePose.translation!![0], 1e-5f)
        assertEquals(-2f, alicePose.translation!![2], 1e-5f)
    }

    @Test
    fun `placeNode broadcasts to peers and reflects locally`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        val bob = session(hub.join("bob"), "Bob")
        alice.start()
        bob.start()
        advanceUntilIdle()

        alice.placeNode(
            nodeKey = "robot-1",
            modelKey = "robot",
            translation = floatArrayOf(0f, 0f, -1f),
            quaternion = floatArrayOf(0f, 0f, 0f, 1f),
        )

        // The placing session reflects its own placement immediately.
        assertTrue(alice.placedNodes.any { it.nodeKey == "robot-1" })
        // The peer receives it over the transport.
        advanceUntilIdle()
        assertTrue(bob.placedNodes.any { it.nodeKey == "robot-1" })
        val node = bob.placedNodes.first { it.nodeKey == "robot-1" }
        assertEquals("robot", node.modelKey)
        assertEquals("alice", node.ownerPeerId)
    }

    @Test
    fun `shared anchor id propagates from a received anchor message`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val bob = session(hub.join("bob"), "Bob")
        bob.start()
        advanceUntilIdle()
        assertNull(bob.sharedCloudAnchorId)

        bob.testOnlyReceive(
            "alice",
            CollaborativeWireFormat.anchor("alice", "cloud-id-42", "room"),
        )
        advanceUntilIdle()
        assertEquals("cloud-id-42", bob.sharedCloudAnchorId)
    }

    @Test
    fun `bye removes the peer from participants`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        val bob = session(hub.join("bob"), "Bob")
        alice.start()
        bob.start()
        advanceUntilIdle()
        assertTrue(bob.participants.any { it.id == "alice" })

        alice.stop()
        advanceUntilIdle()
        assertTrue(bob.participants.none { it.id == "alice" })
    }

    @Test
    fun `start is idempotent`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        alice.start()
        assertTrue(alice.isStarted)
        alice.start() // second call is a harmless no-op
        assertTrue(alice.isStarted)
        advanceUntilIdle()
    }

    @Test
    fun `stop flips isStarted false`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        alice.start()
        alice.stop()
        assertFalse(alice.isStarted)
    }

    @Test
    fun `last-writer-wins across two peers for the same node key`() = runTest {
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
        advanceUntilIdle()
        assertTrue(carol.placedNodes.any { it.nodeKey == "shared-cube" })

        carol.testOnlyReceive(
            "bob",
            CollaborativeWireFormat.node(
                "bob", "shared-cube", "cube",
                floatArrayOf(9f, 9f, 9f), floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(3f, 3f, 3f),
            ),
        )
        advanceUntilIdle()
        assertEquals(
            "bob",
            carol.placedNodes.firstOrNull { it.nodeKey == "shared-cube" }?.ownerPeerId,
        )
        val node = carol.placedNodes.first { it.nodeKey == "shared-cube" }
        assertEquals(9f, node.translation[0], 1e-5f)
        assertEquals(3f, node.scale[0], 1e-5f)
    }

    @Test
    fun `enqueue before start does not crash and onFrame is a no-op without anchor`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        // broadcastLocalPose / placeNode before start() are silently ignored.
        alice.placeNode(
            "n", "m", floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f),
        )
        assertTrue(alice.placedNodes.isEmpty())
    }
}
