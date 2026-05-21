package io.github.sceneview.ar.collaborative

/**
 * The pure-Kotlin merge / conflict-resolution core of a [CollaborativeSession].
 *
 * It owns the authoritative local view of the session — the remote
 * [participants] and the set of [placedNodes] — and applies incoming
 * [CollaborativeMessage]s to it with a deterministic, **last-writer-wins**
 * policy. It has **no** ARCore, Filament, Android, or coroutine dependency, so
 * the entire sync-and-merge logic is unit-testable on plain JVM (see
 * `CollaborativeStateTest`).
 *
 * [CollaborativeSession] is the thin Android/ARCore wrapper around this: it
 * feeds wire messages in via [apply] and reads [participants] / [placedNodes]
 * back out onto Compose-observable state. Keeping the merge logic separate is
 * the same separation [io.github.sceneview.ar.rerun.RerunWireFormat] applies
 * to serialization — pure code stays pure and fully covered by JVM tests.
 *
 * @param localPeerId the local device's peer id. Messages whose `peerId`
 *   equals this are ignored (a peer never tracks itself as a participant).
 */
public class CollaborativeState(private val localPeerId: String) {

    private val participantsById = LinkedHashMap<String, Participant>()
    private val nodesByKey = LinkedHashMap<String, PlacedNode>()

    /** The most recent shared anchor announced for the session, or `null`. */
    public var sharedAnchor: CollaborativeMessage.SharedAnchor? = null
        private set

    /** A stable snapshot of every remote participant currently tracked. */
    public val participants: List<Participant>
        get() = participantsById.values.toList()

    /** A stable snapshot of every placed node currently tracked. */
    public val placedNodes: List<PlacedNode>
        get() = nodesByKey.values.toList()

    /**
     * Applies one decoded [message] to the local state.
     *
     * @return `true` if the state changed (so the caller should publish a
     *   fresh snapshot to observers), `false` if the message was a no-op
     *   (e.g. it originated from the local peer, or duplicated existing state).
     */
    public fun apply(message: CollaborativeMessage): Boolean {
        // A peer never tracks itself as a remote participant or re-applies its
        // own placements — the local scene graph is already the source of
        // truth for anything this device originated.
        if (message.peerId == localPeerId) return false
        return when (message) {
            is CollaborativeMessage.Hello -> applyHello(message)
            is CollaborativeMessage.Bye -> applyBye(message)
            is CollaborativeMessage.SharedAnchor -> applySharedAnchor(message)
            is CollaborativeMessage.ParticipantPose -> applyPose(message)
            is CollaborativeMessage.NodeState -> applyNode(message)
        }
    }

    /** Removes the participant with [peerId] (e.g. transport link dropped). */
    public fun removeParticipant(peerId: String): Boolean =
        participantsById.remove(peerId) != null

    /** Drops every participant not present in [livePeerIds]. */
    public fun retainParticipants(livePeerIds: Set<String>): Boolean {
        val stale = participantsById.keys - livePeerIds - localPeerId
        if (stale.isEmpty()) return false
        stale.forEach { participantsById.remove(it) }
        return true
    }

    private fun applyHello(message: CollaborativeMessage.Hello): Boolean {
        val existing = participantsById[message.peerId]
        val updated = (existing ?: Participant(message.peerId, message.displayName)).copy(
            displayName = message.displayName,
            lastSeenEpochMs = maxOf(existing?.lastSeenEpochMs ?: 0L, nowOr(existing)),
        )
        participantsById[message.peerId] = updated
        return existing != updated
    }

    private fun applyBye(message: CollaborativeMessage.Bye): Boolean =
        participantsById.remove(message.peerId) != null

    private fun applySharedAnchor(message: CollaborativeMessage.SharedAnchor): Boolean {
        if (sharedAnchor == message) return false
        sharedAnchor = message
        return true
    }

    private fun applyPose(message: CollaborativeMessage.ParticipantPose): Boolean {
        val existing = participantsById[message.peerId]
        // Drop out-of-order / stale poses: a later epoch always wins, an
        // earlier one is silently ignored (UDP-style reordering safety).
        if (existing != null && existing.lastSeenEpochMs > message.epochMs) return false
        val updated = (existing ?: Participant(message.peerId, message.peerId)).copy(
            translation = message.translation,
            quaternion = message.quaternion,
            lastSeenEpochMs = message.epochMs,
        )
        participantsById[message.peerId] = updated
        return existing != updated
    }

    private fun applyNode(message: CollaborativeMessage.NodeState): Boolean {
        val placed = PlacedNode(
            nodeKey = message.nodeKey,
            modelKey = message.modelKey,
            translation = message.translation,
            quaternion = message.quaternion,
            scale = message.scale,
            ownerPeerId = message.peerId,
        )
        val existing = nodesByKey[message.nodeKey]
        if (existing == placed) return false
        // Last-writer-wins: the newest NodeState for a key fully replaces it.
        nodesByKey[message.nodeKey] = placed
        return true
    }

    /** Removes the placed node with [nodeKey]. */
    public fun removeNode(nodeKey: String): Boolean = nodesByKey.remove(nodeKey) != null

    private fun nowOr(existing: Participant?): Long =
        existing?.lastSeenEpochMs ?: 0L
}
