package io.github.sceneview.ar.collaborative

/**
 * The pure-Kotlin merge / conflict-resolution core of a [CollaborativeSession].
 *
 * It owns the authoritative local view of the session — the remote
 * [participants] and the set of [placedNodes] — and applies incoming
 * [CollaborativeMessage]s to it. It has **no** ARCore, Filament, Android, or
 * coroutine dependency, so the entire sync-and-merge logic is unit-testable on
 * plain JVM (see `CollaborativeStateTest`).
 *
 * [CollaborativeSession] is the thin Android/ARCore wrapper around this: it
 * feeds wire messages in via [apply] and reads [participants] / [placedNodes]
 * back out onto Compose-observable state. Keeping the merge logic separate is
 * the same separation [io.github.sceneview.ar.rerun.RerunWireFormat] applies
 * to serialization — pure code stays pure and fully covered by JVM tests.
 *
 * ### Node order — last-writer-wins on `(logicalClock, peerId)`
 *
 * Every placement ([CollaborativeMessage.NodeState]) and removal
 * ([CollaborativeMessage.NodeRemoval]) of a node key carries a per-key
 * counter. A write replaces what is stored for its key only when its
 * `(logicalClock, peerId)` pair is **greater** — counters first, peer ids
 * (compared as plain strings) to break a tie. The pair is a total order, so
 * devices that receive the same writes end up with the same node whatever
 * order the writes arrived in, and applying a write twice changes nothing.
 *
 * The writer picks the counter: above every counter it has seen for that key,
 * and never below its own wall clock in milliseconds. The first half makes a
 * write win over everything its author could see. The second lets a device
 * that joins late, or restarts, win over writes it never received — as long
 * as its clock is not behind the clock of whoever wrote them.
 *
 * A removal leaves a **tombstone** — the key and the pair that removed it — so
 * a placement that was written before the removal but arrives after it is
 * rejected instead of bringing the node back. A placement written after the
 * removal carries a greater counter and places the node again.
 *
 * Two limits of that order:
 *
 * - **Peers that predate the counter.** A placement with no counter
 *   (`logicalClock == 0`) cannot be ordered, so it is applied in arrival
 *   order, exactly as before the counter existed: it replaces whatever the
 *   key holds, tombstone included. A session only converges on a key that
 *   such a peer writes if that peer is its only concurrent writer.
 * - **Tombstones are bounded.** At most [maxNodes] are kept; past that the
 *   oldest removal is forgotten, and a placement older than a forgotten
 *   removal would be accepted again if it were still in flight.
 *
 * ### Roster caps (DoS hardening, #2569)
 *
 * Everything is **bounded**: at most [maxParticipants] participants,
 * [maxNodes] placed nodes and [maxNodes] tombstones are tracked. A message
 * from a peer that would create a participant or a node beyond a cap is
 * dropped ([apply] returns `false`); updates to entries that already exist
 * always go through. Without a bound, a malicious (or buggy) peer could grow
 * the maps without limit by streaming thousands of distinct forged `peer` /
 * `node` keys — a memory-amplification DoS, since one small wire line pins a
 * map entry forever.
 *
 * @param localPeerId the local device's peer id. Messages whose `peerId`
 *   equals this are ignored (a peer never tracks itself as a participant).
 * @param maxParticipants upper bound on tracked remote participants. Defaults
 *   to [MAX_PARTICIPANTS] — far above what Nearby-class transports support.
 * @param maxNodes upper bound on tracked placed nodes, and separately on
 *   remembered removals. Defaults to [MAX_NODES].
 */
