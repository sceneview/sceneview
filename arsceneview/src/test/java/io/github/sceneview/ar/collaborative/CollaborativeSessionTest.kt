package io.github.sceneview.ar.collaborative

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
 *
 * The pose throttle waits on that same virtual clock, and the wall clock that
 * seeds node counters and stamps poses is [wallClockMs], which only moves when
 * a test moves it — no test sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CollaborativeSessionTest {

    private val sessions = mutableListOf<CollaborativeSession>()

    /** What every session reads as "now", unless a test hands it its own clock. */
    private var wallClockMs = 1_000L

    private val identity = floatArrayOf(0f, 0f, 0f, 1f)
    private val unit = floatArrayOf(1f, 1f, 1f)

    private fun at(x: Float) = floatArrayOf(x, 0f, 0f)

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
        poseRateHz: Int = CollaborativeSession.DEFAULT_POSE_RATE_HZ,
        now: () -> Long = { wallClockMs },
    ): CollaborativeSession =
        CollaborativeSession(
            transport = transport,
            displayName = displayName,
            poseRateHz = poseRateHz,
            tag = "CollaborativeSessionTest",
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ).also {
            it.epochMillis = now
            sessions.add(it)
        }

    /** A hub member that only records the lines the others send, in order. */
    private class WireProbe(hub: LoopbackCollaborativeTransport.LoopbackHub) {
        val lines = mutableListOf<String>()

        init {
            hub.join("probe").incoming { _, bytes -> lines += bytes.toString(Charsets.UTF_8) }
        }

        fun poses(): List<CollaborativeMessage.ParticipantPose> =
            lines.mapNotNull { CollaborativeWireFormat.parse(it) as? CollaborativeMessage.ParticipantPose }

        fun poseXs(): List<Float> = poses().map { it.translation[0] }

        /** Lines about placed nodes: `node` and `remove`. */
        fun nodeLines(): List<String> =
            lines.filter { "\"type\":\"node\"" in it || "\"type\":\"remove\"" in it }
    }

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

    // ── Order of writes to one key (#4384) ────────────────────────────────

    @Test
    fun `simultaneous writes to one key converge on both sessions`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        val bob = session(hub.join("bob"), "Bob")
        alice.start(); bob.start()
        advanceUntilIdle()

        // Same instant, and neither has heard of the other's write: each
        // applies its own first and the peer's second — opposite orders.
        alice.placeNode("shared", "chair", at(1f), identity)
        bob.placeNode("shared", "lamp", at(2f), identity)
        assertEquals("alice", alice.placedNodes.single().ownerPeerId)
        assertEquals("bob", bob.placedNodes.single().ownerPeerId)
        advanceUntilIdle()

        // Equal counters: the greater peer id wins, on both.
        assertEquals("bob", alice.placedNodes.single().ownerPeerId)
        assertEquals("lamp", alice.placedNodes.single().modelKey)
        assertEquals(alice.placedNodes, bob.placedNodes)
    }

    @Test
    fun `three simultaneous writes and a bystander end on the same node`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        val bob = session(hub.join("bob"), "Bob")
        val carol = session(hub.join("carol"), "Carol")
        val dave = session(hub.join("dave"), "Dave")
        listOf(alice, bob, carol, dave).forEach { it.start() }
        advanceUntilIdle()

        carol.placeNode("shared", "sofa", at(3f), identity)
        alice.placeNode("shared", "chair", at(1f), identity)
        bob.placeNode("shared", "lamp", at(2f), identity)
        advanceUntilIdle()

        assertEquals("carol", dave.placedNodes.single().ownerPeerId)
        assertEquals(dave.placedNodes, alice.placedNodes)
        assertEquals(dave.placedNodes, bob.placedNodes)
        assertEquals(dave.placedNodes, carol.placedNodes)
    }

    @Test
    fun `the same lines delivered in opposite orders leave two sessions in the same state`() = runTest {
        val first = session(LoopbackCollaborativeTransport.LoopbackHub().join("carol"), "Carol")
        val second = session(LoopbackCollaborativeTransport.LoopbackHub().join("carol"), "Carol")
        first.start(); second.start()

        val lines = listOf(
            "alice" to CollaborativeWireFormat.node("alice", "k", "chair", at(1f), identity, unit, 7L),
            "bob" to CollaborativeWireFormat.node("bob", "k", "lamp", at(2f), identity, unit, 7L),
            "alice" to CollaborativeWireFormat.remove("alice", "k", 8L),
            "bob" to CollaborativeWireFormat.node("bob", "k", "lamp", at(4f), identity, unit, 9L),
            "alice" to CollaborativeWireFormat.node("alice", "gone", "chair", at(5f), identity, unit, 3L),
            "bob" to CollaborativeWireFormat.remove("bob", "gone", 4L),
        )
        lines.forEach { (peer, line) -> first.testOnlyReceive(peer, line) }
        lines.asReversed().forEach { (peer, line) -> second.testOnlyReceive(peer, line) }
        advanceUntilIdle()

        val node = first.placedNodes.single()
        assertEquals("k", node.nodeKey)
        assertEquals("bob", node.ownerPeerId)
        assertEquals(4f, node.translation[0], 0f)
        assertEquals(first.placedNodes, second.placedNodes)
    }

    @Test
    fun `a write made after receiving one wins, even from a device whose clock is behind`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        // Alice's wall clock is four seconds behind Bob's.
        val alice = session(hub.join("alice"), "Alice", now = { 1_000L })
        val bob = session(hub.join("bob"), "Bob", now = { 5_000L })
        alice.start(); bob.start()
        advanceUntilIdle()

        bob.placeNode("k", "lamp", at(2f), identity)
        advanceUntilIdle()
        assertEquals("bob", alice.placedNodes.single().ownerPeerId)

        // Alice has seen Bob's write: hers follows it, whatever her clock says.
        alice.placeNode("k", "chair", at(1f), identity)
        assertEquals("alice", alice.placedNodes.single().ownerPeerId)
        advanceUntilIdle()

        assertEquals("alice", bob.placedNodes.single().ownerPeerId)
        assertEquals(1f, bob.placedNodes.single().translation[0], 0f)
        assertEquals(alice.placedNodes, bob.placedNodes)
    }

    @Test
    fun `a device that joins late still moves a node written many times before it joined`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        val bob = session(hub.join("bob"), "Bob")
        alice.start(); bob.start()
        advanceUntilIdle()
        repeat(50) { i ->
            alice.placeNode("k", "chair", at(i.toFloat()), identity)
            advanceUntilIdle()
        }

        // Dave joins a second later. Nodes placed before a device joined are
        // not sent to it, so he has never seen a write to "k" — a restarted
        // peer is in the same position.
        wallClockMs += 1_000L
        val dave = session(hub.join("dave"), "Dave")
        dave.start()
        advanceUntilIdle()
        assertTrue(dave.placedNodes.isEmpty())

        dave.placeNode("k", "lamp", at(99f), identity)
        advanceUntilIdle()

        assertEquals("dave", alice.placedNodes.single().ownerPeerId)
        assertEquals(99f, alice.placedNodes.single().translation[0], 0f)
        assertEquals(alice.placedNodes, bob.placedNodes)
        assertEquals(alice.placedNodes, dave.placedNodes)
    }

    @Test
    fun `a write that cannot outrank what a peer sent is neither shown nor sent`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val probe = WireProbe(hub)
        val alice = session(hub.join("alice"), "Alice")
        alice.start()
        // A peer that sends the largest counter there is leaves no room above
        // it for a peer with a smaller id.
        alice.testOnlyReceive(
            "bob",
            CollaborativeWireFormat.node("bob", "k", "lamp", at(2f), identity, unit, Long.MAX_VALUE),
        )
        advanceUntilIdle()

        alice.placeNode("k", "chair", at(1f), identity)
        alice.removeNode("k")
        advanceUntilIdle()

        // Sending either would only be rejected by every peer: the local view
        // must not show what nobody else will.
        assertEquals("bob", alice.placedNodes.single().ownerPeerId)
        assertTrue(probe.nodeLines().isEmpty())
    }

    @Test
    fun `inbound writes that carry a counter apply in counter order, not arrival order`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val carol = session(hub.join("carol"), "Carol")
        carol.start()

        // Newest first: every later line is older than what is already there.
        for (i in 20 downTo 1) {
            carol.testOnlyReceive(
                "alice",
                CollaborativeWireFormat.node(
                    "alice", "k", "chair", at(i.toFloat()), identity, unit, i.toLong(),
                ),
            )
        }
        advanceUntilIdle()

        assertEquals(20f, carol.placedNodes.single().translation[0], 1e-6f)
    }

    // ── Removal (#4384) ───────────────────────────────────────────────────

    @Test
    fun `removeNode removes the node on every peer, whoever placed it`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        val bob = session(hub.join("bob"), "Bob")
        val carol = session(hub.join("carol"), "Carol")
        alice.start(); bob.start(); carol.start()
        advanceUntilIdle()

        alice.placeNode("shared", "chair", at(1f), identity)
        alice.placeNode("kept", "lamp", at(2f), identity)
        advanceUntilIdle()
        assertEquals(2, carol.placedNodes.size)

        bob.removeNode("shared")
        // Gone from the remover's own view at once.
        assertEquals(listOf("kept"), bob.placedNodes.map { it.nodeKey })
        advanceUntilIdle()

        assertEquals(listOf("kept"), alice.placedNodes.map { it.nodeKey })
        assertEquals(listOf("kept"), carol.placedNodes.map { it.nodeKey })
    }

    @Test
    fun `a placement written before a removal cannot bring the node back`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        val bob = session(hub.join("bob"), "Bob")
        alice.start(); bob.start()
        advanceUntilIdle()

        alice.placeNode("shared", "chair", at(1f), identity) // counter 1000
        advanceUntilIdle()
        bob.removeNode("shared") // counter 1001
        advanceUntilIdle()
        assertTrue(alice.placedNodes.isEmpty())
        assertTrue(bob.placedNodes.isEmpty())

        // A third device wrote this before the removal; it arrives after.
        val stale = CollaborativeWireFormat.node(
            "zed", "shared", "sofa", at(7f), identity, unit, 1_000L,
        )
        alice.testOnlyReceive("zed", stale)
        bob.testOnlyReceive("zed", stale)
        advanceUntilIdle()

        assertTrue(alice.placedNodes.isEmpty())
        assertTrue(bob.placedNodes.isEmpty())
    }

    @Test
    fun `a placement made after a removal places the node again`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        val bob = session(hub.join("bob"), "Bob")
        alice.start(); bob.start()
        advanceUntilIdle()

        alice.placeNode("shared", "chair", at(1f), identity)
        advanceUntilIdle()
        bob.removeNode("shared")
        advanceUntilIdle()

        // The wall clock has not moved: only the counter says this is newer.
        alice.placeNode("shared", "chair", at(3f), identity)
        advanceUntilIdle()

        assertEquals(3f, bob.placedNodes.single().translation[0], 0f)
        assertEquals(alice.placedNodes, bob.placedNodes)
    }

    @Test
    fun `a placement and its removal made back to back leave nothing on the peer`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val probe = WireProbe(hub)
        val alice = session(hub.join("alice"), "Alice")
        val bob = session(hub.join("bob"), "Bob")
        alice.start(); bob.start()
        advanceUntilIdle()

        // No advance in between: the placement is still waiting to be sent.
        alice.placeNode("k", "chair", at(1f), identity)
        alice.removeNode("k")
        advanceUntilIdle()

        assertTrue(alice.placedNodes.isEmpty())
        assertTrue(bob.placedNodes.isEmpty())
        // The removal took the placement's place in the queue…
        assertEquals(
            listOf(CollaborativeWireFormat.remove("alice", "k", 1_001L).trim()),
            probe.nodeLines().map { it.trim() },
        )
        // …and Bob remembers it, though he never saw the node.
        bob.testOnlyReceive(
            "alice",
            CollaborativeWireFormat.node("alice", "k", "chair", at(1f), identity, unit, 1_000L),
        )
        advanceUntilIdle()
        assertTrue(bob.placedNodes.isEmpty())
    }

    @Test
    fun `a removal and a new placement made back to back leave the node on the peer`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val alice = session(hub.join("alice"), "Alice")
        val bob = session(hub.join("bob"), "Bob")
        alice.start(); bob.start()
        advanceUntilIdle()
        alice.placeNode("k", "chair", at(1f), identity)
        advanceUntilIdle()

        alice.removeNode("k")
        alice.placeNode("k", "lamp", at(2f), identity)
        advanceUntilIdle()

        assertEquals("lamp", bob.placedNodes.single().modelKey)
        assertEquals(alice.placedNodes, bob.placedNodes)
    }

    @Test
    fun `removeNode sends nothing for a key that is not placed, or before start`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val probe = WireProbe(hub)
        val alice = session(hub.join("alice"), "Alice")

        alice.removeNode("never-placed")
        alice.start()
        alice.removeNode("never-placed")
        advanceUntilIdle()

        assertTrue(probe.nodeLines().isEmpty())
        assertTrue(alice.placedNodes.isEmpty())
    }

    // ── Peers that predate the counter and `remove` (#4384) ───────────────
    //
    // The older peer is played by PreClockWireFormat, the wire format as
    // released up to 4.53.0: its encoders for what it sends, its parser for
    // what it makes of the lines it receives.

    @Test
    fun `inbound writes to one key apply in arrival order`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val carol = session(hub.join("carol"), "Carol")
        carol.start()

        repeat(20) { i ->
            carol.testOnlyReceive(
                "alice",
                PreClockWireFormat.node(
                    "alice", "k", "chair", at(i.toFloat()), identity, unit,
                ),
            )
        }
        advanceUntilIdle()

        assertEquals(19f, carol.placedNodes.single().translation[0], 1e-6f)
    }

    @Test
    fun `an older peer and this session keep moving the same node`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val probe = WireProbe(hub)
        val carol = session(hub.join("carol"), "Carol")
        carol.start()
        advanceUntilIdle()
        fun oldPeerMoves(x: Float) {
            carol.testOnlyReceive(
                "old", PreClockWireFormat.node("old", "k", "chair", at(x), identity, unit),
            )
        }

        oldPeerMoves(1f)
        advanceUntilIdle()
        assertEquals("old", carol.placedNodes.single().ownerPeerId)

        carol.placeNode("k", "chair", at(2f), identity)
        advanceUntilIdle()
        assertEquals("carol", carol.placedNodes.single().ownerPeerId)
        // What Carol sent is a line the older peer reads, counter and all.
        val seenByOldPeer =
            PreClockWireFormat.parse(probe.nodeLines().last()) as CollaborativeMessage.NodeState
        assertEquals("carol", seenByOldPeer.peerId)
        assertEquals(2f, seenByOldPeer.translation[0], 0f)

        // The older peer moves the node again, and again: its lines carry no
        // counter, and must not lose to Carol's write for ever.
        oldPeerMoves(3f)
        advanceUntilIdle()
        assertEquals(3f, carol.placedNodes.single().translation[0], 0f)
        oldPeerMoves(4f)
        advanceUntilIdle()
        assertEquals(4f, carol.placedNodes.single().translation[0], 0f)

        carol.placeNode("k", "chair", at(5f), identity)
        advanceUntilIdle()
        assertEquals("carol", carol.placedNodes.single().ownerPeerId)
        assertEquals(5f, carol.placedNodes.single().translation[0], 0f)
    }

    @Test
    fun `an older peer ignores a removal and its next move brings the node back`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val probe = WireProbe(hub)
        val carol = session(hub.join("carol"), "Carol")
        carol.start()
        carol.testOnlyReceive(
            "old", PreClockWireFormat.node("old", "k", "chair", at(1f), identity, unit),
        )
        advanceUntilIdle()

        carol.removeNode("k")
        advanceUntilIdle()
        assertTrue(carol.placedNodes.isEmpty())
        // The line is dropped by the older peer's parser — it does not crash
        // it, and it does not remove the node there.
        val removal = probe.nodeLines().single()
        assertTrue(CollaborativeWireFormat.parse(removal) is CollaborativeMessage.NodeRemoval)
        assertNull(PreClockWireFormat.parse(removal))

        // The documented limit of a mixed session.
        carol.testOnlyReceive(
            "old", PreClockWireFormat.node("old", "k", "chair", at(6f), identity, unit),
        )
        advanceUntilIdle()
        assertEquals(6f, carol.placedNodes.single().translation[0], 0f)
    }

    // ── Pose rate limit (#4384) ───────────────────────────────────────────
    //
    // DEFAULT_POSE_RATE_HZ is 10: one pose per 100 ms of virtual time.

    @Test
    fun `the first pose goes out at once and the next one waits for the interval`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val probe = WireProbe(hub)
        val alice = session(hub.join("alice"), "Alice")
        alice.start()
        runCurrent()

        alice.broadcastLocalPose(at(1f), identity)
        runCurrent()
        assertEquals(listOf(1f), probe.poseXs())

        advanceTimeBy(50)
        wallClockMs = 1_050L
        alice.broadcastLocalPose(at(2f), identity)
        runCurrent()
        assertEquals(listOf(1f), probe.poseXs())

        advanceTimeBy(49) // 99 ms after the first pose
        runCurrent()
        assertEquals(listOf(1f), probe.poseXs())

        wallClockMs = 1_100L
        advanceTimeBy(1) // 100 ms
        runCurrent()
        assertEquals(listOf(1f, 2f), probe.poseXs())
        // Stamped when the device was there, not when the line went out.
        assertEquals(listOf(1_000L, 1_050L), probe.poses().map { it.epochMs })
    }

    @Test
    fun `the pose a device stops on is sent when the interval ends`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val probe = WireProbe(hub)
        val alice = session(hub.join("alice"), "Alice")
        val bob = session(hub.join("bob"), "Bob")
        alice.start(); bob.start()
        advanceUntilIdle()

        // A burst inside one interval, then the device stops moving and
        // nothing calls broadcastLocalPose again.
        for (x in 1..4) {
            alice.broadcastLocalPose(at(x.toFloat()), identity)
            advanceTimeBy(10)
        }
        advanceUntilIdle()

        assertEquals(listOf(1f, 4f), probe.poseXs())
        assertEquals(4f, bob.participants.first { it.id == "alice" }.translation!![0], 0f)
    }

    @Test
    fun `a steady stream of poses is sent at the configured rate, in order`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val probe = WireProbe(hub)
        val alice = session(hub.join("alice"), "Alice")
        alice.start()
        runCurrent()

        // 60 frames, 16 ms apart: 960 ms of movement at ~60 fps.
        repeat(60) { frame ->
            alice.broadcastLocalPose(at(frame.toFloat()), identity)
            advanceTimeBy(16)
        }
        advanceUntilIdle()

        // One at once, then one per 100 ms up to the interval that carries
        // the last frame: 11 lines instead of 60.
        val sent = probe.poseXs()
        assertEquals(11, sent.size)
        assertEquals(0f, sent.first(), 0f)
        assertEquals(59f, sent.last(), 0f)
        assertEquals(sent.sorted(), sent)
    }

    @Test
    fun `a pose after a quiet interval goes out at once`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val probe = WireProbe(hub)
        val alice = session(hub.join("alice"), "Alice")
        alice.start()
        runCurrent()

        alice.broadcastLocalPose(at(1f), identity)
        advanceUntilIdle()
        advanceTimeBy(500)
        runCurrent()

        alice.broadcastLocalPose(at(2f), identity)
        runCurrent()
        assertEquals(listOf(1f, 2f), probe.poseXs())
    }

    @Test
    fun `a pose rate of zero sends every pose`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val probe = WireProbe(hub)
        val alice = session(hub.join("alice"), "Alice", poseRateHz = 0)
        alice.start()
        runCurrent()

        for (x in 1..5) {
            alice.broadcastLocalPose(at(x.toFloat()), identity)
            runCurrent()
        }

        assertEquals(listOf(1f, 2f, 3f, 4f, 5f), probe.poseXs())
    }

    @Test
    fun `broadcastLocalPose before start and after stop sends nothing`() = runTest {
        val hub = LoopbackCollaborativeTransport.LoopbackHub()
        val probe = WireProbe(hub)
        val alice = session(hub.join("alice"), "Alice")

        alice.broadcastLocalPose(at(1f), identity)
        alice.start()
        runCurrent()
        alice.broadcastLocalPose(at(2f), identity)
        alice.broadcastLocalPose(at(3f), identity)
        runCurrent()
        // Stopped while a pose is still held back: it is not sent late.
        alice.stop()
        alice.broadcastLocalPose(at(4f), identity)
        advanceUntilIdle()

        assertEquals(listOf(2f), probe.poseXs())
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
