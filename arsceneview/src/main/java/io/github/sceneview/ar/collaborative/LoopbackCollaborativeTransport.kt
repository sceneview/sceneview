package io.github.sceneview.ar.collaborative

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An in-process / loopback [CollaborativeTransport] reference implementation.
 *
 * Every transport created from the same [LoopbackHub] joins the same virtual
 * session: a message [send]-by one peer is delivered synchronously to every
 * **other** peer on the hub. No sockets, no Play Services, no permissions —
 * which makes it perfect for:
 *
 * - **Unit tests** — spin up two transports on one hub and assert that one
 *   side's broadcast reaches the other (see `CollaborativeSessionTest`).
 * - **Single-device demos** — drive two [CollaborativeSession]s in the same
 *   process to prove the API end-to-end without a second phone.
 * - **A drop-in default** so `rememberCollaborativeSession` is never blocked
 *   on the app first wiring real networking.
 *
 * It is **not** a production transport: it cannot reach another device. For
 * real multiplayer, implement [CollaborativeTransport] over Nearby
 * Connections, Firebase, WebRTC, or your existing game backend — the rest of
 * the collaborative stack ([CollaborativeSession], [CollaborativeWireFormat])
 * is transport-agnostic and unchanged.
 *
 * ### Threading
 *
 * Delivery is synchronous on the calling thread — [send] directly invokes
 * every peer's handlers before returning. That is intentional: it keeps tests
 * deterministic (no scheduler to await) and the volume on a loopback link is
 * trivially small. [CollaborativeSession] still marshals everything onto its
 * own supervisor scope, so synchronous delivery here does not leak threading
 * concerns upward. Handler lists use [CopyOnWriteArrayList] so a handler may
 * register / unregister during dispatch without a `ConcurrentModification`.
 */
public class LoopbackCollaborativeTransport private constructor(
    override val localPeerId: String,
    private val hub: LoopbackHub,
) : CollaborativeTransport {

    private val handlers = CopyOnWriteArrayList<(String, ByteArray) -> Unit>()
    private val _peers = MutableStateFlow<Set<String>>(emptySet())
    override val peers: StateFlow<Set<String>> = _peers.asStateFlow()

    @Volatile private var closed = false

    override fun send(message: ByteArray) {
        if (closed) return
        hub.broadcast(from = localPeerId, message = message)
    }

    override fun incoming(
        handler: (peerId: String, message: ByteArray) -> Unit,
    ): AutoCloseable {
        handlers.add(handler)
        return AutoCloseable { handlers.remove(handler) }
    }

    override fun close() {
        if (closed) return
        closed = true
        handlers.clear()
        hub.leave(this)
    }

    /** Called by the hub when a message addressed to this peer arrives. */
    internal fun deliver(fromPeerId: String, message: ByteArray) {
        if (closed) return
        for (handler in handlers) {
            handler(fromPeerId, message)
        }
    }

    /** Called by the hub whenever the peer roster changes. */
    internal fun updatePeers(allPeerIds: Set<String>) {
        _peers.value = allPeerIds - localPeerId
    }

    /**
     * A virtual session that wires together every [LoopbackCollaborativeTransport]
     * created from it. Think of it as the "local network" for loopback peers.
     *
     * Create one hub, then call [join] once per simulated device. All joined
     * transports see each other in [CollaborativeTransport.peers] and exchange
     * messages through [CollaborativeTransport.send].
     *
     * ```kotlin
     * val hub = LoopbackCollaborativeTransport.LoopbackHub()
     * val alice = hub.join("alice")
     * val bob   = hub.join("bob")
     * // alice.send(...) is now delivered to bob, and vice versa.
     * ```
     */
    public class LoopbackHub {

        // peerId -> transport. ConcurrentHashMap so join/leave from different
        // threads (e.g. two test coroutines) never corrupt the roster.
        private val members = ConcurrentHashMap<String, LoopbackCollaborativeTransport>()

        /**
         * Creates a new loopback transport with the given [peerId] and joins
         * it to this hub.
         *
         * @throws IllegalArgumentException if [peerId] is blank or already in
         *   use on this hub — peer ids must be unique per the
         *   [CollaborativeTransport] contract.
         */
        public fun join(peerId: String): LoopbackCollaborativeTransport {
            require(peerId.isNotBlank()) { "peerId must not be blank" }
            val transport = LoopbackCollaborativeTransport(peerId, this)
            val previous = members.putIfAbsent(peerId, transport)
            require(previous == null) { "peerId '$peerId' is already joined to this hub" }
            publishRoster()
            return transport
        }

        /** Number of transports currently joined to this hub. */
        public val peerCount: Int get() = members.size

        internal fun broadcast(from: String, message: ByteArray) {
            for ((peerId, transport) in members) {
                if (peerId == from) continue
                // Defensive copy: a transport must never be able to mutate a
                // payload another transport still holds a reference to.
                transport.deliver(from, message.copyOf())
            }
        }

        internal fun leave(transport: LoopbackCollaborativeTransport) {
            members.remove(transport.localPeerId, transport)
            publishRoster()
        }

        private fun publishRoster() {
            val roster = members.keys.toSet()
            for (transport in members.values) {
                transport.updatePeers(roster)
            }
        }
    }
}
