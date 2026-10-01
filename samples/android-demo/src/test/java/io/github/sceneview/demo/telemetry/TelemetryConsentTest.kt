package io.github.sceneview.demo.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The consent rules: what collection is allowed, who is asked, and how older installs migrate. */
class TelemetryConsentTest {

    private class MemoryStore(
        override var consentState: ConsentState = ConsentState.Unknown,
        override var consentAt: Long = 0L,
        override var consentVersion: Int = 0,
        override var legacyAnalyticsEnabled: Boolean? = null,
    ) : ConsentStore

    @Test
    fun `a fresh install is unknown, then asked in the zone`() {
        val store = MemoryStore()
        TelemetryConsent.migrate(store)
        assertEquals(ConsentState.Unknown, store.consentState)
        assertEquals(TelemetryConsent.VERSION, store.consentVersion)
        assertTrue(TelemetryConsent.mustAsk(store.consentState, inZone = true))
        assertFalse(TelemetryConsent.collectionAllowed(store.consentState, inZone = true))
    }

    @Test
    fun `outside the zone an unknown consent collects and is never asked`() {
        assertTrue(TelemetryConsent.collectionAllowed(ConsentState.Unknown, inZone = false))
        assertFalse(TelemetryConsent.mustAsk(ConsentState.Unknown, inZone = false))
    }

    @Test
    fun `an explicit opt-out from an older build becomes a refusal`() {
        val store = MemoryStore(legacyAnalyticsEnabled = false)
        TelemetryConsent.migrate(store)
        assertEquals(ConsentState.Denied, store.consentState)
        assertNull(store.legacyAnalyticsEnabled)
        assertFalse(TelemetryConsent.mustAsk(store.consentState, inZone = true))
    }

    @Test
    fun `an older install that never opted out is asked once`() {
        val store = MemoryStore(legacyAnalyticsEnabled = true)
        TelemetryConsent.migrate(store)
        assertEquals(ConsentState.Unknown, store.consentState)
        assertNull(store.legacyAnalyticsEnabled)
        assertTrue(TelemetryConsent.mustAsk(store.consentState, inZone = true))
    }

    @Test
    fun `migration is idempotent and keeps a given answer`() {
        val store = MemoryStore()
        TelemetryConsent.migrate(store)
        TelemetryConsent.record(store, granted = true, now = 42L)
        TelemetryConsent.migrate(store)
        assertEquals(ConsentState.Granted, store.consentState)
        assertEquals(42L, store.consentAt)
    }

    @Test
    fun `a refusal is never asked again and never collects`() {
        val store = MemoryStore()
        TelemetryConsent.record(store, granted = false, now = 7L)
        assertEquals(ConsentState.Denied, store.consentState)
        assertEquals(7L, store.consentAt)
        assertFalse(TelemetryConsent.mustAsk(store.consentState, inZone = true))
        assertFalse(TelemetryConsent.collectionAllowed(store.consentState, inZone = true))
        assertFalse(TelemetryConsent.collectionAllowed(store.consentState, inZone = false))
    }

    @Test
    fun `a new consent version asks everybody again`() {
        val store = MemoryStore(consentState = ConsentState.Granted, consentAt = 1L, consentVersion = 0)
        TelemetryConsent.migrate(store)
        assertEquals(ConsentState.Unknown, store.consentState)
        assertEquals(0L, store.consentAt)
    }

    @Test
    fun `the debug extra parses its three values and nothing else`() {
        assertEquals(DebugConsent.Granted, DebugConsent.parse("granted"))
        assertEquals(DebugConsent.Denied, DebugConsent.parse(" Denied "))
        assertEquals(DebugConsent.Ask, DebugConsent.parse("ask"))
        assertNull(DebugConsent.parse(null))
        assertNull(DebugConsent.parse("yes"))
    }
}
