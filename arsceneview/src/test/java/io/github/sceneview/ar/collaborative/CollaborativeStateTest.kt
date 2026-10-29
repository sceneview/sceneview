package io.github.sceneview.ar.collaborative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure-Kotlin merge core [CollaborativeState] — last-writer-
 * wins, self-message filtering, staleness handling. No ARCore, no Android.
 */
class CollaborativeStateTest {

    private val local = "me"

    @Test
    fun `ignores messages from the local peer`() {
        val state = CollaborativeState(local)
        val changed = state.apply(CollaborativeMessage.Hello(local, "Me"))
        assertFalse(changed)
        assertTrue(state.participants.isEmpty())
    }

    @Test
    fun `hello adds a remote participant`() {
        val state = CollaborativeState(local)
        assertTrue(state.apply(CollaborativeMessage.Hello("p1", "Alice")))
        assertEquals(1, state.participants.size)
        assertEquals("Alice", state.participants.first().displayName)
    }

    @Test
    fun `bye removes a participant`() {
        val state = CollaborativeState(local)
        state.apply(CollaborativeMessage.Hello("p1", "Alice"))
        assertTrue(state.apply(CollaborativeMessage.Bye("p1")))
        assertTrue(state.participants.isEmpty())
    }

    @Test
    fun `pose updates participant transform`() {
        val state = CollaborativeState(local)
        state.apply(CollaborativeMessage.Hello("p1", "Alice"))
        val changed = state.apply(
            CollaborativeMessage.ParticipantPose(
                peerId = "p1",
                epochMs = 100L,
                translation = floatArrayOf(1f, 2f, 3f),
                quaternion = floatArrayOf(0f, 0f, 0f, 1f),
            ),
        )
        assertTrue(changed)
        val p = state.participants.first()
        assertTrue(p.hasPose)
        assertEquals(2f, p.translation!![1], 1e-6f)
        assertEquals(100L, p.lastSeenEpochMs)
    }

    @Test
    fun `pose creates participant even without a prior hello`() {
        val state = CollaborativeState(local)
        assertTrue(
            state.apply(
                CollaborativeMessage.ParticipantPose(
                    peerId = "p9",
                    epochMs = 1L,
                    translation = floatArrayOf(0f, 0f, 0f),
                    quaternion = floatArrayOf(0f, 0f, 0f, 1f),
                ),
            ),
        )
        assertEquals(1, state.participants.size)
    }