public class CollaborativeState
@JvmOverloads
public constructor(
    private val localPeerId: String,
    private val maxParticipants: Int = MAX_PARTICIPANTS,
    private val maxNodes: Int = MAX_NODES,
) {

    public companion object {
        /**
         * Default cap on tracked remote participants. A same-room collaborative
         * AR session realistically has a handful of peers (Nearby's
         * `P2P_CLUSTER` mesh tops out well below this); 64 keeps a wide margin
         * while bounding worst-case memory against forged `hello` floods.
         */
        public const val MAX_PARTICIPANTS: Int = 64

        /**
         * Default cap on tracked placed nodes, and on remembered removals.
         * 1024 is far beyond any sane same-room scene while keeping the
         * worst-case roster size bounded against forged `node`-key floods.
         */
        public const val MAX_NODES: Int = 1024
    }

    private val participantsById = LinkedHashMap<String, Participant>()
    private val nodesByKey = LinkedHashMap<String, PlacedNode>()

    // The write that set each placed node — same keys as nodesByKey.
    private val liveVersions = HashMap<String, NodeVersion>()

    // Removed keys with the write that removed them, oldest removal first.
    // A key is in nodesByKey or here, never both.
    private val tombstones = LinkedHashMap<String, NodeVersion>()

    // Highest counter among the writes this state no longer remembers (an
    // evicted tombstone, a node dropped by removeNode). The next local write
    // starts above it, so it still wins on a peer that remembers them.
    private var forgottenClock = 0L

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
     * @return `true` if [participants], [placedNodes] or [sharedAnchor]
     *   changed (so the caller should publish a fresh snapshot to observers),
     *   `false` if the message was a no-op (e.g. it originated from the local
     *   peer, duplicated existing state, or lost to a newer write).
     */
    public fun apply(message: CollaborativeMessage): Boolean {
        // A peer never tracks itself as a remote participant or re-applies its
        // own placements — the local scene graph is already the source of
        // truth for anything this device originated.
        if (message.peerId == localPeerId) return false
        return applyMessage(message)
    }

    /**
     * Applies a message this device originated. Same merge as [apply], minus
     * the self-message filter and the node cap: the cap bounds what a peer
     * can make this device hold, not what its own user places.
     */
    internal fun applyLocal(message: CollaborativeMessage): Boolean =
        applyMessage(message, local = true)

    /**
     * The counter the next local write to [nodeKey] must carry: above the
     * highest counter seen for that key, so the write wins over everything
     * this device knew when it was made, and at least [nowEpochMs], so it
     * also wins over older writes this device never received.
     */
    internal fun nextNodeClock(nodeKey: String, nowEpochMs: Long = 0L): Long {
        val seen = maxOf(versionOf(nodeKey)?.logicalClock ?: 0L, forgottenClock)
        // Saturates instead of wrapping: a wrapped counter would lose to
        // every earlier write.
        val aboveSeen = if (seen == Long.MAX_VALUE) seen else seen + 1L
        return maxOf(aboveSeen, nowEpochMs)
    }

    /** `true` while [nodeKey] is placed — not removed, not unknown. */
    internal fun isPlaced(nodeKey: String): Boolean = nodeKey in nodesByKey

    private fun applyMessage(message: CollaborativeMessage, local: Boolean = false): Boolean =
        when (message) {
            is CollaborativeMessage.Hello -> applyHello(message)
            is CollaborativeMessage.Bye -> applyBye(message)
            is CollaborativeMessage.SharedAnchor -> applySharedAnchor(message)
            is CollaborativeMessage.ParticipantPose -> applyPose(message)
            is CollaborativeMessage.NodeState -> applyNode(message, local)
            is CollaborativeMessage.NodeRemoval -> applyNodeRemoval(message)
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
        // Roster cap: never create a NEW participant beyond maxParticipants —
        // updates to already-tracked peers still apply (see class doc, #2569).
        if (existing == null && participantsById.size >= maxParticipants) return false
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
        // Roster cap: a pose may implicitly create a participant — apply the
        // same maxParticipants bound as hello (see class doc, #2569).
        if (existing == null && participantsById.size >= maxParticipants) return false
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

    private fun applyNode(message: CollaborativeMessage.NodeState, local: Boolean): Boolean {
        val key = message.nodeKey
        val placed = PlacedNode(
            nodeKey = key,
            modelKey = message.modelKey,
            translation = message.translation,
            quaternion = message.quaternion,
            scale = message.scale,
            ownerPeerId = message.peerId,
        )
        val version = if (message.logicalClock > 0L) {
            NodeVersion(message.logicalClock, message.peerId)
        } else {
            // No counter: a peer that predates it. Nothing orders this write,
            // so it lands in arrival order, as it did before the counter
            // existed — stamped one above what the key holds.
            if (nodesByKey[key] == placed) return false
            NodeVersion(nextNodeClock(key), message.peerId)
        }
        val existing = versionOf(key)
        if (existing != null && version <= existing) return false
        // Roster cap: a peer never creates a NEW node beyond maxNodes — moves
        // / rewrites of already-placed keys still apply (see class doc, #2569).
        if (!local && key !in nodesByKey && nodesByKey.size >= maxNodes) return false
        tombstones.remove(key)
        nodesByKey[key] = placed
        liveVersions[key] = version
        return true
    }

    private fun applyNodeRemoval(message: CollaborativeMessage.NodeRemoval): Boolean {
        // A removal with no counter cannot be ordered against a placement.
        if (message.logicalClock <= 0L) return false
        val key = message.nodeKey
        val version = NodeVersion(message.logicalClock, message.peerId)
        val existing = versionOf(key)
        if (existing != null && version <= existing) return false
        val wasPlaced = nodesByKey.remove(key) != null
        liveVersions.remove(key)
        // Re-inserted, not updated in place: the map evicts in insertion
        // order, and this is now the newest removal.
        tombstones.remove(key)
        tombstones[key] = version
        evictOldestTombstones()
        // Remembered either way, so the placement it follows is rejected if
        // it arrives later — but only a node that was there changes what
        // observers see.
        return wasPlaced
    }

    /** Keeps at most [maxNodes] tombstones, forgetting the oldest removals. */
    private fun evictOldestTombstones() {
        val oldestFirst = tombstones.values.iterator()
        while (tombstones.size > maxNodes && oldestFirst.hasNext()) {
            forgottenClock = maxOf(forgottenClock, oldestFirst.next().logicalClock)
            oldestFirst.remove()
        }
    }

    private fun versionOf(nodeKey: String): NodeVersion? =
        liveVersions[nodeKey] ?: tombstones[nodeKey]

    /**
     * Drops [nodeKey] from **this** state only, with everything remembered
     * about it — no tombstone is kept and nothing is sent. A peer that still
     * holds the node brings it back with its next write to that key. To
     * remove a node for every peer, use [CollaborativeSession.removeNode].
     *
     * @return `true` if a placed node was removed.
     */
    public fun removeNode(nodeKey: String): Boolean {
        val forgotten = liveVersions.remove(nodeKey) ?: tombstones.remove(nodeKey)
        if (forgotten != null) forgottenClock = maxOf(forgottenClock, forgotten.logicalClock)
        return nodesByKey.remove(nodeKey) != null
    }

    /**
     * The position of a node write in the last-writer-wins order: counters
     * first, then peer ids as plain strings. Total, and the same on every
     * device.
     */
    private class NodeVersion(val logicalClock: Long, val peerId: String) :
        Comparable<NodeVersion> {
        override fun compareTo(other: NodeVersion): Int {
            val clockOrder = logicalClock.compareTo(other.logicalClock)
            return if (clockOrder != 0) clockOrder else peerId.compareTo(other.peerId)
        }
    }

    private fun nowOr(existing: Participant?): Long =
        existing?.lastSeenEpochMs ?: 0L
}
