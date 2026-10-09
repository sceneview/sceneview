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

    // ── Delivery and local re-placement ───────────────────────────────────

    @Test
    fun `two placements made back to back both reach the peer`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        val bob = session(hub.join("bob"), "Bob")
        alice.start(); bob.start()
        advanceUntilIdle()

        // No advance between the two: the writer has not drained the first
        // line yet. A single conflated outbox dropped it.
        alice.placeNode("a", "chair", floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f))
        alice.placeNode("b", "lamp", floatArrayOf(2f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f))
        advanceUntilIdle()

        assertEquals(setOf("a", "b"), bob.placedNodes.map { it.nodeKey }.toSet())
    }

    @Test
    fun `a placement made right after start does not drop the hello`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        val bob = session(hub.join("bob"), "Bob")
        bob.start()
        alice.start()
        alice.placeNode("a", "chair", floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f))
        advanceUntilIdle()

        assertEquals("Alice", bob.participants.first { it.id == "alice" }.displayName)
        assertEquals(listOf("a"), bob.placedNodes.map { it.nodeKey })
    }

    @Test
    fun `re-placing a key a peer placed first shows locally and reaches the peer`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        val bob = session(hub.join("bob"), "Bob")
        alice.start(); bob.start()
        advanceUntilIdle()

        alice.placeNode("k", "chair", floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f))
        advanceUntilIdle()
        assertEquals("alice", bob.placedNodes.single().ownerPeerId)

        // Bob moves the node Alice placed. His own view must show his write at
        // once — the copy received from Alice used to keep shadowing it.
        bob.placeNode("k", "lamp", floatArrayOf(5f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f))
        val bobView = bob.placedNodes.single()
        assertEquals("bob", bobView.ownerPeerId)
        assertEquals("lamp", bobView.modelKey)
        assertEquals(5f, bobView.translation[0], 1e-6f)

        advanceUntilIdle()
        val aliceView = alice.placedNodes.single()
        assertEquals("bob", aliceView.ownerPeerId)
        assertEquals(5f, aliceView.translation[0], 1e-6f)
        // Both devices converge, and Bob's view was not rolled back.
        assertEquals("bob", bob.placedNodes.single().ownerPeerId)
    }

    @Test
    fun `simultaneous writes to one key converge on both sessions`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        val bob = session(hub.join("bob"), "Bob")
        alice.start(); bob.start()
        advanceUntilIdle()

        alice.placeNode(
            "shared", "chair", floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f),
        )
        bob.placeNode(
            "shared", "lamp", floatArrayOf(2f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f),
        )
        advanceUntilIdle()

        assertEquals("bob", alice.placedNodes.single().ownerPeerId)
        assertEquals(alice.placedNodes, bob.placedNodes)
    }

    @Test
    fun `removeNode broadcasts a tombstone and a stale placement cannot restore it`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        val bob = session(hub.join("bob"), "Bob")
        alice.start(); bob.start()
        advanceUntilIdle()

        alice.placeNode(
            "shared", "chair", floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f),
        )
        advanceUntilIdle()
        bob.removeNode("shared")
        advanceUntilIdle()
        assertTrue(alice.placedNodes.isEmpty())
        assertTrue(bob.placedNodes.isEmpty())

        alice.testOnlyReceive(
            "bob",
            CollaborativeWireFormat.node(
                "bob", "shared", "chair",
                floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f),
                floatArrayOf(1f, 1f, 1f), logicalClock = 1L,
            ),
        )
        advanceUntilIdle()
        assertTrue(alice.placedNodes.isEmpty())
    }

    @Test
    fun `broadcastLocalPose uses the configured rate limit`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        val bob = session(hub.join("bob"), "Bob")
        alice.start(); bob.start()
        advanceUntilIdle()

        val identity = floatArrayOf(0f, 0f, 0f, 1f)
        alice.broadcastLocalPose(floatArrayOf(1f, 0f, 0f), identity, 1_000_000_000L)
        advanceUntilIdle()
        alice.broadcastLocalPose(floatArrayOf(2f, 0f, 0f), identity, 1_050_000_000L)
        advanceUntilIdle()
        assertEquals(1f, bob.participants.first { it.id == "alice" }.translation!![0], 0f)

        alice.broadcastLocalPose(floatArrayOf(3f, 0f, 0f), identity, 1_100_000_000L)
        advanceUntilIdle()
        assertEquals(3f, bob.participants.first { it.id == "alice" }.translation!![0], 0f)
    }

    @Test
    fun `inbound writes to one key apply in arrival order`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val carol = session(hub.join("carol"), "Carol")
        carol.start()

        repeat(20) { i ->
            carol.testOnlyReceive(
                "alice",
                CollaborativeWireFormat.node(
                    "alice", "k", "chair",
                    floatArrayOf(i.toFloat(), 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f),
                    floatArrayOf(1f, 1f, 1f),
                    logicalClock = i.toLong(),
                ),
            )
        }
        advanceUntilIdle()

        assertEquals(19f, carol.placedNodes.single().translation[0], 1e-6f)
    }

    // ── Authenticated peer-id binding (#2569) ─────────────────────────────

    @Test
    fun `message claiming another peer id is rejected as spoofed`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val carol = session(hub.join("carol"), "Carol")
        carol.start()
        advanceUntilIdle()

        // Arrives on mallory's authenticated connection but claims to be alice.
        carol.testOnlyReceive("mallory", CollaborativeWireFormat.hello("alice", "Fake Alice"))
        advanceUntilIdle()
        assertTrue(carol.participants.none { it.id == "alice" })
        // The forged sender is not admitted either — the message is dropped whole.
        assertTrue(carol.participants.none { it.id == "mallory" })
    }

    @Test
    fun `spoofed bye cannot evict another participant`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val carol = session(hub.join("carol"), "Carol")
        carol.start()
        carol.testOnlyReceive("alice", CollaborativeWireFormat.hello("alice", "Alice"))
        advanceUntilIdle()
        assertTrue(carol.participants.any { it.id == "alice" })

        // Mallory forges a bye on alice's behalf — must be dropped.
        carol.testOnlyReceive("mallory", CollaborativeWireFormat.bye("alice"))
        advanceUntilIdle()
        assertTrue(carol.participants.any { it.id == "alice" })
    }

    @Test
    fun `spoofed pose cannot move another participant`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val carol = session(hub.join("carol"), "Carol")
        carol.start()
        carol.testOnlyReceive(
            "alice",
            CollaborativeWireFormat.pose(
                "alice", 100L, floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f),
            ),
        )
        advanceUntilIdle()

        // Mallory forges a newer pose claiming to be alice — must be dropped.
        carol.testOnlyReceive(
            "mallory",
            CollaborativeWireFormat.pose(
                "alice", 200L, floatArrayOf(9f, 9f, 9f), floatArrayOf(0f, 0f, 0f, 1f),
            ),
        )
        advanceUntilIdle()
        val alice = carol.participants.first { it.id == "alice" }
        assertEquals(1f, alice.translation!![0], 1e-6f)
        assertEquals(100L, alice.lastSeenEpochMs)
    }

    @Test
    fun `matching transport and body peer id still applies`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val carol = session(hub.join("carol"), "Carol")
        carol.start()
        carol.testOnlyReceive("alice", CollaborativeWireFormat.hello("alice", "Alice"))
        advanceUntilIdle()
        assertTrue(carol.participants.any { it.id == "alice" })
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