    @Test
    fun `stale pose is dropped`() {
        val state = CollaborativeState(local)
        state.apply(
            CollaborativeMessage.ParticipantPose(
                "p1", 200L, floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f),
            ),
        )
        // An earlier-timestamped pose must NOT overwrite the newer one.
        val changed = state.apply(
            CollaborativeMessage.ParticipantPose(
                "p1", 100L, floatArrayOf(9f, 9f, 9f), floatArrayOf(0f, 0f, 0f, 1f),
            ),
        )
        assertFalse(changed)
        assertEquals(1f, state.participants.first().translation!![0], 1e-6f)
    }

    @Test
    fun `node placement is recorded`() {
        val state = CollaborativeState(local)
        val changed = state.apply(
            CollaborativeMessage.NodeState(
                peerId = "p1",
                nodeKey = "cube-1",
                modelKey = "cube",
                translation = floatArrayOf(1f, 0f, 0f),
                quaternion = floatArrayOf(0f, 0f, 0f, 1f),
                scale = floatArrayOf(1f, 1f, 1f),
            ),
        )
        assertTrue(changed)
        assertEquals(1, state.placedNodes.size)
        assertEquals("cube", state.placedNodes.first().modelKey)
    }

    @Test
    fun `last writer wins for the same node key`() {
        val state = CollaborativeState(local)
        state.apply(
            CollaborativeMessage.NodeState(
                "p1", "cube-1", "cube",
                floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(1f, 1f, 1f),
            ),
        )
        // A different peer moves the same node — its state must replace p1's.
        state.apply(
            CollaborativeMessage.NodeState(
                "p2", "cube-1", "cube",
                floatArrayOf(5f, 5f, 5f), floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(2f, 2f, 2f),
            ),
        )
        assertEquals(1, state.placedNodes.size)
        val node = state.placedNodes.first()
        assertEquals("p2", node.ownerPeerId)
        assertEquals(5f, node.translation[0], 1e-6f)
        assertEquals(2f, node.scale[0], 1e-6f)
    }

    // ── Order of writes to one key (#4384) ────────────────────────────────

    private val identity = floatArrayOf(0f, 0f, 0f, 1f)
    private val unit = floatArrayOf(1f, 1f, 1f)

    private fun place(peer: String, clock: Long, key: String = "shared", x: Float = clock.toFloat()) =
        CollaborativeMessage.NodeState(
            peer, key, "model-of-$peer", floatArrayOf(x, 0f, 0f), identity, unit, clock,
        )

    private fun remove(peer: String, clock: Long, key: String = "shared") =
        CollaborativeMessage.NodeRemoval(peer, key, clock)

    /** Every ordering of [items] (Heap's algorithm). */
    private fun <T> permutations(items: List<T>): Sequence<List<T>> = sequence {
        val a = items.toMutableList()
        val c = IntArray(a.size)
        yield(a.toList())
        var i = 0
        while (i < a.size) {
            if (c[i] < i) {
                val j = if (i % 2 == 0) 0 else c[i]
                val swapped = a[j]; a[j] = a[i]; a[i] = swapped
                yield(a.toList())
                c[i]++
                i = 0
            } else {
                c[i] = 0
                i++
            }
        }
    }

    @Test
    fun `node merge converges when concurrent writes arrive in opposite orders`() {
        val alice = place("alice", clock = 1L, x = 1f)
        val bob = place("bob", clock = 1L, x = 2f)
        val first = CollaborativeState(local)
        val second = CollaborativeState(local)

        first.apply(alice)
        first.apply(bob)
        second.apply(bob)
        second.apply(alice)

        // Equal counters: the greater peer id wins, on both.
        assertEquals("bob", first.placedNodes.single().ownerPeerId)
        assertEquals(first.placedNodes, second.placedNodes)
    }

    @Test
    fun `every delivery order of the same writes ends in the same nodes`() {
        // Three peers, counters that collide, placements and removals mixed,
        // two keys. The greatest (counter, peer) pair of each key decides:
        // "shared" ends placed by bob at counter 3, "other" ends removed.
        val writes = listOf(
            place("alice", 1L), place("bob", 1L), remove("carol", 1L),
            place("alice", 2L), remove("bob", 2L),
            remove("alice", 3L), place("bob", 3L),
            place("alice", 1L, key = "other"), remove("alice", 2L, key = "other"),
        )
        var orders = 0
        for (order in permutations(writes)) {
            val state = CollaborativeState(local)
            order.forEach { state.apply(it) }
            val node = state.placedNodes.single()
            assertEquals("shared", node.nodeKey)
            assertEquals("bob", node.ownerPeerId)
            assertEquals(3f, node.translation[0], 0f)
            // Redelivering any of them changes nothing.
            order.forEach { assertFalse(state.apply(it)) }
            assertEquals(node, state.placedNodes.single())
            orders++
        }
        assertEquals(362_880, orders) // 9!
    }

    @Test
    fun `a removal rejects an older placement and admits a newer one`() {
        val state = CollaborativeState(local)
        val placement = place("p1", clock = 1L, key = "cube-1")
        assertTrue(state.apply(placement))
        assertTrue(state.apply(remove("p1", clock = 2L, key = "cube-1")))
        assertTrue(state.placedNodes.isEmpty())

        assertFalse(state.apply(placement))
        // Same counter as the removal, smaller peer id: still older.
        assertFalse(state.apply(place("a-peer", clock = 2L, key = "cube-1")))
        assertTrue(state.placedNodes.isEmpty())

        assertTrue(state.apply(placement.copy(logicalClock = 3L)))
        assertEquals("cube-1", state.placedNodes.single().nodeKey)
    }

    @Test
    fun `a removal delivered before the placement it follows still wins`() {
        val state = CollaborativeState(local)
        // Nothing to take off the scene yet — but it is remembered.
        assertFalse(state.apply(remove("p1", clock = 2L)))
        assertFalse(state.apply(place("p1", clock = 1L)))
        assertTrue(state.placedNodes.isEmpty())
    }

    @Test
    fun `a removal with no counter is ignored`() {
        val state = CollaborativeState(local)
        state.apply(place("p1", clock = 1L))
        assertFalse(state.apply(remove("p1", clock = 0L)))
        assertEquals(1, state.placedNodes.size)
    }

    @Test
    fun `a write with no counter applies in arrival order`() {
        val state = CollaborativeState(local)
        val fromOldPeer = CollaborativeMessage.NodeState(
            "old", "shared", "chair", floatArrayOf(9f, 0f, 0f), identity, unit,
        )
        assertTrue(state.apply(place("p1", clock = 5L)))

        // Lands on top of a write that has a counter…
        assertTrue(state.apply(fromOldPeer))
        assertEquals("old", state.placedNodes.single().ownerPeerId)
        // …is a no-op when repeated…
        assertFalse(state.apply(fromOldPeer))
        // …and now sits above the write it replaced, which stays replaced
        // when redelivered.
        assertFalse(state.apply(place("p1", clock = 5L)))
        assertEquals("old", state.placedNodes.single().ownerPeerId)
        // The next write that follows it wins again.
        assertTrue(state.apply(place("p1", clock = state.nextNodeClock("shared"))))
        assertEquals("p1", state.placedNodes.single().ownerPeerId)

        // An older peer never saw the removal: its next move brings the node
        // back.
        assertTrue(state.apply(remove("p1", clock = state.nextNodeClock("shared"))))
        assertTrue(state.apply(fromOldPeer))
        assertEquals("old", state.placedNodes.single().ownerPeerId)
    }

    @Test
    fun `the next counter is above everything seen for the key and never below the wall clock`() {
        val state = CollaborativeState(local)
        assertEquals(1L, state.nextNodeClock("k"))
        assertEquals(1_000L, state.nextNodeClock("k", nowEpochMs = 1_000L))

        // Receiving a write moves the key's counter past it, whatever the
        // local wall clock says.
        state.apply(place("p1", clock = 5_000L, key = "k"))
        assertEquals(5_001L, state.nextNodeClock("k", nowEpochMs = 1_000L))
        assertEquals(9_000L, state.nextNodeClock("k", nowEpochMs = 9_000L))
        // A write that lost does not move it back.
        assertFalse(state.apply(place("p1", clock = 10L, key = "k")))
        assertEquals(5_001L, state.nextNodeClock("k", nowEpochMs = 1_000L))
        // A removal counts like a placement.
        state.apply(remove("p2", clock = 7_000L, key = "k"))
        assertEquals(7_001L, state.nextNodeClock("k", nowEpochMs = 1_000L))
        // Counters are per key.
        assertEquals(1_000L, state.nextNodeClock("other", nowEpochMs = 1_000L))
    }

    @Test
    fun `a counter at its ceiling does not wrap`() {
        val state = CollaborativeState(local)
        state.apply(place("p1", clock = Long.MAX_VALUE, key = "k"))
        assertEquals(Long.MAX_VALUE, state.nextNodeClock("k", nowEpochMs = 1_000L))
    }

    @Test
    fun `only the most recent removals are remembered`() {
        val state = CollaborativeState(local, maxNodes = 2)
        state.apply(remove("p1", clock = 10L, key = "k1"))
        state.apply(remove("p1", clock = 11L, key = "k2"))
        state.apply(remove("p1", clock = 12L, key = "k3"))

        // The two newest still reject a placement written before them.
        assertFalse(state.apply(place("p1", clock = 5L, key = "k2")))
        assertFalse(state.apply(place("p1", clock = 5L, key = "k3")))
        // The oldest was forgotten — but this device's own next write to that
        // key starts above it, so peers that remember the removal accept it.
        assertEquals(11L, state.nextNodeClock("k1"))
        // The documented limit: a placement older than the forgotten removal
        // is accepted again.
        assertTrue(state.apply(place("p1", clock = 5L, key = "k1")))
    }

    @Test
    fun `removing a key again makes it the newest removal`() {
        val state = CollaborativeState(local, maxNodes = 2)
        state.apply(remove("p1", clock = 10L, key = "k1"))
        state.apply(remove("p1", clock = 11L, key = "k2"))
        state.apply(remove("p1", clock = 12L, key = "k1"))
        state.apply(remove("p1", clock = 13L, key = "k3"))

        // k2 was the oldest removal, not k1.
        assertFalse(state.apply(place("p1", clock = 11L, key = "k1")))
        assertTrue(state.apply(place("p1", clock = 5L, key = "k2")))
    }

    @Test
    fun `duplicate node state is a no-op`() {
        val state = CollaborativeState(local)
        val msg = CollaborativeMessage.NodeState(
            "p1", "cube-1", "cube",
            floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(1f, 1f, 1f),
        )
        assertTrue(state.apply(msg))
        assertFalse(state.apply(msg))
    }

    @Test
    fun `shared anchor is captured`() {
        val state = CollaborativeState(local)
        assertNull(state.sharedAnchor)
        assertTrue(
            state.apply(CollaborativeMessage.SharedAnchor("host", "ca-1", "session")),
        )
        assertEquals("ca-1", state.sharedAnchor!!.cloudAnchorId)
    }

    @Test
    fun `retainParticipants drops peers absent from the live roster`() {
        val state = CollaborativeState(local)
        state.apply(CollaborativeMessage.Hello("p1", "A"))
        state.apply(CollaborativeMessage.Hello("p2", "B"))
        // p2's transport link died — only p1 is live now.
        val changed = state.retainParticipants(setOf("p1"))
        assertTrue(changed)
        assertEquals(1, state.participants.size)
        assertEquals("p1", state.participants.first().id)
    }

    @Test
    fun `retainParticipants is a no-op when nothing is stale`() {
        val state = CollaborativeState(local)
        state.apply(CollaborativeMessage.Hello("p1", "A"))
        assertFalse(state.retainParticipants(setOf("p1", "p2")))
    }

    // ── Roster caps (#2569) ───────────────────────────────────────────────

    @Test
    fun `hello beyond the participant cap is dropped`() {
        val state = CollaborativeState(local, maxParticipants = 2, maxNodes = 2)
        assertTrue(state.apply(CollaborativeMessage.Hello("p1", "A")))
        assertTrue(state.apply(CollaborativeMessage.Hello("p2", "B")))
        // Third distinct peer exceeds the cap — dropped, state unchanged.
        assertFalse(state.apply(CollaborativeMessage.Hello("p3", "C")))
        assertEquals(2, state.participants.size)
        assertTrue(state.participants.none { it.id == "p3" })
    }

    @Test
    fun `existing participant is still updatable at the cap`() {
        val state = CollaborativeState(local, maxParticipants = 1, maxNodes = 1)
        assertTrue(state.apply(CollaborativeMessage.Hello("p1", "A")))
        // Roster is full, but p1 is already tracked — the update applies.
        assertTrue(state.apply(CollaborativeMessage.Hello("p1", "A-renamed")))
        assertEquals("A-renamed", state.participants.first().displayName)
    }

    @Test
    fun `pose beyond the participant cap does not create a participant`() {
        val state = CollaborativeState(local, maxParticipants = 1, maxNodes = 1)
        state.apply(CollaborativeMessage.Hello("p1", "A"))
        val changed = state.apply(
            CollaborativeMessage.ParticipantPose(
                "p2", 100L, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f),
            ),
        )
        assertFalse(changed)
        assertEquals(1, state.participants.size)
    }

    @Test
    fun `pose for an existing participant is still applied at the cap`() {
        val state = CollaborativeState(local, maxParticipants = 1, maxNodes = 1)
        state.apply(CollaborativeMessage.Hello("p1", "A"))
        assertTrue(
            state.apply(
                CollaborativeMessage.ParticipantPose(
                    "p1", 100L, floatArrayOf(1f, 2f, 3f), floatArrayOf(0f, 0f, 0f, 1f),
                ),
            ),
        )
        assertTrue(state.participants.first().hasPose)
    }

    @Test
    fun `node beyond the node cap is dropped`() {
        val state = CollaborativeState(local, maxParticipants = 2, maxNodes = 1)
        assertTrue(
            state.apply(
                CollaborativeMessage.NodeState(
                    "p1", "n1", "cube",
                    floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(1f, 1f, 1f),
                ),
            ),
        )
        // A second distinct node key exceeds the cap — dropped.
        assertFalse(
            state.apply(
                CollaborativeMessage.NodeState(
                    "p1", "n2", "cube",
                    floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(1f, 1f, 1f),
                ),
            ),
        )
        assertEquals(1, state.placedNodes.size)
        assertEquals("n1", state.placedNodes.first().nodeKey)
    }

    @Test
    fun `existing node key is still updatable at the node cap`() {
        val state = CollaborativeState(local, maxParticipants = 2, maxNodes = 1)
        state.apply(
            CollaborativeMessage.NodeState(
                "p1", "n1", "cube",
                floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(1f, 1f, 1f),
            ),
        )
        // Roster is full, but n1 already exists — last-writer-wins still applies.
        assertTrue(
            state.apply(
                CollaborativeMessage.NodeState(
                    "p2", "n1", "cube",
                    floatArrayOf(9f, 9f, 9f), floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(1f, 1f, 1f),
                ),
            ),
        )
        assertEquals("p2", state.placedNodes.first().ownerPeerId)
    }

    @Test
    fun `removed nodes do not count toward the node cap`() {
        val state = CollaborativeState(local, maxParticipants = 2, maxNodes = 1)
        assertTrue(state.apply(place("p1", clock = 1L, key = "n1")))
        assertTrue(state.apply(remove("p1", clock = 2L, key = "n1")))
        assertTrue(state.apply(place("p1", clock = 1L, key = "n2")))
        assertEquals("n2", state.placedNodes.single().nodeKey)
    }

    @Test
    fun `the node cap bounds peers, not the local device's own placements`() {
        val state = CollaborativeState(local, maxParticipants = 2, maxNodes = 1)
        assertTrue(state.apply(place("p1", clock = 1L, key = "n1")))
        assertTrue(state.applyLocal(place(local, clock = 1L, key = "mine")))
        assertEquals(setOf("n1", "mine"), state.placedNodes.map { it.nodeKey }.toSet())
        // A peer still cannot add a key.
        assertFalse(state.apply(place("p1", clock = 1L, key = "n2")))
    }

    @Test
    fun `default caps are the documented constants`() {
        assertEquals(64, CollaborativeState.MAX_PARTICIPANTS)
        assertEquals(1024, CollaborativeState.MAX_NODES)
    }

    @Test
    fun `removeNode removes a placed node`() {
        val state = CollaborativeState(local)
        state.apply(
            CollaborativeMessage.NodeState(
                "p1", "cube-1", "cube",
                floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(1f, 1f, 1f),
            ),
        )
        assertTrue(state.removeNode("cube-1"))
        assertTrue(state.placedNodes.isEmpty())
        assertFalse(state.removeNode("cube-1"))
    }

    @Test
    fun `removeNode is local - a peer's next write brings the node back`() {
        val state = CollaborativeState(local)
        state.apply(place("p1", clock = 5L))
        assertTrue(state.removeNode("shared"))

        // Nothing is remembered about the key, except that this device's
        // next write to it must still land above what peers hold.
        assertEquals(6L, state.nextNodeClock("shared"))
        assertTrue(state.apply(place("p1", clock = 5L)))
        assertEquals(1, state.placedNodes.size)
    }
}
