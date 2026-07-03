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
    }
}
