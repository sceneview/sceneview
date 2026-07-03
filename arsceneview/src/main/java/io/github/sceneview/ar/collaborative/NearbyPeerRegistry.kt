package io.github.sceneview.ar.collaborative

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks the live Nearby Connections endpoints for [NearbyCollaborativeTransport]
 * and exposes them as the [CollaborativeTransport.peers] flow.
 *
 * This is the SDK-free, unit-testable half of the transport's peer bookkeeping.
 * It maps Nearby's opaque **endpoint id** (a per-discovery handle, not stable
 * across runs) to the peer's **stable collaborative peer id** (advertised in
 * the Nearby connection name), and only surfaces a peer in [peers] once its
 * connection is fully established — pending / failed connections never leak.
 *
 * ### Endpoint id vs. peer id
 *
 * Nearby identifies a remote device by an `endpointId` that is only meaningful
 * within one advertise/discover cycle. The collaborative protocol needs a
 * **stable** peer id (it keys participants and last-writer-wins by it). So a
 * device advertises with its [CollaborativeTransport.localPeerId] as the
 * Nearby connection name; the remote reads it from `ConnectionInfo.endpointName`
 * and the registry stores the `endpointId -> peerId` pairing for the life of
 * the connection. [peerIdFor] / [endpointIdFor] translate between the two.
 *
 * ### Threading
 *
 * Nearby typically invokes its lifecycle callbacks on the main thread, but
 * that is not a documented guarantee — the registry is therefore fully
 * thread-safe ([ConcurrentHashMap] + [MutableStateFlow]) and can be driven
 * from any thread without a `ConcurrentModification`.
 */
internal class NearbyPeerRegistry {

    // endpointId -> stable peer id. Only contains FULLY connected endpoints.
    private val connected = ConcurrentHashMap<String, String>()

    private val _peers = MutableStateFlow<Set<String>>(emptySet())
    val peers: StateFlow<Set<String>> = _peers.asStateFlow()

    /**
     * Records that the Nearby [endpointId] finished connecting and belongs to
     * the stable [peerId]. Publishes the updated roster.
     *
     * Idempotent: re-recording the same pair is a no-op and does not re-emit.
     *
     * @return `true` if the roster changed.
     */
    fun onConnected(endpointId: String, peerId: String): Boolean {
        if (connected[endpointId] == peerId) return false
        connected[endpointId] = peerId
        publish()
        return true
    }

    /**
     * Drops [endpointId] from the roster — the connection was lost, rejected,
     * or the endpoint stopped being discoverable. Publishes the updated roster.
     *
     * @return `true` if the endpoint was present and the roster changed.
     */
    fun onDisconnected(endpointId: String): Boolean {
        val removed = connected.remove(endpointId)
        if (removed != null) publish()
        return removed != null
    }

    /** The stable peer id behind a Nearby [endpointId], or `null` if unknown. */
    fun peerIdFor(endpointId: String): String? = connected[endpointId]

    /**
     * The Nearby endpoint id currently mapped to the stable [peerId], or `null`
     * if that peer is not connected.
     *
     * Not used by the broadcast-only transport today (broadcast paths iterate
     * [connectedEndpointIds]), but kept as the reverse lookup a unicast path
     * needs — e.g. a targeted state catch-up to a newly joined peer — and it
     * is the natural inverse of [peerIdFor] (#2569).
     */
    fun endpointIdFor(peerId: String): String? =
        connected.entries.firstOrNull { it.value == peerId }?.key

    /** Every currently-connected Nearby endpoint id — the broadcast target set. */
    fun connectedEndpointIds(): List<String> = connected.keys.toList()

    /** Number of fully-connected peers. */
    val size: Int get() = connected.size

    /** Drops every endpoint and publishes an empty roster. Used by `close()`. */
    fun clear() {
        if (connected.isEmpty()) return
        connected.clear()
        publish()
    }

    private fun publish() {
        _peers.value = connected.values.toSet()
    }
}
