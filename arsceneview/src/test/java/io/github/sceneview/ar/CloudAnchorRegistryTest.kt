package io.github.sceneview.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * JVM unit tests for [CloudAnchorEntry] and [SharedPreferencesCloudAnchorRegistry]
 * — the local Cloud Anchor index helper added for
 * [#1734](https://github.com/sceneview/sceneview/issues/1734).
 *
 * These tests exercise:
 * - `ttlDays` validation at construction time (1..365 inclusive).
 * - `isExpired()` / `expiresAtEpochMs` math against a fixed wall-clock.
 * - The `SharedPreferences`-backed default impl: add / remove / get / list / clear,
 *   replacement semantics on duplicate keys, corrupt-payload tolerance, and `purgeExpired`.
 *
 * Robolectric provides a real in-memory [android.content.SharedPreferences] backed by
 * `ShadowSharedPreferences` so we can hit the JSON serialisation path end-to-end without
 * a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CloudAnchorRegistryTest {

    private val context: android.content.Context get() = RuntimeEnvironment.getApplication()

    private fun freshRegistry(): SharedPreferencesCloudAnchorRegistry {
        // Use a per-test prefs file to avoid cross-test bleed.
        val name = "sceneview_cloud_anchors_test_${System.nanoTime()}"
        return SharedPreferencesCloudAnchorRegistry(context, name)
    }

    private fun entry(
        name: String = "marker",
        cloudAnchorId: String = "ca-$name",
        hostedAtEpochMs: Long = 1_700_000_000_000L,
        ttlDays: Int = 1
    ) = CloudAnchorEntry(
        name = name,
        cloudAnchorId = cloudAnchorId,
        hostedAtEpochMs = hostedAtEpochMs,
        ttlDays = ttlDays
    )

    // ── CloudAnchorEntry ─────────────────────────────────────────────────────

    @Test
    fun `entry rejects ttlDays below 1`() {
        assertThrows(IllegalArgumentException::class.java) {
            entry(ttlDays = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            entry(ttlDays = -5)
        }
    }

    @Test
    fun `entry rejects ttlDays above 365`() {
        assertThrows(IllegalArgumentException::class.java) {
            entry(ttlDays = 366)
        }
        assertThrows(IllegalArgumentException::class.java) {
            entry(ttlDays = 10_000)
        }
    }

    @Test
    fun `entry accepts both inclusive boundaries of TTL_DAYS_RANGE`() {
        // No exception:
        entry(ttlDays = 1)
        entry(ttlDays = 365)
    }

    @Test
    fun `isExpired returns false strictly before the ttl window ends`() {
        val hostedAt = 1_700_000_000_000L
        val e = entry(hostedAtEpochMs = hostedAt, ttlDays = 7)
        assertFalse("Just after host", e.isExpired(hostedAt + 1L))
        // One ms before expiry:
        assertFalse(e.isExpired(e.expiresAtEpochMs - 1L))
    }

    @Test
    fun `isExpired returns true at and after the ttl window end`() {
        val hostedAt = 1_700_000_000_000L
        val e = entry(hostedAtEpochMs = hostedAt, ttlDays = 7)
        assertTrue("At the exact expiry instant", e.isExpired(e.expiresAtEpochMs))
        assertTrue("Long after expiry", e.isExpired(e.expiresAtEpochMs + 999_999L))
    }

    @Test
    fun `expiresAtEpochMs is hostedAt plus ttlDays in millis`() {
        val hostedAt = 1_700_000_000_000L
        val e = entry(hostedAtEpochMs = hostedAt, ttlDays = 3)
        assertEquals(hostedAt + 3L * 24 * 60 * 60 * 1000, e.expiresAtEpochMs)
    }

    // ── SharedPreferencesCloudAnchorRegistry ─────────────────────────────────

    @Test
    fun `add then get returns the same entry`() {
        val r = freshRegistry()
        val e = entry()
        r.add(e)
        assertEquals(e, r.get(e.name))
    }

    @Test
    fun `add replaces the existing entry with the same name`() {
        val r = freshRegistry()
        r.add(entry(name = "k", cloudAnchorId = "id-1", ttlDays = 1))
        r.add(entry(name = "k", cloudAnchorId = "id-2", ttlDays = 30))
        val stored = r.get("k")
        assertNotNull(stored)
        assertEquals("id-2", stored!!.cloudAnchorId)
        assertEquals(30, stored.ttlDays)
        assertEquals(1, r.list().size)
    }

    @Test
    fun `list returns all stored entries`() {
        val r = freshRegistry()
        r.add(entry(name = "a", cloudAnchorId = "ca-a"))
        r.add(entry(name = "b", cloudAnchorId = "ca-b"))
        r.add(entry(name = "c", cloudAnchorId = "ca-c"))
        val ids = r.list().map { it.cloudAnchorId }.toSet()
        assertEquals(setOf("ca-a", "ca-b", "ca-c"), ids)
    }

    @Test
    fun `get returns null for an unknown name`() {
        val r = freshRegistry()
        r.add(entry(name = "a"))
        assertNull(r.get("missing"))
    }

    @Test
    fun `remove returns true when an entry was removed and false otherwise`() {
        val r = freshRegistry()
        r.add(entry(name = "a"))
        assertTrue(r.remove("a"))
        assertNull(r.get("a"))
        assertFalse("Second remove must be a no-op", r.remove("a"))
        assertFalse("Unknown name must be a no-op", r.remove("never-stored"))
    }

    @Test
    fun `clear removes every entry`() {
        val r = freshRegistry()
        r.add(entry(name = "a"))
        r.add(entry(name = "b"))
        r.clear()
        assertTrue(r.list().isEmpty())
    }

    @Test
    fun `entries survive a fresh registry instance on the same prefs file`() {
        val prefsName = "sceneview_cloud_anchors_persist_${System.nanoTime()}"
        val first = SharedPreferencesCloudAnchorRegistry(context, prefsName)
        first.add(entry(name = "persisted", cloudAnchorId = "ca-persisted", ttlDays = 14))
        val second = SharedPreferencesCloudAnchorRegistry(context, prefsName)
        val read = second.get("persisted")
        assertNotNull(read)
        assertEquals("ca-persisted", read!!.cloudAnchorId)
        assertEquals(14, read.ttlDays)
    }

    @Test
    fun `corrupt payload yields an empty registry rather than crashing`() {
        val prefsName = "sceneview_cloud_anchors_corrupt_${System.nanoTime()}"
        // Write garbage under the registry's PREFS_KEY directly:
        context.getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(SharedPreferencesCloudAnchorRegistry.PREFS_KEY, "not-json")
            .apply()
        val r = SharedPreferencesCloudAnchorRegistry(context, prefsName)
        assertTrue(r.list().isEmpty())
        assertNull(r.get("anything"))
        // Subsequent writes still work:
        r.add(entry(name = "fresh"))
        assertEquals("fresh", r.get("fresh")?.name)
    }

    @Test
    fun `purgeExpired removes only expired entries and returns the removed count`() {
        val r = freshRegistry()
        val now = 1_700_000_000_000L
        val twoDays = 2L * CloudAnchorEntry.MILLIS_PER_DAY
        r.add(entry(name = "fresh", hostedAtEpochMs = now, ttlDays = 7))
        r.add(entry(name = "expired-1", hostedAtEpochMs = now - twoDays, ttlDays = 1))
        r.add(entry(name = "expired-2", hostedAtEpochMs = now - twoDays * 10, ttlDays = 1))
        val removed = r.purgeExpired(now = now)
        assertEquals(2, removed)
        assertEquals(setOf("fresh"), r.list().map { it.name }.toSet())
    }
}
