package io.github.sceneview.ar.collaborative

import kotlinx.coroutines.flow.StateFlow

/**
 * The pluggable network layer for a collaborative ([CollaborativeSession]) AR
 * experience.
 *
 * ### Why this is an interface, not a concrete impl
 *
 * SceneView deliberately does **not** pick a networking stack for the app.
 * Multi-user AR transport is a genuine product decision with real trade-offs:
 *
 * - **Nearby Connections** — offline same-room, no backend, but Android-only
 *   and needs a pairing UX (and a Play Services dependency).
 * - **Firebase Realtime DB** — trivial signaling but a backend dependency and
 *   API keys baked into the app.
 * - **WebRTC** — lowest latency, cross-network, but heavy.
 * - **Raw sockets / your existing game backend** — whatever you already run.
 *
 * Forcing one of those onto every SceneView app would be wrong. Instead the
 * library ships this interface plus one always-available reference impl,
 * [LoopbackCollaborativeTransport], which connects peers **in-process** (no
 * networking) so the API is demonstrable and unit-testable out of the box.
 * Production transports (Nearby Connections, Firebase, …) are app- or
 * plugin-provided and implement this same contract.
 *
 * On iOS the same abstraction maps cleanly onto RealityKit's
 * `MultipeerConnectivityService`, so the interface shape is kept
 * cross-platform-friendly.
 *
 * ### Contract
 *
 * - [send] is **non-blocking** — [CollaborativeSession] may call it from the
 *   AR render callback, so an implementation must never block the caller
 *   thread on I/O. Queue and flush on your own thread.
 * - [incoming] handlers may be invoked on **any** thread. [CollaborativeSession]
 *   marshals everything onto its own scope, so an impl is free to dispatch
 *   from whatever I/O thread it owns.
 * - [peers] is the set of currently-reachable remote peer ids. It does not
 *   include the local peer.
 * - [close] releases every resource; the transport is unusable afterwards.
 *
 * Messages are opaque byte arrays — the transport never inspects them. The
 * serialization contract lives entirely in [CollaborativeWireFormat].
 */
public interface CollaborativeTransport {

    /**
     * This device's stable peer id within the session. Must be unique across
     * all connected peers and stable for the transport's lifetime.
     */
    public val localPeerId: String

    /**
     * The set of remote peer ids currently reachable through this transport.
     * Never includes [localPeerId]. Observed by
     * [CollaborativeSession] to drop participants whose transport link died.
     */
    public val peers: StateFlow<Set<String>>

    /**
     * Broadcasts [message] to every connected peer.
     *
     * **Must not block the caller.** [CollaborativeSession] may invoke this
     * from the AR render loop; the implementation is expected to enqueue and
     * flush asynchronously.
     */
    public fun send(message: ByteArray)

    /**
     * Registers a [handler] invoked for every message received from a peer.
     * The handler may be called on any thread.
     *
     * **Contract (#2569):** the `peerId` reported to handlers must be the
     * message *originator's* stable peer id — the same value that peer encodes
     * in the wire body's `peer` field — not a hop, relay, or channel id.
     * [CollaborativeSession] drops any message whose body `peer` differs from
     * the reported `peerId` as spoofed, so a relay/star transport must
     * propagate the originator id end-to-end.
     *
     * @return a handle whose [AutoCloseable.close] unregisters the handler.
     */
    public fun incoming(handler: (peerId: String, message: ByteArray) -> Unit): AutoCloseable

    /** Releases all resources. The transport is unusable after this call. */
    public fun close()
}
