package io.github.sceneview.ar.collaborative

/**
 * A decoded message exchanged over a [CollaborativeTransport].
 *
 * This is the typed counterpart of the JSON-lines [CollaborativeWireFormat]:
 * [CollaborativeWireFormat.parse] turns a wire line into one of these, and the
 * `encode*` functions on [CollaborativeWireFormat] turn application state into
 * wire lines. [CollaborativeSession] is the only thing that needs to construct
 * these directly; most apps just observe [CollaborativeSession.participants]
 * and [CollaborativeSession.placedNodes].
 *
 * All transforms are expressed in the **shared anchor's local space** so they
 * are directly comparable across devices — see [CollaborativeSession].
 */
public sealed interface CollaborativeMessage {

    /** The id of the peer that originated this message. */
    public val peerId: String

    /**
     * A peer announcing it has joined the session.
     *
     * @param displayName a human-readable name for the peer (defaults to the
     *   peer id when the sender did not provide one).
     */
    public data class Hello(
        override val peerId: String,
        public val displayName: String,
    ) : CollaborativeMessage

    /** A peer announcing it is leaving the session. */
    public data class Bye(
        override val peerId: String,
    ) : CollaborativeMessage

    /**
     * The shared coordinate-frame anchor for the whole session.
     *
     * The host device hosts an ARCore Cloud Anchor via
     * [io.github.sceneview.ar.node.CloudAnchorNode.host] and broadcasts the
     * resulting [cloudAnchorId]. Every other peer resolves the *same* id so all
     * devices agree on one coordinate frame.
     *
     * @param cloudAnchorId the ARCore Cloud Anchor ID to resolve.
     * @param name          an app-meaningful name (e.g. a registry key).
     */
    public data class SharedAnchor(
        override val peerId: String,
        public val cloudAnchorId: String,
        public val name: String,
    ) : CollaborativeMessage

    /**
     * A participant's live camera pose, in shared-anchor local space.
     *
     * @param epochMs     wall-clock capture time — used for staleness checks.
     * @param translation `[x,y,z]` translation in shared-anchor space.
     * @param quaternion  `[x,y,z,w]` rotation in shared-anchor space.
     */
    public data class ParticipantPose(
        override val peerId: String,
        public val epochMs: Long,
        public val translation: FloatArray,
        public val quaternion: FloatArray,
    ) : CollaborativeMessage {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ParticipantPose) return false
            return peerId == other.peerId &&
                epochMs == other.epochMs &&
                translation.contentEquals(other.translation) &&
                quaternion.contentEquals(other.quaternion)
        }

        override fun hashCode(): Int {
            var result = peerId.hashCode()
            result = 31 * result + epochMs.hashCode()
            result = 31 * result + translation.contentHashCode()
            result = 31 * result + quaternion.contentHashCode()
            return result
        }
    }

    /**
     * The transform of a placed object, in shared-anchor local space.
     *
     * Conflict policy is **last-writer-wins** keyed on [nodeKey]: a later
     * `NodeState` for the same key fully replaces the earlier one.
     *
     * @param nodeKey     app-defined unique key for the placed node.
     * @param modelKey    app-defined key for the model asset to instantiate.
     *   ⚠️ Arrives from an **untrusted peer** — validate against an allow-list
     *   of known model keys before mapping it to an asset; never use it
     *   directly in an asset/file path (path-traversal risk).
     * @param translation `[x,y,z]` translation in shared-anchor space.
     * @param quaternion  `[x,y,z,w]` rotation in shared-anchor space.
     * @param scale       `[x,y,z]` scale.
     */
    public data class NodeState(
        override val peerId: String,
        public val nodeKey: String,
        public val modelKey: String,
        public val translation: FloatArray,
        public val quaternion: FloatArray,
        public val scale: FloatArray,
    ) : CollaborativeMessage {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is NodeState) return false
            return peerId == other.peerId &&
                nodeKey == other.nodeKey &&
                modelKey == other.modelKey &&
                translation.contentEquals(other.translation) &&
                quaternion.contentEquals(other.quaternion) &&
                scale.contentEquals(other.scale)
        }

        override fun hashCode(): Int {
            var result = peerId.hashCode()
            result = 31 * result + nodeKey.hashCode()
            result = 31 * result + modelKey.hashCode()
            result = 31 * result + translation.contentHashCode()
            result = 31 * result + quaternion.contentHashCode()
            result = 31 * result + scale.contentHashCode()
            return result
        }
    }
}

