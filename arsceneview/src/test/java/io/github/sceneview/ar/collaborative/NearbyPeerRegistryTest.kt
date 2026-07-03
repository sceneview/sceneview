package io.github.sceneview.ar.collaborative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NearbyPeerRegistry] — the SDK-free peer bookkeeping half of
 * [NearbyCollaborativeTransport]. Pure JVM: no Play Services, no Android.
 *
 * Promised by the #2221 review, tracked by #2569.
 */
class NearbyPeerRegistryTest {

    @Test
    fun `starts empty`() {
        val registry = NearbyPeerRegistry()
        assertTrue(registry.peers.value.isEmpty())
        assertEquals(0, registry.size)
    }

    @Test
    fun `onConnected adds the peer to the roster and reports a change`() {
        val registry = NearbyPeerRegistry()
        assertTrue(registry.onConnected("ep1", "alice"))
        assertEquals(setOf("alice"), registry.peers.value)
        assertEquals(1, registry.size)
    }

    @Test
    fun `idempotent reconnect is a no-op and does not re-emit`() {
        val registry = NearbyPeerRegistry()
        assertTrue(registry.onConnected("ep1", "alice"))
        val rosterBefore = registry.peers.value
        assertFalse(registry.onConnected("ep1", "alice"))
        // No change reported AND the flow value was not republished.
        assertSame(rosterBefore, registry.peers.value)
    }

    @Test
    fun `remapping an endpoint to a new peer id updates the roster`() {
        val registry = NearbyPeerRegistry()
        registry.onConnected("ep1", "alice")
        assertTrue(registry.onConnected("ep1", "alice-2"))
        assertEquals(setOf("alice-2"), registry.peers.value)
        assertEquals("alice-2", registry.peerIdFor("ep1"))
    }

    @Test
    fun `onDisconnected drops the peer and reports a change`() {
        val registry = NearbyPeerRegistry()
        registry.onConnected("ep1", "alice")
        registry.onConnected("ep2", "bob")
        assertTrue(registry.onDisconnected("ep1"))
        assertEquals(setOf("bob"), registry.peers.value)
        assertNull(registry.peerIdFor("ep1"))
    }

    @Test
    fun `onDisconnected of an unknown endpoint is a no-op`() {
        val registry = NearbyPeerRegistry()
        registry.onConnected("ep1", "alice")
        assertFalse(registry.onDisconnected("nope"))
        assertEquals(setOf("alice"), registry.peers.value)
    }

    @Test
    fun `peerIdFor translates endpoint to stable peer id`() {
        val registry = NearbyPeerRegistry()
        registry.onConnected("ep1", "alice")
        assertEquals("alice", registry.peerIdFor("ep1"))
        assertNull(registry.peerIdFor("ep2"))
    }

    @Test
    fun `endpointIdFor is the inverse of peerIdFor`() {
        val registry = NearbyPeerRegistry()
        registry.onConnected("ep1", "alice")
        registry.onConnected("ep2", "bob")
        assertEquals("ep1", registry.endpointIdFor("alice"))
        assertEquals("ep2", registry.endpointIdFor("bob"))
        assertNull(registry.endpointIdFor("carol"))
    }

    @Test
    fun `connectedEndpointIds is the broadcast target set`() {
        val registry = NearbyPeerRegistry()
        registry.onConnected("ep1", "alice")
        registry.onConnected("ep2", "bob")
        assertEquals(setOf("ep1", "ep2"), registry.connectedEndpointIds().toSet())
    }

    @Test
    fun `clear drops everything and publishes an empty roster`() {
        val registry = NearbyPeerRegistry()
        registry.onConnected("ep1", "alice")
        registry.onConnected("ep2", "bob")
        registry.clear()
        assertTrue(registry.peers.value.isEmpty())
        assertEquals(0, registry.size)
        assertTrue(registry.connectedEndpointIds().isEmpty())
    }

    @Test
    fun `clear on an empty registry does not republish`() {
        val registry = NearbyPeerRegistry()
        val rosterBefore = registry.peers.value
        registry.clear()
        assertSame(rosterBefore, registry.peers.value)
    }
}