/**
 * A remote participant in a collaborative AR session, as observed locally.
 *
 * Exposed via [CollaborativeSession.participants]. The local user is **not**
 * included — the session only tracks remote peers.
 *
 * @param id          the peer's stable id.
 * @param displayName the peer's human-readable name.
 * @param translation `[x,y,z]` of the peer's last-known camera pose, in
 *                    shared-anchor local space, or `null` if no pose has been
 *                    received yet.
 * @param quaternion  `[x,y,z,w]` of the peer's last-known camera rotation, in
 *                    shared-anchor local space, or `null` if no pose yet.
 * @param lastSeenEpochMs wall-clock time of the most recent message from this
 *                    peer — use it to dim or drop stale participants.
 */
public data class Participant(
    public val id: String,
    public val displayName: String,
    public val translation: FloatArray? = null,
    public val quaternion: FloatArray? = null,
    public val lastSeenEpochMs: Long = 0L,
) {
    /** `true` once at least one camera pose has been received for this peer. */
    public val hasPose: Boolean get() = translation != null && quaternion != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Participant) return false
        return id == other.id &&
            displayName == other.displayName &&
            (translation?.contentEquals(other.translation) ?: (other.translation == null)) &&
            (quaternion?.contentEquals(other.quaternion) ?: (other.quaternion == null)) &&
            lastSeenEpochMs == other.lastSeenEpochMs
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + (translation?.contentHashCode() ?: 0)
        result = 31 * result + (quaternion?.contentHashCode() ?: 0)
        result = 31 * result + lastSeenEpochMs.hashCode()
        return result
    }
}

/**
 * The transform of a node placed by some peer, in shared-anchor local space.
 *
 * Exposed via [CollaborativeSession.placedNodes]. Apps map [modelKey] to a
 * concrete asset and reconcile their scene graph against this map every frame
 * (instantiate new keys, move existing ones, remove vanished ones).
 *
 * @param nodeKey     app-defined unique key for the placed node.
 * @param modelKey    app-defined key for the model asset to instantiate.
 *   ⚠️ Originates from an **untrusted peer** — validate against an allow-list
 *   of known model keys before mapping it to an asset; never use it directly
 *   in an asset/file path (path-traversal risk in the consuming app).
 * @param translation `[x,y,z]` translation in shared-anchor space.
 * @param quaternion  `[x,y,z,w]` rotation in shared-anchor space.
 * @param scale       `[x,y,z]` scale.
 * @param ownerPeerId the peer that last wrote this node's state.
 */
public data class PlacedNode(
    public val nodeKey: String,
    public val modelKey: String,
    public val translation: FloatArray,
    public val quaternion: FloatArray,
    public val scale: FloatArray,
    public val ownerPeerId: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlacedNode) return false
        return nodeKey == other.nodeKey &&
            modelKey == other.modelKey &&
            translation.contentEquals(other.translation) &&
            quaternion.contentEquals(other.quaternion) &&
            scale.contentEquals(other.scale) &&
            ownerPeerId == other.ownerPeerId
    }

    override fun hashCode(): Int {
        var result = nodeKey.hashCode()
        result = 31 * result + modelKey.hashCode()
        result = 31 * result + translation.contentHashCode()
        result = 31 * result + quaternion.contentHashCode()
        result = 31 * result + scale.contentHashCode()
        result = 31 * result + ownerPeerId.hashCode()
        return result
    }
}
